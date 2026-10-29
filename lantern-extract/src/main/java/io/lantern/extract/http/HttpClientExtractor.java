package io.lantern.extract.http;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.core.vocabulary.VocabularyType;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.config.CodeConfig;
import io.lantern.extract.config.Resolution;
import io.lantern.extract.config.UrlCredentials;
import io.lantern.extract.java.Expression;
import io.lantern.extract.java.JavaAnalysis;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.extract.java.ModuleStructure;
import io.lantern.extract.java.ModuleStructure.Annotation;
import io.lantern.extract.java.ModuleStructure.Assignment;
import io.lantern.extract.java.ModuleStructure.Invocation;
import io.lantern.extract.java.ModuleStructure.Location;
import io.lantern.extract.java.ModuleStructure.Member;
import io.lantern.extract.java.ModuleStructure.Supertype;
import io.lantern.extract.java.ModuleStructure.TypeDecl;
import io.lantern.extract.spring.SpringExtractor;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns the HTTP clients in the code into facts: the requests each method sends, with the HTTP
 * method, the target's URL or service name and the path, through Spring Cloud OpenFeign and
 * MicroProfile REST client interfaces, Spring's HTTP interfaces ({@code @HttpExchange}) and their
 * groups ({@code @ImportHttpServices}), {@code RestTemplate}, {@code RestClient}, {@code
 * WebClient}, the Jakarta REST client API, {@code java.net.http}, OkHttp and Apache HttpClient,
 * with its fluent API; and which code enables or injects a client interface, since that code, not
 * the library that declares the interface, makes the calls. See {@code
 * docs/adr/0038-http-client-extractor.md}.
 *
 * <p>It reads the code as the Java structure extractor read it ({@link JavaAnalysis}). URLs are
 * followed one hop from a {@code @Value} field or constructor argument, and their placeholders
 * resolved through the config extractor, per profile, as the Spring extractor resolves its own.
 */
public final class HttpClientExtractor {

  /** The extractor's name, recorded as the origin of its facts. */
  public static final String NAME = "http-client";

  /** The extractor's version; change it whenever the facts it produces change. */
  public static final String VERSION = "4";

  /** The source recorded on the vocabulary revision that adds this extractor's types. */
  public static final String VOCABULARY_SOURCE = "http-client-extractor";

  /** Attribute: the subject sends an HTTP request. */
  public static final String HTTP_CALL = "http-call";

  /** Attribute: the subject interface is a declarative HTTP client. */
  public static final String HTTP_CLIENT = "http-client";

  /** Relationship: the subject type turns on the object client interface. */
  public static final String ENABLES = "ENABLES";

  /** Relationship: the subject type has the object client interface injected. */
  public static final String INJECTS = "INJECTS";

  /** Client kinds, as the {@code client} attribute holds them. */
  public static final String FEIGN = "feign";

  public static final String MICROPROFILE = "microprofile-rest-client";
  public static final String REST_TEMPLATE = "rest-template";
  public static final String REST_CLIENT = "rest-client";
  public static final String WEB_CLIENT = "web-client";
  public static final String JAKARTA_CLIENT = "jakarta-rest-client";
  public static final String HTTP_INTERFACE = "http-interface";
  public static final String JAVA_HTTP_CLIENT = "java-http-client";
  public static final String OKHTTP = "okhttp";
  public static final String APACHE_CLIENT = "apache-http-client";

  private static final String RESOURCE = "/vocabulary/http-types.json";

  private static final Origin ORIGIN = new Origin(Origin.Kind.STATIC, NAME, VERSION);

  private static final String SPRING = "org.springframework.";
  private static final List<String> FEIGN_CLIENTS =
      List.of(SPRING + "cloud.openfeign.FeignClient", SPRING + "cloud.netflix.feign.FeignClient");
  private static final List<String> ENABLE_FEIGN_CLIENTS =
      List.of(
          SPRING + "cloud.openfeign.EnableFeignClients",
          SPRING + "cloud.netflix.feign.EnableFeignClients");
  private static final String REGISTER_REST_CLIENT =
      "org.eclipse.microprofile.rest.client.inject.RegisterRestClient";
  private static final String REQUEST_MAPPING = SPRING + "web.bind.annotation.RequestMapping";
  private static final String REQUEST_LINE = "feign.RequestLine";
  private static final List<String> REST_PATHS = List.of("jakarta.ws.rs.Path", "javax.ws.rs.Path");
  private static final List<String> REST_HTTP_METHODS =
      List.of("jakarta.ws.rs.HttpMethod", "javax.ws.rs.HttpMethod");
  private static final String VALUE = SPRING + "beans.factory.annotation.Value";
  private static final String BEAN = SPRING + "context.annotation.Bean";
  private static final String QUALIFIER = SPRING + "beans.factory.annotation.Qualifier";
  private static final String PRIMARY = SPRING + "context.annotation.Primary";
  private static final String HTTP_EXCHANGE = SPRING + "web.service.annotation.HttpExchange";

  /** The types an HTTP interface's proxy is made with, and the adapters they take. */
  private static final Set<String> PROXY_FACTORIES =
      Set.of(
          SPRING + "web.service.invoker.HttpServiceProxyFactory",
          SPRING + "web.service.invoker.HttpServiceProxyFactory$Builder");

  private static final Set<String> PROXY_ADAPTERS =
      Set.of(
          SPRING + "web.client.support.RestClientAdapter",
          SPRING + "web.client.support.RestTemplateAdapter",
          SPRING + "web.reactive.function.client.support.WebClientAdapter");

  /** The interface the adapters implement, as a bean may be injected. */
  private static final String EXCHANGE_ADAPTER = SPRING + "web.service.invoker.HttpExchangeAdapter";

  /** The calls on a proxy factory or its builder that give it its adapter. */
  private static final Set<String> ADAPTER_METHODS =
      Set.of("builderFor", "builder", "exchangeAdapter", "clientAdapter");

  /** Spring Framework 7's HTTP service groups: the annotation that imports them. */
  private static final String IMPORT_HTTP_SERVICES =
      SPRING + "web.service.registry.ImportHttpServices";

  /** The group configurers' view of the groups ({@code groups.filterByName("a")}). */
  private static final String GROUPS =
      SPRING + "web.service.registry.HttpServiceGroupConfigurer$Groups";

  /** The group an {@code @ImportHttpServices} without one names. */
  private static final String DEFAULT_GROUP = "default";

  /** Spring Boot's property for a group's base URL, around the group's name. */
  private static final String GROUP_BASE_URL = "spring.http.serviceclient.%s.base-url";

  /** Apache HttpClient's request classes, made with a URL, and the HTTP method each sends. */
  private static final Map<String, String> APACHE_REQUESTS = apacheRequests();

  private static Map<String, String> apacheRequests() {
    Map<String, String> r = new HashMap<>();
    for (String pkg :
        List.of("org.apache.http.client.methods.", "org.apache.hc.client5.http.classic.methods.")) {
      for (String method : List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")) {
        r.put(
            pkg + "Http" + method.charAt(0) + method.substring(1).toLowerCase(Locale.ROOT), method);
      }
      r.put(pkg + "HttpTrace", "TRACE");
    }
    return Map.copyOf(r);
  }

  /**
   * Apache HttpClient's fluent requests, 4 and 5 ({@code Request.get(uri)}, 4's {@code
   * Request.Get(uri)}, {@code Request.create(method, uri)}), and the executors that send them.
   */
  private static final Set<String> FLUENT_REQUESTS =
      Set.of("org.apache.http.client.fluent.Request", "org.apache.hc.client5.http.fluent.Request");

  private static final Set<String> FLUENT_EXECUTORS =
      Set.of(
          "org.apache.http.client.fluent.Executor", "org.apache.hc.client5.http.fluent.Executor");

  /** Apache HttpClient's async request, made with {@code create(method, uri)}. */
  private static final String APACHE_SIMPLE_REQUEST =
      "org.apache.hc.client5.http.async.methods.SimpleHttpRequest";

  /**
   * Apache HttpClient 5's static request factories: {@code get(uri)} and the like, or {@code
   * create(method, uri)}.
   */
  private static final Set<String> APACHE_FACTORIES =
      Set.of(
          "org.apache.hc.client5.http.classic.methods.ClassicHttpRequests",
          "org.apache.hc.client5.http.async.methods.SimpleHttpRequests");

  private static final String WEB = SPRING + "web.client.";
  private static final String REACTIVE = SPRING + "web.reactive.function.client.";

  /** The client builders a group configurer sets a base URL on. */
  private static final Set<String> CLIENT_BUILDERS =
      Set.of(WEB + "RestClient$Builder", REACTIVE + "WebClient$Builder");

  /** The client kind of the types a chain of calls may start from. */
  private static final Map<String, String> KINDS = kinds();

  private static Map<String, String> kinds() {
    Map<String, String> k = new HashMap<>();
    for (String t :
        List.of(
            WEB + "RestTemplate",
            WEB + "RestOperations",
            SPRING + "boot.web.client.RestTemplateBuilder",
            SPRING + "boot.restclient.RestTemplateBuilder")) {
      k.put(t, REST_TEMPLATE);
    }
    k.put(WEB + "RestClient", REST_CLIENT);
    k.put(WEB + "RestClient$Builder", REST_CLIENT);
    k.put(REACTIVE + "WebClient", WEB_CLIENT);
    k.put(REACTIVE + "WebClient$Builder", WEB_CLIENT);
    for (String ns : List.of("jakarta", "javax")) {
      for (String t : List.of("Client", "ClientBuilder", "WebTarget", "Invocation$Builder")) {
        k.put(ns + ".ws.rs.client." + t, JAKARTA_CLIENT);
      }
    }
    k.put("java.net.http.HttpRequest", JAVA_HTTP_CLIENT);
    k.put("java.net.http.HttpRequest$Builder", JAVA_HTTP_CLIENT);
    k.put("okhttp3.Request$Builder", OKHTTP);
    for (String t : APACHE_REQUESTS.keySet()) {
      k.put(t, APACHE_CLIENT);
    }
    for (String t :
        List.of(
            "org.apache.http.client.methods.RequestBuilder",
            "org.apache.hc.core5.http.io.support.ClassicRequestBuilder",
            "org.apache.hc.client5.http.async.methods.SimpleRequestBuilder",
            APACHE_SIMPLE_REQUEST)) {
      k.put(t, APACHE_CLIENT);
    }
    for (String t : APACHE_FACTORIES) {
      k.put(t, APACHE_CLIENT);
    }
    for (String t : FLUENT_REQUESTS) {
      k.put(t, APACHE_CLIENT);
    }
    for (String t : FLUENT_EXECUTORS) {
      k.put(t, APACHE_CLIENT);
    }
    return Map.copyOf(k);
  }

  /** RestTemplate's request methods and the HTTP method each sends; null takes it as argument. */
  private static final Map<String, String> REST_TEMPLATE_METHODS =
      Map.ofEntries(
          Map.entry("getForObject", "GET"),
          Map.entry("getForEntity", "GET"),
          Map.entry("postForObject", "POST"),
          Map.entry("postForEntity", "POST"),
          Map.entry("postForLocation", "POST"),
          Map.entry("put", "PUT"),
          Map.entry("patchForObject", "PATCH"),
          Map.entry("delete", "DELETE"),
          Map.entry("headForHeaders", "HEAD"),
          Map.entry("optionsForAllow", "OPTIONS"),
          Map.entry("exchange", ""),
          Map.entry("execute", ""));

  /** The HTTP methods a RestClient, WebClient or Jakarta REST request names by a method. */
  private static final Set<String> HTTP_METHODS =
      Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE");

  private static final Pattern ABSOLUTE = Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*://)([^/]*)");

  /** The vocabulary types this extractor's facts use besides the built-in ones. */
  public static List<VocabularyType> vocabulary() {
    try (InputStream in = HttpClientExtractor.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Missing resource " + RESOURCE);
      }
      JsonNode array = FactJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
      List<VocabularyType> types = new ArrayList<>();
      for (JsonNode node : array) {
        types.add(VocabularyType.fromJson(node));
      }
      return List.copyOf(types);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + RESOURCE, e);
    }
  }

  /**
   * Extracts the facts of a snapshot's repositories, reading their code first.
   *
   * @param repos the repositories of the snapshot, each at its commit with its build, if any
   * @param snapshot the snapshot number the facts hold from
   * @return the facts, without duplicates, ordered by subject, predicate, object, attributes and ID
   * @throws UncheckedIOException if the sources cannot be read or copied
   * @throws IllegalStateException if git fails or times out, or Lantern runs without a compiler
   */
  public List<Fact> extract(List<RepoBuild> repos, long snapshot) {
    return extract(JavaExtractor.analyze(repos), snapshot);
  }

  /**
   * Extracts the facts of a snapshot's code already read by {@link JavaExtractor#analyze}.
   *
   * @param code the code of the snapshot's repositories
   * @param snapshot the snapshot number the facts hold from
   * @return the facts, without duplicates, ordered by subject, predicate, object, attributes and ID
   * @throws IllegalStateException if git fails or times out while reading config
   */
  public List<Fact> extract(JavaAnalysis code, long snapshot) {
    Objects.requireNonNull(code, "code");
    Run run = new Run(code, Validity.from(snapshot));
    run.findClients();
    for (JavaAnalysis.Module module : code.modules()) {
      run.module(module);
    }
    return run.facts.values().stream()
        .sorted(
            Comparator.comparing((Fact f) -> f.subject().toString())
                .thenComparing(Fact::predicate)
                .thenComparing(f -> f.object().value())
                .thenComparing(f -> f.attributes().toString())
                .thenComparing(Fact::id))
        .toList();
  }

  /** A URL or part of one as written, with the source of the parts not followed. */
  private record Text(String text, List<String> unknown) {}

  /** A declarative client interface: its kind and the annotation that makes it one. */
  private record Client(String kind, List<Annotation> annotation) {}

  /** One extraction: the facts so far. */
  private static final class Run {

    private final JavaAnalysis code;
    private final Validity validity;
    private final Map<String, Fact> facts = new LinkedHashMap<>();
    private final CodeConfig configs = new CodeConfig();

    /** The declarative clients of every module, by binary name. */
    private final Map<String, Client> clients = new LinkedHashMap<>();

    /** The values given to each field, by field, from every module. */
    private final Map<EntityId, List<Assignment>> assignments = new HashMap<>();

    /** Where the proxies of each HTTP interface are made, by the interface's binary name. */
    private final Map<String, List<Proxy>> proxies = new HashMap<>();

    /** The base URLs the HTTP service group configurers of every module set. */
    private final List<GroupBase> groupBases = new ArrayList<>();

    private JavaAnalysis.Module module;
    private CodeConfig.View config;

    Run(JavaAnalysis code, Validity validity) {
      this.code = code;
      this.validity = validity;
    }

    /** Finds the client interfaces of every module first, since others enable and inject them. */
    void findClients() {
      for (JavaAnalysis.Module m : code.modules()) {
        for (TypeDecl type : m.structure().types()) {
          if (type.kind().equals(ModuleStructure.INTERFACE)) {
            Client client = client(type);
            if (client != null) {
              clients.putIfAbsent(type.binaryName(), client);
            }
          }
        }
        for (Assignment a : m.structure().assignments()) {
          assignments.computeIfAbsent(a.field(), k -> new ArrayList<>()).add(a);
        }
      }
      for (JavaAnalysis.Module m : code.modules()) {
        configurers(m);
      }
      for (JavaAnalysis.Module m : code.modules()) {
        for (Invocation invocation : m.structure().invocations()) {
          proxy(invocation, m);
        }
        imports(m);
      }
    }

    private Client client(TypeDecl type) {
      for (String feign : FEIGN_CLIENTS) {
        List<Annotation> path = code.annotationPath(type.annotations(), feign);
        if (path != null) {
          return new Client(FEIGN, path);
        }
      }
      List<Annotation> path = code.annotationPath(type.annotations(), REGISTER_REST_CLIENT);
      if (path != null) {
        return new Client(MICROPROFILE, path);
      }
      // An HTTP interface: @HttpExchange on the interface, or on one of its methods.
      path = code.annotationPath(type.annotations(), HTTP_EXCHANGE);
      for (Member m : type.members()) {
        if (path != null) {
          break;
        }
        if (m.id().type().equals(ModuleStructure.METHOD)) {
          path = code.annotationPath(m.annotations(), HTTP_EXCHANGE);
        }
      }
      return path != null ? new Client(HTTP_INTERFACE, path) : null;
    }

    void module(JavaAnalysis.Module m) {
      this.module = m;
      this.config = configs.forModule(m.repo(), m.directory());
      for (TypeDecl type : m.structure().types()) {
        Client client =
            type.kind().equals(ModuleStructure.INTERFACE) ? clients.get(type.binaryName()) : null;
        if (client != null && code.module(type.binaryName()) == m) {
          declarative(type, client);
        }
        enables(type);
        injects(type);
      }
      for (Invocation invocation : m.structure().invocations()) {
        imperative(invocation);
      }
      for (List<Proxy> made : proxies.values()) {
        for (Proxy p : made) {
          if (p.module() == m) {
            ObjectNode attributes = FactJson.newObject();
            if (p.annotation() != null) {
              attributes.put("annotation", p.annotation());
            } else {
              attributes.put("member", p.in().toString());
            }
            relation(typeId(ownerType(p.in())), ENABLES, typeId(p.client()), attributes, p.where());
          }
        }
      }
    }

    // -------------------------------------------------------------------------------------------
    // Declarative clients: Feign, MicroProfile REST client and HTTP interfaces

    /**
     * Where a proxy of an HTTP interface is made ({@code factory.createClient(Foo.class)}), or
     * imported into an HTTP service group ({@code @ImportHttpServices}).
     *
     * @param client the interface
     * @param base the base URL of the client the factory's adapter wraps, or of the group, or null
     *     if not known
     * @param module the module of the code that makes it
     * @param in the method, constructor or type whose code makes it, or the importing type
     * @param where the evidence
     * @param annotation the annotation that imports it, or null for a proxy made in code
     */
    private record Proxy(
        String client,
        Text base,
        JavaAnalysis.Module module,
        EntityId in,
        Location where,
        String annotation) {}

    /** Records the HTTP interface proxies a chain of calls on a proxy factory makes. */
    private void proxy(Invocation invocation, JavaAnalysis.Module m) {
      Scope scope = new Scope(m, invocation.caller());
      Chain chain = chain(invocation.expression(), scope, 0);
      if (chain.rootType() == null || !PROXY_FACTORIES.contains(chain.rootType())) {
        return;
      }
      List<Link> links = chain.links();
      for (int i = 0; i < links.size(); i++) {
        Expression.Invoke call = links.get(i).call();
        if (!call.method().equals("createClient")
            || call.arguments().size() != 1
            || !(call.arguments().getFirst() instanceof Expression.TypeRef t)
            || clients.get(t.binaryName()) == null
            || !clients.get(t.binaryName()).kind().equals(HTTP_INTERFACE)) {
          continue;
        }
        Chain factory = new Chain(chain.rootType(), links.subList(0, i));
        Text base = factoryBase(factory, invocation.expression(), scope, 0);
        Proxy proxy =
            new Proxy(t.binaryName(), base, m, invocation.caller(), invocation.where(), null);
        List<Proxy> made = proxies.computeIfAbsent(t.binaryName(), k -> new ArrayList<>());
        if (!made.contains(proxy)) {
          made.add(proxy);
        }
      }
    }

    /**
     * The base URL of the client behind a chain on a proxy factory: that of the adapter it is given
     * ({@code builderFor(adapter)}), or, for a factory injected, that of the bean made in another
     * method; null if not known.
     */
    private Text factoryBase(Chain chain, Expression e, Scope scope, int depth) {
      Text base = null;
      boolean adapted = false;
      for (Link link : chain.links()) {
        List<Expression> args = link.call().arguments();
        if (ADAPTER_METHODS.contains(link.call().method()) && args.size() == 1) {
          base = adapterBase(args.getFirst(), link.scope(), depth);
          adapted = true;
        }
      }
      return adapted || made(chain) ? base : injected(e, scope, Set.of(chain.rootType()), depth);
    }

    /**
     * The base URL of the client a proxy factory's adapter wraps ({@code
     * RestClientAdapter.create(restClient)}): its {@code baseUrl}, or a {@code RestTemplate}'s
     * {@code rootUri}, or, for an adapter injected, that of the bean made in another method; null
     * if not known.
     */
    private Text adapterBase(Expression adapter, Scope scope, int depth) {
      Chain made = chain(adapter, scope, 0);
      if (made.rootType() == null
          || !PROXY_ADAPTERS.contains(made.rootType())
              && !made.rootType().equals(EXCHANGE_ADAPTER)) {
        return null;
      }
      Text base = null;
      boolean created = false;
      for (Link link : made.links()) {
        String method = link.call().method();
        List<Expression> args = link.call().arguments();
        if ((method.equals("create") || method.equals("forClient")) && args.size() == 1) {
          base = clientBase(args.getFirst(), link.scope());
          created = true;
        }
      }
      if (created || made(made)) {
        return base;
      }
      Set<String> types = new HashSet<>(Set.of(made.rootType()));
      if (made.rootType().equals(EXCHANGE_ADAPTER)) {
        types.addAll(PROXY_ADAPTERS);
      }
      return injected(adapter, scope, types, depth);
    }

    /** Whether a chain starts from a static call or constructor, so it is not a bean injected. */
    private static boolean made(Chain chain) {
      return chain.links().stream()
          .anyMatch(l -> l.call().receiver() instanceof Expression.TypeRef);
    }

    /**
     * The base URL behind a proxy factory or adapter injected as a parameter or field: that of the
     * bean of its type made by a {@code @Bean} method in the same repository's code, the only one
     * or the one named as the parameter or field, as Spring picks it; null if none or several
     * match.
     */
    private Text injected(Expression e, Scope scope, Set<String> types, int depth) {
      Expression root = e instanceof Expression.Invoke i ? i.root() : e;
      String name =
          switch (root) {
            case Expression.Param p -> {
              ModuleStructure.Parameter parameter = parameter(scope.in(), p.index());
              yield parameter == null ? null : parameter.name();
            }
            case Expression.FieldRef f -> f.name();
            case null, default -> null;
          };
      if (name == null || depth > 2) {
        return null;
      }
      Map<String, Text> beans = new LinkedHashMap<>();
      for (JavaAnalysis.Module m : code.modules()) {
        if (!m.repo().equals(scope.module().repo())) {
          continue;
        }
        for (TypeDecl type : m.structure().types()) {
          for (Member member : type.members()) {
            if (member.returns() != null
                && types.contains(member.returns())
                && code.annotationPath(member.annotations(), BEAN) != null) {
              beans.put(member.id().toString(), beanBase(member, m, depth + 1));
            }
          }
        }
      }
      if (beans.size() == 1) {
        return beans.values().iterator().next();
      }
      for (Map.Entry<String, Text> bean : beans.entrySet()) {
        String key = bean.getKey();
        if (key.substring(key.indexOf('#') + 1, key.indexOf('(')).equals(name)) {
          return bean.getValue();
        }
      }
      return null;
    }

    /** The base URL behind the proxy factory or adapter a bean method makes, or null. */
    private Text beanBase(Member bean, JavaAnalysis.Module m, int depth) {
      Scope scope = new Scope(m, bean.id());
      Text base = null;
      for (Invocation invocation : m.structure().invocations()) {
        if (!invocation.caller().equals(bean.id())) {
          continue;
        }
        Chain chain = chain(invocation.expression(), scope, 0);
        Text found = null;
        if (chain.rootType() != null && PROXY_FACTORIES.contains(chain.rootType())) {
          found = factoryBase(chain, invocation.expression(), scope, depth);
        } else if (chain.rootType() != null && PROXY_ADAPTERS.contains(chain.rootType())) {
          found = adapterBase(invocation.expression(), scope, depth);
        }
        base = found != null ? found : base;
      }
      return base;
    }

    // -------------------------------------------------------------------------------------------
    // HTTP service groups: @ImportHttpServices and the group configurers

    /**
     * A base URL an HTTP service group configurer sets.
     *
     * @param groups the groups it names ({@code filterByName}), empty for all
     * @param builder the client builder it configures: a RestClient configurer's applies to
     *     RestClient groups, a WebClient configurer's to WebClient groups
     * @param base the base URL
     * @param module the module of the configurer
     */
    private record GroupBase(
        Set<String> groups, String builder, Text base, JavaAnalysis.Module module) {}

    /**
     * Reads the group configurers of a module: a chain on the groups ({@code
     * groups.filterByName("a").forEachClient((group, builder) -> builder.baseUrl(url))}) and the
     * {@code baseUrl} set on the client builder inside it.
     */
    private void configurers(JavaAnalysis.Module m) {
      List<Invocation> invocations = m.structure().invocations();
      for (Invocation outer : invocations) {
        if (!GROUPS.equals(outer.rootType())) {
          continue;
        }
        List<Expression.Invoke> calls = outer.expression().chain();
        if (!Set.of("forEachClient", "forEachGroup").contains(calls.getLast().method())) {
          continue;
        }
        Set<String> groups = new LinkedHashSet<>();
        boolean named = true;
        for (Expression.Invoke call : calls.subList(0, calls.size() - 1)) {
          named &= call.method().equals("filterByName");
          for (Expression a : call.arguments()) {
            if (a instanceof Expression.Literal l) {
              groups.add(l.value());
            } else {
              named = false;
            }
          }
        }
        if (!named) {
          // A filter that is not a list of names: which groups it applies to is not known.
          continue;
        }
        Text base = null;
        String builder = null;
        for (Invocation inner : invocations) {
          if (inner == outer
              || !CLIENT_BUILDERS.contains(inner.rootType())
              || !inner.caller().equals(outer.caller())
              || !within(inner.where(), outer.where())) {
            continue;
          }
          for (Expression.Invoke call : inner.expression().chain()) {
            if (call.method().equals("baseUrl") && call.arguments().size() == 1) {
              base = text(call.arguments().getFirst(), new Scope(m, inner.caller()));
              builder = inner.rootType();
            }
          }
        }
        if (base != null) {
          groupBases.add(new GroupBase(Set.copyOf(groups), builder, base, m));
        }
      }
    }

    private static boolean within(Location inner, Location outer) {
      return inner.file().path().equals(outer.file().path())
          && inner.start() >= outer.start()
          && inner.end() <= outer.end();
    }

    /**
     * Records the HTTP interfaces a module's {@code @ImportHttpServices} put in a group, as proxies
     * made by the importing type, with the group's base URL.
     */
    private void imports(JavaAnalysis.Module m) {
      for (TypeDecl type : m.structure().types()) {
        for (Annotation written : type.annotations()) {
          List<Annotation> path = code.annotationPath(written, IMPORT_HTTP_SERVICES);
          if (path == null) {
            continue;
          }
          Annotation a = path.getLast();
          String group = Objects.requireNonNullElse(firstOf(a, "group"), DEFAULT_GROUP);
          Set<String> listed = new LinkedHashSet<>(a.values("value"));
          listed.addAll(a.values("types"));
          Set<String> packages = new LinkedHashSet<>(a.values("basePackages"));
          for (String c : a.values("basePackageClasses")) {
            TypeDecl t = code.type(c);
            packages.add(t != null ? t.packageName() : packageOf(c));
          }
          // A group's clients are WebClients if it says so, else RestClients.
          String builder =
              "WEB_CLIENT".equals(firstOf(a, "clientType"))
                  ? REACTIVE + "WebClient$Builder"
                  : WEB + "RestClient$Builder";
          List<Text> bases = groupBaseUrls(group, builder, m);
          for (Map.Entry<String, Client> c : clients.entrySet()) {
            if (!c.getValue().kind().equals(HTTP_INTERFACE)) {
              continue;
            }
            String pkg = code.type(c.getKey()).packageName();
            boolean covered =
                listed.contains(c.getKey())
                    || packages.stream().anyMatch(p -> pkg.equals(p) || pkg.startsWith(p + "."));
            if (!covered) {
              continue;
            }
            List<Proxy> made = proxies.computeIfAbsent(c.getKey(), k -> new ArrayList<>());
            for (Text base : bases) {
              Proxy proxy =
                  new Proxy(
                      c.getKey(),
                      base,
                      m,
                      typeId(type.binaryName()),
                      written.where() != null ? written.where() : type.where(),
                      written.type());
              if (!made.contains(proxy)) {
                made.add(proxy);
              }
            }
          }
        }
      }
    }

    /**
     * The base URLs of a group's clients: those its configurers of the group's client type in the
     * same repository set, those naming it before those for all groups, else Spring Boot's property
     * for it.
     */
    private List<Text> groupBaseUrls(String group, String builder, JavaAnalysis.Module m) {
      List<Text> named = new ArrayList<>();
      List<Text> all = new ArrayList<>();
      for (GroupBase g : groupBases) {
        if (!g.module().repo().equals(m.repo()) || !g.builder().equals(builder)) {
          continue;
        }
        List<Text> into = g.groups().isEmpty() ? all : g.groups().contains(group) ? named : null;
        if (into != null && !into.contains(g.base())) {
          into.add(g.base());
        }
      }
      if (!named.isEmpty()) {
        return named;
      }
      if (!all.isEmpty()) {
        return all;
      }
      return List.of(new Text("${" + GROUP_BASE_URL.formatted(group) + "}", List.of()));
    }

    private Text clientBase(Expression client, Scope scope) {
      return baseOf(chain(client, scope, 0));
    }

    /** The base URL set by the calls of a chain on a client or its builder, or null. */
    private Text baseOf(Chain made) {
      String kind = made.rootType() == null ? null : KINDS.get(made.rootType());
      if (kind == null) {
        return null;
      }
      Text base = null;
      for (Link link : made.links()) {
        String method = link.call().method();
        List<Expression> args = link.call().arguments();
        if (args.size() != 1) {
          continue;
        }
        if (kind.equals(REST_TEMPLATE)
            ? method.equals("rootUri")
            : (kind.equals(REST_CLIENT) || kind.equals(WEB_CLIENT))
                && (method.equals("baseUrl") || method.equals("create"))) {
          base = text(args.getFirst(), link.scope());
        }
      }
      return base;
    }

    /**
     * A base URL read from a bean method, and the module of that method, whose config resolves it.
     */
    private record Found(Text base, JavaAnalysis.Module module) {}

    /**
     * The base URL of a client (or client builder) injected as a parameter or field: that of the
     * bean of its type made by a {@code @Bean} method in the same repository's code, read from that
     * method's code as above, and through a builder bean when the method's own builder is injected.
     * Beans of the caller's own module are considered first, those of the repository's other
     * modules only if it has none. The bean is picked as Spring does, but only when the choice is
     * certain: the {@code @Qualifier} (a bean's name or its own qualifier), else the only
     * candidate, else the one named as the parameter or field, else the {@code @Primary} one; null
     * if none or several match at a step.
     */
    private Found injectedClientBase(Chain chain, Expression e, Scope scope, int depth) {
      if (depth > 2 || chain.rootType() == null || made(chain)) {
        return null;
      }
      Injection point = injection(e, scope, 0);
      if (point == null) {
        return null;
      }
      List<Member> own = new ArrayList<>();
      List<Member> others = new ArrayList<>();
      Map<Member, JavaAnalysis.Module> modules = new HashMap<>();
      for (JavaAnalysis.Module m : code.modules()) {
        if (!m.repo().equals(scope.module().repo())) {
          continue;
        }
        for (TypeDecl type : m.structure().types()) {
          for (Member member : type.members()) {
            if (chain.rootType().equals(member.returns())
                && code.annotationPath(member.annotations(), BEAN) != null) {
              (m == scope.module() ? own : others).add(member);
              modules.put(member, m);
            }
          }
        }
      }
      Member bean = pick(own.isEmpty() ? others : own, point);
      return bean == null ? null : beanClientBase(bean, modules.get(bean), depth + 1);
    }

    /** What a client is injected as: its name, and the qualifier written on it, or null. */
    private record Injection(String name, String qualifier) {}

    private Injection injection(Expression e, Scope scope, int depth) {
      Expression root = e instanceof Expression.Invoke i ? i.root() : e;
      switch (root) {
        case Expression.Param p -> {
          ModuleStructure.Parameter parameter = parameter(scope.in(), p.index());
          return parameter == null
              ? null
              : new Injection(parameter.name(), qualifier(parameter.annotations()));
        }
        case Expression.FieldRef f -> {
          Assignment given = single(f);
          if (depth < 2 && given != null) {
            Scope inner = new Scope(moduleOf(f.owner(), scope.module()), given.in());
            Injection found = injection(given.value(), inner, depth + 1);
            if (found != null) {
              Member field = field(f);
              String q = field == null ? null : qualifier(field.annotations());
              return new Injection(found.name(), found.qualifier() != null ? found.qualifier() : q);
            }
          }
          Member field = field(f);
          return new Injection(f.name(), field == null ? null : qualifier(field.annotations()));
        }
        default -> {
          return null;
        }
      }
    }

    private String qualifier(List<Annotation> annotations) {
      List<Annotation> path = code.annotationPath(annotations, QUALIFIER);
      return path == null || path.getLast().values("value").isEmpty()
          ? null
          : path.getLast().values("value").getFirst();
    }

    /** The bean's name: its method's, or null when {@code @Bean} names it (not supported). */
    private String beanName(Member bean) {
      List<Annotation> path = code.annotationPath(bean.annotations(), BEAN);
      if (path != null
          && (!path.getLast().values("value").isEmpty()
              || !path.getLast().values("name").isEmpty())) {
        return null;
      }
      String key = bean.id().toString();
      return key.substring(key.indexOf('#') + 1, key.indexOf('('));
    }

    /** The bean Spring injects, if the choice is certain: see {@link #injectedClientBase}. */
    private Member pick(List<Member> beans, Injection point) {
      if (beans.isEmpty()) {
        return null;
      }
      if (point.qualifier() != null) {
        return only(
            beans.stream()
                .filter(
                    b ->
                        point.qualifier().equals(beanName(b))
                            || point.qualifier().equals(qualifier(b.annotations())))
                .toList());
      }
      if (beans.size() == 1) {
        return beans.getFirst();
      }
      Member named = only(beans.stream().filter(b -> point.name().equals(beanName(b))).toList());
      if (named != null) {
        return named;
      }
      if (beans.stream().anyMatch(b -> point.name().equals(beanName(b)))) {
        return null;
      }
      return only(
          beans.stream()
              .filter(b -> code.annotationPath(b.annotations(), PRIMARY) != null)
              .toList());
    }

    private static Member only(List<Member> beans) {
      return beans.size() == 1 ? beans.getFirst() : null;
    }

    /** The base URL of the client a bean method makes, and the module to resolve it in. */
    private Found beanClientBase(Member bean, JavaAnalysis.Module m, int depth) {
      Scope scope = new Scope(m, bean.id());
      Found base = null;
      for (Invocation invocation : m.structure().invocations()) {
        if (!invocation.caller().equals(bean.id())) {
          continue;
        }
        Chain made = chain(invocation.expression(), scope, 0);
        String kind = made.rootType() == null ? null : KINDS.get(made.rootType());
        if (kind == null
            || !(kind.equals(REST_TEMPLATE)
                || kind.equals(REST_CLIENT)
                || kind.equals(WEB_CLIENT))) {
          continue;
        }
        Text text = baseOf(made);
        Found found =
            text != null
                ? new Found(text, m)
                : injectedClientBase(made, invocation.expression(), scope, depth);
        base = found != null ? found : base;
      }
      return base;
    }

    /** A base URL a declarative client's calls go to, resolved in the config of its module. */
    private record Base(Text url, CodeConfig.View config) {}

    private void declarative(TypeDecl type, Client client) {
      Annotation written = client.annotation().getFirst();
      Annotation declared = client.annotation().getLast();
      EntityId typeId = typeId(type.binaryName());
      ObjectNode attributes = FactJson.newObject();
      attributes.put("annotation", written.type());
      fact(typeId, HTTP_CLIENT, client.kind(), attributes, written.where(), module);

      String base = "";
      String service = null;
      String clientPath = "";
      List<Base> bases = new ArrayList<>();
      if (client.kind().equals(HTTP_INTERFACE)) {
        for (Proxy p : proxies.getOrDefault(type.binaryName(), List.of())) {
          Base b =
              new Base(
                  p.base() == null ? new Text("", List.of()) : p.base(),
                  configs.forModule(p.module().repo(), p.module().directory()));
          if (!bases.contains(b)) {
            bases.add(b);
          }
        }
        if (bases.isEmpty()) {
          bases.add(new Base(new Text("", List.of()), config));
        }
      } else if (client.kind().equals(FEIGN)) {
        service = firstOf(declared, "name", "value", "serviceId");
        String url = firstOf(declared, "url");
        clientPath = Objects.requireNonNullElse(firstOf(declared, "path"), "");
        if (url != null && !url.isBlank()) {
          base = url.contains("://") || url.startsWith("${") ? url : "http://" + url;
        } else {
          base = service == null ? "" : "http://" + service;
        }
      } else {
        String uri = firstOf(declared, "baseUri");
        String key = Objects.requireNonNullElse(firstOf(declared, "configKey"), type.binaryName());
        base =
            uri != null && !uri.isBlank()
                ? uri
                : "${quarkus.rest-client." + key + ".url:${" + key + "/mp-rest/url}}";
      }
      if (bases.isEmpty()) {
        bases.add(new Base(new Text(base, List.of()), config));
      }
      Mapping classMapping = null;
      List<TypeDecl> lineage = lineage(type);
      for (TypeDecl t : lineage) {
        classMapping = mapping(t.annotations(), client.kind(), true);
        if (classMapping != null) {
          break;
        }
      }
      String classPath =
          classMapping == null || classMapping.paths().isEmpty()
              ? ""
              : classMapping.paths().getFirst();
      String basePath = SpringExtractor.join(clientPath, classPath);
      Map<String, Member> bySignature = new LinkedHashMap<>();
      Map<String, Mapping> mappings = new HashMap<>();
      Map<String, JavaAnalysis.Module> sources = new HashMap<>();
      for (TypeDecl t : lineage) {
        JavaAnalysis.Module source = t == type ? module : code.module(t.binaryName());
        for (Member m : t.members()) {
          if (!m.id().type().equals(ModuleStructure.METHOD)) {
            continue;
          }
          String signature = signature(m.id());
          bySignature.putIfAbsent(signature, m);
          if (!mappings.containsKey(signature)) {
            Mapping mapping = mapping(m.annotations(), client.kind(), false);
            if (mapping != null) {
              mappings.put(signature, mapping);
              sources.put(signature, source != null ? source : module);
            }
          }
        }
      }
      for (Map.Entry<String, Member> e : bySignature.entrySet()) {
        Mapping mapping = mappings.get(e.getKey());
        if (mapping == null) {
          continue;
        }
        List<String> paths = mapping.paths().isEmpty() ? List.of("") : mapping.paths();
        List<String> methods = mapping.methods();
        if (methods.isEmpty() && client.kind().equals(FEIGN)) {
          methods = List.of("GET");
        } else if (methods.isEmpty() && classMapping != null) {
          // An HTTP interface's method may take its HTTP method from the interface's annotation.
          methods = classMapping.methods();
        }
        for (Base b : bases) {
          for (String path : paths) {
            String own =
                client.kind().equals(HTTP_INTERFACE)
                    // Spring joins the interface's and the method's URL, then applies the base
                    // URL unless the result is a whole URL itself.
                    ? joinUrl(classPath, path)
                    : SpringExtractor.join(basePath, path);
            String url = joinUrl(b.url().text(), own);
            // The base's unfollowed parts count only if the base is part of the URL.
            List<String> unknown = ABSOLUTE.matcher(own).find() ? List.of() : b.url().unknown();
            for (String method : methods.isEmpty() ? List.of("") : methods) {
              Call call =
                  new Call(
                      e.getValue().id(),
                      method.isEmpty() ? null : method,
                      new Text(url.isEmpty() ? "/" : url, unknown),
                      service,
                      client.kind(),
                      type.binaryName(),
                      mapping.written().type(),
                      mapping.written().where(),
                      sources.get(e.getKey()));
              call(call, b.config());
            }
          }
        }
      }
    }

    /** A request mapping: its paths and HTTP methods, and the annotation written. */
    private record Mapping(List<String> paths, List<String> methods, Annotation written) {}

    /**
     * The mapping written on a client interface or method: a Spring request mapping or Feign
     * request line for a Feign client, {@code @HttpExchange} for an HTTP interface, a Jakarta REST
     * one for the others.
     */
    private Mapping mapping(List<Annotation> annotations, String kind, boolean onType) {
      if (kind.equals(HTTP_INTERFACE)) {
        return exchange(annotations);
      }
      if (kind.equals(FEIGN)) {
        Mapping spring = springMapping(annotations);
        if (spring != null) {
          return spring;
        }
        if (!onType) {
          List<Annotation> line = code.annotationPath(annotations, REQUEST_LINE);
          if (line != null && !line.getLast().values("value").isEmpty()) {
            String[] parts = line.getLast().values("value").getFirst().strip().split("\\s+", 2);
            if (parts.length == 2) {
              return new Mapping(List.of(parts[1]), List.of(parts[0]), line.getFirst());
            }
          }
        }
      }
      return onType ? restPath(annotations) : restMethod(annotations);
    }

    private Mapping springMapping(List<Annotation> annotations) {
      Annotation a = null;
      List<Annotation> path = null;
      for (Annotation candidate : annotations) {
        List<Annotation> found = code.annotationPath(candidate, REQUEST_MAPPING);
        if (found != null && (path == null || found.size() < path.size())) {
          a = candidate;
          path = found;
        }
      }
      if (path == null) {
        return null;
      }
      Set<String> paths = new LinkedHashSet<>();
      for (Annotation p : path) {
        paths.addAll(p.values("path"));
        paths.addAll(p.values("value"));
        if (!paths.isEmpty()) {
          break;
        }
      }
      Set<String> methods = new LinkedHashSet<>();
      for (Annotation p : path) {
        methods.addAll(p.values("method"));
      }
      return new Mapping(List.copyOf(paths), List.copyOf(methods), a);
    }

    /**
     * {@code @HttpExchange}, or an annotation that carries it ({@code @GetExchange}): the URL and
     * HTTP method of the nearest annotation on the path that sets them.
     */
    private Mapping exchange(List<Annotation> annotations) {
      for (Annotation candidate : annotations) {
        List<Annotation> path = code.annotationPath(candidate, HTTP_EXCHANGE);
        if (path == null) {
          continue;
        }
        String url = null;
        String method = null;
        for (Annotation p : path) {
          url = url != null ? url : firstOf(p, "value", "url");
          method = method != null ? method : firstOf(p, "method");
        }
        return new Mapping(
            url == null ? List.of() : List.of(url),
            method == null ? List.of() : List.of(upper(method)),
            candidate);
      }
      return null;
    }

    private Mapping restPath(List<Annotation> annotations) {
      for (String type : REST_PATHS) {
        List<Annotation> path = code.annotationPath(annotations, type);
        if (path != null) {
          return new Mapping(path.getLast().values("value"), List.of(), path.getFirst());
        }
      }
      return null;
    }

    private Mapping restMethod(List<Annotation> annotations) {
      for (Annotation a : annotations) {
        for (String httpMethod : REST_HTTP_METHODS) {
          List<Annotation> path = code.annotationPath(a, httpMethod);
          if (path != null && path.size() > 1) {
            Mapping own = restPath(annotations);
            return new Mapping(
                own == null ? List.of() : own.paths(), path.getLast().values("value"), a);
          }
        }
      }
      return null;
    }

    /** The type and its supertypes declared in the analyzed sources, nearest first. */
    private List<TypeDecl> lineage(TypeDecl type) {
      List<TypeDecl> lineage = new ArrayList<>();
      Set<String> seen = new HashSet<>();
      Deque<TypeDecl> queue = new ArrayDeque<>(List.of(type));
      while (!queue.isEmpty()) {
        TypeDecl t = queue.removeFirst();
        if (!seen.add(t.binaryName())) {
          continue;
        }
        lineage.add(t);
        for (Supertype s : t.supertypes()) {
          TypeDecl supertype = code.type(s.binaryName());
          if (supertype != null) {
            queue.addLast(supertype);
          }
        }
      }
      return lineage;
    }

    // -------------------------------------------------------------------------------------------
    // Who enables and injects a client interface

    private void enables(TypeDecl type) {
      for (String enable : ENABLE_FEIGN_CLIENTS) {
        List<Annotation> path = code.annotationPath(type.annotations(), enable);
        if (path == null) {
          continue;
        }
        Annotation a = path.getLast();
        Set<String> listed = new LinkedHashSet<>(a.values("clients"));
        Set<String> packages = new LinkedHashSet<>();
        packages.addAll(a.values("value"));
        packages.addAll(a.values("basePackages"));
        for (String c : a.values("basePackageClasses")) {
          TypeDecl t = code.type(c);
          packages.add(t != null ? t.packageName() : packageOf(c));
        }
        if (listed.isEmpty() && packages.isEmpty()) {
          packages.add(type.packageName());
        }
        for (Map.Entry<String, Client> c : clients.entrySet()) {
          if (!c.getValue().kind().equals(FEIGN)) {
            continue;
          }
          TypeDecl client = code.type(c.getKey());
          boolean covered =
              listed.isEmpty()
                  ? packages.stream()
                      .anyMatch(
                          p ->
                              client.packageName().equals(p)
                                  || client.packageName().startsWith(p + "."))
                  : listed.contains(c.getKey());
          if (covered) {
            ObjectNode attributes = FactJson.newObject();
            attributes.put("annotation", path.getFirst().type());
            relation(
                typeId(type.binaryName()),
                ENABLES,
                typeId(c.getKey()),
                attributes,
                path.getFirst().where() != null ? path.getFirst().where() : type.where());
          }
        }
        return;
      }
    }

    private void injects(TypeDecl type) {
      for (Member m : type.members()) {
        Set<String> injected = new LinkedHashSet<>();
        if (m.type() != null) {
          injected.add(m.type());
        } else if (signature(m.id()).startsWith(ModuleStructure.CONSTRUCTOR + "(")) {
          injected.addAll(parameterTypes(m.id()));
        }
        for (String t : injected) {
          if (clients.containsKey(t) && !t.equals(type.binaryName())) {
            ObjectNode attributes = FactJson.newObject();
            attributes.put("member", m.id().toString());
            relation(typeId(type.binaryName()), INJECTS, typeId(t), attributes, m.where());
          }
        }
      }
    }

    // -------------------------------------------------------------------------------------------
    // Imperative clients: RestTemplate, RestClient, WebClient and the Jakarta REST client API

    /** A call of a chain, and the code it is written in, whose parameters it may use. */
    private record Link(Expression.Invoke call, Scope scope) {}

    /** Code an expression is in: the module, and the method, constructor or type. */
    private record Scope(JavaAnalysis.Module module, EntityId in) {}

    /** A chain of calls with the calls that made the object it starts from, and that type. */
    private record Chain(String rootType, List<Link> links) {}

    private void imperative(Invocation invocation) {
      Scope scope = new Scope(module, invocation.caller());
      Chain chain = chain(invocation.expression(), scope, 0);
      String kind = chain.rootType() == null ? null : KINDS.get(chain.rootType());
      if (kind == null) {
        return;
      }
      Found bean =
          kind.equals(REST_TEMPLATE) || kind.equals(REST_CLIENT) || kind.equals(WEB_CLIENT)
              ? injectedClientBase(chain, invocation.expression(), scope, 0)
              : null;
      Text injected = bean == null ? null : bean.base();
      // The bean's placeholders resolve in its own module's config, unless the call sets its base.
      CodeConfig.View view =
          bean != null && bean.module() != module && baseOf(chain) == null
              ? configs.forModule(bean.module().repo(), bean.module().directory())
              : config;
      Request request =
          switch (kind) {
            case REST_TEMPLATE -> restTemplate(chain.links(), injected);
            case REST_CLIENT, WEB_CLIENT -> fluent(chain.links(), injected);
            case JAKARTA_CLIENT -> jakarta(chain.links());
            default -> built(kind, chain.rootType(), chain.links());
          };
      if (request == null) {
        return;
      }
      call(
          new Call(
              invocation.caller(),
              request.method(),
              request.url(),
              null,
              kind,
              null,
              null,
              invocation.where(),
              module),
          view);
    }

    /** An HTTP request a chain sends: the HTTP method, if known, and the URL as written. */
    private record Request(String method, Text url) {}

    /**
     * The calls of a chain, starting from the calls that made the object the chain starts from when
     * a field it is read from is given one value, in the code that gives it.
     */
    private Chain chain(Expression e, Scope scope, int depth) {
      if (e instanceof Expression.Invoke invoke) {
        Chain inner =
            invoke.receiver() == null
                ? new Chain(null, List.of())
                : chain(invoke.receiver(), scope, depth);
        List<Link> links = new ArrayList<>(inner.links());
        links.add(new Link(invoke, scope));
        String root =
            inner.rootType() == null
                    && invoke.receiver() instanceof Expression.TypeRef t
                    && invoke.method().equals(ModuleStructure.CONSTRUCTOR)
                ? t.binaryName()
                : inner.rootType();
        return new Chain(root, links);
      }
      return switch (e) {
        case Expression.TypeRef t -> new Chain(t.binaryName(), List.of());
        case Expression.Param p -> {
          List<String> types = parameterTypes(scope.in());
          yield new Chain(p.index() < types.size() ? types.get(p.index()) : null, List.of());
        }
        case Expression.FieldRef f -> {
          Assignment given = single(f);
          if (depth < 2 && given != null && given.value() instanceof Expression.Invoke) {
            Chain made =
                chain(
                    given.value(),
                    new Scope(moduleOf(f.owner(), scope.module()), given.in()),
                    depth + 1);
            if (made.rootType() != null) {
              yield made;
            }
          }
          yield new Chain(fieldType(f), List.of());
        }
        default -> new Chain(null, List.of());
      };
    }

    private Request restTemplate(List<Link> links, Text injected) {
      Text base = injected;
      for (Link link : links) {
        String method = link.call().method();
        List<Expression> args = link.call().arguments();
        if (method.equals("rootUri") && args.size() == 1) {
          base = text(args.getFirst(), link.scope());
        }
        String http = REST_TEMPLATE_METHODS.get(method);
        if (http == null || args.isEmpty()) {
          continue;
        }
        // exchange(RequestEntity, type): the entity says where to.
        Request entity = requestEntity(args.getFirst(), link.scope());
        if (entity != null) {
          return new Request(entity.method(), join(base, entity.url()));
        }
        if (http.isEmpty()) {
          if (args.size() < 2) {
            continue;
          }
          http = httpMethod(args.get(1));
        }
        return new Request(http, join(base, url(args.getFirst(), link.scope())));
      }
      return null;
    }

    /** {@code RequestEntity.post(uri)} and the like, or null. */
    private Request requestEntity(Expression e, Scope scope) {
      if (!(e instanceof Expression.Invoke invoke)) {
        return null;
      }
      Expression.Invoke first = invoke.chain().getFirst();
      if (!(first.receiver() instanceof Expression.TypeRef t)
          || !t.binaryName().equals(SPRING + "http.RequestEntity")
          || first.arguments().isEmpty()) {
        return null;
      }
      String method = first.method().toUpperCase(Locale.ROOT);
      if (first.method().equals("method") && first.arguments().size() >= 2) {
        return new Request(
            httpMethod(first.arguments().getFirst()), url(first.arguments().get(1), scope));
      }
      return HTTP_METHODS.contains(method)
          ? new Request(method, url(first.arguments().getFirst(), scope))
          : null;
    }

    /** RestClient and WebClient: a base URL, an HTTP method, then the URI. */
    private Request fluent(List<Link> links, Text injected) {
      Text base = injected;
      String http = null;
      boolean requested = false;
      for (Link link : links) {
        String method = link.call().method();
        List<Expression> args = link.call().arguments();
        if (!requested) {
          if ((method.equals("baseUrl") || method.equals("create")) && args.size() == 1) {
            base = text(args.getFirst(), link.scope());
          } else if (args.isEmpty() && HTTP_METHODS.contains(upper(method))) {
            http = upper(method);
            requested = true;
          } else if (method.equals("method") && args.size() == 1) {
            http = httpMethod(args.getFirst());
            requested = true;
          }
        } else if (method.equals("uri") && !args.isEmpty()) {
          return new Request(http, join(base, url(args.getFirst(), link.scope())));
        }
      }
      return requested ? new Request(http, base == null ? new Text("", List.of()) : base) : null;
    }

    /** The Jakarta REST client API: a target, paths, a request, then the HTTP method. */
    private Request jakarta(List<Link> links) {
      Text url = null;
      boolean requested = false;
      for (Link link : links) {
        String method = link.call().method();
        List<Expression> args = link.call().arguments();
        if (!requested) {
          if (method.equals("target") && args.size() == 1) {
            url = url(args.getFirst(), link.scope());
          } else if (method.equals("path") && args.size() == 1 && url != null) {
            Text path = text(args.getFirst(), link.scope());
            url = new Text(joinUrl(url.text(), slashed(path.text())), url.unknown());
          } else if (method.equals("request")) {
            requested = true;
          }
          continue;
        }
        if (url == null) {
          return null;
        }
        if (HTTP_METHODS.contains(upper(method))) {
          return new Request(upper(method), url);
        }
        if ((method.equals("method") || method.equals("build")) && !args.isEmpty()) {
          return new Request(httpMethod(args.getFirst()), url);
        }
        if (method.startsWith("build") && HTTP_METHODS.contains(upper(method.substring(5)))) {
          return new Request(upper(method.substring(5)), url);
        }
      }
      return null;
    }

    /**
     * A request object that is built, then sent ({@code java.net.http}, OkHttp, Apache HttpClient):
     * read where the chain makes the request, its last call, so a request kept in a variable before
     * it is finished counts once. An HTTP method not said is {@code GET}, as the builders default.
     */
    private Request built(String kind, String rootType, List<Link> links) {
      if (links.isEmpty()) {
        return null;
      }
      if (FLUENT_REQUESTS.contains(rootType)) {
        // Request.get(uri).execute(): a fluent request is read where it is executed, so one kept
        // in a variable counts once.
        boolean executed = links.stream().anyMatch(l -> l.call().method().equals("execute"));
        return executed ? fluentRequest(links.getFirst()) : null;
      }
      if (FLUENT_EXECUTORS.contains(rootType)) {
        // executor.execute(Request.post(uri))
        for (Link link : links) {
          List<Expression> args = link.call().arguments();
          if (link.call().method().equals("execute")
              && args.size() == 1
              && args.getFirst() instanceof Expression.Invoke request
              && request.root() instanceof Expression.TypeRef t
              && FLUENT_REQUESTS.contains(t.binaryName())) {
            return fluentRequest(new Link(request.chain().getFirst(), link.scope()));
          }
        }
        return null;
      }
      Expression.Invoke last = links.getLast().call();
      Scope scope = links.getLast().scope();
      String apache = APACHE_REQUESTS.get(rootType);
      boolean factory = APACHE_FACTORIES.contains(rootType);
      if (apache != null || factory || rootType.equals(APACHE_SIMPLE_REQUEST)) {
        // new HttpGet(uri), SimpleHttpRequest.create(method, uri), ClassicHttpRequests.get(uri)
        if (links.size() != 1) {
          return null;
        }
        List<Expression> args = last.arguments();
        if (apache != null) {
          return last.method().equals(ModuleStructure.CONSTRUCTOR) && !args.isEmpty()
              ? new Request(apache, url(args.getFirst(), scope))
              : null;
        }
        if (last.method().equals("create") && args.size() == 2) {
          return new Request(httpMethod(args.getFirst()), url(args.get(1), scope));
        }
        return factory && args.size() == 1 && HTTP_METHODS.contains(upper(last.method()))
            ? new Request(upper(last.method()), url(args.getFirst(), scope))
            : null;
      }
      if (!last.method().equals("build") || !last.arguments().isEmpty()) {
        return null;
      }
      String http = null;
      Text url = null;
      for (Link link : links) {
        String method = link.call().method();
        List<Expression> args = link.call().arguments();
        if (args.size() == 1 && Set.of("newBuilder", "uri", "url", "setUri").contains(method)) {
          url = url(args.getFirst(), link.scope());
        } else if (method.equals("method") && !args.isEmpty()
            || method.equals("create") && args.size() == 1 && kind.equals(APACHE_CLIENT)) {
          http = httpMethod(args.getFirst());
        } else if (HTTP_METHODS.contains(upper(method)) && args.size() <= 1) {
          http = upper(method);
          // Apache's request builders take the URI with the method: ClassicRequestBuilder.get(uri)
          if (kind.equals(APACHE_CLIENT) && args.size() == 1) {
            url = url(args.getFirst(), link.scope());
          }
        }
      }
      if (url == null) {
        return null;
      }
      return new Request(http == null && !kind.equals(APACHE_CLIENT) ? "GET" : http, url);
    }

    /**
     * The request an Apache fluent {@code Request}'s factory makes: {@code get(uri)} and the like
     * (4's {@code Get(uri)}), or {@code create(method, uri)}; null for anything else.
     */
    private Request fluentRequest(Link first) {
      Expression.Invoke call = first.call();
      List<Expression> args = call.arguments();
      if (!(call.receiver() instanceof Expression.TypeRef)) {
        return null;
      }
      if (call.method().equals("create") && args.size() == 2) {
        return new Request(httpMethod(args.getFirst()), url(args.get(1), first.scope()));
      }
      return args.size() == 1 && HTTP_METHODS.contains(upper(call.method()))
          ? new Request(upper(call.method()), url(args.getFirst(), first.scope()))
          : null;
    }

    /**
     * A URL argument: a string, or a {@code URI}, {@code URL}, OkHttp {@code HttpUrl} or {@code
     * UriComponentsBuilder} made of one.
     */
    private Text url(Expression e, Scope scope) {
      if (e instanceof Expression.Invoke invoke) {
        List<Expression.Invoke> chain = invoke.chain();
        Expression.Invoke first = chain.getFirst();
        if (first.receiver() instanceof Expression.TypeRef t && !first.arguments().isEmpty()) {
          if ((t.binaryName().equals("java.net.URI") || t.binaryName().equals("java.net.URL"))
              && (first.method().equals("create")
                  || first.method().equals(ModuleStructure.CONSTRUCTOR))
              && first.arguments().size() == 1) {
            return text(first.arguments().getFirst(), scope);
          }
          if (t.binaryName().equals("okhttp3.HttpUrl")
              && (first.method().equals("get") || first.method().equals("parse"))
              && first.arguments().size() == 1) {
            return text(first.arguments().getFirst(), scope);
          }
          if (t.binaryName().equals(SPRING + "web.util.UriComponentsBuilder")
              && Set.of("fromHttpUrl", "fromUriString", "fromPath").contains(first.method())) {
            Text url = text(first.arguments().getFirst(), scope);
            for (Expression.Invoke link : chain.subList(1, chain.size())) {
              if (link.method().equals("path") && link.arguments().size() == 1) {
                Text path = text(link.arguments().getFirst(), scope);
                url = new Text(url.text() + path.text(), merge(url.unknown(), path.unknown()));
              }
            }
            return url;
          }
        }
      }
      return text(e, scope);
    }

    /** An HTTP method argument: {@code HttpMethod.POST}, or a string such as {@code "POST"}. */
    private static String httpMethod(Expression e) {
      String name =
          switch (e) {
            case Expression.FieldRef f -> f.name();
            case Expression.Literal l -> l.value();
            case Expression.Invoke i
                when i.method().equals("valueOf")
                    && i.arguments().size() == 1
                    && i.arguments().getFirst() instanceof Expression.Literal l ->
                l.value();
            default -> null;
          };
      return name != null && HTTP_METHODS.contains(upper(name)) ? upper(name) : null;
    }

    // -------------------------------------------------------------------------------------------
    // Values: following an expression one hop

    /**
     * An expression as text: constants and concatenations as written, a {@code @Value} field or
     * parameter as its placeholder, a field given one such value in a constructor as that value,
     * and anything else as its source in braces, {@code {productId}}.
     */
    private Text text(Expression e, Scope scope) {
      List<String> unknown = new ArrayList<>();
      String text = text(e, scope, unknown, 0);
      return new Text(text, List.copyOf(unknown));
    }

    private String text(Expression e, Scope scope, List<String> unknown, int depth) {
      if (e instanceof Expression.Literal l) {
        return l.value();
      }
      if (e instanceof Expression.Concat c) {
        StringBuilder b = new StringBuilder();
        for (Expression p : c.parts()) {
          b.append(text(p, scope, unknown, depth));
        }
        return b.toString();
      }
      if (e instanceof Expression.Param p) {
        ModuleStructure.Parameter parameter = parameter(scope.in(), p.index());
        String value = parameter == null ? null : value(parameter.annotations());
        if (value != null) {
          return value;
        }
      }
      if (e instanceof Expression.FieldRef f) {
        Member field = field(f);
        String value = field == null ? null : value(field.annotations());
        if (value != null) {
          return value;
        }
        Assignment given = single(f);
        if (depth < 1
            && given != null
            && (given.value() instanceof Expression.Param
                || given.value() instanceof Expression.Literal
                || given.value() instanceof Expression.Concat)) {
          List<String> inner = new ArrayList<>();
          String followed =
              text(
                  given.value(),
                  new Scope(moduleOf(f.owner(), scope.module()), given.in()),
                  inner,
                  depth + 1);
          if (inner.isEmpty()) {
            return followed;
          }
        }
      }
      String source = source(e, scope);
      unknown.add(source);
      return "{" + source + "}";
    }

    /** The text of a {@code @Value} annotation among those given, or null. */
    private String value(List<Annotation> annotations) {
      List<Annotation> path = code.annotationPath(annotations, VALUE);
      if (path == null || path.getLast().values("value").isEmpty()) {
        return null;
      }
      return path.getLast().values("value").getFirst();
    }

    /** An expression as Java-like source, for one not followed. */
    private String source(Expression e, Scope scope) {
      return switch (e) {
        case Expression.Literal l -> l.value();
        case Expression.Concat c ->
            String.join(" + ", c.parts().stream().map(p -> source(p, scope)).toList());
        case Expression.FieldRef f -> f.name();
        case Expression.Param p -> {
          ModuleStructure.Parameter parameter = parameter(scope.in(), p.index());
          yield parameter != null ? parameter.name() : "arg" + p.index();
        }
        case Expression.TypeRef t -> simpleName(t.binaryName());
        case Expression.Invoke i -> {
          String args =
              String.join(", ", i.arguments().stream().map(a -> source(a, scope)).toList());
          if (i.method().equals(ModuleStructure.CONSTRUCTOR)) {
            yield "new " + source(i.receiver(), scope) + "(" + args + ")";
          }
          yield (i.receiver() == null ? "" : source(i.receiver(), scope) + ".")
              + i.method()
              + "("
              + args
              + ")";
        }
        case Expression.Lambda l -> l.source();
        case Expression.Unknown u -> u.source();
      };
    }

    /** The one value given to a field, if all the values given to it are the same. */
    private Assignment single(Expression.FieldRef f) {
      EntityId id = fieldId(f);
      List<Assignment> given = id == null ? null : assignments.get(id);
      if (given == null || given.isEmpty()) {
        return null;
      }
      Assignment first = given.getFirst();
      for (Assignment a : given) {
        if (!a.value().equals(first.value())) {
          return null;
        }
      }
      return first;
    }

    private Member field(Expression.FieldRef f) {
      TypeDecl type = code.type(f.owner());
      EntityId id = fieldId(f);
      if (type == null || id == null) {
        return null;
      }
      for (Member m : type.members()) {
        if (m.id().equals(id)) {
          return m;
        }
      }
      return null;
    }

    private String fieldType(Expression.FieldRef f) {
      Member field = field(f);
      return field == null ? null : field.type();
    }

    private ModuleStructure.Parameter parameter(EntityId method, int index) {
      if (!method.type().equals(ModuleStructure.METHOD)) {
        return null;
      }
      String key = method.key();
      TypeDecl type = code.type(key.substring(0, key.indexOf('#')));
      if (type == null) {
        return null;
      }
      for (Member m : type.members()) {
        if (m.id().equals(method)) {
          return index < m.parameters().size() ? m.parameters().get(index) : null;
        }
      }
      return null;
    }

    private JavaAnalysis.Module moduleOf(String binaryName, JavaAnalysis.Module fallback) {
      JavaAnalysis.Module m = code.module(binaryName);
      return m != null ? m : fallback;
    }

    // -------------------------------------------------------------------------------------------
    // Facts

    /**
     * An HTTP request some code sends.
     *
     * @param subject the method sending it, or the type for code outside methods
     * @param method the HTTP method, or null if not known
     * @param url the URL as written, placeholders kept
     * @param service the client name it is sent to, for a Feign client
     * @param client the client kind
     * @param clientType a declarative client's interface
     * @param annotation a declarative client method's mapping annotation
     * @param where the evidence
     * @param source the module the evidence is in
     */
    private record Call(
        EntityId subject,
        String method,
        Text url,
        String service,
        String client,
        String clientType,
        String annotation,
        Location where,
        JavaAnalysis.Module source) {}

    private void call(Call call, CodeConfig.View config) {
      Recorded byDefault = recorded(config, null, call);
      for (String profile : config.views()) {
        Recorded value = profile == null ? byDefault : recorded(config, profile, call);
        if (profile != null && value.equals(byDefault)) {
          continue;
        }
        ObjectNode attributes = FactJson.newObject();
        if (profile != null) {
          attributes.put("profile", profile);
        }
        if (call.method() != null) {
          attributes.put("httpMethod", call.method());
        }
        attributes.put("client", call.client());
        if (call.clientType() != null) {
          attributes.put("clientType", call.clientType());
        }
        if (call.annotation() != null) {
          attributes.put("annotation", call.annotation());
        }
        String object;
        if (value.masked()) {
          attributes.put("masked", true);
          object = value.written();
        } else {
          Split split = split(value.url());
          if (split.path() != null) {
            attributes.put("path", split.path());
          }
          if (split.absolute()) {
            attributes.put("url", value.url());
            attributes.put("baseUrl", split.base());
          }
          if (value.service() != null) {
            attributes.put("service", value.service());
          }
          if (!value.written().equals(value.url())) {
            attributes.put("written", value.written());
          }
          if (!value.unresolved().isEmpty()) {
            ArrayNode keys = attributes.putArray("unresolved");
            value.unresolved().forEach(keys::add);
          }
          object = value.url();
        }
        if (!call.url().unknown().isEmpty()) {
          ArrayNode expressions = attributes.putArray("expressions");
          call.url().unknown().forEach(x -> expressions.add(UrlCredentials.withoutCredentials(x)));
        }
        fact(
            call.subject(),
            HTTP_CALL,
            call.method() == null ? object : call.method() + " " + object,
            attributes,
            call.where(),
            call.source());
      }
    }

    /** A call's URL and service name resolved in one view, as they may be recorded. */
    private record Recorded(
        String url, String written, String service, SortedSet<String> unresolved, boolean masked) {}

    private Recorded recorded(CodeConfig.View config, String profile, Call call) {
      String written = clean(call.url().text());
      Resolution url = config.resolve(profile, written);
      SortedSet<String> unresolved = new TreeSet<>(url.unresolved());
      SortedSet<String> used = new TreeSet<>(url.used());
      String resolved = clean(url.value());
      if (call.client().equals(FEIGN)) {
        // Spring Cloud OpenFeign adds http:// to a url without a scheme, once resolved.
        resolved = withScheme(resolved);
      }
      String service = null;
      if (call.service() != null) {
        Resolution s = config.resolve(profile, call.service());
        unresolved.addAll(s.unresolved());
        used.addAll(s.used());
        service = s.value();
      }
      CodeConfig.CodeValue value = CodeConfig.CodeValue.of(resolved, written, unresolved, used);
      return new Recorded(
          value.value(),
          value.written(),
          value.masked() ? null : service,
          value.unresolved(),
          value.masked());
    }

    private void fact(
        EntityId subject,
        String predicate,
        String object,
        ObjectNode attributes,
        Location where,
        JavaAnalysis.Module source) {
      if (subject != null) {
        put(subject, predicate, FactObject.string(object), attributes, where, source);
      }
    }

    private void relation(
        EntityId subject,
        String predicate,
        EntityId object,
        ObjectNode attributes,
        Location where) {
      if (subject != null && object != null) {
        put(subject, predicate, FactObject.entity(object), attributes, where, module);
      }
    }

    private void put(
        EntityId subject,
        String predicate,
        FactObject object,
        ObjectNode attributes,
        Location where,
        JavaAnalysis.Module source) {
      RepoBuild repo = source.repo();
      Evidence evidence =
          new Evidence(
              repo.repo().name(),
              repo.commit(),
              where.file().path(),
              where.start() > 0 ? new Evidence.LineRange(where.start(), where.end()) : null,
              where.file().contentHash());
      Fact fact =
          Fact.create(
              subject,
              predicate,
              object,
              attributes,
              ORIGIN,
              evidence,
              source.confidence(),
              validity);
      facts.putIfAbsent(fact.id(), fact);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // URLs

  /** A base URL and a path joined, unless the path is a whole URL itself. */
  private static Text join(Text base, Text path) {
    if (base == null) {
      return path;
    }
    return new Text(joinUrl(base.text(), path.text()), merge(base.unknown(), path.unknown()));
  }

  private static List<String> merge(List<String> a, List<String> b) {
    List<String> all = new ArrayList<>(a);
    all.addAll(b);
    return List.copyOf(all);
  }

  /**
   * A base URL and a path joined with one slash between, without the base's query; an absolute path
   * stands alone.
   */
  static String joinUrl(String written, String path) {
    // A base's query and fragment do not carry over to the path.
    String base = clean(written);
    if (path.isEmpty()) {
      return base;
    }
    if (base.isEmpty() || ABSOLUTE.matcher(path).find()) {
      return path;
    }
    if (base.endsWith("/") && path.startsWith("/")) {
      return base + path.substring(1);
    }
    if (!base.endsWith("/") && !path.startsWith("/") && !path.startsWith("?")) {
      return base + "/" + path;
    }
    return base + path;
  }

  private static String slashed(String path) {
    return path.startsWith("/") ? path : "/" + path;
  }

  /**
   * A URL without what must not be recorded or does not say where a request goes: the query, the
   * fragment and the credentials of any URL in it ({@link UrlCredentials}).
   */
  static String clean(String url) {
    String cut = url;
    int depth = 0;
    for (int i = 0; i < url.length(); i++) {
      char c = url.charAt(i);
      if (c == '{') {
        depth++;
      } else if (c == '}') {
        depth = Math.max(0, depth - 1);
      } else if (depth == 0 && (c == '?' || c == '#')) {
        cut = url.substring(0, i);
        break;
      }
    }
    return UrlCredentials.withoutCredentials(cut);
  }

  /** A URL with {@code http://} in front if it names a host without a scheme. */
  static String withScheme(String url) {
    if (url.isEmpty()
        || ABSOLUTE.matcher(url).find()
        || url.startsWith("$")
        || url.startsWith("{")
        || url.startsWith("/")) {
      return url;
    }
    return "http://" + url;
  }

  /**
   * A URL's base (scheme and authority, or a leading placeholder or expression) and path; the path
   * is null when a placeholder or expression stands for the whole URL.
   */
  record Split(String base, String path, boolean absolute) {}

  static Split split(String url) {
    Matcher m = ABSOLUTE.matcher(url);
    if (m.find()) {
      String path = url.substring(m.end());
      return new Split(m.group(), path.isEmpty() ? "/" : path, true);
    }
    int end = -1;
    if (url.startsWith("${") || url.startsWith("{")) {
      int depth = 0;
      for (int i = 0; i < url.length(); i++) {
        char c = url.charAt(i);
        if (c == '{') {
          depth++;
        } else if (c == '}' && --depth == 0) {
          end = i + 1;
          break;
        }
      }
    }
    if (end > 0 && (end == url.length() || url.charAt(end) == '/')) {
      String path = url.substring(end);
      return new Split(url.substring(0, end), path.isEmpty() ? null : path, false);
    }
    return new Split(null, url.isEmpty() ? "/" : url, false);
  }

  // ---------------------------------------------------------------------------------------------
  // Names

  private static String upper(String s) {
    return s.toUpperCase(Locale.ROOT);
  }

  private static String firstOf(Annotation a, String... elements) {
    for (String e : elements) {
      if (!a.values(e).isEmpty() && !a.values(e).getFirst().isEmpty()) {
        return a.values(e).getFirst();
      }
    }
    return null;
  }

  /** The part of a member's key after the type: its name and parameter types. */
  private static String signature(EntityId member) {
    String key = member.key();
    return key.substring(key.indexOf('#') + 1);
  }

  /** The erased parameter types in a method's or constructor's ID. */
  private static List<String> parameterTypes(EntityId method) {
    if (!method.type().equals(ModuleStructure.METHOD)) {
      return List.of();
    }
    String key = method.key();
    String inside = key.substring(key.indexOf('(') + 1, key.lastIndexOf(')'));
    return inside.isEmpty() ? List.of() : List.of(inside.split(","));
  }

  /** The binary name of the type a member ID or type ID belongs to. */
  private static String ownerType(EntityId member) {
    String key = member.key();
    int hash = key.indexOf('#');
    return hash < 0 ? key : key.substring(0, hash);
  }

  private static String packageOf(String binaryName) {
    int dot = binaryName.lastIndexOf('.');
    return dot < 0 ? "" : binaryName.substring(0, dot);
  }

  private static String simpleName(String binaryName) {
    return binaryName.substring(
        Math.max(binaryName.lastIndexOf('.'), binaryName.lastIndexOf('$')) + 1);
  }

  private static EntityId typeId(String binaryName) {
    try {
      return new EntityId(BuiltInTypes.TYPE, binaryName);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static EntityId fieldId(Expression.FieldRef f) {
    try {
      return new EntityId(ModuleStructure.FIELD, f.owner() + "#" + f.name());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}

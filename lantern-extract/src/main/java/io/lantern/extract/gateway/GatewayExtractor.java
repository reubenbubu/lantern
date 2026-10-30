package io.lantern.extract.gateway;

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
import io.lantern.extract.config.CodeConfig.CodeValue;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.config.ConfigValue;
import io.lantern.extract.config.ModuleConfig;
import io.lantern.extract.java.Expression;
import io.lantern.extract.java.JavaAnalysis;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.extract.java.ModuleStructure;
import io.lantern.extract.java.ModuleStructure.Annotation;
import io.lantern.extract.java.ModuleStructure.Invocation;
import io.lantern.extract.java.ModuleStructure.Location;
import io.lantern.extract.java.ModuleStructure.Member;
import io.lantern.extract.java.ModuleStructure.Supertype;
import io.lantern.extract.java.ModuleStructure.TypeDecl;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns Spring Cloud Gateway into facts: each route, with its predicates, filters and target URI,
 * whether built in code with {@code RouteLocatorBuilder} or defined in config, per profile; and the
 * filter classes requests pass through (global, gateway and web filters, and gateway filter
 * factories), which are entry points. See {@code docs/adr/0077-spring-cloud-gateway.md}.
 *
 * <p>It reads the code as the Java structure extractor read it ({@link JavaAnalysis}) and config
 * through the config extractor. A URI is followed one hop from a {@code @Value} field or parameter,
 * and its placeholders resolved, as the HTTP client extractor resolves a URL.
 */
public final class GatewayExtractor {

  /** The extractor's name, recorded as the origin of its facts. */
  public static final String NAME = "gateway";

  /** The extractor's version; change it whenever the facts it produces change. */
  public static final String VERSION = "2";

  /** The source recorded on the vocabulary revision that adds this extractor's types. */
  public static final String VOCABULARY_SOURCE = "gateway-extractor";

  /** Attribute: a gateway route; its value is the route's id. */
  public static final String ROUTE = "gateway-route";

  /** Attribute: the subject class is a filter requests pass through. */
  public static final String FILTER = "gateway-filter";

  /** Where a route is defined, as {@code definedIn} holds it. */
  public static final String JAVA = "java";

  public static final String CONFIG = "config";

  /** Filter kinds, as the value of a {@code gateway-filter} fact. */
  public static final String GLOBAL = "global";

  public static final String GATEWAY = "gateway";
  public static final String FACTORY = "factory";
  public static final String WEB = "web";

  private static final String RESOURCE = "/vocabulary/gateway-types.json";

  private static final Origin ORIGIN = new Origin(Origin.Kind.STATIC, NAME, VERSION);

  private static final String SPRING = "org.springframework.";
  private static final String GATEWAY_PACKAGE = SPRING + "cloud.gateway.";
  private static final String VALUE = SPRING + "beans.factory.annotation.Value";
  private static final String COMPONENT = SPRING + "stereotype.Component";
  private static final String BEAN = SPRING + "context.annotation.Bean";

  /** The builder whose chains define routes in code. */
  private static final Set<String> BUILDERS =
      Set.of(
          GATEWAY_PACKAGE + "route.builder.RouteLocatorBuilder",
          GATEWAY_PACKAGE + "route.builder.RouteLocatorBuilder$Builder");

  /** The library types that make a class a filter, and the kind each makes it. */
  private static final Map<String, String> FILTER_TYPES =
      Map.ofEntries(
          Map.entry(GATEWAY_PACKAGE + "filter.GlobalFilter", GLOBAL),
          Map.entry(GATEWAY_PACKAGE + "filter.GatewayFilter", GATEWAY),
          Map.entry(GATEWAY_PACKAGE + "filter.OrderedGatewayFilter", GATEWAY),
          Map.entry(GATEWAY_PACKAGE + "filter.factory.GatewayFilterFactory", FACTORY),
          Map.entry(GATEWAY_PACKAGE + "filter.factory.AbstractGatewayFilterFactory", FACTORY),
          Map.entry(
              GATEWAY_PACKAGE + "filter.factory.AbstractNameValueGatewayFilterFactory", FACTORY),
          Map.entry(
              GATEWAY_PACKAGE + "filter.factory.AbstractChangeRequestUriGatewayFilterFactory",
              FACTORY),
          Map.entry(SPRING + "web.server.WebFilter", WEB));

  /** Route config, by the prefix Spring Cloud Gateway's versions and servers read it under. */
  private static final Pattern ROUTE_KEY =
      Pattern.compile(
          "^spring\\.cloud\\.gateway(?:\\.server\\.webflux|\\.server\\.webmvc|\\.mvc)?"
              + "\\.routes\\[(\\d+)]\\.(.+)$");

  private static final Pattern LIST_ITEM = Pattern.compile("^(predicates|filters)\\[(\\d+)](.*)$");

  /** Calls in a route's chain that are not predicates. */
  private static final Set<String> NOT_PREDICATES =
      Set.of("uri", "order", "metadata", "replaceMetadata", "filters", "customize", "and", "id");

  /** The vocabulary types this extractor's facts use besides the built-in ones. */
  public static List<VocabularyType> vocabulary() {
    try (InputStream in = GatewayExtractor.class.getResourceAsStream(RESOURCE)) {
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
   */
  public List<Fact> extract(JavaAnalysis code, long snapshot) {
    return extract(code, snapshot, Set.of());
  }

  /**
   * As {@link #extract(JavaAnalysis, long)}, with config keys known to hold a secret: a value
   * substituted from one is masked as for a key whose name says it is secret.
   *
   * @param secretKeys config keys known to hold a secret, such as {@link
   *     ConfigExtractor#jwtSecretKeys}
   */
  public List<Fact> extract(JavaAnalysis code, long snapshot, Set<String> secretKeys) {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(secretKeys, "secretKeys");
    Run run = new Run(code, Validity.from(snapshot), secretKeys);
    for (JavaAnalysis.Module module : code.modules()) {
      run.codeRoutes(module);
    }
    Set<RepoBuild> repos = new LinkedHashSet<>();
    code.modules().forEach(m -> repos.add(m.repo()));
    for (RepoBuild repo : repos) {
      for (ModuleConfig config : ConfigExtractor.read(repo)) {
        run.configRoutes(config);
      }
    }
    for (JavaAnalysis.Module module : code.modules()) {
      run.filters(module);
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

  /**
   * A route as read in one view.
   *
   * @param id its id
   * @param uri its URI as it may be recorded: resolved, or as written when masked
   */
  private record Route(
      String id,
      CodeValue uri,
      List<String> paths,
      List<String> methods,
      List<String> predicates,
      List<String> filters,
      List<String> filterTypes,
      String order) {

    ObjectNode attributes(String definedIn, String profile) {
      ObjectNode a = FactJson.newObject();
      a.put("definedIn", definedIn);
      if (uri != null) {
        if (!uri.masked()) {
          a.put("uri", uri.value());
        }
        if (uri.masked() || !uri.value().equals(uri.written())) {
          a.put("written", uri.written());
        }
        if (!uri.unresolved().isEmpty()) {
          ArrayNode keys = a.putArray("unresolved");
          uri.unresolved().forEach(keys::add);
        }
        if (uri.masked()) {
          a.put("masked", true);
        }
      }
      array(a, "paths", paths);
      array(a, "methods", methods);
      array(a, "predicates", predicates);
      array(a, "filters", filters);
      array(a, "filterTypes", filterTypes);
      if (order != null) {
        a.put("order", order);
      }
      if (profile != null) {
        a.put("profile", profile);
      }
      return a;
    }

    private static void array(ObjectNode a, String name, List<String> values) {
      if (!values.isEmpty()) {
        ArrayNode array = a.putArray(name);
        values.forEach(array::add);
      }
    }
  }

  private static final class Run {

    private final JavaAnalysis code;
    private final Validity validity;
    private final CodeConfig configs;
    private final Map<String, Fact> facts = new LinkedHashMap<>();

    /** The filter classes routes in code apply, by binary name. */
    private final Set<String> applied = new HashSet<>();

    /** The members of the analyzed code, by ID. */
    private final Map<EntityId, Member> members = new LinkedHashMap<>();

    Run(JavaAnalysis code, Validity validity, Set<String> secretKeys) {
      this.configs = new CodeConfig(secretKeys);
      this.code = code;
      this.validity = validity;
      for (JavaAnalysis.Module m : code.modules()) {
        for (TypeDecl t : m.structure().types()) {
          for (Member member : t.members()) {
            members.putIfAbsent(member.id(), member);
          }
        }
      }
    }

    // -------------------------------------------------------------------------------------------
    // Routes in code

    void codeRoutes(JavaAnalysis.Module module) {
      CodeConfig.View config = configs.forModule(module.repo(), module.directory());
      for (Invocation invocation : module.structure().invocations()) {
        if (!BUILDERS.contains(invocation.rootType())) {
          continue;
        }
        int unnamed = 0;
        for (Expression.Invoke call : invocation.expression().chain()) {
          if (!call.method().equals("route") || call.arguments().isEmpty()) {
            continue;
          }
          if (!(call.arguments().getLast() instanceof Expression.Lambda lambda)) {
            continue;
          }
          String id;
          if (call.arguments().size() == 2) {
            Text written = text(call.arguments().getFirst(), invocation.caller());
            id = config.value(config.resolve(null, written.text()), written.text()).value();
          } else {
            unnamed++;
            id = "route-" + unnamed;
          }
          Location where =
              lambda.start() > 0
                  ? new Location(invocation.where().file(), lambda.start(), lambda.end())
                  : invocation.where();
          codeRoute(module, config, invocation.caller(), id, lambda.body(), where);
        }
      }
    }

    /** The facts of one route built in code, in the default view and where a profile differs. */
    private void codeRoute(
        JavaAnalysis.Module module,
        CodeConfig.View config,
        EntityId caller,
        String id,
        Expression body,
        Location where) {
      List<String> paths = new ArrayList<>();
      List<String> methods = new ArrayList<>();
      List<String> predicates = new ArrayList<>();
      List<String> filters = new ArrayList<>();
      List<String> filterTypes = new ArrayList<>();
      String uri = null;
      String order = null;
      if (!(body instanceof Expression.Invoke chain)) {
        return;
      }
      for (Expression.Invoke call : chain.chain()) {
        String method = call.method();
        switch (method) {
          case "uri" -> {
            if (call.arguments().size() == 1) {
              uri = text(uriArgument(call.arguments().getFirst()), caller).text();
            }
          }
          case "order" -> {
            if (call.arguments().size() == 1) {
              order = text(call.arguments().getFirst(), caller).text();
            }
          }
          case "filters" -> {
            if (call.arguments().size() == 1
                && call.arguments().getFirst() instanceof Expression.Lambda spec
                && spec.body() instanceof Expression.Invoke specChain) {
              for (Expression.Invoke f : specChain.chain()) {
                filter(f, caller, filters, filterTypes);
              }
            }
          }
          case "or", "negate" -> predicates.add(method);
          default -> {
            if (NOT_PREDICATES.contains(method)) {
              break;
            }
            List<String> args = new ArrayList<>();
            for (Expression a : call.arguments()) {
              String value = argument(a, caller);
              if (value != null) {
                args.add(value);
              }
            }
            String name = capitalized(method);
            predicates.add(args.isEmpty() ? name : name + "=" + String.join(",", args));
            if (method.equals("path")) {
              paths.addAll(args);
            } else if (method.equals("method")) {
              args.forEach(m -> methods.add(m.toUpperCase(Locale.ROOT)));
            }
          }
        }
      }
      if (uri == null) {
        return;
      }
      Map<String, Route> views = new LinkedHashMap<>();
      for (String view : config.views()) {
        CodeValue value = config.value(config.resolve(view, uri), uri);
        // Paths, and the predicates they are in, resolve through config as the URI does.
        List<String> resolvedPaths = new ArrayList<>();
        for (String path : paths) {
          resolvedPaths.add(config.value(config.resolve(view, path), path).value());
        }
        List<String> resolvedPredicates = new ArrayList<>();
        for (String predicate : predicates) {
          resolvedPredicates.add(config.value(config.resolve(view, predicate), predicate).value());
        }
        views.put(
            view == null ? "" : view,
            new Route(
                id,
                value,
                resolvedPaths,
                methods,
                resolvedPredicates,
                filters,
                filterTypes,
                order));
      }
      record(caller, views, JAVA, where, module.repo(), module.confidence());
    }

    /** A filter applied in a route's {@code filters(spec -> ...)}. */
    private void filter(
        Expression.Invoke call, EntityId caller, List<String> filters, List<String> filterTypes) {
      String method = call.method();
      if (method.equals("filter") || method.equals("filters")) {
        List<Expression> args =
            method.equals("filter") ? call.arguments().subList(0, 1) : call.arguments();
        for (Expression a : args) {
          String type = filterType(a);
          if (type != null) {
            filters.add(simpleName(type));
            filterTypes.add(type);
            applied.add(type);
          } else {
            filters.add("{" + source(a) + "}");
          }
        }
        return;
      }
      List<String> args = new ArrayList<>();
      for (Expression a : call.arguments()) {
        if (!(a instanceof Expression.Lambda)) {
          String value = argument(a, caller);
          if (value != null) {
            args.add(value);
          }
        }
      }
      String name = capitalized(method);
      filters.add(args.isEmpty() ? name : name + "=" + String.join(",", args));
    }

    /**
     * The class of a filter object of the code: a field's type, a new object's class, or the class
     * of the factory a filter is made with ({@code factory.apply(config)}); null if not known.
     */
    private String filterType(Expression e) {
      return switch (e) {
        case Expression.FieldRef f -> {
          Member field = members.get(fieldId(f.owner(), f.name()));
          yield field == null ? null : inCode(field.type());
        }
        case Expression.Invoke i
            when i.method().equals(ModuleStructure.CONSTRUCTOR)
                && i.receiver() instanceof Expression.TypeRef t ->
            inCode(t.binaryName());
        case Expression.Invoke i when i.receiver() != null && !i.method().equals("<init>") ->
            filterType(i.receiver());
        default -> null;
      };
    }

    private String inCode(String binaryName) {
      return binaryName != null && code.type(binaryName) != null ? binaryName : null;
    }

    /** A predicate's or filter's argument as text; an HTTP method constant by its name. */
    private String argument(Expression e, EntityId caller) {
      if (e instanceof Expression.FieldRef f
          && (f.owner().endsWith(".HttpMethod") || f.owner().endsWith(".HttpMethod$Series"))) {
        return f.name();
      }
      if (e instanceof Expression.Lambda) {
        return null;
      }
      return text(e, caller).text();
    }

    /** A URI given as {@code URI.create(text)} or {@code new URI(text)} is the text. */
    private static Expression uriArgument(Expression e) {
      if (e instanceof Expression.Invoke i
          && i.receiver() instanceof Expression.TypeRef t
          && t.binaryName().equals("java.net.URI")
          && (i.method().equals("create") || i.method().equals(ModuleStructure.CONSTRUCTOR))
          && i.arguments().size() == 1) {
        return i.arguments().getFirst();
      }
      return e;
    }

    // -------------------------------------------------------------------------------------------
    // Routes in config

    void configRoutes(ModuleConfig config) {
      List<String> views = new ArrayList<>();
      views.add(null);
      views.addAll(config.profiles());
      // Each route's index in each view, read from that view's values.
      Map<String, Map<String, Route>> byId = new TreeMap<>();
      Map<String, Location> whereOf = new LinkedHashMap<>();
      Map<String, ConfigValue.Where> fileOf = new LinkedHashMap<>();
      for (String view : views) {
        SortedMap<String, ConfigValue> values = config.values(view);
        Map<String, SortedMap<String, ConfigValue>> routes = new TreeMap<>();
        for (Map.Entry<String, ConfigValue> e : values.entrySet()) {
          Matcher m = ROUTE_KEY.matcher(e.getKey());
          if (m.matches()) {
            String prefix = e.getKey().substring(0, e.getKey().indexOf(".routes[") + 8);
            routes
                .computeIfAbsent(prefix + m.group(1), k -> new TreeMap<>())
                .put(m.group(2), e.getValue());
          }
        }
        for (SortedMap<String, ConfigValue> route : routes.values()) {
          ConfigValue idValue = route.get("id");
          ConfigValue uriValue = route.get("uri");
          if (idValue == null || uriValue == null) {
            continue;
          }
          String id = idValue.value();
          Route read = configRoute(id, route, uriValue);
          byId.computeIfAbsent(id, k -> new LinkedHashMap<>()).put(view == null ? "" : view, read);
          if (view == null || !fileOf.containsKey(id)) {
            fileOf.put(id, idValue.where());
            int start = Integer.MAX_VALUE;
            int end = 0;
            for (ConfigValue v : route.values()) {
              if (v.where().path().equals(idValue.where().path()) && v.where().line() > 0) {
                start = Math.min(start, v.where().line());
                end = Math.max(end, v.where().line());
              }
            }
            whereOf.put(id, start <= end ? new Location(null, start, end) : null);
          }
        }
      }
      for (Map.Entry<String, Map<String, Route>> route : byId.entrySet()) {
        ConfigValue.Where file = fileOf.get(route.getKey());
        Location lines = whereOf.get(route.getKey());
        Evidence evidence =
            new Evidence(
                config.repo().repo().name(),
                config.repo().commit(),
                file.path(),
                lines == null ? null : new Evidence.LineRange(lines.start(), lines.end()),
                file.contentHash());
        record(config.module(), route.getValue(), CONFIG, evidence, 1.0);
      }
    }

    private Route configRoute(String id, SortedMap<String, ConfigValue> route, ConfigValue uri) {
      CodeValue value = configs.value(uri.value(), uri.written(), uri.unresolved(), uri.used());
      Map<Integer, String> predicates = new TreeMap<>();
      Map<Integer, String> filters = new TreeMap<>();
      // The full form: name and args, by list and index.
      Map<String, String> names = new TreeMap<>();
      Map<String, Map<String, String>> args = new TreeMap<>();
      for (Map.Entry<String, ConfigValue> e : route.entrySet()) {
        Matcher m = LIST_ITEM.matcher(e.getKey());
        if (!m.matches()) {
          continue;
        }
        String list = m.group(1);
        int index = Integer.parseInt(m.group(2));
        String rest = m.group(3);
        String text = recorded(e.getKey(), e.getValue());
        if (rest.isEmpty()) {
          (list.equals("predicates") ? predicates : filters).put(index, text);
        } else if (rest.equals(".name")) {
          names.put(list + "|" + String.format(Locale.ROOT, "%06d", index), text);
        } else if (rest.startsWith(".args.")) {
          args.computeIfAbsent(
                  list + "|" + String.format(Locale.ROOT, "%06d", index),
                  k -> new TreeMap<>(Comparator.comparing(GatewayExtractor::numbered)))
              .put(rest.substring(6), text);
        }
      }
      names.forEach(
          (key, name) -> {
            String list = key.substring(0, key.indexOf('|'));
            int index = Integer.parseInt(key.substring(key.indexOf('|') + 1));
            Map<String, String> given = args.getOrDefault(key, Map.of());
            String text;
            if (given.isEmpty()) {
              text = name;
            } else if (given.size() == 1
                || list.equals("predicates")
                || given.keySet().stream().allMatch(k -> k.startsWith("_genkey_"))) {
              // A predicate's args are its values (Path's patterns, Method's methods), in order.
              text = name + "=" + String.join(",", given.values());
            } else {
              List<String> pairs = new ArrayList<>();
              given.forEach((k, v) -> pairs.add(k + "=" + v));
              text = name + "(" + String.join(", ", pairs) + ")";
            }
            (list.equals("predicates") ? predicates : filters).put(index, text);
          });
      List<String> paths = new ArrayList<>();
      List<String> methods = new ArrayList<>();
      for (String predicate : predicates.values()) {
        int eq = predicate.indexOf('=');
        if (eq < 0) {
          continue;
        }
        String name = predicate.substring(0, eq).strip();
        List<String> values = split(predicate.substring(eq + 1));
        if (name.equalsIgnoreCase("Path")) {
          paths.addAll(values);
        } else if (name.equalsIgnoreCase("Method")) {
          values.forEach(v -> methods.add(v.toUpperCase(Locale.ROOT)));
        }
      }
      ConfigValue order = route.get("order");
      return new Route(
          id,
          value,
          paths,
          methods,
          List.copyOf(predicates.values()),
          List.copyOf(filters.values()),
          List.of(),
          order == null ? null : order.value());
    }

    /** A config value as it may be recorded: without a secret substituted into it. */
    private String recorded(String key, ConfigValue value) {
      if (configs.isSecret(key)) {
        return ConfigExtractor.MASK;
      }
      return configs
          .value(value.value(), value.written(), value.unresolved(), value.used())
          .value();
    }

    private static List<String> split(String values) {
      List<String> result = new ArrayList<>();
      for (String v : values.split(",")) {
        if (!v.isBlank()) {
          result.add(v.strip());
        }
      }
      return result;
    }

    // -------------------------------------------------------------------------------------------
    // Filters

    void filters(JavaAnalysis.Module module) {
      for (TypeDecl type : module.structure().types()) {
        if (!type.kind().equals(ModuleStructure.CLASS)
            || code.module(type.binaryName()) != module) {
          continue;
        }
        String library = filterLibraryType(type.binaryName(), new HashSet<>());
        if (library == null) {
          continue;
        }
        if (!applied.contains(type.binaryName()) && !isBean(type)) {
          continue;
        }
        ObjectNode attributes = FactJson.newObject();
        attributes.put("implements", library);
        put(
            new EntityId(BuiltInTypes.TYPE, type.binaryName()),
            FILTER,
            FILTER_TYPES.get(library),
            attributes,
            evidence(module.repo(), type.where()),
            module.confidence());
      }
    }

    /** The library type that makes a class a filter, found up its supertypes; null if none. */
    private String filterLibraryType(String binaryName, Set<String> seen) {
      if (!seen.add(binaryName)) {
        return null;
      }
      TypeDecl type = code.type(binaryName);
      if (type == null) {
        return null;
      }
      for (Supertype s : type.supertypes()) {
        if (FILTER_TYPES.containsKey(s.binaryName())) {
          return s.binaryName();
        }
      }
      for (Supertype s : type.supertypes()) {
        String found = filterLibraryType(s.binaryName(), seen);
        if (found != null) {
          return found;
        }
      }
      return null;
    }

    /** Whether a class is a bean: a component, or what a {@code @Bean} method returns. */
    private boolean isBean(TypeDecl type) {
      if (code.annotationPath(type.annotations(), COMPONENT) != null) {
        return true;
      }
      for (Member member : members.values()) {
        if (type.binaryName().equals(member.returns())
            && code.annotationPath(member.annotations(), BEAN) != null) {
          return true;
        }
      }
      return false;
    }

    // -------------------------------------------------------------------------------------------
    // Values: following an expression one hop

    private record Text(String text) {}

    /**
     * An expression as text: constants and concatenations as written, a {@code @Value} field or
     * parameter as its placeholder, and anything else as its source in braces.
     */
    private Text text(Expression e, EntityId in) {
      return new Text(text(e, in, 0));
    }

    private String text(Expression e, EntityId in, int depth) {
      switch (e) {
        case Expression.Literal l -> {
          return l.value();
        }
        case Expression.Concat c -> {
          StringBuilder b = new StringBuilder();
          for (Expression p : c.parts()) {
            b.append(text(p, in, depth + 1));
          }
          return b.toString();
        }
        case Expression.FieldRef f -> {
          Member field = members.get(fieldId(f.owner(), f.name()));
          String value = field == null ? null : value(field.annotations());
          if (value != null) {
            return value;
          }
        }
        case Expression.Param p -> {
          Member method = members.get(in);
          if (method != null && p.index() < method.parameters().size()) {
            String value = value(method.parameters().get(p.index()).annotations());
            if (value != null) {
              return value;
            }
          }
        }
        default -> {}
      }
      return "{" + source(e) + "}";
    }

    /** The text of a {@code @Value} annotation among those given, or null. */
    private String value(List<Annotation> annotations) {
      List<Annotation> path = code.annotationPath(annotations, VALUE);
      if (path == null || path.getLast().values("value").isEmpty()) {
        return null;
      }
      return path.getLast().values("value").getFirst();
    }

    // -------------------------------------------------------------------------------------------
    // Facts

    /**
     * Records a route: its default view's fact, and a fact for each profile whose route differs; a
     * route only a profile has is recorded for that profile alone.
     */
    private void record(
        EntityId subject,
        Map<String, Route> views,
        String definedIn,
        Location where,
        RepoBuild repo,
        double confidence) {
      record(subject, views, definedIn, evidence(repo, where), confidence);
    }

    private void record(
        EntityId subject,
        Map<String, Route> views,
        String definedIn,
        Evidence evidence,
        double confidence) {
      if (subject == null) {
        return;
      }
      Route standard = views.get("");
      if (standard != null) {
        put(
            subject,
            ROUTE,
            standard.id(),
            standard.attributes(definedIn, null),
            evidence,
            confidence);
      }
      views.forEach(
          (view, route) -> {
            if (!view.isEmpty() && !route.equals(standard)) {
              put(
                  subject,
                  ROUTE,
                  route.id(),
                  route.attributes(definedIn, view),
                  evidence,
                  confidence);
            }
          });
    }

    private static Evidence evidence(RepoBuild repo, Location where) {
      return new Evidence(
          repo.repo().name(),
          repo.commit(),
          where.file().path(),
          where.start() > 0 ? new Evidence.LineRange(where.start(), where.end()) : null,
          where.file().contentHash());
    }

    private void put(
        EntityId subject,
        String predicate,
        String object,
        ObjectNode attributes,
        Evidence evidence,
        double confidence) {
      Fact fact =
          Fact.create(
              subject,
              predicate,
              FactObject.string(object),
              attributes,
              ORIGIN,
              evidence,
              confidence,
              validity);
      facts.putIfAbsent(fact.id(), fact);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers

  private static EntityId fieldId(String owner, String name) {
    try {
      return new EntityId(ModuleStructure.FIELD, owner + "#" + name);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** A key with its numbers padded, so {@code patterns[10]} sorts after {@code patterns[2]}. */
  private static String numbered(String key) {
    Matcher m = Pattern.compile("\\d+").matcher(key);
    StringBuilder b = new StringBuilder();
    while (m.find()) {
      m.appendReplacement(b, String.format(Locale.ROOT, "%010d", Long.parseLong(m.group())));
    }
    m.appendTail(b);
    return b.toString();
  }

  private static String simpleName(String binaryName) {
    String simple = binaryName.substring(binaryName.lastIndexOf('.') + 1);
    return simple.replace('$', '.');
  }

  private static String capitalized(String method) {
    return method.isEmpty()
        ? method
        : Character.toUpperCase(method.charAt(0)) + method.substring(1);
  }

  /** An expression as Java-like source, for one not followed. */
  private static String source(Expression e) {
    return switch (e) {
      case Expression.Literal l -> l.value();
      case Expression.Concat c ->
          String.join(" + ", c.parts().stream().map(GatewayExtractor::source).toList());
      case Expression.FieldRef f -> f.name();
      case Expression.Param p -> "arg" + p.index();
      case Expression.TypeRef t -> simpleName(t.binaryName());
      case Expression.Invoke i -> {
        String args =
            String.join(", ", i.arguments().stream().map(GatewayExtractor::source).toList());
        if (i.method().equals(ModuleStructure.CONSTRUCTOR)) {
          yield "new " + source(i.receiver()) + "(" + args + ")";
        }
        yield (i.receiver() == null ? "" : source(i.receiver()) + ".")
            + i.method()
            + "("
            + args
            + ")";
      }
      case Expression.Lambda l -> l.source();
      case Expression.Unknown u -> u.source();
    };
  }
}

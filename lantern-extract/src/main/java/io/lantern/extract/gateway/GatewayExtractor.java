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
import io.lantern.extract.CacheUnit;
import io.lantern.extract.ClaimSink;
import io.lantern.extract.ExtractionContext;
import io.lantern.extract.ExtractionUnit;
import io.lantern.extract.Extractor;
import io.lantern.extract.ExtractorInfo;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.config.CodeConfig;
import io.lantern.extract.config.CodeConfig.CodeValue;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.config.ConfigValue;
import io.lantern.extract.java.Expression;
import io.lantern.extract.java.JavaAnalysis;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.extract.java.ModuleStructure;
import io.lantern.extract.java.ModuleStructure.Invocation;
import io.lantern.extract.java.ModuleStructure.Location;
import io.lantern.extract.java.ModuleStructure.Member;
import io.lantern.extract.java.ModuleStructure.Supertype;
import io.lantern.extract.java.ModuleStructure.TypeDecl;
import io.lantern.extract.java.Values;
import io.lantern.extract.spring.SpringExtractor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
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
 * <p>It reads the code as the Java structure extractor read it ({@link JavaAnalysis}) and no
 * config. A route built in code is recorded as written ({@value #ROUTE_WRITTEN}), its URI followed
 * one hop from a {@code @Value} field or parameter, and linking resolves it per view; linking also
 * reads the routes, Zuul routes and discovery locator config defines from the config facts, through
 * {@link #routes} (ADR 0094, #558).
 */
public final class GatewayExtractor implements Extractor {

  /** The extractor's name, recorded as the origin of its facts. */
  public static final String NAME = "gateway";

  /** The extractor's version; change it whenever the facts it produces change. */
  public static final String VERSION = "5";

  /** The source recorded on the vocabulary revision that adds this extractor's types. */
  public static final String VOCABULARY_SOURCE = "gateway-extractor";

  /** Attribute: a gateway route; its value is the route's id. */
  public static final String ROUTE = "gateway-route";

  /** Attribute: a route built in code, as written; linking resolves it into its routes. */
  public static final String ROUTE_WRITTEN = "gateway-route-written";

  /** Attribute: the subject class is a filter requests pass through. */
  public static final String FILTER = "gateway-filter";

  /** The name of the attribute type of a discovery locator turned on in config. */
  public static final String LOCATOR = "gateway-discovery-locator";

  /** The framework of a route read from Netflix Zuul's config. */
  public static final String ZUUL = "zuul";

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

  /** Discovery locator config, by the prefix Spring Cloud Gateway's versions read it under. */
  private static final Pattern LOCATOR_KEY =
      Pattern.compile(
          "^spring\\.cloud\\.gateway(?:\\.server\\.webflux)?\\.discovery\\.locator\\.(.+)$");

  /** A Zuul route: its name, and the property set, or none for the shorthand form. */
  private static final Pattern ZUUL_ROUTE_KEY =
      Pattern.compile("^zuul\\.routes\\.([^.\\[]+)(?:\\.(.+))?$");

  /** The default URL of a route the discovery locator makes for a service. */
  private static final String LOCATOR_URL = "'lb://'+serviceId";

  private static final Pattern LIST_ITEM = Pattern.compile("^(predicates|filters)\\[(\\d+)](.*)$");

  /** Calls in a route's chain that are not predicates. */
  private static final Set<String> NOT_PREDICATES =
      Set.of("uri", "order", "metadata", "replaceMetadata", "filters", "customize", "and", "id");

  /** The vocabulary types this extractor's facts use besides the built-in ones. */
  public static List<VocabularyType> vocabulary() {
    return ExtractorInfo.load(RESOURCE);
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
    Objects.requireNonNull(code, "code");
    Run run = new Run(code, Validity.from(snapshot), Set.of(), ORIGIN);
    for (JavaAnalysis.Module module : code.modules()) {
      run.codeRoutes(module);
    }
    for (JavaAnalysis.Module module : code.modules()) {
      run.filters(module);
    }
    return run.sorted();
  }

  /** What a text written in code resolves to in one module's config, in each view. */
  public interface Resolver {

    /** The views: the default view (null), then each profile. */
    List<String> views();

    /** A text as written resolved in a view, as it may be recorded (masked if a secret). */
    CodeValue value(String view, String written);
  }

  /**
   * The routes linking gives (ADR 0094, #558): each route built in code ({@value #ROUTE_WRITTEN})
   * resolved in each view of its module's config, and the routes, Zuul routes and discovery locator
   * each module's config defines, as this extractor recorded them before it kept values as written:
   * {@value #ROUTE} and {@value #LOCATOR} facts, the default view's and each differing profile's.
   *
   * @param written the snapshot's facts; only {@value #ROUTE_WRITTEN} facts are read
   * @param resolver the resolver of a written route's module, by {@code configModule} and {@code
   *     configRepo}
   * @param configs the config of each module
   * @param secretKeys config keys known to hold a secret
   * @param origin the origin of the facts
   * @param snapshot the snapshot number the facts hold from
   */
  public static List<Fact> routes(
      List<Fact> written,
      java.util.function.BiFunction<EntityId, String, Resolver> resolver,
      List<io.lantern.extract.config.ModuleValues> configs,
      Set<String> secretKeys,
      Origin origin,
      long snapshot) {
    Run run = new Run(null, Validity.from(snapshot), secretKeys, origin);
    for (Fact f : written) {
      if (f.predicate().equals(ROUTE_WRITTEN)) {
        run.writtenRoute(
            f,
            resolver.apply(
                EntityId.parse(f.attributes().get("configModule").stringValue()),
                f.attributes().get("configRepo").stringValue()));
      }
    }
    for (io.lantern.extract.config.ModuleValues config : configs) {
      run.configRoutes(config);
      run.zuulRoutes(config);
      run.locator(config);
    }
    return run.sorted();
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
      String order,
      String framework) {

    ObjectNode attributes(String definedIn, String profile) {
      ObjectNode a = FactJson.newObject();
      a.put("definedIn", definedIn);
      if (framework != null) {
        a.put("framework", framework);
      }
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

    private final Origin origin;

    Run(JavaAnalysis code, Validity validity, Set<String> secretKeys, Origin origin) {
      this.configs = new CodeConfig(secretKeys);
      this.code = code;
      this.validity = validity;
      this.origin = origin;
      for (JavaAnalysis.Module m : code == null ? List.<JavaAnalysis.Module>of() : code.modules()) {
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
            id = text(call.arguments().getFirst(), invocation.caller()).text();
          } else {
            unnamed++;
            id = "route-" + unnamed;
          }
          Location where =
              lambda.start() > 0
                  ? new Location(invocation.where().file(), lambda.start(), lambda.end())
                  : invocation.where();
          codeRoute(module, invocation.caller(), id, lambda.body(), where);
        }
      }
    }

    /** The fact of one route built in code, as written. */
    private void codeRoute(
        JavaAnalysis.Module module, EntityId caller, String id, Expression body, Location where) {
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
      ObjectNode a = FactJson.newObject();
      a.put("uri", uri);
      Route.array(a, "paths", paths);
      Route.array(a, "methods", methods);
      Route.array(a, "predicates", predicates);
      Route.array(a, "filters", filters);
      Route.array(a, "filterTypes", filterTypes);
      if (order != null) {
        a.put("order", order);
      }
      a.put("configModule", module.id().toString());
      a.put("configRepo", module.repo().repo().name());
      put(caller, ROUTE_WRITTEN, id, a, evidence(module.repo(), where), module.confidence());
    }

    /** The routes of a route built in code, resolved in each view of its module's config. */
    void writtenRoute(Fact written, Resolver config) {
      JsonNode a = written.attributes();
      String uri = a.get("uri").stringValue();
      List<String> paths = strings(a, "paths");
      List<String> methods = strings(a, "methods");
      List<String> predicates = strings(a, "predicates");
      List<String> filters = strings(a, "filters");
      List<String> filterTypes = strings(a, "filterTypes");
      String order = a.has("order") ? a.get("order").stringValue() : null;
      String id = config.value(null, written.object().value()).value();
      Map<String, Route> views = new LinkedHashMap<>();
      for (String view : config.views()) {
        CodeValue value = config.value(view, uri);
        // Paths, and the predicates they are in, resolve through config as the URI does.
        List<String> resolvedPaths = new ArrayList<>();
        for (String path : paths) {
          resolvedPaths.add(config.value(view, path).value());
        }
        List<String> resolvedPredicates = new ArrayList<>();
        for (String predicate : predicates) {
          resolvedPredicates.add(config.value(view, predicate).value());
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
                order,
                null));
      }
      record(written.subject(), views, JAVA, written.evidence(), written.confidence());
    }

    private static List<String> strings(JsonNode a, String name) {
      List<String> values = new ArrayList<>();
      if (a.has(name)) {
        a.get(name).forEach(v -> values.add(v.stringValue()));
      }
      return values;
    }

    List<Fact> sorted() {
      return facts.values().stream()
          .sorted(
              Comparator.comparing((Fact f) -> f.subject().toString())
                  .thenComparing(Fact::predicate)
                  .thenComparing(f -> f.object().value())
                  .thenComparing(f -> f.attributes().toString())
                  .thenComparing(Fact::id))
          .toList();
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

    void configRoutes(io.lantern.extract.config.ModuleValues config) {
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
            Evidence.of(
                config.repo(),
                file.path(),
                lines == null ? null : new Evidence.LineRange(lines.start(), lines.end()),
                file.contentHash().isEmpty() ? null : file.contentHash());
        record(config.module(), route.getValue(), CONFIG, evidence, 1.0);
      }
    }

    /**
     * Netflix Zuul's routes ({@code zuul.routes.<name>}): the path (shorthand value, or {@code
     * path}, else {@code /<name>/**}) behind {@code zuul.prefix}, to {@code url}, else to the load
     * balanced {@code serviceId}, else to the route's name. Zuul strips the path's literal start
     * unless the route's {@code strip-prefix} is false, and the global prefix unless {@code
     * zuul.strip-prefix} is; both are recorded as the {@code StripPrefix} filter with as many
     * segments.
     */
    void zuulRoutes(io.lantern.extract.config.ModuleValues config) {
      List<String> views = new ArrayList<>();
      views.add(null);
      views.addAll(config.profiles());
      Map<String, Map<String, Route>> byId = new TreeMap<>();
      Map<String, List<ConfigValue>> keysOf = new LinkedHashMap<>();
      for (String view : views) {
        SortedMap<String, ConfigValue> values = config.values(view);
        Map<String, Map<String, ConfigValue>> routes = new TreeMap<>();
        for (Map.Entry<String, ConfigValue> e : values.entrySet()) {
          Matcher m = ZUUL_ROUTE_KEY.matcher(e.getKey());
          if (m.matches()) {
            String property = m.group(2) == null ? "path" : relaxed(m.group(2));
            routes.computeIfAbsent(m.group(1), k -> new TreeMap<>()).put(property, e.getValue());
          }
        }
        String prefix = zuulSetting(values, "prefix");
        boolean stripGlobal = !"false".equalsIgnoreCase(zuulSetting(values, "stripprefix"));
        for (Map.Entry<String, Map<String, ConfigValue>> e : routes.entrySet()) {
          String name = e.getKey();
          Map<String, ConfigValue> route = e.getValue();
          ConfigValue pathValue = route.get("path");
          String path =
              pathValue == null ? "/" + name + "/**" : recorded(pathValue.key(), pathValue);
          ConfigValue url = route.get("url");
          ConfigValue serviceId = route.get("serviceid");
          CodeValue uri;
          if (url != null) {
            uri = configs.value(url.value(), url.written(), url.unresolved(), url.used());
          } else if (serviceId != null) {
            uri =
                configs.value(
                    "lb://" + serviceId.value(),
                    "lb://" + serviceId.written(),
                    serviceId.unresolved(),
                    serviceId.used());
          } else {
            uri = configs.value("lb://" + name, "lb://" + name, new TreeSet<>(), new TreeSet<>());
          }
          int strip = 0;
          ConfigValue stripRoute = route.get("stripprefix");
          if (stripRoute == null || !"false".equalsIgnoreCase(stripRoute.value().strip())) {
            strip += zuulStripped(path);
          }
          String full = path;
          if (prefix != null && !prefix.isBlank()) {
            full = SpringExtractor.join(prefix, path);
            if (stripGlobal) {
              strip += segments(prefix);
            }
          }
          List<String> filters = strip > 0 ? List.of("StripPrefix=" + strip) : List.of();
          Route read =
              new Route(
                  name,
                  uri,
                  List.of(full),
                  List.of(),
                  List.of("Path=" + full),
                  filters,
                  List.of(),
                  null,
                  ZUUL);
          byId.computeIfAbsent(name, k -> new LinkedHashMap<>())
              .put(view == null ? "" : view, read);
          if (view == null || !keysOf.containsKey(name)) {
            keysOf.put(name, List.copyOf(route.values()));
          }
        }
      }
      for (Map.Entry<String, Map<String, Route>> route : byId.entrySet()) {
        Evidence evidence = configEvidence(config, keysOf.get(route.getKey()));
        if (evidence != null) {
          record(config.module(), route.getValue(), CONFIG, evidence, 1.0);
        }
      }
    }

    /** A global Zuul setting ({@code zuul.<name>}, in any relaxed form), or null. */
    private static String zuulSetting(SortedMap<String, ConfigValue> values, String name) {
      for (Map.Entry<String, ConfigValue> e : values.entrySet()) {
        if (e.getKey().startsWith("zuul.")
            && e.getKey().indexOf('.', 5) < 0
            && relaxed(e.getKey().substring(5)).equals(name)) {
          return e.getValue().value().strip();
        }
      }
      return null;
    }

    /**
     * Spring Cloud Gateway's discovery locator, when config turns it on: a route to each service
     * the discovery client knows. The value is its URL expression; the attributes are the settings
     * that change which services it routes to and how: {@code lowerCaseServiceId}, {@code
     * includeExpression}, {@code routeIdPrefix}, and predicates and filters that replace its
     * default ones. Recorded in the default view, and with {@code profile} where one differs.
     */
    void locator(io.lantern.extract.config.ModuleValues config) {
      List<String> views = new ArrayList<>();
      views.add(null);
      views.addAll(config.profiles());
      ObjectNode standard = null;
      for (String view : views) {
        SortedMap<String, ConfigValue> settings = new TreeMap<>();
        SortedMap<String, ConfigValue> lists = new TreeMap<>();
        for (Map.Entry<String, ConfigValue> e : config.values(view).entrySet()) {
          Matcher m = LOCATOR_KEY.matcher(e.getKey());
          if (m.matches()) {
            if (LIST_ITEM.matcher(m.group(1)).matches()) {
              lists.put(m.group(1), e.getValue());
            } else {
              settings.put(relaxed(m.group(1)), e.getValue());
            }
          }
        }
        ConfigValue enabled = settings.get("enabled");
        if (enabled == null || !"true".equalsIgnoreCase(enabled.value().strip())) {
          if (view != null && standard != null) {
            // A profile that turns off the locator the default view turns on.
            ObjectNode off = FactJson.newObject();
            off.put("enabled", false);
            off.put("profile", view);
            List<ConfigValue> keys =
                enabled == null ? new ArrayList<>(settings.values()) : List.of(enabled);
            Evidence evidence = configEvidence(config, keys);
            if (evidence != null) {
              put(config.module(), LOCATOR, standard.get("value").asString(), off, evidence, 1.0);
            }
          }
          continue;
        }
        ObjectNode attributes = FactJson.newObject();
        ConfigValue lower = settings.get("lowercaseserviceid");
        if (lower != null) {
          attributes.put("lowerCaseServiceId", "true".equalsIgnoreCase(lower.value().strip()));
        }
        ConfigValue include = settings.get("includeexpression");
        if (include != null) {
          attributes.put("includeExpression", recorded(include.key(), include));
        }
        ConfigValue idPrefix = settings.get("routeidprefix");
        if (idPrefix != null) {
          attributes.put("routeIdPrefix", recorded(idPrefix.key(), idPrefix));
        }
        Shortcuts shortcuts = shortcuts(lists);
        Route.array(attributes, "predicates", shortcuts.predicates());
        Route.array(attributes, "filters", shortcuts.filters());
        ConfigValue url = settings.get("urlexpression");
        String value = url == null ? LOCATOR_URL : recorded(url.key(), url);
        List<ConfigValue> keys = new ArrayList<>(settings.values());
        keys.addAll(lists.values());
        if (view == null) {
          standard = attributes.deepCopy();
          standard.put("value", value);
          put(config.module(), LOCATOR, value, attributes, configEvidence(config, keys), 1.0);
        } else {
          ObjectNode compared = attributes.deepCopy();
          compared.put("value", value);
          if (!compared.equals(standard)) {
            attributes.put("profile", view);
            put(config.module(), LOCATOR, value, attributes, configEvidence(config, keys), 1.0);
          }
        }
      }
    }

    /**
     * The evidence of a group of config keys: the lines they span in the file of the first one that
     * has a line, or the file alone; null when there are none.
     */
    private static Evidence configEvidence(
        io.lantern.extract.config.ModuleValues config, List<ConfigValue> keys) {
      if (keys == null || keys.isEmpty()) {
        return null;
      }
      ConfigValue.Where file =
          keys.stream()
              .map(ConfigValue::where)
              .filter(w -> w.line() > 0)
              .findFirst()
              .orElse(keys.getFirst().where());
      int start = Integer.MAX_VALUE;
      int end = 0;
      for (ConfigValue v : keys) {
        if (v.where().path().equals(file.path()) && v.where().line() > 0) {
          start = Math.min(start, v.where().line());
          end = Math.max(end, v.where().line());
        }
      }
      return Evidence.of(
          config.repo(),
          file.path(),
          start <= end ? new Evidence.LineRange(start, end) : null,
          file.contentHash().isEmpty() ? null : file.contentHash());
    }

    private Route configRoute(String id, SortedMap<String, ConfigValue> route, ConfigValue uri) {
      CodeValue value = configs.value(uri.value(), uri.written(), uri.unresolved(), uri.used());
      Shortcuts lists = shortcuts(route);
      List<String> paths = new ArrayList<>();
      List<String> methods = new ArrayList<>();
      for (String predicate : lists.predicates()) {
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
          lists.predicates(),
          lists.filters(),
          List.of(),
          order == null ? null : order.value(),
          null);
    }

    /** A route's predicates and filters, each in config's shortcut form. */
    private record Shortcuts(List<String> predicates, List<String> filters) {}

    /**
     * The predicates and filters of keys such as {@code predicates[0]} and {@code
     * filters[1].args.regexp}, in the shortcut form or made into it from name and args.
     */
    private Shortcuts shortcuts(SortedMap<String, ConfigValue> route) {
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
        if (rest.startsWith(".args[") && rest.endsWith("]")) {
          // A map key in brackets, as properties files write it, binds as a dotted one.
          rest = ".args." + rest.substring(6, rest.length() - 1);
        }
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
      return new Shortcuts(List.copyOf(predicates.values()), List.copyOf(filters.values()));
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
      StringBuilder b = new StringBuilder();
      for (Values.Part part : code.values().follow(e, in, false).parts()) {
        b.append(
            switch (part) {
              case Values.Part.Written w -> w.text();
              case Values.Part.Annotated a -> a.text();
              case Values.Part.Open o -> "{" + source(o.expression()) + "}";
            });
      }
      return new Text(b.toString());
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
      return Evidence.of(
          repo.repo().name(),
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
              origin,
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

  /** A property name as relaxed binding compares it: lower case, without dashes or underscores. */
  private static String relaxed(String name) {
    return name.replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
  }

  /**
   * The number of path segments Zuul strips of a route's path: the text before the first {@code *}
   * and the slash in front of it, in whole segments; none when the path has no wildcard.
   */
  private static int zuulStripped(String path) {
    int star = path.indexOf('*');
    if (star <= 0) {
      return 0;
    }
    int whole = path.charAt(star - 1) == '/' ? star - 1 : path.lastIndexOf('/', star - 1);
    return whole <= 0 ? 0 : segments(path.substring(0, whole));
  }

  /** The number of non-empty segments of a path. */
  private static int segments(String path) {
    int count = 0;
    for (String segment : path.split("/")) {
      if (!segment.isEmpty()) {
        count++;
      }
    }
    return count;
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

  private static final ExtractorInfo INFO =
      ExtractorInfo.of(NAME, VERSION, VOCABULARY_SOURCE, RESOURCE, Set.of());

  @Override
  public ExtractorInfo info() {
    return INFO;
  }

  @Override
  public CacheUnit unit() {
    return CacheUnit.SNAPSHOT;
  }

  @Override
  public void extract(ExtractionContext context, ExtractionUnit unit, ClaimSink sink) {
    sink.emitAll(extract(context.code(), context.snapshot()));
  }
}

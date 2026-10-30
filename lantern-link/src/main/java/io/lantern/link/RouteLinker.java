package io.lantern.link;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.gateway.GatewayExtractor;
import io.lantern.extract.spring.SpringExtractor;
import io.lantern.extract.websocket.WebSocketExtractor;
import io.lantern.link.Addresses.Answer;
import io.lantern.link.Addresses.Kind;
import io.lantern.link.Addresses.Target;
import io.lantern.link.Deployments.Container;
import io.lantern.link.FoundLinks.Found;
import io.lantern.link.LinkIndex.Unit;
import io.lantern.link.LinkText.Url;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Links Spring Cloud Gateway routes to the units they send requests to (ADR 0077): a load-balanced
 * URI ({@code lb://name}, {@code lb:ws://name}) by the name against {@code
 * spring.application.name}, any other as {@link Addresses} resolves it, as an HTTP call's URL is.
 * Each route is a {@code CALLS_HTTP} link from the gateway, with the route's id, paths and protocol
 * (HTTP, HTTPS or WebSocket); a WebSocket route also names the target's WebSocket endpoint it
 * reaches (ADR 0080). Then, for each unit a gateway routes to, the endpoints no route of that
 * gateway reaches are recorded on the gateway as {@code unrouted-endpoint}.
 */
final class RouteLinker {

  /** Filters that change the path in ways the linker does not follow. */
  private static final Set<String> REWRITES = Set.of("RewritePath", "SetPath");

  /** The config keys that turn on the discovery locator, by Spring Cloud Gateway's versions. */
  private static final List<String> LOCATOR =
      List.of(
          "spring.cloud.gateway.discovery.locator",
          "spring.cloud.gateway.server.webflux.discovery.locator");

  /** The most choices of optional characters a RewritePath pattern is reversed through. */
  private static final int MAX_CHOICES = 4;

  /**
   * What one route reaches of a unit, in one view.
   *
   * @param patterns the path patterns of the requests it forwards, as the target sees them (after
   *     StripPrefix and PrefixPath); null when a filter rewrites the path in a way not followed
   * @param methods the HTTP methods it takes, empty for any
   */
  private record Reach(Fact route, List<String> patterns, List<String> methods) {}

  /** A route linked to the unit it sends requests to, in a view (null for the default one). */
  record Routed(Unit gateway, String view, Fact route, Unit target) {}

  private final LinkIndex index;
  private final FoundLinks out;
  private final Addresses addresses;
  private final List<Routed> routed = new ArrayList<>();

  RouteLinker(LinkIndex index, FoundLinks out, Addresses addresses) {
    this.index = index;
    this.out = out;
    this.addresses = addresses;
  }

  void link() {
    Map<String, List<Fact>> routes = new TreeMap<>();
    for (Fact f : index.of(GatewayExtractor.ROUTE)) {
      routes.computeIfAbsent(f.subject() + "|" + f.object().value(), k -> new ArrayList<>()).add(f);
    }
    // What each gateway's routes reach of each unit, by view ("" for the default one).
    Map<Unit, Map<String, Map<Unit, List<Reach>>>> reached = new LinkedHashMap<>();
    for (List<Fact> route : routes.values()) {
      Fact first = route.getFirst();
      for (Unit gateway : index.unitsOf(first.subject())) {
        for (String view : index.views()) {
          Fact fact = LinkIndex.inView(Map.of("", route), view).get("");
          if (fact == null
              || !index.active(fact.subject(), view)
              || LinkIndex.flag(fact, "masked")) {
            continue;
          }
          Unit target = link(gateway, fact, view);
          if (target != null) {
            routed.add(new Routed(gateway, view, fact, target));
            reached
                .computeIfAbsent(gateway, g -> new LinkedHashMap<>())
                .computeIfAbsent(view == null ? "" : view, v -> new LinkedHashMap<>())
                .computeIfAbsent(target, t -> new ArrayList<>())
                .add(new Reach(fact, patterns(fact), LinkIndex.texts(fact, "methods")));
          }
        }
      }
    }
    reached.forEach(
        (gateway, views) ->
            views.forEach(
                (view, targets) -> unrouted(gateway, view.isEmpty() ? null : view, targets)));
    routedEndpoints();
  }

  /** Links one route in a view; the unit it reaches, or null if it reaches none. */
  private Unit link(Unit gateway, Fact route, String view) {
    String uri = LinkIndex.text(route, "uri");
    if (uri == null) {
      return null;
    }
    boolean balanced = uri.startsWith("lb:");
    String rest = balanced ? uri.substring(3) : uri;
    if (rest.startsWith("//")) {
      rest = "http:" + rest;
    }
    Url url = LinkText.parse(rest);
    String scheme = url.scheme();
    Answer answer = null;
    // The URI as a deployment filled it in, with the container whose environment did.
    String filledUri = null;
    Container filledBy = null;
    if (!balanced && scheme == null && rest.startsWith("${")) {
      // The whole URI is left to a placeholder, which the deployment may set.
      answer = addresses.resolve(gateway, view, rest, written(route, false));
      scheme = answer.url().scheme();
      if (answer.kind() == Kind.AMBIGUOUS) {
        // Deployments that fill it differently: left open with each unit they lead to.
        unresolved(gateway, route, view, answer.targets(), "ambiguous");
        return null;
      }
      if (answer.address().startsWith("lb:")) {
        // The deployment gives a load-balanced URI: matched by its name, as one written so.
        balanced = true;
        rest = answer.address().substring(3);
        if (rest.startsWith("//")) {
          rest = "http:" + rest;
        }
        url = LinkText.parse(rest);
        scheme = url.scheme();
        filledBy = answer.filledBy();
        filledUri = filledBy == null ? null : answer.address();
        answer = null;
      }
      if (scheme == null) {
        unresolved(
            gateway, route, view, candidates(gateway, route, view), reasonWithoutHost(route));
        return null;
      }
    }
    if (scheme == null || !Set.of("http", "https", "ws", "wss").contains(scheme)) {
      // forward:, no://op and the like: the gateway answers itself.
      return null;
    }
    String protocol =
        switch (scheme) {
          case "ws", "wss" -> "WebSocket";
          case "https" -> "HTTPS";
          default -> "HTTP";
        };
    Target target;
    Set<String> facts = new TreeSet<>();
    facts.add(route.id());
    if (filledBy != null) {
      facts.add(filledBy.fact().id());
    }
    if (balanced) {
      if (url.host() == null) {
        unresolved(gateway, route, view, List.of(), reasonWithoutHost(route));
        return null;
      }
      List<Target> targets =
          addresses.byApplicationName(url.host(), view, "service-name", Addresses.BY_SERVICE_NAME);
      if (targets.size() != 1) {
        unresolved(
            gateway,
            route,
            view,
            targets.isEmpty() ? candidates(gateway, route, view) : targets,
            targets.isEmpty() ? "unknown-host" : "ambiguous");
        return null;
      }
      target = targets.getFirst();
    } else {
      if (answer == null) {
        answer = addresses.resolve(gateway, view, rest, written(route, balanced));
      }
      if (answer.filledBy() != null) {
        facts.add(answer.filledBy().fact().id());
      }
      switch (answer.kind()) {
        case EXTERNAL -> external(gateway, route, view, answer.external(), protocol, facts);
        case AMBIGUOUS -> unresolved(gateway, route, view, answer.targets(), "ambiguous");
        case UNKNOWN ->
            unresolved(
                gateway,
                route,
                view,
                candidates(gateway, route, view),
                answer.url().host() == null
                    ? reasonWithoutHost(route)
                    : answer.local() ? "no-match" : "unknown-host");
        case UNIT -> {}
      }
      if (answer.kind() != Kind.UNIT) {
        return null;
      }
      target = answer.target();
    }
    if (target.unit().equals(gateway)) {
      return null;
    }
    ObjectNode attributes = common(route);
    attributes.put(
        "url",
        filledUri != null
            ? filledUri
            : answer != null && answer.filledBy() != null ? answer.address() : uri);
    attributes.put("protocol", protocol);
    attributes.put("matchedBy", target.matchedBy());
    if (target.fact() != null) {
      facts.add(target.fact().id());
    }
    if (protocol.equals("WebSocket")) {
      List<Fact> endpoints = webSocketEndpoints(target.unit(), route, view);
      if (endpoints.size() == 1) {
        Fact endpoint = endpoints.getFirst();
        attributes.put("endpoint", LinkIndex.text(endpoint, "path"));
        attributes.put("target", endpoint.subject().toString());
      } else if (!endpoints.isEmpty()) {
        ArrayNode array = attributes.putArray("endpoints");
        endpoints.stream()
            .map(e -> LinkIndex.text(e, "path"))
            .distinct()
            .sorted()
            .forEach(array::add);
      }
      endpoints.forEach(e -> facts.add(e.id()));
    }
    out.add(
        view,
        new Found(
            CrossRepoLinks.CALLS_HTTP
                + "|"
                + gateway.id()
                + "|"
                + target.unit().id()
                + "|route|"
                + route.subject()
                + "|"
                + route.object().value(),
            gateway.id(),
            CrossRepoLinks.CALLS_HTTP,
            FactObject.entity(target.unit().id()),
            attributes,
            route.evidence(),
            CrossRepoLinks.round(route.confidence() * target.factor()),
            facts));
    return target.unit();
  }

  /**
   * The WebSocket endpoints of a unit (ADR 0080) a WebSocket route reaches in the view, by its
   * paths as the unit sees them and the unit's servlet prefix; none when a filter rewrites the path
   * in a way not followed.
   */
  private List<Fact> webSocketEndpoints(Unit target, Fact route, String view) {
    List<Fact> found = new ArrayList<>();
    List<String> patterns = patterns(route);
    if (patterns == null) {
      return found;
    }
    String prefix = index.servletPrefix(target, view);
    for (Fact endpoint : index.of(WebSocketExtractor.ENDPOINT)) {
      String template = LinkIndex.text(endpoint, "path");
      if (template == null
          || !index.unitsOf(endpoint.subject()).contains(target)
          || !index.active(endpoint.subject(), view)) {
        continue;
      }
      String path = LinkText.join(prefix, template);
      if (patterns.stream().anyMatch(p -> matches(path, p))) {
        found.add(endpoint);
      }
    }
    return found;
  }

  /** A route's URI as written, without its {@code lb:} prefix, when config changed it. */
  private static String written(Fact route, boolean balanced) {
    String written = LinkIndex.text(route, "written");
    if (written == null) {
      return null;
    }
    String rest = balanced && written.startsWith("lb:") ? written.substring(3) : written;
    return rest.startsWith("//") ? "http:" + rest : rest;
  }

  /** Why a route whose URI has no host is left open. */
  private static String reasonWithoutHost(Fact route) {
    return LinkIndex.texts(route, "unresolved").isEmpty()
        ? "unknown-url"
        : "unresolved-placeholder";
  }

  /**
   * The units a route left open might go to: those, other than the gateway, with an endpoint its
   * paths reach, when a path has a literal segment to tell them by.
   */
  private List<Target> candidates(Unit gateway, Fact route, String view) {
    List<String> patterns = patterns(route);
    List<Target> found = new ArrayList<>();
    if (patterns == null || patterns.stream().allMatch(p -> literals(p) == 0)) {
      return found;
    }
    for (Unit unit : index.units.values()) {
      if (!unit.equals(gateway) && !reachedEndpoints(unit, route, view).isEmpty()) {
        found.add(new Target(unit, null, "path", 0));
      }
    }
    return found;
  }

  /** The endpoints of a unit the route's paths reach in the view, behind its servlet prefix. */
  List<Fact> reachedEndpoints(Unit unit, Fact route, String view) {
    List<Fact> found = new ArrayList<>();
    List<String> patterns = patterns(route);
    if (patterns == null) {
      return found;
    }
    List<String> methods = LinkIndex.texts(route, "methods");
    String prefix = index.servletPrefix(unit, view);
    for (Fact endpoint : endpointsOf(unit, view)) {
      String method = LinkIndex.text(endpoint, "httpMethod");
      String path = LinkIndex.text(endpoint, "path");
      if (path == null || (method != null && !methods.isEmpty() && !methods.contains(method))) {
        continue;
      }
      String served = LinkText.join(prefix, path);
      if (patterns.stream().anyMatch(p -> matches(served, p))) {
        found.add(endpoint);
      }
    }
    return found;
  }

  private void external(
      Unit gateway, Fact route, String view, EntityId system, String protocol, Set<String> facts) {
    ObjectNode attributes = common(route);
    attributes.put("url", LinkIndex.text(route, "uri"));
    attributes.put("protocol", protocol);
    attributes.put("matchedBy", "external");
    out.add(
        view,
        new Found(
            CrossRepoLinks.CALLS_HTTP
                + "|"
                + gateway.id()
                + "|"
                + system
                + "|route|"
                + route.subject()
                + "|"
                + route.object().value(),
            gateway.id(),
            CrossRepoLinks.CALLS_HTTP,
            FactObject.entity(system),
            attributes,
            route.evidence(),
            CrossRepoLinks.round(route.confidence()),
            facts));
  }

  /** Records a route static matching cannot place, with the units it might go to. */
  private void unresolved(
      Unit gateway, Fact route, String view, List<Target> candidates, String reason) {
    ObjectNode attributes = FactJson.newObject();
    attributes.put("kind", "route");
    attributes.put("reason", reason);
    attributes.put("source", route.subject().toString());
    List<String> keys = LinkIndex.texts(route, "unresolved");
    if (!keys.isEmpty()) {
      ArrayNode array = attributes.putArray("unresolved");
      keys.forEach(array::add);
    }
    if (!candidates.isEmpty()) {
      ArrayNode units = attributes.putArray("candidates");
      candidates.stream().map(t -> t.unit().id().toString()).distinct().forEach(units::add);
    }
    out.add(
        view,
        new Found(
            CrossRepoLinks.UNRESOLVED_LINK
                + "|"
                + gateway.id()
                + "|"
                + route.subject()
                + "|route|"
                + route.object().value(),
            gateway.id(),
            CrossRepoLinks.UNRESOLVED_LINK,
            FactObject.string(LinkIndex.text(route, "uri")),
            attributes,
            route.evidence(),
            CrossRepoLinks.round(route.confidence()),
            new TreeSet<>(Set.of(route.id()))));
  }

  /** The attributes every route link has: the route, its paths and methods, and its code. */
  private static ObjectNode common(Fact route) {
    ObjectNode attributes = FactJson.newObject();
    attributes.put("route", route.object().value());
    List<String> paths = LinkIndex.texts(route, "paths");
    if (!paths.isEmpty()) {
      ArrayNode array = attributes.putArray("paths");
      paths.forEach(array::add);
    }
    List<String> methods = LinkIndex.texts(route, "methods");
    if (!methods.isEmpty()) {
      ArrayNode array = attributes.putArray("methods");
      methods.forEach(array::add);
    }
    attributes.put("source", route.subject().toString());
    return attributes;
  }

  /**
   * The route of the gateway that takes a request with the method (null for any) and path in the
   * view, with the path as the unit it goes to sees it; null when none takes it, or the route
   * rewrites the path in a way not followed. Of several, the lowest {@code order} wins, then the
   * pattern with most literal segments, then the route's id, since the code's order of routes is
   * not kept.
   */
  Forward forward(Unit gateway, String view, String method, String path) {
    Forward best = null;
    int bestOrder = 0;
    int bestLiterals = 0;
    for (Routed r : routed) {
      if (!r.gateway().equals(gateway) || !java.util.Objects.equals(r.view(), view)) {
        continue;
      }
      List<String> methods = LinkIndex.texts(r.route(), "methods");
      if (method != null
          && !methods.isEmpty()
          && !methods.contains(method.toUpperCase(Locale.ROOT))) {
        continue;
      }
      List<String> paths = new ArrayList<>(LinkIndex.texts(r.route(), "paths"));
      if (paths.isEmpty()) {
        paths.add("/**");
      }
      int literals = -1;
      for (String pattern : paths) {
        if (matches(path, pattern)) {
          literals = Math.max(literals, literals(pattern));
        }
      }
      if (literals < 0) {
        continue;
      }
      int order = order(r.route());
      if (best == null
          || order < bestOrder
          || (order == bestOrder && literals > bestLiterals)
          || (order == bestOrder
              && literals == bestLiterals
              && r.route().object().value().compareTo(best.routed().route().object().value())
                  < 0)) {
        List<String> forwarded = rewritten(r.route(), List.of(path), true);
        best = new Forward(r, forwarded == null ? null : forwarded.getFirst());
        bestOrder = order;
        bestLiterals = literals;
      }
    }
    return best == null || best.path() == null ? null : best;
  }

  /** A request a route takes, and its path as the route's target sees it. */
  record Forward(Routed routed, String path) {}

  private static int order(Fact route) {
    String order = LinkIndex.text(route, "order");
    if (order == null) {
      return 0;
    }
    try {
      return Integer.parseInt(order.strip());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static int literals(String pattern) {
    int count = 0;
    for (String segment : segments(pattern)) {
      if (!segment.contains("*") && !segment.contains("{")) {
        count++;
      }
    }
    return count;
  }

  // ---------------------------------------------------------------------------------------------
  // Endpoints no route reaches

  /**
   * The path patterns of the requests a route forwards, as its target sees them: its Path
   * predicates ({@code /**} without one) through its StripPrefix and PrefixPath filters; null when
   * another filter rewrites the path.
   */
  static List<String> patterns(Fact route) {
    List<String> patterns = new ArrayList<>(LinkIndex.texts(route, "paths"));
    if (patterns.isEmpty()) {
      patterns.add("/**");
    }
    return rewritten(route, patterns, false);
  }

  /**
   * Paths or patterns as the route's target sees them, through its StripPrefix and PrefixPath
   * filters, and for paths (not patterns) its RewritePath filters; null when another filter
   * rewrites the path.
   *
   * @param concrete whether the paths are requests' paths, which a RewritePath's regular expression
   *     can rewrite, rather than patterns
   */
  private static List<String> rewritten(Fact route, List<String> paths, boolean concrete) {
    List<String> patterns = new ArrayList<>(paths);
    for (String filter : LinkIndex.texts(route, "filters")) {
      String name = filterName(filter);
      int eq = filter.indexOf('=');
      String value = eq < 0 ? null : filter.substring(eq + 1);
      if (concrete && name.equals("RewritePath")) {
        String[] rewrite = rewriteArgs(filter);
        if (rewrite == null) {
          return null;
        }
        try {
          patterns.replaceAll(p -> p.replaceAll(rewrite[0], rewrite[1]));
        } catch (RuntimeException e) {
          return null;
        }
        continue;
      }
      if (REWRITES.contains(name)) {
        return null;
      }
      if (name.equals("StripPrefix")) {
        int parts;
        try {
          parts = value == null ? 1 : Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
          return null;
        }
        patterns.replaceAll(p -> strip(p, parts));
      } else if (name.equals("PrefixPath")) {
        if (value == null || value.contains("{")) {
          return null;
        }
        patterns.replaceAll(p -> LinkText.join(value.strip(), p));
      }
    }
    return patterns;
  }

  /** A path pattern without its first segments; a {@code **} among them leaves {@code /**}. */
  private static String strip(String pattern, int parts) {
    List<String> segments = new ArrayList<>();
    for (String s : pattern.split("/")) {
      if (!s.isEmpty()) {
        segments.add(s);
      }
    }
    for (int i = 0; i < parts && !segments.isEmpty(); i++) {
      if (segments.getFirst().equals("**") || segments.getFirst().startsWith("{*")) {
        return "/**";
      }
      segments.removeFirst();
    }
    return "/" + String.join("/", segments);
  }

  /**
   * Records, on the gateway, each endpoint of a unit it routes to that none of its routes to that
   * unit reaches by path and method, unless a route to it rewrites paths in a way not followed.
   */
  private void unrouted(Unit gateway, String view, Map<Unit, List<Reach>> targets) {
    Map<String, Set<String>> calledBy = new TreeMap<>();
    for (Found f : out.found(view)) {
      String target = f.attributes().path("target").asString(null);
      if (f.predicate().equals(CrossRepoLinks.CALLS_HTTP) && target != null) {
        calledBy.computeIfAbsent(target, t -> new TreeSet<>()).add(f.subject().toString());
      }
    }
    for (Map.Entry<Unit, List<Reach>> e : targets.entrySet()) {
      Unit target = e.getKey();
      List<Reach> reaches = e.getValue();
      if (reaches.stream().anyMatch(r -> r.patterns() == null)) {
        continue;
      }
      String prefix = index.servletPrefix(target, view);
      for (Fact endpoint : index.of(SpringExtractor.ENDPOINT)) {
        String controller = LinkIndex.text(endpoint, "controller");
        EntityId code =
            controller == null ? endpoint.subject() : new EntityId(BuiltInTypes.TYPE, controller);
        String template = LinkIndex.text(endpoint, "path");
        if (template == null
            || !index.unitsOf(code).contains(target)
            || !index.active(endpoint.subject(), view)) {
          continue;
        }
        String path = LinkText.join(prefix, template);
        String method = LinkIndex.text(endpoint, "httpMethod");
        if (reaches.stream().anyMatch(r -> reaches(r, method, path))) {
          continue;
        }
        ObjectNode attributes = FactJson.newObject();
        attributes.put("target", target.id().toString());
        attributes.put("endpoint", endpoint.subject().toString());
        Set<String> callers = calledBy.getOrDefault(endpoint.subject().toString(), Set.of());
        if (!callers.isEmpty()) {
          ArrayNode array = attributes.putArray("calledBy");
          callers.forEach(array::add);
        }
        Set<String> facts = new TreeSet<>();
        facts.add(endpoint.id());
        double confidence = endpoint.confidence();
        for (Reach r : reaches) {
          facts.add(r.route().id());
          confidence = Math.min(confidence, r.route().confidence());
        }
        String text = method == null ? path : method.toUpperCase(Locale.ROOT) + " " + path;
        out.add(
            view,
            new Found(
                CrossRepoLinks.UNROUTED_ENDPOINT
                    + "|"
                    + gateway.id()
                    + "|"
                    + endpoint.subject()
                    + "|"
                    + text,
                gateway.id(),
                CrossRepoLinks.UNROUTED_ENDPOINT,
                FactObject.string(text),
                attributes,
                endpoint.evidence(),
                CrossRepoLinks.round(confidence),
                facts));
      }
    }
  }

  /** Whether a route forwards requests with the method (null for any) and path. */
  private static boolean reaches(Reach reach, String method, String path) {
    if (method != null
        && !reach.methods().isEmpty()
        && !reach.methods().contains(method.toUpperCase(Locale.ROOT))) {
      return false;
    }
    for (String pattern : reach.patterns()) {
      if (matches(segments(path), 0, segments(pattern), 0)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether a route's path pattern takes the requests an endpoint's path template serves: {@code
   * **} and {@code {*rest}} match any rest, {@code *} and a template variable any one segment, a
   * segment with {@code *} in it as a wildcard; a literal segment of the pattern matches only the
   * same literal, not a variable of the endpoint, since the endpoint is then reached only for that
   * one value.
   */
  static boolean matches(String endpoint, String pattern) {
    return matches(segments(endpoint), 0, segments(pattern), 0);
  }

  private static boolean matches(List<String> e, int i, List<String> p, int j) {
    if (j == p.size()) {
      return i == e.size();
    }
    String ps = p.get(j);
    if (ps.equals("**") || ps.startsWith("{*")) {
      for (int k = i; k <= e.size(); k++) {
        if (matches(e, k, p, j + 1)) {
          return true;
        }
      }
      return false;
    }
    if (i == e.size()) {
      return false;
    }
    String es = e.get(i);
    boolean variable = es.contains("{");
    boolean any = ps.equals("*") || (ps.startsWith("{") && ps.endsWith("}"));
    boolean ok;
    if (any) {
      ok = true;
    } else if (variable) {
      ok = false;
    } else if (ps.contains("*")) {
      ok = es.matches(Pattern.quote(ps).replace("*", "\\E.*\\Q"));
    } else {
      ok = ps.equals(es);
    }
    return ok && matches(e, i + 1, p, j + 1);
  }

  private static List<String> segments(String path) {
    List<String> segments = new ArrayList<>();
    for (String s : path.split("/")) {
      if (!s.isEmpty()) {
        segments.add(s);
      }
    }
    return segments;
  }

  // ---------------------------------------------------------------------------------------------
  // Requests as the gateway receives them

  /**
   * Records, on each gateway, each endpoint of a unit it routes to with the request as the gateway
   * receives it, the reverse of the route's StripPrefix, PrefixPath and RewritePath filters, so a
   * flow that starts at the endpoint can start at the gateway (ADR 0047, amendment for #351). An
   * endpoint no route reaches is recorded through the gateway's discovery locator, when it has one
   * on: the unit's service ID as the first segment of the path.
   */
  private void routedEndpoints() {
    // The endpoints recorded through a route, by gateway, view and endpoint fact.
    Set<String> done = new TreeSet<>();
    for (Routed r : routed) {
      if (!"HTTP".equals(protocolOf(r.route())) && !"HTTPS".equals(protocolOf(r.route()))) {
        continue;
      }
      String prefix = index.servletPrefix(r.target(), r.view());
      for (Fact endpoint : endpointsOf(r.target(), r.view())) {
        String served = LinkText.join(prefix, LinkIndex.text(endpoint, "path"));
        String method = LinkIndex.text(endpoint, "httpMethod");
        List<String> methods = LinkIndex.texts(r.route(), "methods");
        if (method != null
            && !methods.isEmpty()
            && !methods.contains(method.toUpperCase(Locale.ROOT))) {
          continue;
        }
        String request = reverse(r.route(), served);
        if (request == null) {
          continue;
        }
        // The request goes to this route, not an earlier or narrower one, and reaches the
        // endpoint.
        Forward forward = forward(r.gateway(), r.view(), method, request);
        if (forward == null
            || !forward.routed().route().equals(r.route())
            || !forward.path().equals(served)) {
          continue;
        }
        done.add(r.gateway().id() + "|" + r.view() + "|" + endpoint.id());
        ObjectNode attributes = routedAttributes(r.target(), endpoint, method, served);
        attributes.put("route", r.route().object().value());
        attributes.put("source", r.route().subject().toString());
        String uri = LinkIndex.text(r.route(), "uri");
        if (uri != null) {
          attributes.put("url", uri);
        }
        Set<String> facts = new TreeSet<>(Set.of(endpoint.id(), r.route().id()));
        routedEndpoint(
            r.gateway(),
            r.view(),
            endpoint,
            method,
            request,
            attributes,
            r.route().evidence(),
            Math.min(endpoint.confidence(), r.route().confidence()),
            facts);
      }
    }
    for (Unit gateway : index.units.values()) {
      for (String view : index.views()) {
        locator(gateway, view, done);
      }
    }
  }

  /**
   * The endpoints the gateway's discovery locator reaches in a view, when it is on and no route
   * reaches them: {@code /SERVICE-ID/**} to {@code lb://SERVICE-ID} without its first segment, for
   * each other unit with a {@code spring.application.name}. Eureka gives service IDs in upper case,
   * which {@code lower-case-service-id} turns to lower case; other registries keep the name.
   */
  private void locator(Unit gateway, String view, Set<String> done) {
    Fact enabled = null;
    Fact lowerCase = null;
    for (String key : LOCATOR) {
      Fact on = index.config(gateway, view, ConfigExtractor.CONFIG_VALUE, key + ".enabled");
      if (on != null && "true".equalsIgnoreCase(on.object().value().strip())) {
        enabled = on;
        for (String name : List.of(".lower-case-service-id", ".lowerCaseServiceId")) {
          Fact lower = index.config(gateway, view, ConfigExtractor.CONFIG_VALUE, key + name);
          if (lower != null) {
            lowerCase = lower;
          }
        }
        break;
      }
    }
    if (enabled == null || !index.active(enabled.subject(), view)) {
      return;
    }
    boolean eureka =
        index.configKeys(gateway, ConfigExtractor.CONFIG_VALUE).stream()
            .anyMatch(k -> k.startsWith("eureka."));
    boolean lower =
        lowerCase != null && "true".equalsIgnoreCase(lowerCase.object().value().strip());
    for (Unit target : index.units.values()) {
      Fact name = index.name(target.id(), view);
      if (target.equals(gateway) || name == null) {
        continue;
      }
      String serviceId = name.object().value().strip();
      if (lower) {
        serviceId = serviceId.toLowerCase(Locale.ROOT);
      } else if (eureka) {
        serviceId = serviceId.toUpperCase(Locale.ROOT);
      }
      String prefix = index.servletPrefix(target, view);
      for (Fact endpoint : endpointsOf(target, view)) {
        if (done.contains(gateway.id() + "|" + view + "|" + endpoint.id())) {
          continue;
        }
        String served = LinkText.join(prefix, LinkIndex.text(endpoint, "path"));
        String method = LinkIndex.text(endpoint, "httpMethod");
        String request = LinkText.join("/" + serviceId, served);
        if (forward(gateway, view, method, request) != null) {
          // A route of the gateway takes the request first.
          continue;
        }
        ObjectNode attributes = routedAttributes(target, endpoint, method, served);
        attributes.put("discoveryLocator", true);
        attributes.put("source", enabled.subject().toString());
        attributes.put("url", "lb://" + serviceId);
        Set<String> facts = new TreeSet<>(Set.of(endpoint.id(), enabled.id(), name.id()));
        if (lowerCase != null) {
          facts.add(lowerCase.id());
        }
        routedEndpoint(
            gateway,
            view,
            endpoint,
            method,
            request,
            attributes,
            enabled.evidence(),
            Math.min(endpoint.confidence(), enabled.confidence()),
            facts);
      }
    }
  }

  /** The endpoints of a unit's code active in a view. */
  private List<Fact> endpointsOf(Unit unit, String view) {
    List<Fact> found = new ArrayList<>();
    for (Fact endpoint : index.of(SpringExtractor.ENDPOINT)) {
      String controller = LinkIndex.text(endpoint, "controller");
      EntityId code =
          controller == null ? endpoint.subject() : new EntityId(BuiltInTypes.TYPE, controller);
      if (LinkIndex.text(endpoint, "path") != null
          && index.unitsOf(code).contains(unit)
          && index.active(endpoint.subject(), view)) {
        found.add(endpoint);
      }
    }
    return found;
  }

  private static ObjectNode routedAttributes(
      Unit target, Fact endpoint, String method, String served) {
    ObjectNode attributes = FactJson.newObject();
    attributes.put("target", target.id().toString());
    attributes.put("endpoint", endpoint.subject().toString());
    attributes.put(
        "served", method == null ? served : method.toUpperCase(Locale.ROOT) + " " + served);
    return attributes;
  }

  private void routedEndpoint(
      Unit gateway,
      String view,
      Fact endpoint,
      String method,
      String request,
      ObjectNode attributes,
      Evidence evidence,
      double confidence,
      Set<String> facts) {
    String text = method == null ? request : method.toUpperCase(Locale.ROOT) + " " + request;
    out.add(
        view,
        new Found(
            CrossRepoLinks.ROUTED_ENDPOINT
                + "|"
                + gateway.id()
                + "|"
                + endpoint.subject()
                + "|"
                + text,
            gateway.id(),
            CrossRepoLinks.ROUTED_ENDPOINT,
            FactObject.string(text),
            attributes,
            evidence,
            CrossRepoLinks.round(confidence),
            facts));
  }

  /** The protocol a route's URI speaks: HTTP, HTTPS or WebSocket; null for none. */
  private static String protocolOf(Fact route) {
    String uri = LinkIndex.text(route, "uri");
    if (uri == null) {
      return null;
    }
    String rest = uri.startsWith("lb:") ? uri.substring(3) : uri;
    if (rest.startsWith("//")) {
      return "HTTP";
    }
    int colon = rest.indexOf(':');
    String scheme = colon < 0 ? "" : rest.substring(0, colon).toLowerCase(Locale.ROOT);
    return switch (scheme) {
      case "http" -> "HTTP";
      case "https" -> "HTTPS";
      case "ws", "wss" -> "WebSocket";
      default -> null;
    };
  }

  /** A filter's name: {@code StripPrefix} of {@code StripPrefix=1} or {@code RewritePath(...)}. */
  private static String filterName(String filter) {
    int eq = filter.indexOf('=');
    int paren = filter.indexOf('(');
    int end = eq < 0 ? paren : paren < 0 ? eq : Math.min(eq, paren);
    return (end < 0 ? filter : filter.substring(0, end)).strip();
  }

  /**
   * A RewritePath filter's regular expression and replacement, as Java's {@link String#replaceAll}
   * takes them ({@code $\{name}}, as YAML needs it written, as {@code ${name}}); null when they
   * cannot be read.
   */
  static String[] rewriteArgs(String filter) {
    String regex;
    String replacement;
    int eq = filter.indexOf('=');
    int paren = filter.indexOf('(');
    if (paren >= 0 && (eq < 0 || paren < eq)) {
      // The full form: RewritePath(regexp=..., replacement=...).
      int r = filter.indexOf("regexp=");
      int p = filter.indexOf(", replacement=");
      if (r < 0 || p < r || !filter.endsWith(")")) {
        return null;
      }
      regex = filter.substring(r + "regexp=".length(), p);
      replacement = filter.substring(p + ", replacement=".length(), filter.length() - 1);
    } else {
      if (eq < 0) {
        return null;
      }
      String value = filter.substring(eq + 1);
      int comma = value.indexOf(',');
      if (comma < 0) {
        return null;
      }
      regex = value.substring(0, comma);
      replacement = value.substring(comma + 1);
    }
    return new String[] {regex.strip(), replacement.strip().replace("$\\", "$")};
  }

  /**
   * The request path a route takes that its filters turn into the path the target serves: the
   * reverse of its StripPrefix (the stripped segments taken from its Path pattern), PrefixPath and
   * RewritePath filters, taken from the last; null when it cannot be reversed or no Path pattern of
   * the route takes the result. Other filters are taken not to change the path.
   */
  static String reverse(Fact route, String served) {
    // The path's segments before the filters, a null for each segment a StripPrefix removed.
    List<String> path = segments(served);
    List<String> filters = LinkIndex.texts(route, "filters");
    for (int i = filters.size() - 1; i >= 0; i--) {
      String filter = filters.get(i);
      String name = filterName(filter);
      int eq = filter.indexOf('=');
      String value = eq < 0 ? null : filter.substring(eq + 1).strip();
      switch (name) {
        case "StripPrefix" -> {
          int parts;
          try {
            parts = value == null ? 1 : Integer.parseInt(value);
          } catch (NumberFormatException e) {
            return null;
          }
          for (int k = 0; k < parts; k++) {
            path.addFirst(null);
          }
        }
        case "PrefixPath" -> {
          if (value == null || value.contains("{")) {
            return null;
          }
          for (String segment : segments(value)) {
            if (path.isEmpty()) {
              return null;
            }
            String first = path.removeFirst();
            if (first != null && !first.equals(segment)) {
              return null;
            }
          }
        }
        case "RewritePath" -> {
          String[] rewrite = rewriteArgs(filter);
          if (rewrite == null || path.contains(null)) {
            return null;
          }
          String before = unrewrite(rewrite[0], rewrite[1], "/" + String.join("/", path));
          if (before == null) {
            return null;
          }
          path = segments(before);
        }
        case "SetPath" -> {
          return null;
        }
        default -> {}
      }
    }
    List<String> patterns = new ArrayList<>(LinkIndex.texts(route, "paths"));
    if (patterns.isEmpty()) {
      patterns.add("/**");
    }
    for (String pattern : patterns) {
      List<String> fixed = segments(pattern);
      List<String> request = new ArrayList<>(path);
      boolean ok = true;
      for (int k = 0; k < request.size() && ok; k++) {
        if (request.get(k) != null) {
          continue;
        }
        // A stripped segment is the pattern's literal segment at that place; a wildcard or a
        // variable there cannot be told.
        String segment = k < fixed.size() ? fixed.get(k) : null;
        if (segment == null || segment.contains("*") || segment.contains("{")) {
          ok = false;
        } else {
          request.set(k, segment);
        }
      }
      if (!ok) {
        continue;
      }
      String text = "/" + String.join("/", request);
      if (matches(text, pattern)) {
        return text;
      }
    }
    return null;
  }

  /**
   * A path that a RewritePath turns into the one given, read from the regular expression: its
   * literal characters, each group as the value the replacement gives it, and each optional
   * character either there or not, whichever rewrites back to the path; null when the expression
   * has other constructs (character classes, alternatives, quantifiers outside groups) or none
   * fits.
   */
  static String unrewrite(String regex, String replacement, String path) {
    // The replacement's literal text and group references, as a pattern that reads the groups'
    // values from the path.
    StringBuilder reader = new StringBuilder("^");
    List<String> refs = new ArrayList<>();
    StringBuilder literal = new StringBuilder();
    for (int i = 0; i < replacement.length(); i++) {
      char c = replacement.charAt(i);
      if (c == '\\' && i + 1 < replacement.length()) {
        literal.append(replacement.charAt(++i));
      } else if (c == '$' && i + 1 < replacement.length()) {
        String ref;
        if (replacement.charAt(i + 1) == '{') {
          int close = replacement.indexOf('}', i);
          if (close < 0) {
            return null;
          }
          ref = replacement.substring(i + 2, close);
          i = close;
        } else if (Character.isDigit(replacement.charAt(i + 1))) {
          ref = String.valueOf(replacement.charAt(++i));
        } else {
          return null;
        }
        reader.append(Pattern.quote(literal.toString())).append("(.*?)");
        literal.setLength(0);
        refs.add(ref);
      } else {
        literal.append(c);
      }
    }
    reader.append(Pattern.quote(literal.toString())).append('$');
    Matcher read = Pattern.compile(reader.toString()).matcher(path);
    if (!read.matches()) {
      return null;
    }
    Map<String, String> values = new LinkedHashMap<>();
    for (int k = 0; k < refs.size(); k++) {
      String earlier = values.putIfAbsent(refs.get(k), read.group(k + 1));
      if (earlier != null && !earlier.equals(read.group(k + 1))) {
        return null;
      }
    }
    // The expression's text with each group's value in its place; each optional character is a
    // choice, tried both ways.
    List<StringBuilder> candidates = new ArrayList<>(List.of(new StringBuilder()));
    int choices = 0;
    for (int i = 0; i < regex.length(); i++) {
      char c = regex.charAt(i);
      if ((c == '^' && i == 0) || (c == '$' && i == regex.length() - 1)) {
        continue;
      }
      String text;
      if (c == '\\') {
        if (i + 1 == regex.length() || Character.isLetterOrDigit(regex.charAt(i + 1))) {
          return null;
        }
        text = String.valueOf(regex.charAt(++i));
      } else if (c == '(') {
        int close = closing(regex, i);
        if (close < 0) {
          return null;
        }
        String name = null;
        if (regex.startsWith("(?<", i)
            && regex.charAt(i + 3) != '='
            && regex.charAt(i + 3) != '!') {
          name = regex.substring(i + 3, regex.indexOf('>', i));
        } else if (regex.startsWith("(?", i)) {
          return null;
        }
        String value =
            name != null && values.containsKey(name)
                ? values.get(name)
                : values.get(String.valueOf(groupNumber(regex, i)));
        i = close;
        boolean optional =
            i + 1 < regex.length() && (regex.charAt(i + 1) == '?' || regex.charAt(i + 1) == '*');
        if (optional) {
          i++;
        } else if (i + 1 < regex.length() && regex.charAt(i + 1) == '+') {
          i++;
        }
        if (value == null) {
          if (!optional) {
            return null;
          }
          value = "";
        }
        text = value;
      } else if ("[]{}|*+.?)".indexOf(c) >= 0) {
        return null;
      } else {
        text = String.valueOf(c);
      }
      if (i + 1 < regex.length() && regex.charAt(i + 1) == '?' && c != '(') {
        i++;
        if (++choices > MAX_CHOICES) {
          return null;
        }
        List<StringBuilder> both = new ArrayList<>();
        for (StringBuilder candidate : candidates) {
          both.add(new StringBuilder(candidate).append(text));
          both.add(new StringBuilder(candidate));
        }
        candidates = both;
      } else {
        for (StringBuilder candidate : candidates) {
          candidate.append(text);
        }
      }
    }
    for (StringBuilder candidate : candidates) {
      String before = candidate.toString();
      try {
        if (before.replaceAll(regex, replacement).equals(path)) {
          return before;
        }
      } catch (RuntimeException e) {
        return null;
      }
    }
    return null;
  }

  /** The number of the capturing group that opens at an index, counting from 1. */
  private static int groupNumber(String regex, int open) {
    int number = 0;
    for (int k = 0; k <= open; k++) {
      if (regex.charAt(k) == '\\') {
        k++;
      } else if (regex.charAt(k) == '('
          && (!regex.startsWith("(?", k)
              || (regex.startsWith("(?<", k)
                  && regex.charAt(k + 3) != '='
                  && regex.charAt(k + 3) != '!'))) {
        number++;
      }
    }
    return number;
  }

  /** The index of the parenthesis that closes the one at an index; -1 if none. */
  private static int closing(String regex, int open) {
    int depth = 0;
    for (int k = open; k < regex.length(); k++) {
      char c = regex.charAt(k);
      if (c == '\\') {
        k++;
      } else if (c == '(') {
        depth++;
      } else if (c == ')' && --depth == 0) {
        return k;
      }
    }
    return -1;
  }
}

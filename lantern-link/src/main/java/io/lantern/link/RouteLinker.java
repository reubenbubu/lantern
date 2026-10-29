package io.lantern.link;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.gateway.GatewayExtractor;
import io.lantern.extract.spring.SpringExtractor;
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
import java.util.regex.Pattern;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Links Spring Cloud Gateway routes to the units they send requests to (ADR 0077): a load-balanced
 * URI ({@code lb://name}, {@code lb:ws://name}) by the name against {@code
 * spring.application.name}, any other by its host as an HTTP call's URL is matched, and one to this
 * machine by its port against the units' {@code server.port}. Each route is a {@code CALLS_HTTP}
 * link from the gateway, with the route's id, paths and protocol (HTTP, HTTPS or WebSocket). Then,
 * for each unit a gateway routes to, the endpoints no route of that gateway reaches are recorded on
 * the gateway as {@code unrouted-endpoint}.
 */
final class RouteLinker {

  /** Confidence factors of the ways a target is found. */
  static final double BY_NAME = 1.0;

  static final double BY_HOST = 0.9;
  static final double BY_ARTIFACT_ID = 0.8;
  static final double BY_PORT = 0.8;

  /** The port a unit that sets none listens on, as Spring Boot's embedded server does. */
  private static final String DEFAULT_PORT = "8080";

  /** Filters that change the path in ways the linker does not follow. */
  private static final Set<String> REWRITES = Set.of("RewritePath", "SetPath");

  /**
   * What one route reaches of a unit, in one view.
   *
   * @param patterns the path patterns of the requests it forwards, as the target sees them (after
   *     StripPrefix and PrefixPath); null when a filter rewrites the path in a way not followed
   * @param methods the HTTP methods it takes, empty for any
   */
  private record Reach(Fact route, List<String> patterns, List<String> methods) {}

  private final LinkIndex index;
  private final FoundLinks out;

  RouteLinker(LinkIndex index, FoundLinks out) {
    this.index = index;
    this.out = out;
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
    String host = url.host();
    if (host == null) {
      unresolved(
          gateway,
          route,
          view,
          List.of(),
          LinkIndex.texts(route, "unresolved").isEmpty()
              ? "unknown-url"
              : "unresolved-placeholder");
      return null;
    }
    List<Target> targets;
    if (balanced) {
      targets = byName(host, view, "service-name", BY_NAME);
    } else if (url.local() || host.equals("0.0.0.0")) {
      targets = byPort(gateway, url, view);
      if (targets.isEmpty()) {
        unresolved(gateway, route, view, List.of(), "no-match");
        return null;
      }
    } else {
      targets = byName(host, view, "host", BY_HOST);
      if (targets.isEmpty() && (host.endsWith(".svc") || host.endsWith(".svc.cluster.local"))) {
        targets = byName(host.substring(0, host.indexOf('.')), view, "host", BY_HOST);
      }
      if (targets.isEmpty()) {
        for (Unit unit : index.units.values()) {
          if (unit.artifactId().equalsIgnoreCase(host)) {
            targets.add(new Target(unit, null, "artifact-id", BY_ARTIFACT_ID));
          }
        }
      }
      if (targets.isEmpty() && (host.contains(".") || host.contains(":"))) {
        external(gateway, route, view, host, protocol);
        return null;
      }
    }
    if (targets.size() != 1) {
      unresolved(gateway, route, view, targets, targets.isEmpty() ? "unknown-host" : "ambiguous");
      return null;
    }
    Target target = targets.getFirst();
    if (target.unit().equals(gateway)) {
      return null;
    }
    ObjectNode attributes = common(route);
    attributes.put("url", uri);
    attributes.put("protocol", protocol);
    attributes.put("matchedBy", target.matchedBy());
    Set<String> facts = new TreeSet<>();
    facts.add(route.id());
    if (target.fact() != null) {
      facts.add(target.fact().id());
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

  /** A target found for a route, with the fact it was found by (a name or port), if any. */
  private record Target(Unit unit, Fact fact, String matchedBy, double factor) {}

  private List<Target> byName(String name, String view, String matchedBy, double factor) {
    List<Target> targets = new ArrayList<>();
    for (Unit unit : index.units.values()) {
      Fact fact = index.name(unit.id(), view);
      if (fact != null && fact.object().value().equalsIgnoreCase(name)) {
        targets.add(new Target(unit, fact, matchedBy, factor));
      }
    }
    return targets;
  }

  /** The other units listening on the URL's port in the view ({@value #DEFAULT_PORT} if unset). */
  private List<Target> byPort(Unit gateway, Url url, String view) {
    List<Target> targets = new ArrayList<>();
    if (url.port() == null) {
      return targets;
    }
    for (Unit unit : index.units.values()) {
      if (unit.equals(gateway)) {
        continue;
      }
      Fact port = index.config(unit, view, ConfigExtractor.CONFIG_VALUE, "server.port");
      if (port != null && LinkIndex.flag(port, "masked")) {
        continue;
      }
      String value = port == null ? DEFAULT_PORT : port.object().value();
      if (value.equals(url.port())) {
        targets.add(new Target(unit, port, "port", BY_PORT));
      }
    }
    return targets;
  }

  private void external(Unit gateway, Fact route, String view, String host, String protocol) {
    ObjectNode attributes = common(route);
    attributes.put("url", LinkIndex.text(route, "uri"));
    attributes.put("protocol", protocol);
    attributes.put("matchedBy", "external");
    EntityId system = new EntityId(CrossRepoLinks.EXTERNAL_SYSTEM, host);
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
            new TreeSet<>(Set.of(route.id()))));
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
    for (String filter : LinkIndex.texts(route, "filters")) {
      int eq = filter.indexOf('=');
      int paren = filter.indexOf('(');
      int end = eq < 0 ? paren : paren < 0 ? eq : Math.min(eq, paren);
      String name = end < 0 ? filter : filter.substring(0, end);
      String value = eq < 0 ? null : filter.substring(eq + 1);
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
      if (segments.getFirst().equals("**")) {
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
   * **} matches any rest, {@code *} and a template variable any one segment, a segment with {@code
   * *} in it as a wildcard; a literal segment of the pattern matches only the same literal, not a
   * variable of the endpoint, since the endpoint is then reached only for that one value.
   */
  static boolean matches(String endpoint, String pattern) {
    return matches(segments(endpoint), 0, segments(pattern), 0);
  }

  private static boolean matches(List<String> e, int i, List<String> p, int j) {
    if (j == p.size()) {
      return i == e.size();
    }
    String ps = p.get(j);
    if (ps.equals("**")) {
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
}

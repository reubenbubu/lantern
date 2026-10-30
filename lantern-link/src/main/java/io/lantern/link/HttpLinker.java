package io.lantern.link;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.http.HttpClientExtractor;
import io.lantern.extract.spring.SpringExtractor;
import io.lantern.link.FoundLinks.Found;
import io.lantern.link.LinkIndex.Unit;
import io.lantern.link.LinkText.Url;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Links HTTP calls to the units that serve them, or to external systems (ADR 0042): by a Feign
 * client's name against {@code spring.application.name}, else by the URL's host against unit names,
 * then the path against the target's endpoints; a call to this machine by its port against the
 * units' {@code server.port} when one other unit listens there, else by its path alone.
 */
final class HttpLinker {

  /** Confidence factors of the ways a target is found. */
  static final double BY_NAME = 1.0;

  static final double BY_HOST = 0.9;
  static final double BY_ARTIFACT_ID = 0.8;
  static final double BY_PORT = 0.8;
  static final double BY_PATH = 0.6;

  /** The port a unit that sets none listens on, as Spring Boot's embedded server does. */
  private static final String DEFAULT_PORT = "8080";

  /** Factor for a target found without an endpoint that matches the path. */
  static final double WITHOUT_ENDPOINT = 0.8;

  /** An endpoint a unit serves. */
  private record Endpoint(Fact fact, Unit unit) {}

  /** A call as it is made in one view by one unit. */
  private record Call(
      Fact fact,
      String httpMethod,
      String service,
      Url url,
      Set<String> unresolved,
      String text,
      List<Fact> used) {}

  /** A target found for a call. */
  private record Target(Unit unit, Fact name, String matchedBy, double factor) {}

  private final LinkIndex index;
  private final FoundLinks out;
  private final List<Endpoint> endpoints = new ArrayList<>();

  /** The client interfaces each type enables ({@code @EnableFeignClients}). */
  private final Map<EntityId, List<Fact>> enabledBy = new HashMap<>();

  HttpLinker(LinkIndex index, FoundLinks out) {
    this.index = index;
    this.out = out;
    for (Fact f : index.of(SpringExtractor.ENDPOINT)) {
      String controller = LinkIndex.text(f, "controller");
      EntityId code =
          controller == null ? f.subject() : new EntityId(BuiltInTypes.TYPE, controller);
      for (Unit unit : index.unitsOf(code)) {
        endpoints.add(new Endpoint(f, unit));
      }
    }
    for (Fact f : index.of(HttpClientExtractor.ENABLES)) {
      if (f.object().kind() == FactObject.Kind.ENTITY) {
        enabledBy.computeIfAbsent(f.object().entityId(), c -> new ArrayList<>()).add(f);
      }
    }
  }

  void link() {
    for (List<Fact> site : LinkIndex.codeSites(index.of(HttpClientExtractor.HTTP_CALL)).values()) {
      Fact first = site.getFirst();
      for (Unit caller : callers(first)) {
        Map<String, Call> calls = new LinkedHashMap<>();
        for (String view : index.views()) {
          Call call = call(site, caller, view);
          if (call != null) {
            calls.put(view == null ? "" : view, call);
          }
        }
        for (Map.Entry<String, Call> e : calls.entrySet()) {
          String view = e.getKey().isEmpty() ? null : e.getKey();
          Call call = e.getValue();
          link(caller, call, view, targets(call, view));
        }
        flush();
      }
    }
  }

  /** The links of one call by one unit, in every view, before they are added. */
  private final List<Map.Entry<String, Found>> pending = new ArrayList<>();

  private void emit(String view, Found found) {
    pending.add(new AbstractMap.SimpleEntry<>(view, found));
  }

  /**
   * Adds the pending links of one call, each naming in {@code alternates} the other units and
   * external systems the same call reaches under other profiles.
   */
  private void flush() {
    Set<String> targets = new TreeSet<>();
    for (Map.Entry<String, Found> e : pending) {
      if (e.getValue().predicate().equals(CrossRepoLinks.CALLS_HTTP)) {
        targets.add(e.getValue().object().value());
      }
    }
    for (Map.Entry<String, Found> e : pending) {
      Found found = e.getValue();
      if (found.predicate().equals(CrossRepoLinks.CALLS_HTTP) && targets.size() > 1) {
        ArrayNode alternates = found.attributes().putArray("alternates");
        targets.stream().filter(t -> !t.equals(found.object().value())).forEach(alternates::add);
      }
      out.add(e.getKey(), found);
    }
    pending.clear();
  }

  /**
   * The units that make a call: those running its code; for a Feign client, only those that also
   * run code enabling it, since a client nothing enables is never created.
   */
  private List<Unit> callers(Fact call) {
    List<Unit> units = index.unitsOf(call.subject());
    if (!HttpClientExtractor.FEIGN.equals(LinkIndex.text(call, "client"))) {
      return units;
    }
    String clientType = LinkIndex.text(call, "clientType");
    EntityId client =
        clientType == null
            ? LinkIndex.typeOf(call.subject())
            : new EntityId(BuiltInTypes.TYPE, clientType);
    Set<Unit> enabling = new LinkedHashSet<>();
    for (Fact enables : enabledBy.getOrDefault(client, List.of())) {
      enabling.addAll(index.unitsOf(enables.subject()));
    }
    return units.stream().filter(enabling::contains).toList();
  }

  /**
   * The call in a view, or null when its code is gated off or its URL hides a secret. Code in a
   * library runs with the config of the unit that bundles it, so a URL written with placeholders
   * there is resolved again with the unit's config.
   */
  private Call call(List<Fact> site, Unit caller, String view) {
    Fact fact = LinkIndex.inView(Map.of("", site), view).get("");
    if (fact == null || !index.active(fact.subject(), view) || LinkIndex.flag(fact, "masked")) {
      return null;
    }
    String httpMethod = LinkIndex.text(fact, "httpMethod");
    String service = LinkIndex.text(fact, "service");
    String written = LinkIndex.text(fact, "written");
    if (written != null
        && !caller.modules().isEmpty()
        && !index.modulesOf(fact.subject()).contains(caller.modules().getFirst())) {
      LinkText.Resolved resolved =
          LinkText.resolve(
              written, key -> index.config(caller, view, ConfigExtractor.CONFIG_VALUE, key));
      if (resolved.masked()) {
        return null;
      }
      return new Call(
          fact,
          httpMethod,
          service,
          LinkText.parse(resolved.text()),
          resolved.unresolved(),
          (httpMethod == null ? "" : httpMethod + " ") + resolved.text(),
          resolved.used());
    }
    String url = LinkIndex.text(fact, "url");
    Url parsed =
        url != null ? LinkText.parse(url) : new Url(null, null, null, LinkIndex.text(fact, "path"));
    return new Call(
        fact,
        httpMethod,
        service,
        parsed,
        new LinkedHashSet<>(LinkIndex.texts(fact, "unresolved")),
        fact.object().value(),
        List.of());
  }

  /**
   * The units a call goes to: by the Feign client's name when its URL is the load-balanced one,
   * else by the URL's host. An empty list when the host is not known or is this machine, so only
   * the path can tell; null when a host with a domain names no unit, so it is an external system.
   */
  private List<Target> targets(Call call, String view) {
    String host = call.url().host();
    if (call.service() != null
        && (host == null || host.equals(call.service().toLowerCase(Locale.ROOT)))) {
      List<Target> byName = byName(call.service(), view, "service-name", BY_NAME);
      if (!byName.isEmpty() || host == null) {
        return byName;
      }
    }
    if (host == null || call.url().local()) {
      return List.of();
    }
    List<Target> byHost = byName(host, view, "host", BY_HOST);
    if (byHost.isEmpty() && (host.endsWith(".svc") || host.endsWith(".svc.cluster.local"))) {
      // A Kubernetes service name: <service>.<namespace>.svc...
      byHost = byName(host.substring(0, host.indexOf('.')), view, "host", BY_HOST);
    }
    if (byHost.isEmpty()) {
      for (Unit unit : index.units.values()) {
        if (unit.artifactId().equalsIgnoreCase(host)) {
          byHost.add(new Target(unit, null, "artifact-id", BY_ARTIFACT_ID));
        }
      }
    }
    if (byHost.isEmpty() && !host.contains(".") && !host.contains(":") && !host.startsWith("[")) {
      // A name without a domain is a compose, Kubernetes or discovery name, not a system on the
      // internet: only the path can tell which unit it is.
      return List.of();
    }
    return byHost.isEmpty() ? null : byHost;
  }

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

  /** Records what a call in a view links to. */
  private void link(Unit caller, Call call, String view, List<Target> targets) {
    String path = call.url().path();
    if (targets == null) {
      linkExternal(caller, call, view, call.url().host());
      return;
    }
    if (targets.isEmpty() && call.url().local()) {
      // A call to this machine goes to what listens on its port, when that is one other unit.
      List<Target> byPort = byPort(caller, call.url(), view);
      if (byPort.size() == 1) {
        Target target = byPort.getFirst();
        Endpoint endpoint = path == null ? null : endpoint(target.unit(), call, view);
        // A unit on the default port, setting none, is taken only where it serves the path: other
        // servers on this machine (an identity provider, say) listen on 8080 too.
        if (target.name() != null || endpoint != null) {
          linkUnit(caller, call, view, target, endpoint);
          return;
        }
      }
    }
    if (targets.isEmpty()) {
      // Only the path can tell: decided for a call to this machine when one unit serves it.
      List<Target> byPath = new ArrayList<>();
      Map<Unit, Endpoint> best = new LinkedHashMap<>();
      if (path != null) {
        for (Unit unit : index.units.values()) {
          Endpoint endpoint = endpoint(unit, call, view);
          if (endpoint != null && !unit.equals(caller)) {
            byPath.add(new Target(unit, null, "path", BY_PATH));
            best.put(unit, endpoint);
          }
        }
      }
      if (call.url().local() && byPath.size() == 1) {
        Target target = byPath.getFirst();
        linkUnit(caller, call, view, target, best.get(target.unit()));
      } else if (call.url().local()) {
        unresolved(caller, call, view, byPath, byPath.isEmpty() ? "no-match" : "ambiguous");
      } else if (call.url().host() != null) {
        unresolved(caller, call, view, byPath, "unknown-host");
      } else {
        unresolved(
            caller,
            call,
            view,
            byPath,
            call.unresolved().isEmpty() ? "unknown-url" : "unresolved-placeholder");
      }
      return;
    }
    if (targets.size() > 1) {
      unresolved(caller, call, view, targets, "ambiguous");
      return;
    }
    Target target = targets.getFirst();
    if (!target.unit().equals(caller)) {
      linkUnit(
          caller, call, view, target, path == null ? null : endpoint(target.unit(), call, view));
    }
  }

  /** The other units listening on the URL's port in the view ({@value #DEFAULT_PORT} if unset). */
  private List<Target> byPort(Unit caller, Url url, String view) {
    List<Target> targets = new ArrayList<>();
    if (url.port() == null) {
      return targets;
    }
    for (Unit unit : index.units.values()) {
      if (unit.equals(caller)) {
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

  private void linkUnit(Unit caller, Call call, String view, Target target, Endpoint endpoint) {
    Fact fact = call.fact();
    ObjectNode attributes = common(call);
    Set<String> facts = new TreeSet<>();
    facts.add(fact.id());
    call.used().forEach(u -> facts.add(u.id()));
    if (target.name() != null) {
      facts.add(target.name().id());
    }
    double confidence = fact.confidence() * target.factor();
    String protocol = "https".equals(call.url().scheme()) ? "HTTPS" : "HTTP";
    if (endpoint != null) {
      Fact e = endpoint.fact();
      facts.add(e.id());
      attributes.put("endpoint", e.object().value());
      attributes.put("target", e.subject().toString());
      confidence = Math.min(confidence, e.confidence() * target.factor());
      String controller = LinkIndex.text(e, "controller");
      if (controller != null
          && index.restControllers.contains(new EntityId(BuiltInTypes.TYPE, controller))) {
        protocol = "JSON/" + protocol;
      }
    } else {
      confidence *= WITHOUT_ENDPOINT;
    }
    attributes.put("protocol", protocol);
    attributes.put("matchedBy", target.matchedBy());
    String identity =
        CrossRepoLinks.CALLS_HTTP
            + "|"
            + caller.id()
            + "|"
            + target.unit().id()
            + "|"
            + fact.subject()
            + "|"
            + (endpoint == null ? "" : endpoint.fact().subject());
    emit(
        view,
        new Found(
            identity,
            caller.id(),
            CrossRepoLinks.CALLS_HTTP,
            FactObject.entity(target.unit().id()),
            attributes,
            fact.evidence(),
            CrossRepoLinks.round(confidence),
            facts));
  }

  private void linkExternal(Unit caller, Call call, String view, String host) {
    Fact fact = call.fact();
    ObjectNode attributes = common(call);
    attributes.put("protocol", "https".equals(call.url().scheme()) ? "HTTPS" : "HTTP");
    attributes.put("matchedBy", "external");
    Set<String> facts = new TreeSet<>();
    facts.add(fact.id());
    call.used().forEach(u -> facts.add(u.id()));
    EntityId system = new EntityId(CrossRepoLinks.EXTERNAL_SYSTEM, host);
    emit(
        view,
        new Found(
            CrossRepoLinks.CALLS_HTTP + "|" + caller.id() + "|" + system + "|" + fact.subject(),
            caller.id(),
            CrossRepoLinks.CALLS_HTTP,
            FactObject.entity(system),
            attributes,
            fact.evidence(),
            CrossRepoLinks.round(fact.confidence()),
            facts));
  }

  /** Records a call static matching cannot decide, with the units it might go to. */
  private void unresolved(
      Unit caller, Call call, String view, List<Target> candidates, String reason) {
    Fact fact = call.fact();
    ObjectNode attributes = FactJson.newObject();
    attributes.put("kind", "http");
    attributes.put("reason", reason);
    attributes.put("source", fact.subject().toString());
    if (!call.unresolved().isEmpty()) {
      ArrayNode keys = attributes.putArray("unresolved");
      call.unresolved().forEach(keys::add);
    }
    if (!candidates.isEmpty()) {
      ArrayNode units = attributes.putArray("candidates");
      candidates.stream().map(t -> t.unit().id().toString()).distinct().forEach(units::add);
    }
    Set<String> facts = new TreeSet<>();
    facts.add(fact.id());
    call.used().forEach(u -> facts.add(u.id()));
    emit(
        view,
        new Found(
            CrossRepoLinks.UNRESOLVED_LINK + "|" + caller.id() + "|" + fact.subject() + "|http",
            caller.id(),
            CrossRepoLinks.UNRESOLVED_LINK,
            FactObject.string(call.text()),
            attributes,
            fact.evidence(),
            CrossRepoLinks.round(fact.confidence()),
            facts));
  }

  /** The attributes every HTTP link has: the call's client, method, path and URL, and its code. */
  private static ObjectNode common(Call call) {
    ObjectNode attributes = FactJson.newObject();
    Fact fact = call.fact();
    String client = LinkIndex.text(fact, "client");
    if (client != null) {
      attributes.put("client", client);
    }
    if (call.httpMethod() != null) {
      attributes.put("httpMethod", call.httpMethod());
    }
    if (call.url().path() != null) {
      attributes.put("path", call.url().path().isEmpty() ? "/" : call.url().path());
    }
    String text = call.text();
    String url = call.httpMethod() == null ? text : text.substring(call.httpMethod().length() + 1);
    if (call.url().host() != null) {
      attributes.put("url", url);
    }
    attributes.put("source", fact.subject().toString());
    return attributes;
  }

  /**
   * The unit's endpoint that best matches a call's method and path, behind the unit's context and
   * servlet paths in the view: the one with most literal segments matching, then the first by ID;
   * null if none.
   */
  private Endpoint endpoint(Unit unit, Call call, String view) {
    List<Endpoint> ranked = ranked(unit, call.httpMethod(), call.url().path(), view);
    return ranked.isEmpty() ? null : ranked.getFirst();
  }

  /**
   * The {@code endpoint} facts of a unit that a request with the method (null for any) and path
   * matches in the view, best first as {@link #endpoint} ranks them, for the LLM resolver.
   */
  List<Fact> endpoints(Unit unit, String httpMethod, String path, String view) {
    return ranked(unit, httpMethod, path, view).stream().map(Endpoint::fact).toList();
  }

  /**
   * The unit's endpoints that match a method and path behind the unit's context and servlet paths
   * in the view, by the number of literal segments matching, most first, then by ID.
   */
  private List<Endpoint> ranked(Unit unit, String httpMethod, String path, String view) {
    if (path == null) {
      return List.of();
    }
    String prefix = index.servletPrefix(unit, view);
    List<Map.Entry<Endpoint, Integer>> scored = new ArrayList<>();
    for (Endpoint endpoint : endpoints) {
      if (!endpoint.unit().equals(unit) || !index.active(endpoint.fact().subject(), view)) {
        continue;
      }
      String method = LinkIndex.text(endpoint.fact(), "httpMethod");
      if (method != null && httpMethod != null && !method.equals(httpMethod)) {
        continue;
      }
      String template = LinkIndex.text(endpoint.fact(), "path");
      if (template == null) {
        continue;
      }
      int score = LinkText.pathMatch(path, LinkText.join(prefix, template));
      if (score >= 0) {
        scored.add(new AbstractMap.SimpleEntry<>(endpoint, score));
      }
    }
    // A stable sort keeps the endpoints' ID order among equal scores.
    scored.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
    return scored.stream().map(Map.Entry::getKey).toList();
  }
}

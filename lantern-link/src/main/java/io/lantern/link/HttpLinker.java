package io.lantern.link;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.extract.attributes.HttpCallAttributes;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.http.HttpClientExtractor;
import io.lantern.extract.spring.SpringExtractor;
import io.lantern.link.Addresses.Answer;
import io.lantern.link.Addresses.Target;
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
 * client's name against {@code spring.application.name}, else by the URL as address resolution
 * answers it ({@link AddressAnswers}), then the path against the target's endpoints; a call to this
 * machine or a private address that no single unit listens on is linked by its path alone.
 */
final class HttpLinker {

  /** Confidence factor of a target found by the call's path alone. */
  static final double BY_PATH = 0.6;

  /** Factor for a target found without an endpoint that matches the path. */
  static final double WITHOUT_ENDPOINT = 0.8;

  /** An endpoint a unit serves. */
  private record Endpoint(Fact fact, Unit unit) {}

  /**
   * A call as it is made in one view by one unit.
   *
   * @param address the URL as the unit's config resolved it, placeholders it left kept as written
   * @param used the config facts, and the deployment container, that filled the URL in
   * @param filled the URL as a deployment container's environment filled it in, or null
   */
  private record Call(
      Fact fact,
      String httpMethod,
      String service,
      Url url,
      Set<String> unresolved,
      String text,
      String address,
      String written,
      List<Fact> used,
      String filled) {

    /** The call with its URL as a deployment container's environment filled it in. */
    Call filled(String filled, Fact container) {
      List<Fact> facts = new ArrayList<>(used);
      facts.add(container);
      return new Call(
          fact,
          httpMethod,
          service,
          LinkText.parse(filled),
          new LinkedHashSet<>(),
          text,
          address,
          written,
          facts,
          filled);
    }
  }

  private final LinkIndex index;
  private final FoundLinks out;
  private final AddressAnswers addresses;
  private final List<Endpoint> endpoints = new ArrayList<>();

  /** The client interfaces each type enables ({@code @EnableFeignClients}). */
  private final Map<EntityId, List<Fact>> enabledBy = new HashMap<>();

  HttpLinker(LinkIndex index, FoundLinks out, AddressAnswers addresses) {
    this.index = index;
    this.out = out;
    this.addresses = addresses;
    for (Fact f : index.of(SpringExtractor.ENDPOINT)) {
      String controller = Endpoints.of(f).controller();
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

  /**
   * What the calls ask address resolution (#589): for each call, unit making it and view, where its
   * address leads, and, for a call with a service name, which units that name is.
   */
  static void queries(LinkIndex index, AddressResolution.Questions questions) {
    HttpLinker http = new HttpLinker(index, null, null);
    for (List<Fact> site : LinkIndex.codeSites(index.of(HttpClientExtractor.HTTP_CALL)).values()) {
      for (Unit caller : http.callers(site.getFirst())) {
        for (String view : index.views()) {
          Call call = http.call(site, caller, view);
          if (call != null) {
            questions.address(caller, view, call.address(), call.written());
            if (call.service() != null) {
              questions.name(call.service(), view);
            }
          }
        }
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
          link(caller, call, view);
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
    if (!HttpClientExtractor.FEIGN.equals(Claims.of(call, HttpCallAttributes.class).client())) {
      return units;
    }
    String clientType = Claims.of(call, HttpCallAttributes.class).clientType();
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
    if (fact == null || !index.active(fact.subject(), view) || Claims.masked(fact)) {
      return null;
    }
    String httpMethod = Claims.of(fact, HttpCallAttributes.class).httpMethod();
    String service = Claims.of(fact, HttpCallAttributes.class).service();
    String written = Claims.written(fact);
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
          resolved.text(),
          written,
          resolved.used(),
          null);
    }
    String url = Claims.of(fact, HttpCallAttributes.class).url();
    Url parsed =
        url != null
            ? LinkText.parse(url)
            : new Url(null, null, null, Claims.of(fact, HttpCallAttributes.class).path());
    String object = fact.object().value();
    String address =
        url != null
            ? url
            : httpMethod != null && object.startsWith(httpMethod + " ")
                ? object.substring(httpMethod.length() + 1)
                : object;
    return new Call(
        fact,
        httpMethod,
        service,
        parsed,
        new LinkedHashSet<>(Claims.unresolved(fact)),
        object,
        address,
        written,
        List.of(),
        null);
  }

  /**
   * Records what a call in a view links to: by the Feign client's name when its URL is the
   * load-balanced one, else where its URL leads ({@link Addresses}). A call to this machine or a
   * private address that no single unit listens on, or one whose host names nothing, is decided by
   * its path when only one unit serves it there, else left open with the units that serve it.
   */
  private void link(Unit caller, Call call, String view) {
    String host = call.url().host();
    if (call.service() != null
        && (host == null || host.equals(call.service().toLowerCase(Locale.ROOT)))) {
      List<Target> byName =
          addresses.byApplicationName(
              call.service(), view, "service-name", Addresses.BY_SERVICE_NAME);
      if (byName.size() > 1) {
        unresolved(caller, call, view, byName, "ambiguous");
        return;
      }
      if (byName.size() == 1) {
        linkTo(caller, call, view, byName.getFirst());
        return;
      }
      if (host == null) {
        byPath(caller, call, view, false);
        return;
      }
    }
    Answer answer = addresses.resolve(caller, view, call.address(), call.written());
    Call used =
        answer.filledBy() == null ? call : call.filled(answer.address(), answer.filledBy().fact());
    switch (answer.kind()) {
      case EXTERNAL -> linkExternal(caller, used, view, answer.external());
      case UNIT -> {
        Target target = answer.target();
        String path = used.url().path();
        Endpoint endpoint = path == null ? null : endpoint(target.unit(), used, view);
        // A unit on the default port, setting none, is taken only where it serves the path: other
        // servers on this machine (an identity provider, say) listen on 8080 too.
        if (answer.local() && target.fact() == null && endpoint == null) {
          byPath(caller, used, view, true);
        } else if (!target.unit().equals(caller)) {
          linkUnit(caller, used, view, target, endpoint);
        }
      }
      case AMBIGUOUS -> {
        if (answer.local()) {
          byPath(caller, used, view, true);
        } else {
          unresolved(caller, used, view, answer.targets(), "ambiguous");
        }
      }
      case UNKNOWN -> byPath(caller, used, view, answer.local());
    }
  }

  /** Links a call to a unit found by name, with the endpoint its path reaches. */
  private void linkTo(Unit caller, Call call, String view, Target target) {
    if (!target.unit().equals(caller)) {
      String path = call.url().path();
      linkUnit(
          caller, call, view, target, path == null ? null : endpoint(target.unit(), call, view));
    }
  }

  /**
   * Decides a call by its path: for this machine or a private address, linked when exactly one
   * other unit serves it; else left open with the units serving it as candidates.
   */
  private void byPath(Unit caller, Call call, String view, boolean local) {
    String path = call.url().path();
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
    if (local && byPath.size() == 1) {
      Target target = byPath.getFirst();
      linkUnit(caller, call, view, target, best.get(target.unit()));
    } else if (local) {
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
  }

  private void linkUnit(Unit caller, Call call, String view, Target target, Endpoint endpoint) {
    Fact fact = call.fact();
    ObjectNode attributes = common(call);
    Set<String> facts = new TreeSet<>();
    facts.add(fact.id());
    call.used().forEach(u -> facts.add(u.id()));
    if (target.fact() != null) {
      facts.add(target.fact().id());
    }
    double confidence = fact.confidence() * target.factor();
    String protocol = "https".equals(call.url().scheme()) ? "HTTPS" : "HTTP";
    if (endpoint != null) {
      Fact e = endpoint.fact();
      facts.add(e.id());
      attributes.put("endpoint", e.object().value());
      attributes.put("target", e.subject().toString());
      confidence = Math.min(confidence, e.confidence() * target.factor());
      String controller = Endpoints.of(e).controller();
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

  private void linkExternal(Unit caller, Call call, String view, EntityId system) {
    Fact fact = call.fact();
    ObjectNode attributes = common(call);
    attributes.put("protocol", "https".equals(call.url().scheme()) ? "HTTPS" : "HTTP");
    attributes.put("matchedBy", "external");
    Set<String> facts = new TreeSet<>();
    facts.add(fact.id());
    call.used().forEach(u -> facts.add(u.id()));
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
    String client = Claims.of(fact, HttpCallAttributes.class).client();
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
    String url =
        call.filled() != null
            ? call.filled()
            : call.httpMethod() == null ? text : text.substring(call.httpMethod().length() + 1);
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
      String method = Endpoints.of(endpoint.fact()).httpMethod();
      if (method != null && httpMethod != null && !method.equals(httpMethod)) {
        continue;
      }
      String template = Endpoints.of(endpoint.fact()).path();
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

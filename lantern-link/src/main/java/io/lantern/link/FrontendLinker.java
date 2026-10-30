package io.lantern.link;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.extract.frontend.FrontendExtractor;
import io.lantern.extract.websocket.WebSocketExtractor;
import io.lantern.link.Addresses.Answer;
import io.lantern.link.Deployments.Container;
import io.lantern.link.FoundLinks.Found;
import io.lantern.link.LinkIndex.Unit;
import io.lantern.link.LinkText.Url;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.node.ObjectNode;

/**
 * Links the URLs a frontend calls from the browser to the units that serve them, or to external
 * systems (ADR 0083). A path on the page's own origin goes where the nginx that serves the frontend
 * passes it on ({@code proxy_pass}), else where its development server does; an absolute URL goes
 * to its host, as {@link Addresses} resolves it: a unit (by its {@code spring.application.name}, a
 * deployment container that runs it, its artifact ID, or for this machine or a private address the
 * port a deployment container publishes, else its {@code server.port}), or an external system for a
 * public domain that names no unit. Each call is a {@code CALLS_HTTP} link from the frontend; a
 * proxy that no call goes through is one too.
 */
final class FrontendLinker {

  /** A target found for a URL: the unit or external system, the fact it was found by, if any. */
  private record Target(EntityId to, Fact fact, String matchedBy, double factor) {}

  /** Where a URL goes: its target, the URL as it reaches it, its protocol and the proxy used. */
  private record Reached(Target target, String url, String path, String protocol, Fact proxy) {}

  private final LinkIndex index;
  private final FoundLinks out;
  private final Addresses addresses;
  private final Deployments deployments;
  private final HttpLinker http;
  private final RouteLinker routes;

  FrontendLinker(
      LinkIndex index, FoundLinks out, Addresses addresses, HttpLinker http, RouteLinker routes) {
    this.index = index;
    this.out = out;
    this.addresses = addresses;
    this.deployments = addresses.deployments();
    this.http = http;
    this.routes = routes;
  }

  /**
   * Links the frontends' calls and proxies, and ties each frontend to the compose containers that
   * build it from its folder, which also name it.
   */
  void link(Origin origin, Validity validity) {
    deployed(origin, validity);
    Map<EntityId, List<Fact>> calls = bySubject(FrontendExtractor.CALL);
    Map<EntityId, List<Fact>> proxies = bySubject(FrontendExtractor.PROXY);
    Set<EntityId> frontends = new TreeSet<>(LinkIndex.BY_TEXT);
    index.of(FrontendExtractor.APP).forEach(f -> frontends.add(f.subject()));
    for (EntityId frontend : frontends) {
      List<Fact> served = new ArrayList<>(proxies.getOrDefault(frontend, List.of()));
      // nginx serves the built app, so its proxies are the ones a deployed page goes through;
      // the longest matching prefix wins, as nginx chooses.
      served.sort(
          Comparator.comparing(
                  (Fact p) -> !FrontendExtractor.NGINX.equals(LinkIndex.text(p, "source")))
              .thenComparing(p -> -p.object().value().length())
              .thenComparing(LinkIndex.BY_ID));
      for (String view : index.views()) {
        Set<String> used = new LinkedHashSet<>();
        Set<EntityId> reachedTargets = new LinkedHashSet<>();
        for (Fact call : calls.getOrDefault(frontend, List.of())) {
          Reached reached = reach(call, served, view);
          if (reached == null) {
            continue;
          }
          if (reached.proxy() != null) {
            used.add(reached.proxy().id());
          }
          reachedTargets.add(reached.target().to());
          emit(frontend, call, reached, view);
        }
        boolean nginx =
            served.stream()
                .anyMatch(p -> FrontendExtractor.NGINX.equals(LinkIndex.text(p, "source")));
        for (Fact proxy : served) {
          // A development server's proxy says where the deployed page goes only when no nginx
          // serves it.
          if (used.contains(proxy.id())
              || (nginx && !FrontendExtractor.NGINX.equals(LinkIndex.text(proxy, "source")))) {
            continue;
          }
          Reached reached = target(LinkIndex.text(proxy, "target"), view, proxy);
          if (reached != null && !reachedTargets.contains(reached.target().to())) {
            reachedTargets.add(reached.target().to());
            emit(frontend, proxy, reached, view);
          }
        }
      }
    }
  }

  /**
   * Each compose container whose build context is a frontend's folder runs it: a {@code
   * deployed-in} fact on the frontend, and its name as the frontend's {@code display-name} when
   * every such container has the same one, since that is the name it runs under.
   */
  private void deployed(Origin origin, Validity validity) {
    for (Fact app : index.of(FrontendExtractor.APP)) {
      String directory = LinkIndex.text(app, "directory");
      Set<String> names = new TreeSet<>();
      List<Container> building = new ArrayList<>();
      for (Container c : deployments.all()) {
        String build = LinkIndex.text(c.fact(), "build");
        if (build != null
            && "compose".equals(LinkIndex.text(c.fact(), "platform"))
            && c.fact().evidence().repo().equals(app.evidence().repo())
            && (directory == null ? "" : directory)
                .equals(folder(c.fact().evidence().path(), build))) {
          building.add(c);
          names.add(c.name());
        }
      }
      for (Container c : building) {
        out.add(Deployments.deployedIn(app.subject(), c, origin, validity));
      }
      if (names.size() == 1) {
        Container first = building.getFirst();
        out.add(
            Fact.create(
                app.subject(),
                DataLinker.DISPLAY_NAME,
                FactObject.string(names.iterator().next()),
                FactJson.newObject(),
                origin,
                first.fact().evidence(),
                first.fact().confidence(),
                validity));
      }
    }
  }

  /**
   * The folder a compose build context names, relative to the top of the repository: the context
   * (before a space and the Dockerfile) resolved against the compose file's folder; null for one
   * outside the repository or a URL.
   */
  static String folder(String composeFile, String build) {
    String context = build.contains(" ") ? build.substring(0, build.indexOf(' ')) : build;
    if (context.contains("://") || context.startsWith("/")) {
      return null;
    }
    List<String> parts = new ArrayList<>();
    int slash = composeFile.lastIndexOf('/');
    if (slash > 0) {
      parts.addAll(List.of(composeFile.substring(0, slash).split("/")));
    }
    for (String part : context.split("/")) {
      if (part.isEmpty() || part.equals(".")) {
        continue;
      }
      if (part.equals("..")) {
        if (parts.isEmpty()) {
          return null;
        }
        parts.removeLast();
      } else {
        parts.add(part);
      }
    }
    return String.join("/", parts);
  }

  private Map<EntityId, List<Fact>> bySubject(String predicate) {
    Map<EntityId, List<Fact>> result = new LinkedHashMap<>();
    for (Fact f : index.of(predicate)) {
      result.computeIfAbsent(f.subject(), s -> new ArrayList<>()).add(f);
    }
    return result;
  }

  /** Where a call goes in a view: its URL in the default build, else in another environment. */
  private Reached reach(Fact call, List<Fact> proxies, String view) {
    List<String> urls = new ArrayList<>();
    urls.add(LinkIndex.text(call, "url"));
    urls.addAll(LinkIndex.texts(call, "alternates"));
    for (String url : urls) {
      if (url == null) {
        continue;
      }
      Reached reached;
      if (url.startsWith("/")) {
        Fact proxy = proxyFor(url, proxies);
        if (proxy == null) {
          continue;
        }
        reached = target(through(url, proxy), view, proxy);
      } else {
        reached = target(url, view, null);
      }
      if (reached != null) {
        String protocol =
            FrontendExtractor.WEBSOCKET.equals(LinkIndex.text(call, "protocol"))
                ? FrontendExtractor.WEBSOCKET
                : reached.protocol();
        return new Reached(
            reached.target(), reached.url(), reached.path(), protocol, reached.proxy());
      }
    }
    return null;
  }

  /** The proxy a path of the page's origin goes through, or null when the page serves it. */
  private static Fact proxyFor(String path, List<Fact> proxies) {
    for (Fact proxy : proxies) {
      String prefix = proxy.object().value();
      String bare = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
      if (path.startsWith(prefix)
          || path.equals(bare)
          || (!prefix.endsWith("/") && path.startsWith(prefix + "/"))
          || prefix.equals("/")) {
        return proxy;
      }
    }
    return null;
  }

  /**
   * The URL a proxied path is passed on as: nginx replaces the location's prefix with the path of a
   * {@code proxy_pass} URL that has one, and passes the path whole otherwise; a development server
   * passes it whole.
   */
  static String through(String path, Fact proxy) {
    String target = LinkIndex.text(proxy, "target");
    Url url = LinkText.parse(target);
    String base = target;
    String rest = path;
    if (FrontendExtractor.NGINX.equals(LinkIndex.text(proxy, "source"))
        && url.path() != null
        && !url.path().isEmpty()) {
      base = target.substring(0, target.length() - url.path().length());
      String prefix = proxy.object().value();
      rest = url.path() + (path.length() > prefix.length() ? path.substring(prefix.length()) : "");
    }
    if (base.endsWith("/") && rest.startsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    return (base + rest).replaceAll("(?<!:)//+", "/");
  }

  /** Where an absolute URL goes in a view, or null when it names no unit and no system. */
  private Reached target(String url, String view, Fact proxy) {
    Url parsed = LinkText.parse(url);
    String host = parsed.host();
    String scheme = parsed.scheme();
    if (host == null || scheme == null || host.contains("{")) {
      return null;
    }
    String protocol =
        switch (scheme) {
          case "ws", "wss" -> FrontendExtractor.WEBSOCKET;
          case "https" -> "HTTPS";
          default -> FrontendExtractor.HTTP;
        };
    Answer answer = addresses.resolve(null, view, url, null);
    Target target =
        switch (answer.kind()) {
          case UNIT -> {
            Addresses.Target t = answer.target();
            yield new Target(t.unit().id(), t.fact(), t.matchedBy(), t.factor());
          }
          case EXTERNAL -> new Target(answer.external(), null, "external", 1.0);
          default -> null;
        };
    return target == null ? null : new Reached(target, url, parsed.path(), protocol, proxy);
  }

  private void emit(EntityId frontend, Fact from, Reached reached, String view) {
    Target target = reached.target();
    ObjectNode attributes = FactJson.newObject();
    String method = LinkIndex.text(from, "httpMethod");
    if (method != null) {
      attributes.put("httpMethod", method);
    }
    if (from.predicate().equals(FrontendExtractor.CALL)) {
      attributes.put("url", reached.url());
      if (reached.path() != null && !reached.path().isEmpty()) {
        attributes.put("path", reached.path());
      }
    } else {
      attributes.put("url", LinkIndex.text(from, "target"));
    }
    attributes.put(
        "protocol",
        target.to().type().equals(CrossRepoLinks.EXTERNAL_SYSTEM)
            ? reached.protocol()
            : reached.protocol().equals(FrontendExtractor.WEBSOCKET)
                ? FrontendExtractor.WEBSOCKET
                : "JSON/" + reached.protocol());
    attributes.put("matchedBy", target.matchedBy());
    if (LinkIndex.flag(from, "navigation")) {
      attributes.put("navigation", true);
    }
    if (reached.proxy() != null) {
      attributes.put("proxy", reached.proxy().object().value());
    }
    Set<String> facts = new TreeSet<>();
    facts.add(from.id());
    if (reached.proxy() != null) {
      facts.add(reached.proxy().id());
    }
    if (target.fact() != null) {
      facts.add(target.fact().id());
    }
    Unit unit = index.units.get(target.to());
    if (from.predicate().equals(FrontendExtractor.CALL)
        && unit != null
        && reached.path() != null
        && !reached.path().isEmpty()) {
      boolean socket = reached.protocol().equals(FrontendExtractor.WEBSOCKET);
      Entry entry = entry(unit, method, reached.path(), socket, view);
      if (entry != null) {
        attributes.put("reaches", entry.endpoint().subject().toString());
        attributes.put("reachesUnit", entry.unit().id().toString());
        attributes.put(
            "reachesEndpoint",
            socket ? LinkIndex.text(entry.endpoint(), "path") : entry.endpoint().object().value());
        facts.add(entry.endpoint().id());
        if (entry.route() != null) {
          attributes.put("throughRoute", entry.route().object().value());
          facts.add(entry.route().id());
        }
      }
    }
    out.add(
        view,
        new Found(
            CrossRepoLinks.CALLS_HTTP
                + "|"
                + frontend
                + "|"
                + target.to()
                + "|frontend|"
                + from.predicate()
                + "|"
                + from.object().value()
                + "|"
                + from.evidence().path(),
            frontend,
            CrossRepoLinks.CALLS_HTTP,
            FactObject.entity(target.to()),
            attributes,
            from.evidence(),
            CrossRepoLinks.round(from.confidence() * target.factor()),
            facts));
  }

  /**
   * The endpoint a request from the browser reaches: the unit's own endpoint (or WebSocket
   * endpoint) for the method and path, else, when the unit is a gateway, the endpoint of the unit
   * the route that takes the request sends it to, by the path as that unit sees it.
   *
   * @param route the route it goes through; null when the unit serves the request itself
   */
  private record Entry(Unit unit, Fact endpoint, Fact route) {}

  private Entry entry(Unit unit, String method, String path, boolean socket, String view) {
    Fact own = endpoint(unit, method, path, socket, view);
    if (own != null) {
      return new Entry(unit, own, null);
    }
    RouteLinker.Forward forward = routes.forward(unit, view, method, path);
    if (forward == null) {
      return null;
    }
    Unit to = forward.routed().target();
    Fact endpoint = endpoint(to, method, forward.path(), socket, view);
    return endpoint == null ? null : new Entry(to, endpoint, forward.routed().route());
  }

  /**
   * The unit's endpoint, or WebSocket endpoint, best matching the method and path; null if none.
   */
  private Fact endpoint(Unit unit, String method, String path, boolean socket, String view) {
    if (!socket) {
      List<Fact> endpoints = http.endpoints(unit, method, path, view);
      return endpoints.isEmpty() ? null : endpoints.getFirst();
    }
    String prefix = index.servletPrefix(unit, view);
    Fact best = null;
    int score = -1;
    for (Fact endpoint : index.of(WebSocketExtractor.ENDPOINT)) {
      String template = LinkIndex.text(endpoint, "path");
      if (template == null
          || !index.unitsOf(endpoint.subject()).contains(unit)
          || !index.active(endpoint.subject(), view)) {
        continue;
      }
      int match = LinkText.pathMatch(path, LinkText.join(prefix, template));
      if (match > score) {
        best = endpoint;
        score = match;
      }
    }
    return best;
  }

  /**
   * Whether a compose container publishes the port on the host: {@code 8080:8080}, {@code
   * 127.0.0.1:8080:80}; a container port alone is published on a random host port, so on none.
   */
  static boolean publishes(Fact container, String port) {
    if (!"compose".equals(LinkIndex.text(container, "platform"))) {
      return false;
    }
    for (String written : LinkIndex.texts(container, "ports")) {
      String[] parts = written.replaceAll("/(tcp|udp)$", "").split(":");
      if (parts.length == 1) {
        continue;
      }
      String published = parts[parts.length - 2];
      if (published.equals(port)) {
        return true;
      }
    }
    return false;
  }
}

package io.lantern.link;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.extract.config.CodeConfig;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.config.ConfigValue;
import io.lantern.extract.gateway.GatewayExtractor;
import io.lantern.extract.http.HttpClientExtractor;
import io.lantern.extract.messaging.MessagingExtractor;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The first link step: the values extractors keep as written, resolved in each view against the
 * config of the module they name ({@link CodeValues}), as the facts each extractor recorded before
 * it kept them as written (ADR 0094). So every later step reads the facts it read before, one kind
 * at a time as the slices of #519 land:
 *
 * <ul>
 *   <li>a {@code request} sent by code ({@code SENT_BY}) gives the code's {@code http-call} facts,
 *       one for the default view and one for each profile whose URL differs (#557);
 *   <li>a gateway route built in code ({@code gateway-route-written}) gives its {@code
 *       gateway-route} facts, and each module's config gives the routes, Zuul routes and discovery
 *       locator it defines, through the gateway extractor's own reading (#558), and the datasources
 *       and data stores it connects to, with their entities and addresses, through the persistence
 *       extractor's (#560);
 *   <li>a send, listener, binding or declaration written in code ({@code SENDS_TO}, {@code
 *       LISTENS_ON}, {@code BINDS}, {@code DECLARES}) gives its {@code message-send}, {@code
 *       message-listener}, {@code message-binding} or {@code message-destination} facts, and each
 *       module's Spring Cloud Stream functions their bindings', through the messaging extractor's
 *       own resolving, and a STOMP send written in code its {@code stomp-send} fact (#559).
 * </ul>
 *
 * <p>The value resolution rule of #522 replaces this step with {@code RESOLVES_TO} claims.
 */
public final class ValueViews implements LinkStep {

  /** The step's name, recorded as the origin of its facts. */
  public static final String NAME = "values";

  /** The step's version. */
  public static final String VERSION = "1";

  private static final Origin ORIGIN = new Origin(Origin.Kind.STATIC, NAME, VERSION);

  private static final Pattern ABSOLUTE = Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*://)([^/]*)");

  private static final String FEIGN = "feign";

  @Override
  public Origin origin() {
    return ORIGIN;
  }

  @Override
  public List<Fact> derive(List<Fact> facts, long snapshot) {
    Objects.requireNonNull(facts, "facts");
    CodeValues values = CodeValues.of(facts);
    Validity validity = Validity.from(snapshot);
    Map<String, Fact> derived = new LinkedHashMap<>();
    for (Fact route : gatewayRoutes(facts, values, snapshot)) {
      derived.putIfAbsent(route.id(), route);
    }
    java.util.Set<String> known =
        ConfigExtractor.normalizedKeys(ConfigExtractor.jwtSecretKeys(facts));
    for (Fact message :
        MessagingExtractor.resolve(
            facts,
            (module, repo) -> lookup(values.forModule(module, repo), known),
            ORIGIN,
            snapshot)) {
      derived.putIfAbsent(message.id(), message);
    }
    for (Fact send :
        io.lantern.extract.websocket.WebSocketExtractor.sends(facts, ORIGIN, snapshot)) {
      derived.putIfAbsent(send.id(), send);
    }
    for (Fact f : facts) {
      if (f.predicate().equals(HttpClientExtractor.SENT_BY)
          && f.subject().type().equals(HttpClientExtractor.REQUEST)
          && f.object().kind() == FactObject.Kind.ENTITY) {
        for (Fact call : calls(f, values, validity)) {
          derived.putIfAbsent(call.id(), call);
        }
      }
    }
    return derived.values().stream()
        .sorted(
            Comparator.comparing((Fact f) -> f.subject().toString())
                .thenComparing(Fact::predicate)
                .thenComparing(f -> f.object().value())
                .thenComparing(Fact::id))
        .toList();
  }

  // ---------------------------------------------------------------------------------------------
  // Gateway routes

  /** A key that names a secret, standing for the secret keys a masked config value used. */
  private static final String MASKED = "lantern.masked.secret";

  private static List<Fact> gatewayRoutes(List<Fact> facts, CodeValues values, long snapshot) {
    java.util.Set<String> secretKeys = ConfigExtractor.jwtSecretKeys(facts);
    Map<String, String> blobs = new java.util.HashMap<>();
    List<io.lantern.extract.config.ModuleValues> configs = configs(values, blobs);
    List<Fact> routes =
        GatewayExtractor.routes(
            facts,
            (module, repo) -> resolver(values.forModule(module, repo)),
            configs,
            secretKeys,
            ORIGIN,
            snapshot);
    List<Fact> datasources =
        io.lantern.extract.persistence.PersistenceExtractor.datasources(
            configs, secretKeys, ORIGIN, snapshot);
    List<Fact> all = new java.util.ArrayList<>(routes);
    all.addAll(datasources);
    all.addAll(
        io.lantern.extract.persistence.PersistenceExtractor.tables(
            facts, configs, ORIGIN, snapshot));
    all.addAll(
        io.lantern.extract.redis.RedisExtractor.fromConfig(facts, configs, ORIGIN, snapshot));
    return all.stream().map(f -> withBlobs(f, blobs)).toList();
  }

  /**
   * Each module's config as the readings of config in linking take it, from the config facts; the
   * blobs of the files read are put in {@code blobs}, by repository and path.
   */
  private static List<io.lantern.extract.config.ModuleValues> configs(
      CodeValues values, Map<String, String> blobs) {
    List<io.lantern.extract.config.ModuleValues> configs = new java.util.ArrayList<>();
    for (EntityId module : values.modules()) {
      String repo = values.repoOf(module);
      CodeValues.View view = values.forModule(module, repo);
      configs.add(
          new io.lantern.extract.config.ModuleValues() {
            // The blobs of the config files, which the reading's evidence leaves out.
            @Override
            public EntityId module() {
              return module;
            }

            @Override
            public String repo() {
              return repo;
            }

            @Override
            public List<String> profiles() {
              return view.views().subList(1, view.views().size());
            }

            private final Map<String, java.util.SortedMap<String, ConfigValue>> read =
                new java.util.HashMap<>();

            @Override
            public java.util.SortedMap<String, ConfigValue> values(String profile) {
              return read.computeIfAbsent(
                  profile,
                  p -> {
                    java.util.SortedMap<String, ConfigValue> values = new java.util.TreeMap<>();
                    view.facts(p)
                        .forEach(
                            (key, f) -> {
                              values.put(key, configValue(key, f));
                              Evidence.Location at = f.evidence().declared();
                              if (at.blob() != null) {
                                blobs.put(at.repo() + ":" + at.path(), at.blob());
                              }
                            });
                    return java.util.Collections.unmodifiableSortedMap(values);
                  });
            }
          });
    }
    return configs;
  }

  /** A config fact as the gateway's reading takes a config value. */
  private static ConfigValue configValue(String key, Fact f) {
    JsonNode a = f.attributes();
    SortedSet<String> unresolved = new TreeSet<>();
    if (a.has("unresolved")) {
      a.get("unresolved").forEach(k -> unresolved.add(k.stringValue()));
    }
    String written = text(a, "written");
    String value = f.object().value();
    SortedSet<String> used = new TreeSet<>();
    if (value.equals(ConfigExtractor.MASK)) {
      // Masked whole for a secret: the reading masks what uses it by the keys it was resolved
      // from, which the facts do not keep, so a key that names a secret stands for them, however
      // many values deep the secret was.
      used.add(MASKED);
      if (written != null) {
        value = written;
      }
    }
    Evidence.Location at = f.evidence().declared();
    // A stored fact names its file by blob, which withBlobs puts back; only one read from the
    // extractors directly, in tests, has a content hash.
    String file = at.contentHash();
    return new ConfigValue(
        key,
        value,
        written != null ? written : value,
        unresolved,
        used,
        new ConfigValue.Where(
            at.path(), file == null ? "" : file, at.lines() == null ? 0 : at.lines().start()));
  }

  /** A fact whose evidence names its files by blob, as the config facts it was read from did. */
  private static Fact withBlobs(Fact f, Map<String, String> blobs) {
    boolean missing =
        f.evidence().locations().stream()
            .anyMatch(
                l ->
                    l.path() != null
                        && l.blob() == null
                        && l.contentHash() == null
                        && blobs.containsKey(l.repo() + ":" + l.path()));
    if (!missing) {
      return f;
    }
    Evidence evidence =
        f.evidence()
            .map(
                l -> {
                  String blob = blobs.get(l.repo() + ":" + l.path());
                  return l.path() != null
                          && l.blob() == null
                          && l.contentHash() == null
                          && blob != null
                      ? l.withBlob(blob)
                      : l;
                });
    return Fact.create(
        f.subject(),
        f.predicate(),
        f.object(),
        f.attributes(),
        f.origin(),
        evidence,
        f.confidence(),
        f.validity());
  }

  /** A module's config as the messaging extractor resolves its values, from the config facts. */
  private static io.lantern.extract.config.ValueLookup lookup(
      CodeValues.View view, java.util.Set<String> known) {
    Map<String, java.util.SortedMap<String, ConfigValue>> read = new java.util.HashMap<>();
    return new io.lantern.extract.config.ValueLookup() {
      @Override
      public List<String> views() {
        return view.views();
      }

      @Override
      public java.util.SortedMap<String, ConfigValue> values(String profile) {
        return read.computeIfAbsent(
            profile,
            k -> {
              java.util.SortedMap<String, ConfigValue> values = new java.util.TreeMap<>();
              view.facts(profile).forEach((key, f) -> values.put(key, configValue(key, f)));
              return java.util.Collections.unmodifiableSortedMap(values);
            });
      }

      @Override
      public CodeConfig.CodeValue value(String profile, String written) {
        CodeValues.Value v = view.value(profile, written);
        return new CodeConfig.CodeValue(v.value(), v.written(), v.unresolved(), v.masked());
      }

      @Override
      public CodeConfig.CodeValue value(ConfigValue v) {
        return CodeConfig.CodeValue.of(v.value(), v.written(), v.unresolved(), v.used(), known);
      }

      @Override
      public boolean isSecret(String key) {
        return ConfigExtractor.isSecret(key, known);
      }
    };
  }

  /** A module's config as the gateway's reading resolves a route built in code. */
  private static GatewayExtractor.Resolver resolver(CodeValues.View view) {
    return new GatewayExtractor.Resolver() {
      @Override
      public List<String> views() {
        return view.views();
      }

      @Override
      public CodeConfig.CodeValue value(String profile, String written) {
        CodeValues.Value v = view.value(profile, written);
        return new CodeConfig.CodeValue(v.value(), v.written(), v.unresolved(), v.masked());
      }
    };
  }

  // ---------------------------------------------------------------------------------------------
  // HTTP requests

  /** A request's URL and service name resolved in one view, as they may be recorded. */
  private record Recorded(
      String url,
      String written,
      String service,
      SortedSet<String> unresolved,
      boolean masked,
      boolean loadBalanced) {}

  /**
   * The {@code http-call} facts of one request: the default view's and each differing profile's.
   */
  private static List<Fact> calls(Fact sent, CodeValues values, Validity validity) {
    JsonNode a = sent.attributes();
    String client = text(a, "client");
    String written = text(a, "url");
    String service = text(a, "service");
    String method = text(a, "httpMethod");
    boolean loadBalanced = a.path("loadBalanced").asBoolean(false);
    CodeValues.View config =
        values.forModule(EntityId.parse(text(a, "configModule")), text(a, "configRepo"));
    Recorded byDefault = recorded(config, null, client, written, service, loadBalanced);
    List<Fact> calls = new java.util.ArrayList<>();
    for (String profile : config.views()) {
      Recorded value =
          profile == null
              ? byDefault
              : recorded(config, profile, client, written, service, loadBalanced);
      if (profile != null && value.equals(byDefault)) {
        continue;
      }
      ObjectNode attributes = FactJson.newObject();
      if (profile != null) {
        attributes.put("profile", profile);
      }
      if (method != null) {
        attributes.put("httpMethod", method);
      }
      attributes.put("client", client);
      copy(a, attributes, "clientType");
      copy(a, attributes, "annotation");
      String object;
      if (value.masked()) {
        attributes.put("masked", true);
        object = value.written();
      } else {
        HttpClientExtractor.Split split = HttpClientExtractor.split(value.url());
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
        if (value.loadBalanced()) {
          attributes.put("loadBalanced", true);
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
      if (a.has("expressions")) {
        attributes.set("expressions", a.get("expressions").deepCopy());
      }
      calls.add(
          Fact.create(
              sent.object().entityId(),
              HttpClientExtractor.HTTP_CALL,
              FactObject.string(method == null ? object : method + " " + object),
              attributes,
              ORIGIN,
              sent.evidence(),
              sent.confidence(),
              validity));
    }
    return calls;
  }

  private static Recorded recorded(
      CodeValues.View config,
      String profile,
      String client,
      String written,
      String service,
      boolean callLoadBalanced) {
    CodeValues.Value url = config.value(profile, written);
    CodeValues.Value name = service == null ? null : config.value(profile, service);
    if (url.masked() || (name != null && name.masked())) {
      return new Recorded(written, written, null, new TreeSet<>(), true, false);
    }
    String resolved = HttpClientExtractor.clean(url.value());
    if (client.equals(FEIGN)) {
      // Spring Cloud OpenFeign adds http:// to a url without a scheme, once resolved.
      resolved = HttpClientExtractor.withScheme(resolved);
    }
    SortedSet<String> unresolved = new TreeSet<>(url.unresolved());
    String resolvedService = null;
    if (name != null) {
      unresolved.addAll(name.unresolved());
      resolvedService = name.value();
    }
    // A load-balanced client's host, and an lb:// URL's, is a service name, not a DNS name.
    boolean loadBalanced = callLoadBalanced || resolved.startsWith("lb://");
    if (resolvedService == null && loadBalanced) {
      resolvedService = host(resolved);
    }
    return new Recorded(resolved, written, resolvedService, unresolved, false, loadBalanced);
  }

  /** The host of an absolute URL without placeholders in it, or null. */
  private static String host(String url) {
    Matcher m = ABSOLUTE.matcher(url);
    if (!m.find()) {
      return null;
    }
    String host = m.group(2);
    int at = host.lastIndexOf('@');
    host = at < 0 ? host : host.substring(at + 1);
    int colon = host.indexOf(':');
    host = colon < 0 ? host : host.substring(0, colon);
    return host.isEmpty() || host.startsWith("[") || host.contains("{") || host.contains("$")
        ? null
        : host;
  }

  private static void copy(JsonNode from, ObjectNode to, String name) {
    if (from.has(name)) {
      to.set(name, from.get(name).deepCopy());
    }
  }

  private static String text(JsonNode node, String name) {
    JsonNode value = node.get(name);
    return value == null || value.isNull() ? null : value.stringValue();
  }
}

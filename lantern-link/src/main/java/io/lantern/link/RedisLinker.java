package io.lantern.link;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.redis.RedisExtractor;
import io.lantern.link.FoundLinks.Found;
import io.lantern.link.LinkIndex.Unit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Links units to the Redis servers their config connects them to (ADR 0073): one data store per
 * server, shared by the units that use it, with the protocol RESP, the technology the deployments'
 * containers run when known, and what the units' code keeps in it where it writes the keys out. A
 * unit with Redis code or a Redis client dependency but no connection config uses Spring's default
 * server.
 */
final class RedisLinker {

  /** The default Redis server, as Spring Boot's. */
  private static final String DEFAULT_SERVER = "localhost:6379";

  private static final String DEFAULT_PORT = "6379";

  /** The config prefixes that hold a connection: Spring Boot 3 and later, and before. */
  private static final List<String> PREFIXES = List.of("spring.data.redis.", "spring.redis.");

  private static final int MAX_STORES = 40;

  /** The products a Redis client connects to. */
  private static final Set<String> PRODUCTS = Set.of("Redis", "Valkey", "Redis Stack");

  private final LinkIndex index;
  private final FoundLinks out;

  /** The units' Redis uses, by unit, in ID order. */
  private final Map<Unit, List<Fact>> uses = new HashMap<>();

  /** The containers the deployments run Redis (or Valkey, Redis Stack) in, in ID order. */
  private final List<Deployments.Container> images;

  /** The containers each store's technology came from. */
  private final Map<EntityId, Set<Deployments.Container>> containers =
      new TreeMap<>(LinkIndex.BY_TEXT);

  RedisLinker(LinkIndex index, FoundLinks out, Deployments deployments) {
    this.index = index;
    this.out = out;
    for (Fact f : index.of(RedisExtractor.REDIS_USE)) {
      for (Unit unit : index.unitsOf(f.subject())) {
        uses.computeIfAbsent(unit, u -> new ArrayList<>()).add(f);
      }
    }
    this.images =
        deployments.all().stream()
            .filter(c -> c.product() != null && PRODUCTS.contains(c.product()))
            .toList();
  }

  /** A server a unit connects to in a view. */
  private record Connection(
      String server,
      String key,
      Fact evidence,
      List<Fact> facts,
      boolean ssl,
      boolean defaulted,
      boolean unresolved,
      String inferredFrom) {}

  void link(Origin origin, Validity validity) {
    Map<EntityId, Fact> technologies = new TreeMap<>(LinkIndex.BY_TEXT);
    Map<EntityId, String> servers = new TreeMap<>(LinkIndex.BY_TEXT);
    Map<String, Set<EntityId>> perView = new TreeMap<>();
    for (String view : index.views()) {
      Map<Unit, Connection> configured = new LinkedHashMap<>();
      for (Unit unit : index.units.values()) {
        Connection connection = connection(unit, view);
        if (connection != null) {
          configured.put(unit, connection);
        }
      }
      Set<EntityId> inView =
          perView.computeIfAbsent(view == null ? "" : view, v -> new TreeSet<>(LinkIndex.BY_TEXT));
      for (Unit unit : index.units.values()) {
        EntityId database = link(unit, view, configured, technologies, servers);
        if (database != null) {
          inView.add(database);
        }
      }
    }
    technologies.forEach(
        (database, source) ->
            out.add(
                Fact.create(
                    database,
                    DeployableUnits.TECHNOLOGY,
                    FactObject.string(technology(source)),
                    FactJson.newObject(),
                    origin,
                    source.evidence(),
                    source.confidence(),
                    validity)));
    containers.forEach(
        (database, used) ->
            used.forEach(c -> out.add(Deployments.deployedIn(database, c, origin, validity))));
    // A store alone in every view it is in is just Redis; with others in a view, each is told
    // apart by its server.
    servers.forEach(
        (database, server) -> {
          Fact source = technologies.get(database);
          String product = technology(source).replaceFirst(" \\d.*$", "");
          boolean several =
              perView.values().stream().anyMatch(v -> v.contains(database) && v.size() > 1);
          out.add(
              Fact.create(
                  database,
                  DataLinker.DISPLAY_NAME,
                  FactObject.string(several ? product + " (" + server + ")" : product),
                  FactJson.newObject(),
                  origin,
                  source.evidence(),
                  source.confidence(),
                  validity));
        });
  }

  private static String technology(Fact source) {
    return source.object().value();
  }

  /**
   * Links a unit in a view and returns the store, or null if it uses none or cannot be placed.
   *
   * @param configured the units whose config names a server in the view, with it
   */
  private EntityId link(
      Unit unit,
      String view,
      Map<Unit, Connection> configured,
      Map<EntityId, Fact> technologies,
      Map<EntityId, String> servers) {
    List<Fact> used = new ArrayList<>();
    for (Fact f : uses.getOrDefault(unit, List.of())) {
      if (index.active(f.subject(), view)) {
        used.add(f);
      }
    }
    Connection connection = configured.get(unit);
    if (connection == null && used.isEmpty()) {
      return null;
    }
    if (connection == null) {
      // Redis code or a client with no connection written: the snapshot's only configured
      // server, else (none is configured) Spring Boot's default one; with several it is left open.
      Map<String, Connection> known = new TreeMap<>();
      configured.values().forEach(c -> known.putIfAbsent(c.server(), c));
      if (known.size() > 1) {
        return null;
      }
      if (known.isEmpty()) {
        connection =
            new Connection(
                DEFAULT_SERVER,
                "default",
                used.getFirst(),
                List.of(),
                false,
                true,
                false,
                "No config names the Redis server; Spring Boot's default, localhost:6379, is taken.");
      } else {
        Connection only = known.values().iterator().next();
        connection =
            new Connection(
                only.server(),
                "inferred",
                used.getFirst(),
                only.facts(),
                only.ssl(),
                false,
                only.unresolved(),
                "No config of this unit names the Redis server; the only one the snapshot's"
                    + " config names is taken.");
      }
    }
    EntityId database =
        new EntityId(CrossRepoLinks.DATABASE, connection.server().replace(' ', '_') + "/redis");
    servers.putIfAbsent(database, connection.server());

    List<Deployments.Container> imagesUsed = images(unit);
    String technology = technology(imagesUsed);
    containers.computeIfAbsent(database, d -> new LinkedHashSet<>()).addAll(imagesUsed);
    ObjectNode attributes = FactJson.newObject();
    attributes.put("protocol", "RESP");
    attributes.put("technology", technology);
    attributes.put("server", connection.server());
    attributes.put("key", connection.key() == null ? "default" : connection.key());
    if (connection.ssl()) {
      attributes.put("ssl", true);
    }
    if (connection.defaulted()) {
      attributes.put("defaulted", true);
    }
    if (connection.inferredFrom() != null) {
      attributes.put("inferred", connection.inferredFrom());
    } else if (connection.unresolved()) {
      attributes.put(
          "inferred",
          "The host or port is a placeholder no config resolves; units that write the same"
              + " placeholders are taken to share one server.");
    }
    Set<String> kinds = new TreeSet<>();
    used.forEach(f -> kinds.add(f.object().value()));
    if (!kinds.isEmpty()) {
      ArrayNode array = attributes.putArray("uses");
      kinds.forEach(array::add);
    }
    ArrayNode stores = stores(used);
    if (!stores.isEmpty()) {
      attributes.set("stores", stores);
    }
    Set<String> ids = new TreeSet<>();
    connection.facts().forEach(f -> ids.add(f.id()));
    used.forEach(f -> ids.add(f.id()));
    imagesUsed.forEach(c -> ids.add(c.fact().id()));

    double confidence = connection.evidence().confidence() * unit.fact().confidence();
    if (connection.inferredFrom() != null) {
      confidence *= 0.6;
    } else if (connection.unresolved()) {
      confidence *= 0.8;
    }
    out.add(
        view,
        new Found(
            CrossRepoLinks.USES_DATABASE + "|" + unit.id() + "|" + database,
            unit.id(),
            CrossRepoLinks.USES_DATABASE,
            FactObject.entity(database),
            attributes,
            connection.evidence().evidence(),
            CrossRepoLinks.round(confidence),
            ids));
    Fact source = imagesUsed.isEmpty() ? connection.evidence() : imagesUsed.getFirst().fact();
    technologies.merge(
        database,
        technologyFact(source, technology),
        (a, b) -> LinkIndex.BY_ID.compare(a, b) <= 0 ? a : b);
    return database;
  }

  /** The source of a data store's technology: the fact whose evidence it cites. */
  private static Fact technologyFact(Fact source, String technology) {
    // The technology is carried as the object of a copy, so the merge keeps one per store.
    return Fact.create(
        source.subject(),
        source.predicate(),
        FactObject.string(technology),
        source.attributes(),
        source.origin(),
        source.evidence(),
        source.confidence(),
        source.validity());
  }

  // ---------------------------------------------------------------------------------------------
  // Technology

  /**
   * The Redis containers that run next to a unit: those its own repository's descriptors run, else
   * those of any repository in the snapshot.
   */
  private List<Deployments.Container> images(Unit unit) {
    String repo = unit.fact().evidence().repo();
    List<Deployments.Container> own =
        images.stream().filter(c -> c.fact().evidence().repo().equals(repo)).toList();
    return own.isEmpty() ? images : own;
  }

  /**
   * The technology the containers give: their product with its versions when they name one product
   * (ADR 0082), else Redis.
   */
  private static String technology(List<Deployments.Container> images) {
    Set<String> products = new TreeSet<>();
    images.forEach(c -> products.add(c.product()));
    return products.size() == 1
        ? Deployments.technology(products.iterator().next(), images)
        : "Redis";
  }

  // ---------------------------------------------------------------------------------------------
  // What is stored

  /** What the units' code keeps in Redis, one entry per kind of use and key. */
  private static ArrayNode stores(List<Fact> used) {
    Map<String, ObjectNode> byKey = new TreeMap<>();
    Map<String, Set<String>> operations = new HashMap<>();
    for (Fact f : used) {
      String kind = f.object().value();
      if (kind.equals(RedisExtractor.CLIENT)) {
        continue;
      }
      String name = firstText(f, "key", "channel", "hash", "namespace");
      String id = kind + "|" + (name == null ? "" : name);
      ObjectNode entry =
          byKey.computeIfAbsent(
              id,
              k -> {
                ObjectNode node = FactJson.newObject();
                node.put("kind", kind);
                return node;
              });
      for (String attribute : List.of("key", "channel", "hash", "namespace", "structure", "ttl")) {
        String value = LinkIndex.text(f, attribute);
        if (value != null && !entry.has(attribute)) {
          entry.put(attribute, value);
        }
      }
      String operation = LinkIndex.text(f, "operation");
      if (operation != null) {
        operations.computeIfAbsent(id, k -> new TreeSet<>()).add(operation);
      }
    }
    ArrayNode array = FactJson.newObject().putArray("stores");
    int count = 0;
    for (Map.Entry<String, ObjectNode> e : byKey.entrySet()) {
      if (count++ == MAX_STORES) {
        break;
      }
      Set<String> ops = operations.get(e.getKey());
      if (ops != null) {
        ArrayNode list = e.getValue().putArray("operations");
        ops.forEach(list::add);
      }
      array.add(e.getValue());
    }
    return array;
  }

  private static String firstText(Fact f, String... attributes) {
    for (String attribute : attributes) {
      String value = LinkIndex.text(f, attribute);
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  // ---------------------------------------------------------------------------------------------
  // The connection

  /** The server a unit's config names in a view, or null if it names none. */
  private Connection connection(Unit unit, String view) {
    Fact url = setting(unit, view, "url");
    List<Fact> nodes = settings(unit, view, "cluster.nodes");
    List<Fact> sentinelNodes = settings(unit, view, "sentinel.nodes");
    Fact host = setting(unit, view, "host");
    Fact port = setting(unit, view, "port");
    Fact ssl = setting(unit, view, "ssl.enabled");
    boolean secure = ssl != null && "true".equalsIgnoreCase(ssl.object().value().strip());
    List<Fact> facts = new ArrayList<>();
    String server;
    String key;
    Fact evidence;
    if (url != null) {
      String text = url.object().value();
      secure |= text.toLowerCase(Locale.ROOT).startsWith("rediss://");
      server = withPort(address(text));
      key = LinkIndex.text(url, "key");
      evidence = url;
      facts.add(url);
    } else if (!nodes.isEmpty() || !sentinelNodes.isEmpty()) {
      boolean sentinel = nodes.isEmpty();
      List<Fact> list = sentinel ? sentinelNodes : nodes;
      Set<String> addresses = new TreeSet<>();
      for (Fact f : list) {
        for (String part : f.object().value().split(",")) {
          String address = address(part);
          if (!address.isEmpty()) {
            addresses.add(withPort(address));
          }
        }
      }
      if (addresses.isEmpty()) {
        return null;
      }
      Fact master = sentinel ? setting(unit, view, "sentinel.master") : null;
      server =
          (sentinel ? "sentinel:" + (master == null ? "" : master.object().value()) + "@" : "")
              + String.join(",", addresses);
      key = LinkIndex.text(list.getFirst(), "key");
      evidence = list.getFirst();
      facts.addAll(list);
      if (master != null) {
        facts.add(master);
      }
    } else if (host != null || port != null) {
      String name = host == null ? "localhost" : address(host.object().value());
      String number = port == null ? DEFAULT_PORT : port.object().value().strip();
      server =
          (name.contains(":") ? name.substring(0, name.lastIndexOf(':')) : name) + ":" + number;
      Fact main = host != null ? host : port;
      key = LinkIndex.text(main, "key");
      evidence = main;
      if (host != null) {
        facts.add(host);
      }
      if (port != null) {
        facts.add(port);
      }
    } else {
      return null;
    }
    if (ssl != null) {
      facts.add(ssl);
    }
    boolean unresolved = server.contains("${");
    for (Fact f : facts) {
      unresolved |= !LinkIndex.texts(f, "unresolved").isEmpty();
    }
    return new Connection(
        unresolved ? server : server.toLowerCase(Locale.ROOT),
        key,
        evidence,
        facts,
        secure,
        false,
        unresolved,
        null);
  }

  /** The host[:port] of an address; a placeholder is kept as written, case included. */
  private static String address(String text) {
    return text.contains("${") ? text.strip() : DataLinker.address(text);
  }

  private static String withPort(String address) {
    return address.isEmpty() || address.contains(":") ? address : address + ":" + DEFAULT_PORT;
  }

  /** The config fact a unit sees in a view for a Redis setting, unless masked or blank. */
  private Fact setting(Unit unit, String view, String suffix) {
    List<Fact> all = settings(unit, view, suffix);
    return all.isEmpty() ? null : all.getFirst();
  }

  /**
   * The config facts a unit sees in a view for a Redis setting, or for the items of a list under it
   * ({@code key[0]}), under Spring Boot's prefix or the older one, keys compared as relaxed binding
   * does; masked and blank values are left out. A value with a placeholder nothing resolves is
   * kept, as written.
   */
  private List<Fact> settings(Unit unit, String view, String suffix) {
    for (String prefix : PREFIXES) {
      String wanted = relaxed(prefix + suffix);
      Map<String, Fact> found = new LinkedHashMap<>();
      for (String k : index.configKeys(unit, ConfigExtractor.CONFIG_VALUE)) {
        if (!relaxed(k.replaceAll("\\[\\d+]$", "")).equals(wanted)) {
          continue;
        }
        Fact f = index.config(unit, view, ConfigExtractor.CONFIG_VALUE, k);
        if (f != null && !LinkIndex.flag(f, "masked") && !f.object().value().isBlank()) {
          found.put(k, f);
        }
      }
      if (!found.isEmpty()) {
        return new ArrayList<>(new LinkedHashSet<>(found.values()));
      }
    }
    return List.of();
  }

  private static String relaxed(String key) {
    return key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
  }
}

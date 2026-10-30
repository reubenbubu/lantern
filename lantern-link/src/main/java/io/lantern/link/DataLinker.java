package io.lantern.link;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.persistence.PersistenceExtractor;
import io.lantern.extract.sql.SqlExtractor;
import io.lantern.link.FoundLinks.Found;
import io.lantern.link.LinkIndex.Unit;
import java.util.ArrayList;
import java.util.Comparator;
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
 * Links units to what their config connects them to (ADR 0042): databases by their datasources'
 * server and database, so two units on one database share it; the tables a unit's code reads or
 * writes in another unit's schema on a shared database; message brokers; and, as infrastructure
 * rather than business dependencies, service discovery, config servers and tracing.
 */
final class DataLinker {

  /** Attribute of a database entity: the name its views show; the entity key when absent. */
  static final String DISPLAY_NAME = "display-name";

  /** A display name with the datasource it comes from. */
  private record Named(Fact datasource, String name) {}

  /** A data store's technology with the fact it comes from. */
  private record Technology(Fact source, String technology) {}

  /** A setting naming an infrastructure service: its key, role and technology. */
  private record Setting(String key, String role, String technology) {}

  private static final List<Setting> INFRASTRUCTURE =
      List.of(
          new Setting("eureka.client.service-url.defaultzone", "service-discovery", "Eureka"),
          new Setting("eureka.client.serviceurl.defaultzone", "service-discovery", "Eureka"),
          new Setting("spring.cloud.consul.host", "service-discovery", "Consul"),
          new Setting("spring.cloud.zookeeper.connect-string", "service-discovery", "ZooKeeper"),
          new Setting("spring.cloud.config.uri", "config-server", "Spring Cloud Config"),
          new Setting("spring.config.import", "config-server", "Spring Cloud Config"),
          new Setting("management.zipkin.tracing.endpoint", "tracing", "Zipkin"),
          new Setting("spring.zipkin.base-url", "tracing", "Zipkin"),
          new Setting("management.otlp.tracing.endpoint", "tracing", "OpenTelemetry"));

  private static final List<Setting> BROKERS =
      List.of(
          new Setting("spring.kafka.bootstrap-servers", "Kafka", "Kafka"),
          new Setting("spring.kafka.producer.bootstrap-servers", "Kafka", "Kafka"),
          new Setting("spring.kafka.consumer.bootstrap-servers", "Kafka", "Kafka"),
          new Setting("spring.cloud.stream.kafka.binder.brokers", "Kafka", "Kafka"),
          new Setting("spring.rabbitmq.addresses", "AMQP", "RabbitMQ"),
          new Setting("spring.rabbitmq.host", "AMQP", "RabbitMQ"),
          new Setting("spring.artemis.broker-url", "JMS", "ActiveMQ Artemis"),
          new Setting("spring.activemq.broker-url", "JMS", "ActiveMQ"));

  private static final String RABBIT_PORT = "spring.rabbitmq.port";
  private static final String CONFIG_SERVER = "configserver:";

  /** The datasource keys of the connection a unit's code uses, first that is set wins. */
  private static final List<String> CODE_KEYS =
      List.of(
          "spring.datasource.url",
          "spring.datasource.jdbc-url",
          "spring.datasource.hikari.jdbc-url",
          "spring.r2dbc.url");

  /** The datasource keys of the connection a unit's migrations run on, first that is set wins. */
  private static final List<String> MIGRATION_KEYS =
      List.of(
          "spring.flyway.url",
          "spring.liquibase.url",
          "spring.datasource.url",
          "spring.datasource.jdbc-url",
          "spring.datasource.hikari.jdbc-url",
          "spring.r2dbc.url");

  /** A table a unit's schema declares. */
  private record Owned(EntityId table, Unit unit, Fact fact) {}

  private final LinkIndex index;
  private final FoundLinks out;
  private final Deployments deployments;

  /** The schemas each module holds. */
  private final Map<EntityId, List<EntityId>> schemas = new HashMap<>();

  /** The tables the schemas declare, by name. */
  private final Map<String, List<Owned>> owned = new TreeMap<>();

  DataLinker(LinkIndex index, FoundLinks out, Deployments deployments) {
    this.index = index;
    this.out = out;
    this.deployments = deployments;
    for (Fact f : index.of(BuiltInTypes.CONTAINS)) {
      if (f.object().kind() != FactObject.Kind.ENTITY) {
        continue;
      }
      EntityId object = f.object().entityId();
      if (f.subject().type().equals(BuiltInTypes.MODULE)
          && object.type().equals(SqlExtractor.SCHEMA)) {
        schemas.computeIfAbsent(f.subject(), m -> new ArrayList<>()).add(object);
      } else if (f.subject().type().equals(SqlExtractor.SCHEMA)
          && object.type().equals(SqlExtractor.TABLE)) {
        for (Unit unit : index.unitsOf(object)) {
          owned
              .computeIfAbsent(tableName(object), n -> new ArrayList<>())
              .add(new Owned(object, unit, f));
        }
      }
    }
  }

  void link(Origin origin, Validity validity) {
    Map<EntityId, Technology> technologies = new TreeMap<>(LinkIndex.BY_TEXT);
    Map<EntityId, Named> ownNames = new TreeMap<>(LinkIndex.BY_TEXT);
    Map<EntityId, Set<Deployments.Container>> servers = new TreeMap<>(LinkIndex.BY_TEXT);
    for (String view : index.views()) {
      for (Unit unit : index.units.values()) {
        databases(unit, view, technologies, ownNames, servers);
        brokers(unit, view);
        infrastructure(unit, view);
      }
      access(view);
    }
    technologies.forEach(
        (database, technology) ->
            out.add(
                Fact.create(
                    database,
                    DeployableUnits.TECHNOLOGY,
                    FactObject.string(technology.technology()),
                    FactJson.newObject(),
                    origin,
                    technology.source().evidence(),
                    technology.source().confidence(),
                    validity)));
    servers.forEach(
        (database, containers) ->
            containers.forEach(
                c -> out.add(Deployments.deployedIn(database, c, origin, validity))));
    ownNames.forEach(
        (database, named) ->
            out.add(
                Fact.create(
                    database,
                    DISPLAY_NAME,
                    FactObject.string(named.name()),
                    FactJson.newObject(),
                    origin,
                    named.datasource().evidence(),
                    named.datasource().confidence(),
                    validity)));
  }

  // ---------------------------------------------------------------------------------------------
  // Databases

  /** Links a unit to the databases its datasources point to in a view. */
  private void databases(
      Unit unit,
      String view,
      Map<EntityId, Technology> technologies,
      Map<EntityId, Named> ownNames,
      Map<EntityId, Set<Deployments.Container>> servers) {
    for (String key : index.configKeys(unit, PersistenceExtractor.DATASOURCE)) {
      Fact f = index.config(unit, view, PersistenceExtractor.DATASOURCE, key);
      if (f == null || LinkIndex.flag(f, "masked")) {
        continue;
      }
      EntityId database = database(unit, f);
      if (database == null) {
        continue;
      }
      List<Deployments.Placed> placed = placed(unit, f);
      Deployments.Placed named =
          placed.stream()
              .filter(p -> p.database() != null)
              .min(
                  Comparator.comparing((Deployments.Placed p) -> p.guessed())
                      .thenComparing(p -> p.unit().fact().id()))
              .orElse(null);
      String displayName = ownName(unit, f, named == null ? null : named.database());
      if (displayName != null) {
        ownNames.merge(
            database,
            new Named(f, displayName),
            (a, b) -> LinkIndex.BY_ID.compare(a.datasource(), b.datasource()) <= 0 ? a : b);
      }
      String technology = LinkIndex.text(f, "technology");
      List<Deployments.Container> onServers = new ArrayList<>();
      placed.forEach(
          p -> {
            if (p.server() != null && !onServers.contains(p.server())) {
              onServers.add(p.server());
            }
          });
      if (technology != null && !onServers.isEmpty()) {
        technology = Deployments.technology(technology, onServers);
      }
      if (technology != null) {
        Technology candidate =
            new Technology(onServers.isEmpty() ? f : onServers.getFirst().fact(), technology);
        technologies.merge(
            database,
            candidate,
            (a, b) -> LinkIndex.BY_ID.compare(a.source(), b.source()) <= 0 ? a : b);
      }
      if (!onServers.isEmpty()) {
        servers.computeIfAbsent(database, d -> new LinkedHashSet<>()).addAll(onServers);
      }
      ObjectNode attributes = FactJson.newObject();
      attributes.put("protocol", f.object().value().startsWith("r2dbc:") ? "R2DBC" : "JDBC");
      if (technology != null) {
        attributes.put("technology", technology);
      }
      attributes.put("url", f.object().value());
      attributes.put("key", key);
      List<EntityId> own = new ArrayList<>();
      for (EntityId module : unit.modules()) {
        own.addAll(schemas.getOrDefault(module, List.of()));
      }
      if (!own.isEmpty()) {
        ArrayNode array = attributes.putArray("schemas");
        own.forEach(s -> array.add(s.toString()));
      }
      Set<String> ids = new TreeSet<>(List.of(f.id()));
      double confidence = f.confidence() * unit.fact().confidence();
      if (!placed.isEmpty()) {
        ArrayNode array = attributes.putArray("deployments");
        for (Deployments.Placed p : placed) {
          ObjectNode entry = array.addObject();
          entry.put("environment", p.unit().label());
          entry.put("container", p.unit().name());
          if (p.server() != null) {
            entry.put("server", p.server().name());
          }
          if (p.address() != null) {
            entry.put("address", p.address());
          }
          if (p.database() != null) {
            entry.put("database", p.database());
          }
          ids.add(p.unit().fact().id());
          if (p.server() != null) {
            ids.add(p.server().fact().id());
          }
        }
        if (named != null && named.guessed()) {
          attributes.put(
              "inferred",
              "The database's name is a placeholder the environment leaves to a secret; of the"
                  + " databases its server creates, the one whose name shares a word with the unit"
                  + " or the placeholder is taken.");
          confidence *= 0.8;
        }
      }
      out.add(
          view,
          new Found(
              CrossRepoLinks.USES_DATABASE + "|" + unit.id() + "|" + database,
              unit.id(),
              CrossRepoLinks.USES_DATABASE,
              FactObject.entity(database),
              attributes,
              f.evidence(),
              CrossRepoLinks.round(confidence),
              ids));
    }
  }

  /**
   * The display name of a unit's own database (an embedded one, or one whose server or database is
   * a placeholder no config resolves): its database when that is written out, else the one a
   * deployment names, else "&lt;unit name&gt; database", with the datasource key in parentheses
   * when that is not the main one. Null for a database on a server, which is named by its key.
   *
   * @param deployed the database's name as a deployment gives it, or null
   */
  private String ownName(Unit unit, Fact datasource, String deployed) {
    String name = LinkIndex.text(datasource, "database");
    boolean unresolved = !LinkIndex.texts(datasource, "unresolved").isEmpty();
    if (!unresolved && (LinkIndex.text(datasource, "server") != null || name == null)) {
      return null;
    }
    if (deployed != null && (name == null || name.contains("${"))) {
      name = deployed;
    } else if (name == null || name.contains("${")) {
      Fact application = index.name(unit.id(), null);
      name = (application == null ? unit.artifactId() : application.object().value()) + " database";
    }
    String key = LinkIndex.text(datasource, "key");
    return unresolved && key != null && !key.equals("spring.datasource.url")
        ? name + " (" + key + ")"
        : name;
  }

  /**
   * Where the deployments put a datasource whose server or database is a placeholder no config
   * resolves, in each environment that runs the unit; empty for any other datasource.
   */
  private List<Deployments.Placed> placed(Unit unit, Fact datasource) {
    List<String> unresolved = LinkIndex.texts(datasource, "unresolved");
    if (unresolved.isEmpty()) {
      return List.of();
    }
    List<String> words = new ArrayList<>(unresolved);
    Fact application = index.name(unit.id(), null);
    if (application != null) {
      words.add(application.object().value());
    }
    words.add(unit.artifactId());
    return deployments.place(
        unit,
        LinkIndex.text(datasource, "server"),
        LinkIndex.text(datasource, "database"),
        LinkIndex.text(datasource, "technology"),
        words);
  }

  /**
   * The database a datasource points to: {@code database:<server>/<database>}, so units on the same
   * server and database share it; an embedded one ({@code jdbc:h2:mem:x}, no server) is the unit's
   * own. One whose server or database is a placeholder no config resolves is the unit's own too,
   * named by its database when that is written out, else by the unit (and by the datasource key
   * when that is not the main one), never shared. Null when the URL does not say.
   */
  private static EntityId database(Unit unit, Fact datasource) {
    String server = LinkIndex.text(datasource, "server");
    String name = LinkIndex.text(datasource, "database");
    if (!LinkIndex.texts(datasource, "unresolved").isEmpty()) {
      String key = LinkIndex.text(datasource, "key");
      String own = name == null || name.contains("${") ? unit.id().key() : name;
      if (key != null && !key.equals("spring.datasource.url")) {
        own += "@" + key;
      }
      return ownDatabase(unit, datasource, own);
    }
    if (server != null && name != null) {
      return new EntityId(CrossRepoLinks.DATABASE, server.toLowerCase(Locale.ROOT) + "/" + name);
    }
    if (name != null) {
      return ownDatabase(unit, datasource, name);
    }
    return null;
  }

  /**
   * A unit's own database: {@code <unit>/<technology>:<own>}, the technology as the key writes it.
   */
  private static EntityId ownDatabase(Unit unit, Fact datasource, String own) {
    String technology = LinkIndex.text(datasource, "technology");
    return new EntityId(
        CrossRepoLinks.DATABASE,
        unit.id().key()
            + "/"
            + (technology == null
                ? ""
                : technology.toLowerCase(Locale.ROOT).replace(' ', '-') + ":")
            + own);
  }

  /**
   * Units whose code reads or writes a table, by name, that another unit's schema declares, when
   * the first unit's main datasource and the datasource the second's migrations run on are the same
   * database in the view: the first accesses the second's data.
   */
  private void access(String view) {
    record Key(Unit from, Unit to, EntityId database) {}
    Map<Key, Set<String>> reads = new LinkedHashMap<>();
    Map<Key, Set<String>> writes = new LinkedHashMap<>();
    Map<Key, Set<String>> facts = new LinkedHashMap<>();
    Map<Key, Fact> first = new LinkedHashMap<>();
    Map<Key, Double> confidence = new LinkedHashMap<>();
    for (String predicate :
        List.of(PersistenceExtractor.READS_TABLE, PersistenceExtractor.WRITES_TABLE)) {
      for (Fact f : index.of(predicate)) {
        if (f.object().kind() != FactObject.Kind.ENTITY || !index.active(f.subject(), view)) {
          continue;
        }
        String name = tableName(f.object().entityId());
        List<Owned> owners = owned.getOrDefault(name, List.of());
        for (Unit from : index.unitsOf(f.subject())) {
          if (owners.stream().anyMatch(o -> o.unit().equals(from))) {
            continue;
          }
          for (Owned owner : owners) {
            // The data the code reaches is in the database its unit's main datasource points to;
            // the owner's tables are in the one its migrations run against.
            Fact used = primary(from, view, CODE_KEYS);
            Fact migrated = primary(owner.unit(), view, MIGRATION_KEYS);
            EntityId database = used == null ? null : database(from, used);
            if (database == null
                || migrated == null
                || !database.equals(database(owner.unit(), migrated))) {
              continue;
            }
            Key key = new Key(from, owner.unit(), database);
            (predicate.equals(PersistenceExtractor.READS_TABLE) ? reads : writes)
                .computeIfAbsent(key, k -> new TreeSet<>())
                .add(owner.table().toString());
            Set<String> ids = facts.computeIfAbsent(key, k -> new TreeSet<>());
            ids.add(f.id());
            ids.add(owner.fact().id());
            ids.add(used.id());
            ids.add(migrated.id());
            first.merge(key, f, (a, b) -> LinkIndex.BY_ID.compare(a, b) <= 0 ? a : b);
            confidence.merge(key, f.confidence(), Math::min);
          }
        }
      }
    }
    for (Map.Entry<Key, Fact> e : first.entrySet()) {
      Key key = e.getKey();
      ObjectNode attributes = FactJson.newObject();
      attributes.put("database", key.database().toString());
      if (reads.containsKey(key)) {
        ArrayNode array = attributes.putArray("reads");
        reads.get(key).forEach(array::add);
      }
      if (writes.containsKey(key)) {
        ArrayNode array = attributes.putArray("writes");
        writes.get(key).forEach(array::add);
      }
      attributes.put("protocol", "JDBC");
      out.add(
          view,
          new Found(
              CrossRepoLinks.ACCESSES_DATA_OF
                  + "|"
                  + key.from().id()
                  + "|"
                  + key.to().id()
                  + "|"
                  + key.database(),
              key.from().id(),
              CrossRepoLinks.ACCESSES_DATA_OF,
              FactObject.entity(key.to().id()),
              attributes,
              e.getValue().evidence(),
              CrossRepoLinks.round(confidence.get(key)),
              facts.get(key)));
    }
  }

  /** A unit's datasource for the first of the keys it sets in a view, unless masked; or null. */
  private Fact primary(Unit unit, String view, List<String> keys) {
    for (String key : keys) {
      Fact f = index.config(unit, view, PersistenceExtractor.DATASOURCE, key);
      if (f != null) {
        return LinkIndex.flag(f, "masked") ? null : f;
      }
    }
    return null;
  }

  /** A table's name: its key after the module's group and artifact ID. */
  private static String tableName(EntityId table) {
    String key = table.key();
    int first = key.indexOf(':');
    int second = first < 0 ? -1 : key.indexOf(':', first + 1);
    return second < 0 ? key : key.substring(second + 1);
  }

  // ---------------------------------------------------------------------------------------------
  // Brokers and infrastructure

  /** The message brokers a unit's config connects it to in a view. */
  private void brokers(Unit unit, String view) {
    for (Setting setting : BROKERS) {
      List<Fact> values = values(unit, view, setting.key());
      if (values.isEmpty()) {
        continue;
      }
      Set<String> addresses = new TreeSet<>();
      List<Fact> used = new ArrayList<>(values);
      for (Fact value : values) {
        for (String part : value.object().value().split(",")) {
          String address = address(part);
          if (!address.isEmpty()) {
            if (setting.key().equals("spring.rabbitmq.host") && !address.contains(":")) {
              List<Fact> port = values(unit, view, RABBIT_PORT);
              used.addAll(port);
              address += ":" + (port.isEmpty() ? "5672" : port.getFirst().object().value());
            }
            addresses.add(address);
          }
        }
      }
      if (addresses.isEmpty()) {
        continue;
      }
      EntityId broker =
          new EntityId(
              CrossRepoLinks.MESSAGE_BROKER,
              setting.technology().toLowerCase(Locale.ROOT).replace(' ', '-')
                  + ":"
                  + String.join(",", addresses));
      ObjectNode attributes = FactJson.newObject();
      attributes.put("protocol", setting.role());
      attributes.put("technology", setting.technology());
      attributes.put("key", setting.key());
      Fact evidence = values.getFirst();
      Set<String> ids = new TreeSet<>();
      used.forEach(u -> ids.add(u.id()));
      out.add(
          view,
          new Found(
              CrossRepoLinks.USES_BROKER + "|" + unit.id() + "|" + broker,
              unit.id(),
              CrossRepoLinks.USES_BROKER,
              FactObject.entity(broker),
              attributes,
              evidence.evidence(),
              CrossRepoLinks.round(evidence.confidence() * unit.fact().confidence()),
              ids));
    }
  }

  /**
   * The service discovery, config server and tracing services a unit's config connects it to in a
   * view: a unit of the snapshot when the host is its name, else an infrastructure entity.
   */
  private void infrastructure(Unit unit, String view) {
    for (Setting setting : INFRASTRUCTURE) {
      for (Fact value : values(unit, view, setting.key())) {
        String text = value.object().value();
        if (setting.key().equals("spring.config.import")) {
          int at = text.indexOf(CONFIG_SERVER);
          if (at < 0) {
            continue;
          }
          text = text.substring(at + CONFIG_SERVER.length());
          if (text.isBlank()) {
            Fact uri =
                index.config(unit, view, ConfigExtractor.CONFIG_VALUE, "spring.cloud.config.uri");
            // Spring Cloud Config's default server; a set uri is its own setting.
            if (uri != null) {
              continue;
            }
            text = "http://localhost:8888";
          }
        }
        String address = address(text.split(",")[0]);
        if (address.isEmpty() || address.contains("${")) {
          continue;
        }
        String host =
            address.contains(":") ? address.substring(0, address.lastIndexOf(':')) : address;
        EntityId target = null;
        Fact name = null;
        for (Unit other : index.units.values()) {
          Fact otherName = index.name(other.id(), view);
          if (otherName != null && otherName.object().value().equalsIgnoreCase(host)) {
            target = other.id();
            name = otherName;
            break;
          }
        }
        if (unit.id().equals(target)) {
          continue;
        }
        if (target == null) {
          target = new EntityId(CrossRepoLinks.INFRASTRUCTURE, address);
        }
        ObjectNode attributes = FactJson.newObject();
        attributes.put("role", setting.role());
        attributes.put("technology", setting.technology());
        attributes.put("key", setting.key());
        attributes.put("value", value.object().value());
        Set<String> ids = new TreeSet<>(List.of(value.id()));
        if (name != null) {
          ids.add(name.id());
        }
        out.add(
            view,
            new Found(
                CrossRepoLinks.USES_INFRASTRUCTURE
                    + "|"
                    + unit.id()
                    + "|"
                    + target
                    + "|"
                    + setting.role(),
                unit.id(),
                CrossRepoLinks.USES_INFRASTRUCTURE,
                FactObject.entity(target),
                attributes,
                value.evidence(),
                CrossRepoLinks.round(value.confidence() * unit.fact().confidence()),
                ids));
      }
    }
  }

  /**
   * The config values a unit sees in a view for a key, or for the items of a list under it ({@code
   * key[0]}), keys compared as Spring's relaxed binding does (case, dashes and underscores
   * ignored); masked values and values with placeholders nothing resolves are left out.
   */
  private List<Fact> values(Unit unit, String view, String key) {
    return values(index, unit, view, key);
  }

  /**
   * The config values a unit sees in a view for a key, as {@link #values(Unit, String, String)}.
   */
  static List<Fact> values(LinkIndex index, Unit unit, String view, String key) {
    List<Fact> values = new ArrayList<>();
    String wanted = relaxed(key);
    for (String k : index.configKeys(unit, ConfigExtractor.CONFIG_VALUE)) {
      String plain = k.replaceAll("\\[\\d+]$", "");
      if (!relaxed(plain).equals(wanted)) {
        continue;
      }
      Fact f = index.config(unit, view, ConfigExtractor.CONFIG_VALUE, k);
      if (f != null
          && !LinkIndex.flag(f, "masked")
          && LinkIndex.texts(f, "unresolved").isEmpty()
          && !f.object().value().isBlank()) {
        values.add(f);
      }
    }
    return values;
  }

  static String relaxed(String key) {
    return key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
  }

  /**
   * The {@code host[:port]} of an address or URL, in lower case: without a scheme, an {@code
   * optional:} prefix, user information, path or query, and a failover list's brackets.
   */
  static String address(String text) {
    String a = text.trim();
    if (a.startsWith("optional:")) {
      a = a.substring("optional:".length());
    }
    int scheme = a.indexOf("://");
    if (scheme >= 0) {
      a = a.substring(scheme + 3);
    } else if (a.matches("[a-zA-Z]+:\\(.*")) {
      a = a.substring(a.indexOf(':') + 1);
    }
    a = a.replace("(", "").replace(")", "");
    for (char c : new char[] {'/', '?', '#', ','}) {
      int cut = a.indexOf(c);
      if (cut >= 0) {
        a = a.substring(0, cut);
      }
    }
    return a.substring(a.lastIndexOf('@') + 1).toLowerCase(Locale.ROOT);
  }
}

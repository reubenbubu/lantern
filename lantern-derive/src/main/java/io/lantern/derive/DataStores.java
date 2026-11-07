package io.lantern.derive;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.types.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Which of a unit's stores a piece of its code uses (#962): a table it reads or writes goes to the
 * relational database whose schema holds it, a repository to the store of its kind, Redis use to
 * the unit's Redis store. A line from a component goes only to the stores its code touches.
 *
 * <p>Of a unit's relational databases, a class's SQL runs on the one Spring wires into it ({@code
 * datasource-use}: the injected JdbcTemplate, JdbcClient or DataSource, followed through
 * {@code @Qualifier}, {@code @Primary} and the {@code @Bean} methods to the config the datasource
 * is bound to). Without that, those its main datasource ({@code spring.datasource.url} or {@code
 * spring.r2dbc.url}) points to hold the tables of the unit's own schemas, which its migrations
 * build, and a table no schema of the unit declares is in the others. When there is only one, or
 * nothing tells them apart, every candidate is given.
 */
public final class DataStores {

  /** The datasource keys of the connection a unit's code uses by default. */
  private static final Set<String> MAIN_KEYS =
      Set.of(
          "spring.datasource.url",
          "spring.datasource.jdbc-url",
          "spring.datasource.hikari.jdbc-url",
          "spring.r2dbc.url");

  private static final Set<String> RELATIONAL_PROTOCOLS = Set.of("JDBC", "R2DBC");

  private static final Pattern REDIS = Pattern.compile("(?i)redis");

  /** Spring Data modules whose repositories keep data in no relational database. */
  private static final Pattern OTHER_STORE =
      Pattern.compile(
          "org\\.springframework\\.data\\.(elasticsearch|mongodb|cassandra|couchbase|neo4j|solr"
              + "|redis|keyvalue|gemfire|geode|aerospike|arangodb|azure|cosmos)\\.");

  /** Frameworks of a mapped class that keep it in no relational database. */
  private static final Pattern OTHER_FRAMEWORK =
      Pattern.compile("(?i)mongo|elasticsearch|cassandra|couchbase|neo4j|solr|redis");

  /** The kinds of Redis use, in the order a line names them, with what each does there. */
  private static final Map<String, String> REDIS_USES = new java.util.LinkedHashMap<>();

  static {
    REDIS_USES.put("template", "reads and writes keys");
    REDIS_USES.put("client", "reads and writes keys");
    REDIS_USES.put("hash", "keeps hashes");
    REDIS_USES.put("cache", "caches results");
    REDIS_USES.put("lock", "takes locks");
    REDIS_USES.put("session", "keeps sessions");
    REDIS_USES.put("pubsub", "publishes and subscribes");
    REDIS_USES.put("rate-limiter", "limits request rates");
  }

  /**
   * What some code does in one store: how it accesses it ("Reads", "Writes", "Reads and writes",
   * or, for Redis, what it does there), what it touches there in words (tables or records, or the
   * kinds of Redis use), and the facts that show it.
   */
  public record Access(
      EntityId store, String verb, SortedSet<String> touched, List<Fact> basis, boolean redis) {}

  private final Knowledge knowledge;
  private final Map<EntityId, Stores> byUnit = new HashMap<>();

  public DataStores(Knowledge knowledge) {
    this.knowledge = knowledge;
  }

  /** A unit's stores, as its datasources point to them. */
  private record Stores(
      Set<EntityId> relational,
      Set<EntityId> main,
      Set<EntityId> redis,
      Set<String> ownTables,
      boolean schemasKnown,
      Map<EntityId, Set<String>> keys) {}

  private Stores stores(EntityId unit) {
    return byUnit.computeIfAbsent(
        unit,
        u -> {
          Set<EntityId> relational = new TreeSet<>(Knowledge.BY_ID);
          Set<EntityId> main = new TreeSet<>(Knowledge.BY_ID);
          Set<EntityId> redis = new TreeSet<>(Knowledge.BY_ID);
          Set<String> schemas = new TreeSet<>();
          Map<EntityId, Set<String>> keys = new TreeMap<>(Knowledge.BY_ID);
          for (Fact fact : knowledge.relations(u, Types.USES_DATABASE)) {
            if (!Knowledge.isEntity(fact)) {
              continue;
            }
            EntityId database = Knowledge.object(fact);
            String protocol = Elements.attribute(fact, "protocol", "");
            String technology = Elements.attribute(fact, "technology", "");
            if (RELATIONAL_PROTOCOLS.contains(protocol)) {
              relational.add(database);
              keys.computeIfAbsent(database, d -> new TreeSet<>())
                  .add(Elements.attribute(fact, "key", ""));
              if (MAIN_KEYS.contains(Elements.attribute(fact, "key", ""))) {
                main.add(database);
              }
              schemas.addAll(Elements.strings(fact, "schemas"));
            } else if (REDIS.matcher(technology).find()
                || fact.attributes().has("uses")
                || fact.attributes().has("server")) {
              redis.add(database);
            }
          }
          Set<String> own = new TreeSet<>();
          for (String schema : schemas) {
            EntityId id;
            try {
              id = EntityId.parse(schema);
            } catch (IllegalArgumentException e) {
              continue;
            }
            for (Fact contains : knowledge.relations(id, Types.CONTAINS)) {
              if (Knowledge.isEntity(contains)
                  && Knowledge.object(contains).type().equals(Types.TABLE)) {
                own.add(tableName(Knowledge.object(contains)));
              }
            }
          }
          return new Stores(relational, main, redis, own, !own.isEmpty(), keys);
        });
  }

  /**
   * The relational databases of the unit a table a class's code reads or writes is in: those Spring
   * wires into the class ({@link #wired}), of them the ones the schema rule allows when it allows
   * any; without wiring, by the unit's schemas ({@link #tableStores(EntityId, EntityId)}).
   */
  public Set<EntityId> tableStores(EntityId unit, EntityId type, EntityId table) {
    Set<EntityId> bySchema = tableStores(unit, table);
    Set<EntityId> wired = wired(unit, type);
    if (wired.isEmpty()) {
      return bySchema;
    }
    Set<EntityId> both = new TreeSet<>(Knowledge.BY_ID);
    both.addAll(wired);
    both.retainAll(bySchema);
    return both.isEmpty() ? wired : both;
  }

  /**
   * The relational databases of the unit a class's JDBC or R2DBC access runs on, as Spring wires it
   * ({@code datasource-use}): those whose datasource key is under the config prefix it names; for
   * Spring Boot's own datasource when the unit's config sets none, the one datasource bean the code
   * declares beside it. Empty when the class names none, or none matches.
   */
  public Set<EntityId> wired(EntityId unit, EntityId type) {
    Set<EntityId> result = new TreeSet<>(Knowledge.BY_ID);
    if (type == null) {
      return result;
    }
    Stores stores = stores(unit);
    for (Fact use : knowledge.attributes(type)) {
      if (!use.predicate().equals(Types.DATASOURCE_USE)) {
        continue;
      }
      Set<EntityId> matched = under(stores, String.valueOf(use.object().value()));
      List<String> declared = Elements.strings(use, "declared");
      if (matched.isEmpty() && declared.size() == 1) {
        matched = under(stores, declared.getFirst());
      }
      result.addAll(matched);
    }
    return result;
  }

  private static Set<EntityId> under(Stores stores, String prefix) {
    Set<EntityId> matched = new TreeSet<>(Knowledge.BY_ID);
    stores
        .keys()
        .forEach(
            (database, keys) -> {
              if (keys.stream().anyMatch(k -> k.startsWith(prefix + "."))) {
                matched.add(database);
              }
            });
    return matched;
  }

  /**
   * The relational databases of the unit a table its code reads or writes is in, by its schemas
   * alone: the main ones when the unit's own schemas declare it (or no table is known, as for a
   * repository whose entity maps to none), else the others; every relational database when that
   * does not tell.
   */
  public Set<EntityId> tableStores(EntityId unit, EntityId table) {
    Stores stores = stores(unit);
    if (stores.relational().size() <= 1
        || stores.main().isEmpty()
        || stores.main().size() == stores.relational().size()
        || !stores.schemasKnown()) {
      return stores.relational();
    }
    if (table == null || stores.ownTables().contains(tableName(table))) {
      return stores.main();
    }
    Set<EntityId> others = new TreeSet<>(Knowledge.BY_ID);
    others.addAll(stores.relational());
    others.removeAll(stores.main());
    return others;
  }

  /**
   * The stores a repository keeps its records in: none for a store Lantern does not draw yet
   * (Elasticsearch, MongoDB; #466), the unit's Redis store for a Redis repository, else the
   * relational database its entity's table is in.
   */
  public Set<EntityId> repositoryStores(EntityId unit, Fact repositoryFor) {
    String base = Elements.attribute(repositoryFor, "base", "");
    EntityId entity = Knowledge.isEntity(repositoryFor) ? Knowledge.object(repositoryFor) : null;
    String framework = null;
    EntityId table = null;
    if (entity != null) {
      for (Fact maps : knowledge.relations(entity, Types.MAPS_TO)) {
        if (Knowledge.isEntity(maps)) {
          table = Knowledge.object(maps);
          framework = Elements.attribute(maps, "framework", null);
          break;
        }
      }
    }
    boolean redisHash =
        entity != null
            && knowledge.attributes(entity).stream()
                .anyMatch(
                    f ->
                        f.predicate().equals(Types.REDIS_USE)
                            && "hash".equals(String.valueOf(f.object().value())));
    if (base.startsWith("org.springframework.data.redis.") || redisHash) {
      return stores(unit).redis();
    }
    if (OTHER_STORE.matcher(base + ".").find()
        || framework != null && OTHER_FRAMEWORK.matcher(framework).find()) {
      return Set.of();
    }
    return tableStores(unit, repositoryFor.subject(), table);
  }

  /**
   * What the types (a component's members) do in each store of the unit, by store; a store they do
   * not touch is not in it.
   */
  public SortedMap<EntityId, Access> access(EntityId unit, Collection<EntityId> types) {
    Map<EntityId, Boolean> reads = new HashMap<>();
    Map<EntityId, Boolean> writes = new HashMap<>();
    Map<EntityId, SortedSet<String>> touched = new HashMap<>();
    Map<EntityId, List<Fact>> basis = new HashMap<>();
    Set<EntityId> redis = stores(unit).redis();
    SortedSet<EntityId> sorted = new TreeSet<>(Knowledge.BY_ID);
    sorted.addAll(types);
    for (EntityId type : sorted) {
      for (Fact repository : knowledge.relations(type, Types.REPOSITORY_FOR)) {
        for (EntityId store : repositoryStores(unit, repository)) {
          reads.put(store, true);
          writes.put(store, true);
          if (redis.contains(store)) {
            touched.computeIfAbsent(store, s -> new TreeSet<>()).add("hash");
          } else {
            String words = words(repository);
            if (words != null) {
              touched.computeIfAbsent(store, s -> new TreeSet<>()).add(words);
            }
          }
          basis.computeIfAbsent(store, s -> new ArrayList<>()).add(repository);
        }
      }
      List<EntityId> members = new ArrayList<>(List.of(type));
      members.addAll(knowledge.parts(type, Types.METHOD));
      for (EntityId member : members) {
        for (String predicate : List.of(Types.READS_TABLE, Types.WRITES_TABLE)) {
          for (Fact access : knowledge.relations(member, predicate)) {
            if (!Knowledge.isEntity(access)) {
              continue;
            }
            for (EntityId store : tableStores(unit, type, Knowledge.object(access))) {
              (predicate.equals(Types.READS_TABLE) ? reads : writes).put(store, true);
              String words = words(access);
              if (words != null) {
                touched.computeIfAbsent(store, s -> new TreeSet<>()).add(words);
              }
              basis.computeIfAbsent(store, s -> new ArrayList<>()).add(access);
            }
          }
        }
        for (Fact use : knowledge.attributes(member)) {
          if (!use.predicate().equals(Types.REDIS_USE)) {
            continue;
          }
          for (EntityId store : redis) {
            touched
                .computeIfAbsent(store, s -> new TreeSet<>())
                .add(String.valueOf(use.object().value()));
            basis.computeIfAbsent(store, s -> new ArrayList<>()).add(use);
          }
        }
      }
    }
    SortedMap<EntityId, Access> result = new TreeMap<>(Knowledge.BY_ID);
    basis.forEach(
        (store, facts) -> {
          boolean isRedis = redis.contains(store);
          SortedSet<String> what = touched.getOrDefault(store, new TreeSet<>());
          String verb;
          if (isRedis) {
            verb = redisVerb(what);
          } else {
            boolean r = reads.getOrDefault(store, false);
            boolean w = writes.getOrDefault(store, false);
            verb = r && w ? "Reads and writes" : r ? "Reads" : "Writes";
          }
          result.put(store, new Access(store, verb, what, List.copyOf(facts), isRedis));
        });
    return result;
  }

  /**
   * What code does in Redis, by the kinds of use it shows: "Reads and writes keys and takes locks";
   * at most two, in a fixed order.
   */
  public static String redisVerb(Collection<String> uses) {
    List<String> said = new ArrayList<>();
    for (Map.Entry<String, String> use : REDIS_USES.entrySet()) {
      if (uses.contains(use.getKey()) && !said.contains(use.getValue())) {
        said.add(use.getValue());
      }
    }
    if (said.isEmpty()) {
      return "Uses";
    }
    String text = said.size() == 1 ? said.getFirst() : said.get(0) + " and " + said.get(1);
    return Character.toUpperCase(text.charAt(0)) + text.substring(1);
  }

  /**
   * A table or a repository's record type in words, "work_orders" and {@code WorkOrder} as "work
   * orders" (#944), or null.
   */
  static String words(Fact basis) {
    if (basis.object() == null || !Knowledge.isEntity(basis)) {
      return null;
    }
    EntityId object = Knowledge.object(basis);
    String key = object.key();
    String last = key.substring(key.replaceAll("[.:/#$]", " ").lastIndexOf(' ') + 1);
    String words =
        last.replaceAll("([a-z0-9])([A-Z])", "$1 $2")
            .replace('_', ' ')
            .replace('-', ' ')
            .strip()
            .toLowerCase(Locale.ROOT);
    if (words.isBlank()) {
      return null;
    }
    // A repository's type names one ("work order"), a table many ("work orders"): many. A type's
    // "Entity" suffix is how it is coded, not what it holds (#944's real run).
    boolean type = !object.type().equals(Types.TABLE);
    if (type) {
      words = words.replaceAll(" entity$", "").strip();
    }
    return type ? ArchitectureModel.plural(words) : words;
  }

  private static String tableName(EntityId table) {
    if (table == null) {
      return "";
    }
    String key = table.key();
    return key.substring(key.lastIndexOf(':') + 1).toLowerCase(Locale.ROOT);
  }
}

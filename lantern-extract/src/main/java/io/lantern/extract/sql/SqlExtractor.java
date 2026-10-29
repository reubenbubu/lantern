package io.lantern.extract.sql;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.core.vocabulary.VocabularyType;
import io.lantern.extract.GitFiles;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.maven.MavenExtractor;
import io.lantern.extract.sql.SchemaModel.Where;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns database migrations into the schema they build as of the commit: tables, their columns and
 * keys. It reads Flyway migrations, Liquibase changelogs (XML, YAML, JSON and formatted SQL) and
 * other {@code .sql} files with JSqlParser. See {@code docs/adr/0034-sql-extractor.md}.
 *
 * <p>Each Maven module with migrations has one schema, {@code schema:<groupId>:<artifactId>}, which
 * the module contains. Its tables are {@code table:<schema key>:<table>} and their columns {@code
 * column:<schema key>:<table>#<column>}, with names as the database folds them: unquoted names in
 * lower case. Every fact points at the migration and lines that last shaped what it says.
 */
public final class SqlExtractor {

  /** The extractor's name, recorded as the origin of its facts. */
  public static final String NAME = "sql";

  /** The extractor's version; change it whenever the facts it produces change. */
  public static final String VERSION = "2";

  /** The source recorded on the vocabulary revision that adds this extractor's types. */
  public static final String VOCABULARY_SOURCE = "sql-extractor";

  /** Entity type: the schema a module's migrations build. */
  public static final String SCHEMA = "schema";

  /** Entity type: a table of a schema. */
  public static final String TABLE = "table";

  /** Entity type: a column of a table. */
  public static final String COLUMN = "column";

  /** Relationship: a table's primary key includes the column. */
  public static final String PRIMARY_KEY = "PRIMARY_KEY";

  /** Relationship: a unique key of the table includes the column. */
  public static final String UNIQUE_KEY = "UNIQUE_KEY";

  /** Relationship: a foreign key of the table references the other table. */
  public static final String REFERENCES = "REFERENCES";

  /** Attribute: a column's SQL type. */
  public static final String DATA_TYPE = "data-type";

  /** Attribute: whether a column accepts null. */
  public static final String NULLABLE = "nullable";

  /** Attribute: a column's place in its table, from 1. */
  public static final String POSITION = "position";

  /** Attribute (the Java extractor's): how a schema is migrated. */
  public static final String KIND = "kind";

  /** The {@code kind} of a schema built by Flyway, Liquibase or other SQL files. */
  public static final String FLYWAY = "flyway";

  public static final String LIQUIBASE = "liquibase";
  public static final String SQL = "sql";

  /** Confidence of facts from Flyway and Liquibase migrations, which are what the app runs. */
  static final double MIGRATION_CONFIDENCE = 1.0;

  /** Confidence of facts from other SQL files, which may be run by hand, or not at all. */
  static final double SCRIPT_CONFIDENCE = 0.8;

  private static final String RESOURCE = "/vocabulary/sql-types.json";
  private static final int MAX_FILE_BYTES = 16 * 1024 * 1024;
  private static final String RESOURCES = "src/main/resources/";

  /** Flyway's versioned and repeatable migrations, with its default prefixes and separator. */
  private static final Pattern FLYWAY_VERSIONED =
      Pattern.compile("V(\\d+(?:[._]\\d+)*)__[^/]*\\.sql");

  private static final Pattern FLYWAY_REPEATABLE = Pattern.compile("R__[^/]*\\.sql");
  private static final Pattern FLYWAY_OTHER = Pattern.compile("[UB]\\d+(?:[._]\\d+)*__[^/]*\\.sql");

  /** A path outside the main sources: under {@code src/} but not {@code src/main/}. */
  private static final Pattern NOT_MAIN = Pattern.compile("(^|.*/)src/(?!main/)[^/]+/.*");

  private static final List<String> CHANGELOG_EXTENSIONS =
      List.of(".xml", ".yaml", ".yml", ".json");

  private static final Origin ORIGIN = new Origin(Origin.Kind.STATIC, NAME, VERSION);

  /** The vocabulary types this extractor's facts use besides the built-in and Java ones. */
  public static List<VocabularyType> vocabulary() {
    try (InputStream in = SqlExtractor.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Missing resource " + RESOURCE);
      }
      JsonNode array = FactJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
      List<VocabularyType> types = new ArrayList<>();
      for (JsonNode node : array) {
        types.add(VocabularyType.fromJson(node));
      }
      return List.copyOf(types);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + RESOURCE, e);
    }
  }

  /**
   * A table name as {@code table} entities key it, for other extractors that name tables: an
   * unquoted name in lower case, as PostgreSQL folds it, and a quoted one as written without the
   * quotes, each part of a qualified name on its own.
   *
   * @param written the name as written, such as {@code app.Orders} or {@code "Orders"}
   * @return the name, or null for a blank one
   */
  public static String tableName(String written) {
    return SchemaModel.name(written);
  }

  /**
   * Extracts the schemas of a snapshot's repositories. Each repository is read on its own, from git
   * at its commit; builds are not used.
   *
   * @param repos the repositories of the snapshot, each at its commit
   * @param snapshot the snapshot number the facts hold from
   * @return the facts, without duplicates, ordered by subject, predicate, object and ID
   * @throws UncheckedIOException if git cannot be run
   * @throws IllegalStateException if git fails or times out
   */
  public List<Fact> extract(List<RepoBuild> repos, long snapshot) {
    Objects.requireNonNull(repos, "repos");
    Validity validity = Validity.from(snapshot);
    Map<String, Fact> facts = new LinkedHashMap<>();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (RepoBuild repo : repos) {
        extract(repo, validity, facts, executor);
      }
    }
    return facts.values().stream()
        .sorted(
            Comparator.comparing((Fact f) -> f.subject().toString())
                .thenComparing(Fact::predicate)
                .thenComparing(f -> f.object().value())
                .thenComparing(Fact::id))
        .toList();
  }

  // ---------------------------------------------------------------------------------------------
  // Finding each module's migrations

  /** The migration files of one module, by how they are run. */
  private record Migrations(List<String> flyway, List<String> changelogs, List<String> scripts) {}

  private void extract(
      RepoBuild repo, Validity validity, Map<String, Fact> facts, ExecutorService executor) {
    List<String> paths = GitFiles.list(repo, "");
    SortedMap<String, EntityId> modules = MavenExtractor.moduleDirectories(repo);
    Map<String, RepoFiles.Content> cache = new HashMap<>();
    Function<String, RepoFiles.Content> reader =
        path -> cache.computeIfAbsent(path, p -> RepoFiles.read(repo, p, MAX_FILE_BYTES));

    // Files are grouped by the module whose directory holds them most closely; a repository
    // without modules is one group, keyed by "".
    Map<String, List<String>> byModule = new TreeMap<>();
    for (String path : paths) {
      String lower = path.toLowerCase(Locale.ROOT);
      boolean sql = lower.endsWith(".sql");
      boolean changelog =
          path.contains(RESOURCES) && CHANGELOG_EXTENSIONS.stream().anyMatch(lower::endsWith);
      if ((!sql && !changelog) || NOT_MAIN.matcher(path).matches()) {
        continue;
      }
      byModule.computeIfAbsent(moduleDirectory(path, modules), d -> new ArrayList<>()).add(path);
    }
    SortedSet<String> all = new TreeSet<>(paths);
    for (Map.Entry<String, List<String>> group : byModule.entrySet()) {
      EntityId module = modules.get(group.getKey());
      Migrations migrations = classify(group.getValue(), reader);
      SchemaModel model = new SchemaModel();
      Map<String, String> kinds = new LinkedHashMap<>();
      for (String path : migrations.flyway()) {
        RepoFiles.Content content = reader.apply(path);
        if (content != null) {
          kinds.put(path, FLYWAY);
          SqlScript.apply(
              model,
              new String(content.bytes(), StandardCharsets.UTF_8),
              new Where(path, content.hash(), 1, 1, MIGRATION_CONFIDENCE),
              false,
              executor);
        }
      }
      Liquibase liquibase = new Liquibase(all, reader, executor, MIGRATION_CONFIDENCE);
      for (String root : Liquibase.roots(new TreeSet<>(migrations.changelogs()), liquibase)) {
        liquibase.apply(model, root);
      }
      for (String path : liquibase.used()) {
        kinds.putIfAbsent(path, LIQUIBASE);
      }
      for (String path : migrations.scripts()) {
        RepoFiles.Content content = reader.apply(path);
        if (content != null && !liquibase.used().contains(path)) {
          kinds.put(path, SQL);
          SqlScript.apply(
              model,
              new String(content.bytes(), StandardCharsets.UTF_8),
              new Where(path, content.hash(), 1, 1, SCRIPT_CONFIDENCE),
              false,
              executor);
        }
      }
      String key = module != null ? module.key() : repo.repo().name();
      facts(repo, module, entity(SCHEMA, key), key, model, kinds, validity, facts);
    }
  }

  /** The directory of the module holding the file: the longest module directory it is under. */
  private static String moduleDirectory(String path, SortedMap<String, EntityId> modules) {
    String best = null;
    for (String directory : modules.keySet()) {
      if ((directory.isEmpty() || path.startsWith(directory + "/"))
          && (best == null || directory.length() > best.length())) {
        best = directory;
      }
    }
    return best == null ? "" : best;
  }

  /**
   * Sorts a module's files into Flyway migrations in the order Flyway runs them (versioned by
   * version, then repeatable by name), Liquibase changelogs, and other SQL scripts by path. Flyway
   * undo and baseline migrations are left out: they do not run on a fresh database by default.
   */
  private static Migrations classify(
      List<String> files, Function<String, RepoFiles.Content> reader) {
    List<String> versioned = new ArrayList<>();
    List<String> repeatable = new ArrayList<>();
    List<String> changelogs = new ArrayList<>();
    List<String> scripts = new ArrayList<>();
    for (String path : files) {
      String file = path.substring(path.lastIndexOf('/') + 1);
      if (FLYWAY_VERSIONED.matcher(file).matches()) {
        versioned.add(path);
      } else if (FLYWAY_REPEATABLE.matcher(file).matches()) {
        repeatable.add(path);
      } else if (FLYWAY_OTHER.matcher(file).matches()) {
        continue;
      } else {
        RepoFiles.Content content = reader.apply(path);
        if (content == null) {
          continue;
        }
        if (Liquibase.isChangelog(path, content.bytes())) {
          changelogs.add(path);
        } else if (file.toLowerCase(Locale.ROOT).endsWith(".sql")) {
          scripts.add(path);
        }
      }
    }
    versioned.sort(
        Comparator.comparing(SqlExtractor::flywayVersion, SqlExtractor::compareVersions)
            .thenComparing(Comparator.naturalOrder()));
    repeatable.sort(
        Comparator.comparing((String p) -> p.substring(p.lastIndexOf('/') + 1))
            .thenComparing(Comparator.naturalOrder()));
    List<String> flyway = new ArrayList<>(versioned);
    flyway.addAll(repeatable);
    return new Migrations(flyway, changelogs, scripts);
  }

  private static List<BigInteger> flywayVersion(String path) {
    Matcher m = FLYWAY_VERSIONED.matcher(path.substring(path.lastIndexOf('/') + 1));
    if (!m.matches()) {
      return List.of();
    }
    List<BigInteger> parts = new ArrayList<>();
    for (String part : m.group(1).split("[._]")) {
      parts.add(new BigInteger(part));
    }
    return parts;
  }

  /** Compares versions part by part, as Flyway does: 1.10 comes after 1.9, and 1 equals 1.0. */
  private static int compareVersions(List<BigInteger> a, List<BigInteger> b) {
    for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
      BigInteger x = i < a.size() ? a.get(i) : BigInteger.ZERO;
      BigInteger y = i < b.size() ? b.get(i) : BigInteger.ZERO;
      int c = x.compareTo(y);
      if (c != 0) {
        return c;
      }
    }
    return 0;
  }

  // ---------------------------------------------------------------------------------------------
  // Facts

  private static void facts(
      RepoBuild repo,
      EntityId module,
      EntityId schema,
      String key,
      SchemaModel model,
      Map<String, String> kinds,
      Validity validity,
      Map<String, Fact> facts) {
    if (schema == null || model.tables().isEmpty()) {
      return;
    }
    Set<Where> used = new LinkedHashSet<>();
    List<Fact> found = new ArrayList<>();
    for (SchemaModel.Table table : model.tables()) {
      EntityId tableId = entity(TABLE, key + ":" + table.name);
      if (tableId == null) {
        continue;
      }
      add(
          found,
          used,
          repo,
          schema,
          BuiltInTypes.CONTAINS,
          FactObject.entity(tableId),
          null,
          table.defined,
          validity);
      int position = 0;
      for (SchemaModel.Column column : table.columns.values()) {
        position++;
        EntityId columnId = entity(COLUMN, key + ":" + table.name + "#" + column.name);
        if (columnId == null) {
          continue;
        }
        add(
            found,
            used,
            repo,
            tableId,
            BuiltInTypes.CONTAINS,
            FactObject.entity(columnId),
            null,
            column.defined,
            validity);
        add(
            found,
            used,
            repo,
            columnId,
            POSITION,
            FactObject.integer(position),
            null,
            column.defined,
            validity);
        if (column.type != null) {
          add(
              found,
              used,
              repo,
              columnId,
              DATA_TYPE,
              FactObject.string(column.type),
              null,
              column.typeAt,
              validity);
        }
        add(
            found,
            used,
            repo,
            columnId,
            NULLABLE,
            FactObject.bool(column.nullable),
            null,
            column.nullableAt,
            validity);
      }
      if (table.primaryKey != null) {
        key(found, used, repo, key, tableId, table, PRIMARY_KEY, table.primaryKey, validity);
      }
      for (SchemaModel.Key unique : table.uniqueKeys) {
        key(found, used, repo, key, tableId, table, UNIQUE_KEY, unique, validity);
      }
      for (SchemaModel.ForeignKey fk : table.foreignKeys) {
        EntityId target = entity(TABLE, key + ":" + fk.table());
        if (target == null) {
          continue;
        }
        ObjectNode attributes = FactJson.newObject();
        fk.columns().forEach(attributes.putArray("columns")::add);
        List<String> referenced = model.referencedColumns(fk);
        if (!referenced.isEmpty()) {
          referenced.forEach(attributes.putArray("referencedColumns")::add);
        }
        if (fk.name() != null) {
          attributes.put("name", fk.name());
        }
        add(
            found,
            used,
            repo,
            tableId,
            REFERENCES,
            FactObject.entity(target),
            attributes,
            fk.where(),
            validity);
      }
    }
    // The schema itself: which module it belongs to, and how it is migrated, pointing at the
    // first file of each kind that shaped it.
    Map<String, Where> firstOfKind = new LinkedHashMap<>();
    Where first = null;
    for (Map.Entry<String, String> file : kinds.entrySet()) {
      for (Where w : used) {
        if (w.path().equals(file.getKey())) {
          firstOfKind.putIfAbsent(file.getValue(), w);
          if (first == null) {
            first = w;
          }
          break;
        }
      }
    }
    if (first == null) {
      first = used.iterator().next();
    }
    if (module != null) {
      add(
          found,
          null,
          repo,
          module,
          BuiltInTypes.CONTAINS,
          FactObject.entity(schema),
          null,
          wholeFile(first),
          validity);
    }
    for (Map.Entry<String, Where> kind : firstOfKind.entrySet()) {
      add(
          found,
          null,
          repo,
          schema,
          KIND,
          FactObject.string(kind.getKey()),
          null,
          wholeFile(kind.getValue()),
          validity);
    }
    for (Fact fact : found) {
      facts.putIfAbsent(fact.id(), fact);
    }
  }

  private static void key(
      List<Fact> found,
      Set<Where> used,
      RepoBuild repo,
      String key,
      EntityId tableId,
      SchemaModel.Table table,
      String predicate,
      SchemaModel.Key k,
      Validity validity) {
    ObjectNode attributes = FactJson.newObject();
    k.columns().forEach(attributes.putArray("columns")::add);
    if (k.name() != null) {
      attributes.put("name", k.name());
    }
    for (String column : k.columns()) {
      EntityId columnId = entity(COLUMN, key + ":" + table.name + "#" + column);
      if (columnId != null) {
        add(
            found,
            used,
            repo,
            tableId,
            predicate,
            FactObject.entity(columnId),
            attributes.deepCopy(),
            k.where(),
            validity);
      }
    }
  }

  private static Where wholeFile(Where where) {
    return new Where(where.path(), where.hash(), 0, 0, where.confidence());
  }

  private static void add(
      List<Fact> found,
      Set<Where> used,
      RepoBuild repo,
      EntityId subject,
      String predicate,
      FactObject object,
      ObjectNode attributes,
      Where where,
      Validity validity) {
    if (used != null) {
      used.add(where);
    }
    Evidence evidence =
        new Evidence(
            repo.repo().name(),
            repo.commit(),
            where.path(),
            where.start() < 1
                ? null
                : new Evidence.LineRange(where.start(), Math.max(where.start(), where.end())),
            where.hash());
    found.add(
        Fact.create(
            subject,
            predicate,
            object,
            attributes == null ? FactJson.newObject() : attributes,
            ORIGIN,
            evidence,
            where.confidence(),
            validity));
  }

  /** An entity ID, or null if the key is not a valid entity key. */
  private static EntityId entity(String type, String key) {
    try {
      return new EntityId(type, key);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}

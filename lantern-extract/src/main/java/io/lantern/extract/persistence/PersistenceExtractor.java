package io.lantern.extract.persistence;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.core.vocabulary.VocabularyType;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.config.ConfigValue;
import io.lantern.extract.config.ModuleConfig;
import io.lantern.extract.java.JavaAnalysis;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.extract.java.ModuleStructure;
import io.lantern.extract.java.ModuleStructure.Annotation;
import io.lantern.extract.java.ModuleStructure.Call;
import io.lantern.extract.java.ModuleStructure.Location;
import io.lantern.extract.java.ModuleStructure.Member;
import io.lantern.extract.java.ModuleStructure.Supertype;
import io.lantern.extract.java.ModuleStructure.TypeDecl;
import io.lantern.extract.sql.SqlExtractor;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns persistence code and config into facts: the table each JPA (or Spring Data relational)
 * entity maps to, the entity each Spring Data repository stores, the tables SQL in code reads and
 * writes ({@code JdbcTemplate}, {@code NamedParameterJdbcTemplate}, {@code JdbcClient}, R2DBC's
 * {@code DatabaseClient}, {@code SimpleJdbcInsert} and native {@code @Query} methods), and each
 * module's datasource URLs per profile, without credentials. See {@code
 * docs/adr/0036-persistence-extractor.md}.
 *
 * <p>It reads the code as the Java structure extractor read it ({@link JavaAnalysis}), with the
 * same confidence, and the config through the config extractor. Tables are {@code
 * table:<groupId>:<artifactId>:<name>} entities of the module whose code names them, the SQL
 * extractor's type and naming; matching them to the tables migrations create, and code to the
 * datasource of the deployable that runs it, is the linker's.
 */
public final class PersistenceExtractor {

  /** The extractor's name, recorded as the origin of its facts. */
  public static final String NAME = "persistence";

  /** The extractor's version; change it whenever the facts it produces change. */
  public static final String VERSION = "1";

  /** The source recorded on the vocabulary revision that adds this extractor's types. */
  public static final String VOCABULARY_SOURCE = "persistence-extractor";

  /** Attribute: a datasource URL of the subject module, without credentials. */
  public static final String DATASOURCE = "datasource";

  /** Relationship: the subject entity type is stored in the object table. */
  public static final String MAPS_TO = "MAPS_TO";

  /** Relationship: the subject repository interface stores the object entity type. */
  public static final String REPOSITORY_FOR = "REPOSITORY_FOR";

  /** Relationship: the subject code reads the object table. */
  public static final String READS_TABLE = "READS_TABLE";

  /** Relationship: the subject code writes the object table. */
  public static final String WRITES_TABLE = "WRITES_TABLE";

  /** Mapping frameworks, as the {@code framework} attribute of {@code MAPS_TO} holds them. */
  public static final String JPA = "jpa";

  public static final String SPRING_DATA_RELATIONAL = "spring-data-relational";

  private static final String RESOURCE = "/vocabulary/persistence-types.json";

  private static final Origin ORIGIN = new Origin(Origin.Kind.STATIC, NAME, VERSION);

  private static final List<String> JPA_PACKAGES =
      List.of("jakarta.persistence.", "javax.persistence.");

  private static final String RELATIONAL_TABLE =
      "org.springframework.data.relational.core.mapping.Table";

  private static final String DATA = "org.springframework.data.";

  /** Spring Data's repository interfaces that a repository extends to get its store's methods. */
  private static final Set<String> REPOSITORY_BASES =
      Set.of(
          DATA + "repository.Repository",
          DATA + "repository.CrudRepository",
          DATA + "repository.ListCrudRepository",
          DATA + "repository.PagingAndSortingRepository",
          DATA + "repository.ListPagingAndSortingRepository",
          DATA + "repository.reactive.ReactiveCrudRepository",
          DATA + "repository.reactive.ReactiveSortingRepository",
          DATA + "repository.reactive.RxJava3CrudRepository",
          DATA + "repository.reactive.RxJava3SortingRepository",
          DATA + "jpa.repository.JpaRepository",
          DATA + "r2dbc.repository.R2dbcRepository",
          DATA + "mongodb.repository.MongoRepository",
          DATA + "mongodb.repository.ReactiveMongoRepository",
          DATA + "elasticsearch.repository.ElasticsearchRepository",
          DATA + "cassandra.repository.CassandraRepository",
          DATA + "neo4j.repository.Neo4jRepository");

  private static final String REPOSITORY_DEFINITION = DATA + "repository.RepositoryDefinition";

  private static final String NO_REPOSITORY_BEAN = DATA + "repository.NoRepositoryBean";

  /** {@code @Query} annotations whose text is SQL: always, or with {@code nativeQuery = true}. */
  private static final Set<String> SQL_QUERIES =
      Set.of(DATA + "jdbc.repository.query.Query", DATA + "r2dbc.repository.Query");

  private static final String JPA_QUERY = DATA + "jpa.repository.Query";

  /** Types whose methods take SQL text in their {@code String} parameters. */
  private static final Set<String> SQL_TYPES =
      Set.of(
          "org.springframework.jdbc.core.JdbcTemplate",
          "org.springframework.jdbc.core.JdbcOperations",
          "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate",
          "org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations",
          "org.springframework.jdbc.core.simple.JdbcClient",
          "org.springframework.r2dbc.core.DatabaseClient");

  /** The methods of those types that take SQL. */
  private static final Set<String> SQL_METHODS =
      Set.of(
          "query",
          "queryForObject",
          "queryForList",
          "queryForMap",
          "queryForRowSet",
          "queryForStream",
          "update",
          "batchUpdate",
          "execute",
          "sql");

  /** {@code SimpleJdbcInsert.withTableName(String)}: the insert's table. */
  private static final String TABLE_NAME_INSERT =
      "org.springframework.jdbc.core.simple.SimpleJdbcInsert#withTableName(java.lang.String)";

  /**
   * Keys that hold a datasource URL whatever their value; any key whose value is a JDBC URL also.
   */
  private static final Set<String> DATASOURCE_KEYS =
      Set.of(
          "spring.datasource.url",
          "spring.datasource.jdbc-url",
          "spring.datasource.hikari.jdbc-url",
          "spring.r2dbc.url",
          "spring.flyway.url",
          "spring.liquibase.url");

  /** The vocabulary types this extractor's facts use besides the built-in, Java and SQL ones. */
  public static List<VocabularyType> vocabulary() {
    try (InputStream in = PersistenceExtractor.class.getResourceAsStream(RESOURCE)) {
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
   * Extracts the facts of a snapshot's repositories, reading their code first.
   *
   * @param repos the repositories of the snapshot, each at its commit with its build, if any
   * @param snapshot the snapshot number the facts hold from
   * @return the facts, without duplicates, ordered by subject, predicate, object, evidence,
   *     attributes and ID
   * @throws UncheckedIOException if the sources cannot be read or copied
   * @throws IllegalStateException if git fails or times out, or Lantern runs without a compiler
   */
  public List<Fact> extract(List<RepoBuild> repos, long snapshot) {
    return extract(JavaExtractor.analyze(repos), snapshot);
  }

  /**
   * Extracts the facts of a snapshot's code already read by {@link JavaExtractor#analyze}, and of
   * the config of the repositories it is in.
   *
   * @param code the code of the snapshot's repositories
   * @param snapshot the snapshot number the facts hold from
   * @return the facts, without duplicates, ordered by subject, predicate, object, evidence,
   *     attributes and ID
   * @throws IllegalStateException if git fails or times out while reading config
   */
  public List<Fact> extract(JavaAnalysis code, long snapshot) {
    Objects.requireNonNull(code, "code");
    Run run = new Run(code, Validity.from(snapshot));
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      run.executor = executor;
      Set<RepoBuild> repos = new LinkedHashSet<>();
      for (JavaAnalysis.Module module : code.modules()) {
        repos.add(module.repo());
        run.module(module);
      }
      for (RepoBuild repo : repos) {
        for (ModuleConfig config : ConfigExtractor.read(repo)) {
          run.datasources(config);
        }
      }
    }
    return run.facts.values().stream()
        .sorted(
            Comparator.comparing((Fact f) -> f.subject().toString())
                .thenComparing(Fact::predicate)
                .thenComparing(f -> f.object().value())
                .thenComparing(f -> f.evidence().path())
                .thenComparing(f -> f.evidence().lines() == null ? 0 : f.evidence().lines().start())
                .thenComparing(f -> f.attributes().toString())
                .thenComparing(Fact::id))
        .toList();
  }

  /** One extraction: the facts so far. */
  private static final class Run {

    private final JavaAnalysis code;
    private final Validity validity;
    private final Map<String, Fact> facts = new LinkedHashMap<>();
    private ExecutorService executor;

    Run(JavaAnalysis code, Validity validity) {
      this.code = code;
      this.validity = validity;
    }

    // -------------------------------------------------------------------------------------------
    // Code

    void module(JavaAnalysis.Module m) {
      for (TypeDecl type : m.structure().types()) {
        if (type.kind().equals(ModuleStructure.CLASS)) {
          entity(m, type);
        } else if (type.kind().equals(ModuleStructure.INTERFACE)) {
          repository(m, type);
        }
        for (Member member : type.members()) {
          if (member.id().type().equals(ModuleStructure.METHOD)) {
            nativeQuery(m, member);
          }
        }
      }
      for (Call call : m.structure().calls()) {
        sql(m, call);
      }
    }

    /** {@code MAPS_TO} for an entity class. */
    private void entity(JavaAnalysis.Module m, TypeDecl type) {
      EntityId subject = typeId(type.binaryName());
      Annotation jpa = jpa(type.annotations(), "Entity");
      if (jpa != null) {
        TypeDecl root = inheritanceRoot(type);
        boolean inherited = root != type && jpa(type.annotations(), "Table") == null;
        TypeDecl owner = inherited ? root : type;
        Annotation table = jpa(owner.annotations(), "Table");
        Annotation entity = inherited ? jpa(owner.annotations(), "Entity") : jpa;
        String name = table == null ? null : first(table, "name");
        if (name == null) {
          name = entity == null ? null : first(entity, "name");
        }
        if (name == null) {
          name = simpleName(owner.binaryName());
        }
        String schema = table == null ? null : first(table, "schema");
        String physical = jpaName(name);
        if (schema != null) {
          physical = jpaName(schema) + "." + physical;
        }
        ObjectNode attributes = FactJson.newObject();
        attributes.put("framework", JPA);
        if (inherited) {
          attributes.put("inheritedFrom", root.binaryName());
        }
        Location where = located(table != null && !inherited ? table : jpa, type.where());
        relationship(m, subject, MAPS_TO, table(m, physical), attributes, where);
        return;
      }
      Annotation relational = annotation(type.annotations(), RELATIONAL_TABLE);
      if (relational != null) {
        String name = first(relational, "value");
        if (name == null) {
          name = first(relational, "name");
        }
        // Spring Data quotes the names it is given and derives the others in snake case.
        String physical = name != null ? name : snakeCase(simpleName(type.binaryName()));
        String schema = first(relational, "schema");
        if (schema != null) {
          physical = schema + "." + physical;
        }
        ObjectNode attributes = FactJson.newObject();
        attributes.put("framework", SPRING_DATA_RELATIONAL);
        relationship(
            m,
            subject,
            MAPS_TO,
            tableEntity(m, physical),
            attributes,
            located(relational, type.where()));
      }
    }

    /**
     * The topmost JPA entity among a class's superclasses in the analyzed code, whose table a
     * subclass without its own {@code @Table} shares under single-table inheritance (the default),
     * or the class itself.
     */
    private TypeDecl inheritanceRoot(TypeDecl type) {
      TypeDecl root = type;
      Set<String> seen = new HashSet<>();
      TypeDecl current = type;
      while (seen.add(current.binaryName())) {
        TypeDecl parent = null;
        for (Supertype s : current.supertypes()) {
          if (s.predicate().equals(BuiltInTypes.EXTENDS)) {
            parent = code.type(s.binaryName());
          }
        }
        if (parent == null) {
          break;
        }
        if (jpa(parent.annotations(), "Entity") != null) {
          root = parent;
        }
        current = parent;
      }
      if (root == type) {
        return type;
      }
      Annotation inheritance = jpa(root.annotations(), "Inheritance");
      String strategy = inheritance == null ? null : first(inheritance, "strategy");
      return strategy == null || strategy.equals("SINGLE_TABLE") ? root : type;
    }

    /** {@code REPOSITORY_FOR} for a Spring Data repository interface. */
    private void repository(JavaAnalysis.Module m, TypeDecl type) {
      if (annotation(type.annotations(), NO_REPOSITORY_BEAN) != null) {
        return;
      }
      EntityId subject = typeId(type.binaryName());
      Annotation definition = annotation(type.annotations(), REPOSITORY_DEFINITION);
      if (definition != null) {
        String domain = first(definition, "domainClass");
        if (domain != null) {
          ObjectNode attributes = FactJson.newObject();
          attributes.put("base", REPOSITORY_DEFINITION);
          relationship(
              m,
              subject,
              REPOSITORY_FOR,
              typeId(domain),
              attributes,
              located(definition, type.where()));
        }
        return;
      }
      Base base = base(type, new HashSet<>());
      if (base != null && base.domain() != null) {
        ObjectNode attributes = FactJson.newObject();
        attributes.put("base", base.base());
        relationship(m, subject, REPOSITORY_FOR, typeId(base.domain()), attributes, base.where());
      }
    }

    /**
     * The Spring Data interface a repository extends, directly or through interfaces of the
     * analyzed code, and the domain type: the first type argument closest to that interface that is
     * not a type variable.
     */
    private Base base(TypeDecl type, Set<String> seen) {
      if (!seen.add(type.binaryName())) {
        return null;
      }
      for (Supertype s : type.supertypes()) {
        String argument = s.typeArguments().isEmpty() ? null : s.typeArguments().getFirst();
        String domain = argument == null || argument.equals("java.lang.Object") ? null : argument;
        if (REPOSITORY_BASES.contains(s.binaryName())) {
          return new Base(s.binaryName(), domain, s.where());
        }
        TypeDecl declared = code.type(s.binaryName());
        if (declared != null && declared.kind().equals(ModuleStructure.INTERFACE)) {
          Base inner = base(declared, seen);
          if (inner != null) {
            return new Base(
                inner.base(), inner.domain() != null ? inner.domain() : domain, s.where());
          }
        }
      }
      return null;
    }

    private record Base(String base, String domain, Location where) {}

    /** {@code READS_TABLE} and {@code WRITES_TABLE} for a repository method's SQL query. */
    private void nativeQuery(JavaAnalysis.Module m, Member method) {
      for (Annotation a : method.annotations()) {
        boolean sql =
            SQL_QUERIES.contains(a.type())
                || (a.type().equals(JPA_QUERY) && "true".equals(first(a, "nativeQuery")));
        String text = first(a, "value");
        if (sql && text != null) {
          access(m, method.id(), text, a.type(), located(a, method.where()));
        }
      }
    }

    /** {@code READS_TABLE} and {@code WRITES_TABLE} for SQL passed to a JDBC or R2DBC API. */
    private void sql(JavaAnalysis.Module m, Call call) {
      String key = call.callee().key();
      if (key.equals(TABLE_NAME_INSERT)) {
        String name = call.argument(0) == null ? null : SqlExtractor.tableName(call.argument(0));
        if (name != null) {
          ObjectNode attributes = FactJson.newObject();
          attributes.put("statement", "insert");
          attributes.put("api", key.substring(0, key.indexOf('#')));
          relationship(
              m, call.caller(), WRITES_TABLE, tableEntity(m, name), attributes, call.where());
        }
        return;
      }
      int hash = key.indexOf('#');
      String type = key.substring(0, hash);
      String method = key.substring(hash + 1, key.indexOf('(', hash));
      if (!SQL_TYPES.contains(type) || !SQL_METHODS.contains(method)) {
        return;
      }
      for (String text : call.arguments()) {
        if (text != null) {
          access(m, call.caller(), text, type, call.where());
        }
      }
    }

    private void access(
        JavaAnalysis.Module m, EntityId subject, String sql, String api, Location where) {
      for (SqlAccess.Access a : SqlAccess.of(sql, executor)) {
        ObjectNode attributes = FactJson.newObject();
        attributes.put("statement", a.statement());
        attributes.put("api", api);
        relationship(
            m,
            subject,
            a.write() ? WRITES_TABLE : READS_TABLE,
            tableEntity(m, a.table()),
            attributes,
            where);
      }
    }

    private void relationship(
        JavaAnalysis.Module m,
        EntityId subject,
        String predicate,
        EntityId object,
        ObjectNode attributes,
        Location where) {
      if (subject == null || object == null) {
        return;
      }
      RepoBuild repo = m.repo();
      Evidence evidence =
          new Evidence(
              repo.repo().name(),
              repo.commit(),
              where.file().path(),
              where.start() > 0 ? new Evidence.LineRange(where.start(), where.end()) : null,
              where.file().contentHash());
      add(
          Fact.create(
              subject,
              predicate,
              FactObject.entity(object),
              attributes,
              ORIGIN,
              evidence,
              m.confidence(),
              validity));
    }

    /** The table entity for a JPA physical name, folded as {@link SqlExtractor#tableName}. */
    private static EntityId table(JavaAnalysis.Module m, String physical) {
      String name = SqlExtractor.tableName(physical);
      return name == null ? null : tableEntity(m, name);
    }

    // -------------------------------------------------------------------------------------------
    // Config

    /**
     * {@code datasource} facts for a module's config: every datasource URL of the default view, and
     * those that differ with a profile active.
     */
    void datasources(ModuleConfig config) {
      SortedMap<String, Datasource> defaults = datasources(config, null);
      defaults.values().forEach(d -> datasource(config, d, null));
      for (String profile : config.profiles()) {
        for (Datasource d : datasources(config, profile).values()) {
          Datasource byDefault = defaults.get(d.key());
          if (byDefault == null
              || !byDefault.value().equals(d.value())
              || !byDefault.attributes().equals(d.attributes())) {
            datasource(config, d, profile);
          }
        }
      }
    }

    /**
     * A datasource URL as recorded.
     *
     * @param key the config key
     * @param value the URL without credentials, or the mask
     * @param attributes the attributes besides {@code profile}
     * @param where where the value is set
     */
    private record Datasource(
        String key, String value, ObjectNode attributes, ConfigValue.Where where) {}

    private static SortedMap<String, Datasource> datasources(ModuleConfig config, String profile) {
      SortedMap<String, ConfigValue> values = config.values(profile);
      SortedMap<String, Datasource> found = new TreeMap<>();
      for (ConfigValue v : values.values()) {
        String lower = v.value().strip().toLowerCase(Locale.ROOT);
        boolean url =
            DATASOURCE_KEYS.contains(v.key())
                || lower.startsWith("jdbc:")
                || lower.startsWith("r2dbc:");
        if (!url || ConfigExtractor.isSecret(v.key())) {
          continue;
        }
        boolean masked = v.used().stream().anyMatch(ConfigExtractor::isSecret);
        JdbcUrl jdbc = JdbcUrl.of(masked ? v.written() : v.value(), driver(values, v.key()));
        ObjectNode attributes = FactJson.newObject();
        attributes.put("key", v.key());
        if (jdbc.technology() != null) {
          attributes.put("technology", jdbc.technology());
        }
        String value = jdbc.url();
        if (value == null) {
          value = ConfigExtractor.MASK;
          masked = true;
        } else {
          if (jdbc.server() != null) {
            attributes.put("server", jdbc.server());
          }
          if (jdbc.database() != null) {
            attributes.put("database", jdbc.database());
          }
        }
        if (masked) {
          attributes.put("masked", true);
        } else {
          String written = JdbcUrl.of(v.written(), null).url();
          if (written != null && !written.equals(value)) {
            attributes.put("written", written);
          }
          if (!v.unresolved().isEmpty()) {
            ArrayNode unresolved = attributes.putArray("unresolved");
            v.unresolved().forEach(unresolved::add);
          }
        }
        found.put(v.key(), new Datasource(v.key(), value, attributes, v.where()));
      }
      return found;
    }

    /** The driver class configured next to a URL key, if it can be recorded. */
    private static String driver(SortedMap<String, ConfigValue> values, String key) {
      String prefix = key.substring(0, Math.max(0, key.lastIndexOf('.')));
      List<String> candidates = new ArrayList<>();
      candidates.add(prefix + ".driver-class-name");
      candidates.add(prefix + ".driverClassName");
      if (key.startsWith("spring.datasource.")) {
        candidates.add("spring.datasource.driver-class-name");
      }
      for (String candidate : candidates) {
        ConfigValue v = values.get(candidate);
        if (v != null && v.used().stream().noneMatch(ConfigExtractor::isSecret)) {
          return v.value();
        }
      }
      return null;
    }

    private void datasource(ModuleConfig config, Datasource d, String profile) {
      ObjectNode attributes = FactJson.newObject();
      attributes.put("key", d.key());
      if (profile != null) {
        attributes.put("profile", profile);
      }
      d.attributes()
          .properties()
          .forEach(
              e -> {
                if (!e.getKey().equals("key")) {
                  attributes.set(e.getKey(), e.getValue());
                }
              });
      ConfigValue.Where where = d.where();
      Evidence evidence =
          new Evidence(
              config.repo().repo().name(),
              config.repo().commit(),
              where.path(),
              where.line() > 0 ? new Evidence.LineRange(where.line(), where.line()) : null,
              where.contentHash());
      add(
          Fact.create(
              config.module(),
              DATASOURCE,
              FactObject.string(d.value()),
              attributes,
              ORIGIN,
              evidence,
              1.0,
              validity));
    }

    private void add(Fact fact) {
      facts.putIfAbsent(fact.id(), fact);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Names

  /** The JPA annotation with a simple name, in the Jakarta or the older javax package, or null. */
  private static Annotation jpa(List<Annotation> annotations, String simpleName) {
    for (String pkg : JPA_PACKAGES) {
      Annotation a = annotation(annotations, pkg + simpleName);
      if (a != null) {
        return a;
      }
    }
    return null;
  }

  private static Annotation annotation(List<Annotation> annotations, String type) {
    for (Annotation a : annotations) {
      if (a.type().equals(type)) {
        return a;
      }
    }
    return null;
  }

  /** An annotation element's first value, or null if it is not written or blank. */
  private static String first(Annotation a, String element) {
    List<String> values = a.values(element);
    return values.isEmpty() || values.getFirst().isBlank() ? null : values.getFirst().strip();
  }

  private static Location located(Annotation a, Location fallback) {
    return a.where() != null ? a.where() : fallback;
  }

  /**
   * A JPA logical name as Spring Boot's default physical naming strategy (Hibernate's {@code
   * CamelCaseToUnderscoresNamingStrategy}) makes it: dots become underscores, an underscore goes
   * before each upper-case letter between a lower-case letter or digit and another, and the name is
   * lower-cased; a quoted name ({@code "Name"} or {@code `Name`}) is kept as written.
   */
  static String jpaName(String logical) {
    String name = logical.strip();
    if (name.length() > 1
        && (name.startsWith("\"") && name.endsWith("\"")
            || name.startsWith("`") && name.endsWith("`"))) {
      return "\"" + name.substring(1, name.length() - 1) + "\"";
    }
    StringBuilder b = new StringBuilder(name.replace('.', '_'));
    for (int i = 1; i < b.length() - 1; i++) {
      char before = b.charAt(i - 1);
      char current = b.charAt(i);
      char after = b.charAt(i + 1);
      if ((Character.isLowerCase(before) || Character.isDigit(before))
          && Character.isUpperCase(current)
          && (Character.isLowerCase(after) || Character.isDigit(after))) {
        b.insert(i++, '_');
      }
    }
    return b.toString().toLowerCase(Locale.ROOT);
  }

  /** A class name in snake case, as Spring Data relational derives a table name. */
  static String snakeCase(String name) {
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (Character.isUpperCase(c) && i > 0) {
        b.append('_');
      }
      b.append(Character.toLowerCase(c));
    }
    return b.toString();
  }

  private static String simpleName(String binaryName) {
    String name = binaryName.substring(binaryName.lastIndexOf('.') + 1);
    return name.substring(name.lastIndexOf('$') + 1);
  }

  /** {@code table:<groupId>:<artifactId>:<name>} of the module whose code names it, or null. */
  private static EntityId tableEntity(JavaAnalysis.Module m, String name) {
    return entity(SqlExtractor.TABLE, m.id().key() + ":" + name);
  }

  private static EntityId typeId(String binaryName) {
    return entity(BuiltInTypes.TYPE, binaryName);
  }

  private static EntityId entity(String type, String key) {
    try {
      return new EntityId(type, key);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}

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
import io.lantern.extract.CacheUnit;
import io.lantern.extract.ClaimSink;
import io.lantern.extract.ExtractionContext;
import io.lantern.extract.ExtractionUnit;
import io.lantern.extract.Extractor;
import io.lantern.extract.ExtractorInfo;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.config.ConfigValue;
import io.lantern.extract.config.ModuleConfig;
import io.lantern.extract.config.ModuleValues;
import io.lantern.extract.config.UrlCredentials;
import io.lantern.extract.java.JavaAnalysis;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.extract.java.ModuleStructure;
import io.lantern.extract.java.ModuleStructure.Annotation;
import io.lantern.extract.java.ModuleStructure.Call;
import io.lantern.extract.java.ModuleStructure.Location;
import io.lantern.extract.java.ModuleStructure.Member;
import io.lantern.extract.java.ModuleStructure.Supertype;
import io.lantern.extract.java.ModuleStructure.TypeDecl;
import io.lantern.extract.sql.JdbcUrl;
import io.lantern.extract.sql.SqlExtractor;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
import java.util.function.Function;
import java.util.regex.Pattern;
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
public final class PersistenceExtractor implements Extractor {

  /** The extractor's name, recorded as the origin of its facts. */
  public static final String NAME = "persistence";

  /** The extractor's version; change it whenever the facts it produces change. */
  public static final String VERSION = "6";

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

  /** The framework of a MongoDB document's collection. */
  public static final String MONGODB = "mongodb";

  /** The framework of an Elasticsearch document's index. */
  public static final String ELASTICSEARCH = "elasticsearch";

  /** The name of the attribute type of a non-relational store a module's config connects to. */
  public static final String DATA_STORE = "data-store";

  /** Entity: a datasource or other data store a module's config connects it to (#560). */
  public static final String DATASOURCE_ENTITY = "data-source";

  /** Relationship: the subject module's config sets up the object datasource. */
  public static final String HAS_DATASOURCE = "HAS_DATASOURCE";

  /** Relationship: the subject datasource connects to the object address, as written. */
  public static final String CONNECTS_TO = "CONNECTS_TO";

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

  /** MyBatis's statement annotations on a mapper interface's methods: their text is SQL. */
  private static final Set<String> MYBATIS_SQL =
      Set.of(
          "org.apache.ibatis.annotations.Select",
          "org.apache.ibatis.annotations.Insert",
          "org.apache.ibatis.annotations.Update",
          "org.apache.ibatis.annotations.Delete");

  /** The API that runs a MyBatis XML mapper's statements. */
  private static final String MYBATIS_SESSION = "org.apache.ibatis.session.SqlSession";

  private static final String MONGO_DOCUMENT = DATA + "mongodb.core.mapping.Document";

  private static final String ELASTICSEARCH_DOCUMENT = DATA + "elasticsearch.annotations.Document";

  /** Hibernate's physical naming strategy that keeps logical names as they are. */
  private static final String STANDARD_NAMING =
      "org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl";

  /** The physical naming strategies that make names as Spring Boot's default does. */
  private static final Set<String> SNAKE_CASE_NAMING =
      Set.of(
          "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
          "org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy");

  /** The config keys that set Hibernate's physical naming strategy, in relaxed form. */
  private static final Set<String> NAMING_KEYS =
      Set.of(
          "spring.jpa.hibernate.naming.physicalstrategy",
          "spring.jpa.properties.hibernate.physicalnamingstrategy");

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

  /**
   * A kind of non-relational store and the config keys (in relaxed form: lower case, without dashes
   * or underscores) that connect a module to it, by Spring Boot's versions.
   *
   * @param uriList whether a URI key holds a list of URIs (rather than one URI with several hosts)
   * @param databaseAlone whether a database key alone connects to a default server, as Spring
   *     Boot's MongoDB and Cassandra auto-configuration does on localhost
   */
  private record StoreKind(
      String technology,
      String scheme,
      List<String> uris,
      List<String> hosts,
      List<String> ports,
      List<String> databases,
      boolean uriList,
      boolean databaseAlone) {}

  private static final List<StoreKind> STORES =
      List.of(
          new StoreKind(
              "MongoDB",
              "mongodb",
              List.of("spring.data.mongodb.uri", "spring.mongodb.uri"),
              List.of("spring.data.mongodb.host", "spring.mongodb.host"),
              List.of("spring.data.mongodb.port", "spring.mongodb.port"),
              List.of("spring.data.mongodb.database", "spring.mongodb.database"),
              false,
              true),
          new StoreKind(
              "Elasticsearch",
              "http",
              List.of(
                  "spring.elasticsearch.uris",
                  "spring.elasticsearch.rest.uris",
                  "spring.data.elasticsearch.client.reactive.endpoints",
                  "spring.data.elasticsearch.clusternodes"),
              List.of(),
              List.of(),
              List.of(),
              true,
              false),
          new StoreKind(
              "Cassandra",
              "cassandra",
              List.of(),
              List.of("spring.cassandra.contactpoints", "spring.data.cassandra.contactpoints"),
              List.of("spring.cassandra.port", "spring.data.cassandra.port"),
              List.of("spring.cassandra.keyspacename", "spring.data.cassandra.keyspacename"),
              false,
              true),
          new StoreKind(
              "Neo4j",
              "bolt",
              List.of("spring.neo4j.uri", "spring.data.neo4j.uri"),
              List.of(),
              List.of(),
              List.of("spring.data.neo4j.database"),
              false,
              false),
          new StoreKind(
              "Couchbase",
              "couchbase",
              List.of("spring.couchbase.connectionstring", "spring.couchbase.bootstraphosts"),
              List.of(),
              List.of(),
              List.of("spring.data.couchbase.bucketname", "spring.couchbase.bucket.name"),
              false,
              false));

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
    Run run = new Run(code, Validity.from(snapshot), Set.of());
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      run.executor = executor;
      Set<RepoBuild> repos = new LinkedHashSet<>();
      code.modules().forEach(m -> repos.add(m.repo()));
      Map<RepoBuild, List<ModuleConfig>> configs = new LinkedHashMap<>();
      for (RepoBuild repo : repos) {
        configs.put(repo, ConfigExtractor.read(repo));
        configs.get(repo).forEach(run::naming);
      }
      for (JavaAnalysis.Module module : code.modules()) {
        run.module(module);
      }
      for (RepoBuild repo : repos) {
        run.mappers(repo);
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
    private final Set<String> known;
    private Origin origin = ORIGIN;

    /** The physical naming strategy each module's config sets, by module. */
    private final Map<EntityId, String> namingStrategies = new HashMap<>();

    Run(JavaAnalysis code, Validity validity, Set<String> known) {
      this.known = known;
      this.code = code;
      this.validity = validity;
    }

    // -------------------------------------------------------------------------------------------
    // Code

    void module(JavaAnalysis.Module m) {
      for (TypeDecl type : m.structure().types()) {
        // A record can be a document or a mapped row (Spring Data MongoDB, Elasticsearch, JDBC).
        if (type.kind().equals(ModuleStructure.CLASS)
            || type.kind().equals(ModuleStructure.RECORD)) {
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
        String strategy = namingStrategies.get(m.id());
        boolean standard = strategy != null && !SNAKE_CASE_NAMING.contains(strategy);
        String physical = standard ? name : jpaName(name);
        if (schema != null) {
          physical = (standard ? schema : jpaName(schema)) + "." + physical;
        }
        ObjectNode attributes = FactJson.newObject();
        attributes.put("framework", JPA);
        if (standard && !strategy.equals(STANDARD_NAMING)) {
          // A strategy of the code's own: the name is recorded as the entity gives it.
          attributes.put("namingStrategy", strategy);
        }
        if (inherited) {
          attributes.put("inheritedFrom", root.binaryName());
        }
        Location where = located(table != null && !inherited ? table : jpa, type.where());
        relationship(m, subject, MAPS_TO, table(m, physical), attributes, where);
        return;
      }
      Annotation mongo = annotation(type.annotations(), MONGO_DOCUMENT);
      if (mongo != null) {
        String name = first(mongo, "collection");
        if (name == null) {
          name = first(mongo, "value");
        }
        if (name == null) {
          // Spring Data MongoDB names a collection after the class, its first letter lower case.
          String simple = simpleName(type.binaryName());
          name = Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
        }
        document(m, subject, name, MONGODB, located(mongo, type.where()));
        return;
      }
      Annotation search = annotation(type.annotations(), ELASTICSEARCH_DOCUMENT);
      if (search != null) {
        String name = first(search, "indexName");
        if (name != null) {
          document(m, subject, name, ELASTICSEARCH, located(search, type.where()));
        }
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
     * {@code MAPS_TO} for a document class: its collection or index, by name as written (both are
     * case sensitive); none for a name an expression makes at run time.
     */
    private void document(
        JavaAnalysis.Module m, EntityId subject, String name, String framework, Location where) {
      if (name.contains("#{") || name.contains("${")) {
        return;
      }
      ObjectNode attributes = FactJson.newObject();
      attributes.put("framework", framework);
      relationship(m, subject, MAPS_TO, tableEntity(m, name), attributes, where);
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
      Base base = base(type, List.of(), new HashSet<>());
      if (base != null && base.domain() != null) {
        ObjectNode attributes = FactJson.newObject();
        attributes.put("base", base.base());
        relationship(m, subject, REPOSITORY_FOR, typeId(base.domain()), attributes, base.where());
      }
    }

    /**
     * The Spring Data interface a repository extends, directly or through interfaces of the
     * analyzed code, and the domain type: the base interface's first type argument, with the type
     * variables of the interfaces in between replaced by the arguments given for them, by position,
     * or null if that is not a concrete type.
     *
     * @param bindings the types given for the type's own type parameters, by index; null where a
     *     type is not known
     */
    private Base base(TypeDecl type, List<String> bindings, Set<String> seen) {
      if (!seen.add(type.binaryName())) {
        return null;
      }
      for (Supertype s : type.supertypes()) {
        List<String> arguments = new ArrayList<>();
        for (int i = 0; i < s.typeArguments().size(); i++) {
          int variable = s.typeVariables().get(i);
          String argument =
              variable < 0
                  ? s.typeArguments().get(i)
                  : variable < bindings.size() ? bindings.get(variable) : null;
          arguments.add(argument == null || argument.equals("java.lang.Object") ? null : argument);
        }
        if (REPOSITORY_BASES.contains(s.binaryName())) {
          return new Base(
              s.binaryName(), arguments.isEmpty() ? null : arguments.getFirst(), s.where());
        }
        TypeDecl declared = code.type(s.binaryName());
        if (declared != null && declared.kind().equals(ModuleStructure.INTERFACE)) {
          Base inner = base(declared, arguments, seen);
          if (inner != null) {
            return new Base(inner.base(), inner.domain(), s.where());
          }
        }
      }
      return null;
    }

    private record Base(String base, String domain, Location where) {}

    /** {@code READS_TABLE} and {@code WRITES_TABLE} for a repository method's SQL query. */
    private void nativeQuery(JavaAnalysis.Module m, Member method) {
      for (Annotation a : method.annotations()) {
        if (MYBATIS_SQL.contains(a.type())) {
          // MyBatis joins the strings of its value with spaces.
          String text = String.join(" ", a.values("value")).strip();
          if (!text.isEmpty() && !text.contains("${") && !text.startsWith("<script>")) {
            String sql = MyBatisMappers.parameters(text);
            access(m, method.id(), sql, a.type(), located(a, method.where()));
          }
          continue;
        }
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
          Evidence.of(
              repo.repo().name(),
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
    // MyBatis XML mappers

    /**
     * {@code READS_TABLE} and {@code WRITES_TABLE} for the statements of a repository's MyBatis XML
     * mappers, from the method of the mapper interface the statement's namespace and id name (the
     * interface itself when no method has that name), evidenced by the statement's lines.
     */
    void mappers(RepoBuild repo) {
      for (MyBatisMappers.Statement st : MyBatisMappers.read(repo)) {
        JavaAnalysis.Module m = code.module(st.namespace());
        if (m == null || !m.repo().equals(repo)) {
          continue;
        }
        // MyBatis runs the statement for every method of that name.
        List<EntityId> subjects = new ArrayList<>();
        for (TypeDecl type : m.structure().types()) {
          if (!type.binaryName().equals(st.namespace())) {
            continue;
          }
          for (Member member : type.members()) {
            if (member.id().type().equals(ModuleStructure.METHOD)
                && member.id().key().startsWith(type.binaryName() + "#" + st.id() + "(")) {
              subjects.add(member.id());
            }
          }
          if (subjects.isEmpty()) {
            subjects.add(typeId(type.binaryName()));
          }
        }
        Evidence evidence =
            Evidence.of(
                repo.repo().name(),
                st.path(),
                st.start() > 0 ? new Evidence.LineRange(st.start(), st.end()) : null,
                st.contentHash());
        List<SqlAccess.Access> accesses = SqlAccess.of(st.sql(), executor);
        for (EntityId subject : subjects) {
          for (SqlAccess.Access a : accesses) {
            EntityId table = tableEntity(m, a.table());
            if (table == null) {
              continue;
            }
            ObjectNode attributes = FactJson.newObject();
            attributes.put("statement", a.statement());
            attributes.put("api", MYBATIS_SESSION);
            add(
                Fact.create(
                    subject,
                    a.write() ? WRITES_TABLE : READS_TABLE,
                    FactObject.entity(table),
                    attributes,
                    ORIGIN,
                    evidence,
                    m.confidence(),
                    validity));
          }
        }
      }
    }

    // -------------------------------------------------------------------------------------------
    // Config

    /**
     * {@code data-store} facts for a module's config: each non-relational store (MongoDB,
     * Elasticsearch, Cassandra, Neo4j, Couchbase) its keys connect it to, in the default view and
     * where a profile differs.
     */
    void stores(ModuleValues config) {
      Map<String, Datasource> defaults = new TreeMap<>();
      for (Datasource d : stores(config, null)) {
        defaults.put(d.key(), d);
        datasourceFact(config, DATA_STORE, d, null);
      }
      for (String profile : config.profiles()) {
        for (Datasource d : stores(config, profile)) {
          Datasource byDefault = defaults.get(d.key());
          if (byDefault == null
              || !byDefault.value().equals(d.value())
              || !byDefault.attributes().equals(d.attributes())) {
            datasourceFact(config, DATA_STORE, d, profile);
          }
        }
      }
    }

    private List<Datasource> stores(ModuleValues config, String profile) {
      Map<String, ConfigValue> relaxed = new TreeMap<>();
      for (ConfigValue v : config.values(profile).values()) {
        relaxed.putIfAbsent(v.key().replace("-", "").replace("_", "").toLowerCase(Locale.ROOT), v);
      }
      List<Datasource> found = new ArrayList<>();
      for (StoreKind kind : STORES) {
        ConfigValue uri = firstOf(relaxed, kind.uris());
        ConfigValue host = firstOf(relaxed, kind.hosts());
        ConfigValue port = firstOf(relaxed, kind.ports());
        ConfigValue database = firstOf(relaxed, kind.databases());
        ConfigValue main =
            uri != null ? uri : host != null ? host : kind.databaseAlone() ? database : null;
        if (main == null || ConfigExtractor.isSecret(main.key(), known)) {
          continue;
        }
        boolean masked = false;
        for (ConfigValue v : new ConfigValue[] {uri, host, port, database}) {
          if (v != null && v.used().stream().anyMatch(k -> ConfigExtractor.isSecret(k, known))) {
            masked = true;
          }
        }
        boolean secret = masked;
        Function<ConfigValue, String> text = v -> (secret ? v.written() : v.value()).strip();
        ObjectNode attributes = FactJson.newObject();
        attributes.put("key", main.key());
        attributes.put("technology", kind.technology());
        String server;
        String name = null;
        String value;
        if (uri != null) {
          // Credentials go first, as a password may hold a comma; then only a list of URIs is
          // split, since one URI may name several hosts (a MongoDB replica set).
          value = UrlCredentials.withoutCredentials(text.apply(uri));
          if (kind.uriList()) {
            value = value.split(",")[0].strip();
          }
          String authority =
              value.contains("://") ? value.substring(value.indexOf("://") + 3) : value;
          int end = authority.length();
          for (char c : new char[] {'/', '?'}) {
            int at = authority.indexOf(c);
            end = at < 0 ? end : Math.min(end, at);
          }
          String rest = authority.substring(end);
          server = authority.substring(0, end).split(",")[0].strip();
          if (kind.technology().equals("MongoDB") && rest.startsWith("/")) {
            int q = rest.indexOf('?');
            name = q < 0 ? rest.substring(1) : rest.substring(1, q);
          }
        } else {
          String first = host == null ? "localhost" : text.apply(host).split(",")[0].strip();
          // A contact point may give its own port.
          server = first + (port == null || first.contains(":") ? "" : ":" + text.apply(port));
          value = kind.scheme() + "://" + server;
        }
        if (database != null && !database.value().isBlank()) {
          name = text.apply(database);
        }
        if (uri == null && name != null) {
          value = value + "/" + name;
        }
        if (!server.isBlank()) {
          attributes.put("server", server);
        }
        if (name != null && !name.isBlank()) {
          attributes.put("database", name);
        }
        if (UrlCredentials.mayHoldCredential(value)
            || value.toLowerCase(Locale.ROOT).contains("authmechanismproperties")) {
          // A credential in a form not understood, such as a token in a MongoDB URI's options.
          value = ConfigExtractor.MASK;
          attributes.remove("server");
          masked = true;
        }
        if (masked) {
          attributes.put("masked", true);
        } else if (!main.unresolved().isEmpty()) {
          ArrayNode unresolved = attributes.putArray("unresolved");
          main.unresolved().forEach(unresolved::add);
        }
        found.add(new Datasource(main.key(), value, attributes, main.where()));
      }
      return found;
    }

    private static ConfigValue firstOf(Map<String, ConfigValue> relaxed, List<String> keys) {
      for (String key : keys) {
        ConfigValue v = relaxed.get(key);
        if (v == null) {
          // A YAML list: its first item.
          v = relaxed.get(key + "[0]");
        }
        if (v != null && !v.value().isBlank()) {
          return v;
        }
      }
      return null;
    }

    /** The physical naming strategy a module's config sets, if any, for its entities' names. */
    void naming(ModuleConfig config) {
      for (ConfigValue v : config.values(null).values()) {
        String key = v.key().replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
        if (NAMING_KEYS.contains(key) && !v.value().isBlank()) {
          namingStrategies.put(config.module(), v.value().strip());
        }
      }
    }

    /**
     * {@code datasource} facts for a module's config: every datasource URL of the default view, and
     * those that differ with a profile active.
     */
    void datasources(ModuleValues config) {
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

    private SortedMap<String, Datasource> datasources(ModuleValues config, String profile) {
      SortedMap<String, ConfigValue> values = config.values(profile);
      SortedMap<String, Datasource> found = new TreeMap<>();
      for (ConfigValue v : values.values()) {
        String lower = v.value().strip().toLowerCase(Locale.ROOT);
        boolean url =
            DATASOURCE_KEYS.contains(v.key())
                || lower.startsWith("jdbc:")
                || lower.startsWith("r2dbc:");
        if (!url || ConfigExtractor.isSecret(v.key(), known)) {
          continue;
        }
        boolean masked = v.used().stream().anyMatch(k -> ConfigExtractor.isSecret(k, known));
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
    private String driver(SortedMap<String, ConfigValue> values, String key) {
      String prefix = key.substring(0, Math.max(0, key.lastIndexOf('.')));
      List<String> candidates = new ArrayList<>();
      candidates.add(prefix + ".driver-class-name");
      candidates.add(prefix + ".driverClassName");
      if (key.startsWith("spring.datasource.")) {
        candidates.add("spring.datasource.driver-class-name");
      }
      for (String candidate : candidates) {
        ConfigValue v = values.get(candidate);
        if (v != null && v.used().stream().noneMatch(k -> ConfigExtractor.isSecret(k, known))) {
          return v.value();
        }
      }
      return null;
    }

    private void datasource(ModuleValues config, Datasource d, String profile) {
      datasourceFact(config, DATASOURCE, d, profile);
    }

    private void datasourceFact(
        ModuleValues config, String predicate, Datasource d, String profile) {
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
          Evidence.of(
              config.repo(),
              where.path(),
              where.line() > 0 ? new Evidence.LineRange(where.line(), where.line()) : null,
              where.contentHash().isEmpty() ? null : where.contentHash());
      add(
          Fact.create(
              config.module(),
              predicate,
              FactObject.string(d.value()),
              attributes,
              origin,
              evidence,
              1.0,
              validity));
    }

    /**
     * The datasource entities of a module's config: one per key that is a datasource or data
     * store's in any view, {@code datasource:<module>:<key>}, connected to the address of its URL
     * as written, read from the values as written: {@code address:<host:port>} for a host written
     * out, or {@code address:<module>:<server as written>} for one config resolves.
     */
    void entities(ModuleValues config) {
      ModuleValues written = asWritten(config);
      List<String> views = new ArrayList<>();
      views.add(null);
      views.addAll(config.profiles());
      Set<String> seen = new HashSet<>();
      for (String view : views) {
        List<Datasource> found = new ArrayList<>(datasources(config, view).values());
        found.addAll(stores(config, view));
        Map<String, Datasource> asWritten = new HashMap<>(datasources(written, view));
        stores(written, view).forEach(d -> asWritten.putIfAbsent(d.key(), d));
        for (Datasource resolved : found) {
          // The same datasource read from the values as written, for its URL and server as
          // written; a URL whose kind only its value tells is taken whole.
          Datasource d = asWritten.get(resolved.key());
          if (d != null && !d.attributes().has("server") && resolved.attributes().has("server")) {
            // A server only the resolved value tells (a URL that is one placeholder): its
            // address is the text as written, the module's own.
            d = null;
          }
          if (d == null) {
            ConfigValue v = config.values(view).get(resolved.key());
            String text =
                UrlCredentials.withoutCredentials(
                    v == null ? resolved.value() : v.written().strip());
            ObjectNode a = FactJson.newObject();
            resolved.attributes().properties().forEach(e -> a.set(e.getKey(), e.getValue()));
            a.remove("server");
            if (resolved.attributes().has("server")) {
              a.put("server", text);
            }
            d =
                new Datasource(
                    resolved.key(),
                    ConfigExtractor.MASK.equals(resolved.value()) ? ConfigExtractor.MASK : text,
                    a,
                    resolved.where());
          }
          EntityId datasource =
              io.lantern.extract.EntityKeys.of(
                  DATASOURCE_ENTITY, config.module().key() + ":" + d.key());
          Evidence evidence = evidence(config, d.where());
          ObjectNode has = FactJson.newObject();
          has.put("key", d.key());
          JsonNode technology = d.attributes().get("technology");
          if (technology != null) {
            has.set("technology", technology);
          }
          if (seen.add(HAS_DATASOURCE + " " + datasource + " " + has))
            add(
                Fact.create(
                    config.module(),
                    HAS_DATASOURCE,
                    FactObject.entity(datasource),
                    has,
                    origin,
                    evidence,
                    1.0,
                    validity));
          JsonNode server = d.attributes().get("server");
          if (server == null || server.stringValue().isBlank()) {
            continue;
          }
          String host = server.stringValue();
          EntityId address =
              io.lantern.extract.EntityKeys.of(
                  "address",
                  host.contains("${")
                      ? config.module().key() + ":" + host
                      : host.toLowerCase(Locale.ROOT));
          ObjectNode connects = FactJson.newObject();
          if (!d.value().equals(ConfigExtractor.MASK)) {
            connects.put("url", d.value());
          }
          if (seen.add(CONNECTS_TO + " " + datasource + " " + address + " " + connects))
            add(
                Fact.create(
                    datasource,
                    CONNECTS_TO,
                    FactObject.entity(address),
                    connects,
                    origin,
                    evidence,
                    1.0,
                    validity));
        }
      }
    }

    private static Evidence evidence(ModuleValues config, ConfigValue.Where where) {
      return Evidence.of(
          config.repo(),
          where.path(),
          where.line() > 0 ? new Evidence.LineRange(where.line(), where.line()) : null,
          where.contentHash().isEmpty() ? null : where.contentHash());
    }

    /** A module's config with each value as written: placeholders kept, nothing resolved. */
    private static ModuleValues asWritten(ModuleValues config) {
      return new ModuleValues() {
        @Override
        public EntityId module() {
          return config.module();
        }

        @Override
        public String repo() {
          return config.repo();
        }

        @Override
        public List<String> profiles() {
          return config.profiles();
        }

        @Override
        public SortedMap<String, ConfigValue> values(String view) {
          SortedMap<String, ConfigValue> written = new TreeMap<>();
          config
              .values(view)
              .forEach(
                  (key, v) ->
                      written.put(
                          key,
                          new ConfigValue(
                              v.key(),
                              v.written(),
                              v.written(),
                              new java.util.TreeSet<>(),
                              v.used(),
                              v.where())));
          return written;
        }
      };
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
   * lower-cased. The strategy changes a quoted name ({@code "Name"} or {@code `Name`}) the same way
   * and keeps it quoted, so {@code "OrderItems"} is {@code "order_items"}.
   */
  static String jpaName(String logical) {
    String name = logical.strip();
    boolean quoted =
        name.length() > 1
            && (name.startsWith("\"") && name.endsWith("\"")
                || name.startsWith("`") && name.endsWith("`"));
    if (quoted) {
      name = name.substring(1, name.length() - 1);
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
    String physical = b.toString().toLowerCase(Locale.ROOT);
    return quoted ? "\"" + physical + "\"" : physical;
  }

  /** Where Spring Data's {@code ParsingUtils} splits a name in camel case. */
  private static final Pattern CAMEL_CASE =
      Pattern.compile("(?<!(^|[\\p{Lu}_$]))(?=\\p{Lu})|(?<!^)(?=\\p{Lu}\\p{Ll})");

  /**
   * A class name in snake case, as Spring Data relational derives a table name ({@code
   * ParsingUtils.reconcatenateCamelCase}): split before an upper-case letter that follows another
   * character than an upper-case letter, an underscore or {@code $}, and before an upper-case
   * letter followed by a lower-case one; parts lower-cased and joined with underscores, so {@code
   * URLMapping} is {@code url_mapping}.
   */
  static String snakeCase(String name) {
    List<String> parts = new ArrayList<>();
    for (String part : CAMEL_CASE.split(name)) {
      if (!part.isEmpty()) {
        parts.add(part.toLowerCase(Locale.ROOT));
      }
    }
    return String.join("_", parts);
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

  /**
   * The datasources and data stores linking gives from each module's config (ADR 0094, #560): the
   * {@value #DATASOURCE} and {@value #DATA_STORE} facts this extractor recorded before it stopped
   * reading config, the default view's and each differing profile's; and for each, a {@value
   * #DATASOURCE_ENTITY} entity its module {@value #HAS_DATASOURCE}, which {@value #CONNECTS_TO} the
   * address of its URL as written.
   *
   * @param configs the config of each module
   * @param secretKeys config keys known to hold a secret
   * @param origin the origin of the facts
   * @param snapshot the snapshot number the facts hold from
   */
  public static List<Fact> datasources(
      List<ModuleValues> configs, Set<String> secretKeys, Origin origin, long snapshot) {
    Run run = new Run(null, Validity.from(snapshot), ConfigExtractor.normalizedKeys(secretKeys));
    run.origin = origin;
    for (ModuleValues config : configs) {
      run.datasources(config);
      run.stores(config);
      run.entities(config);
    }
    return run.facts.values().stream()
        .sorted(
            Comparator.comparing((Fact f) -> f.subject().toString())
                .thenComparing(Fact::predicate)
                .thenComparing(f -> f.object().value())
                .thenComparing(f -> f.attributes().toString())
                .thenComparing(Fact::id))
        .toList();
  }

  @Override
  public ExtractorInfo info() {
    return new ExtractorInfo(NAME, VERSION);
  }

  @Override
  public CacheUnit unit() {
    return CacheUnit.SNAPSHOT;
  }

  @Override
  public void extract(ExtractionContext context, ExtractionUnit unit, ClaimSink sink) {
    sink.emitAll(extract(context.code(), context.snapshot()));
  }
}

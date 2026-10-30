# 0036: Persistence extractor

Status: accepted (#130, part of #92)

## Context

`docs/design.md` (Static analysis and extractors) says the Persistence extractor reads JPA
entities, repositories and JDBC templates and produces entities, tables touched and datasource
bindings. The linker matches shared databases by datasource URLs and table names (Cross-repo
linking), and the container view draws data stores and "Reads/writes · JDBC" relationships with
the database's technology (report spec `c4-model-sequence-diagrams`), which the deployable-unit,
linker and view issues (#93 to #95) build from these facts. The SQL extractor (ADR 0034) already
names tables. The design leaves open how the code is read, how tables are named and keyed, which
SQL counts, and how a datasource URL is recorded without its credentials.

## Decisions

- **One reading of the code.** `PersistenceExtractor` (`lantern-extract`) takes the
  `JavaAnalysis` the Java structure and Spring extractors use (ADR 0035), with the same confidence
  (1.0 compiled, 0.7 parsed), and the config through `ConfigExtractor.read` (ADR 0032). Both
  analyzers now also record, without changing the Java extractor's facts: the type arguments of
  each declared supertype (`JpaRepository<Order, Long>`), and for each call the `String` constants
  passed to the callee's `String` parameters (and a last `String[]`, for varargs), folded as javac
  folds constants: literals, text blocks, `+`, and `final` variables and fields with constant
  initializers. A local variable that is not `final`, or SQL built at run time, is not a constant
  and gives nothing. The parser now folds only final variables, as javac does. JPA and Spring Data
  annotations join the well-known list so wildcard imports name them in degraded mode.
- **Tables reuse the SQL extractor's `table` type and key**, `table:<groupId>:<artifactId>:<name>`,
  keyed by the module whose code names the table (an entity's, or the code running the SQL), with
  names folded as the SQL extractor folds them (`SqlExtractor.tableName`: unquoted lower case,
  quoted kept). So a JPA entity in a library and the migrations in the service that packages it
  name the same table under two modules; matching them by name, and code to the datasource of the
  deployable that runs it, is the linker's (#94), as ADR 0034 says.
- **Entities.** `type MAPS_TO table` for a class with `@Entity` (Jakarta or `javax`), with
  `framework: jpa`. The table is `@Table(name)`, else the entity name, else the simple class name,
  with `@Table(schema)` as a qualifier, named as Spring Boot's default physical naming strategy
  (Hibernate's `CamelCaseToUnderscoresNamingStrategy`) does: `CustomerOrder` is `customer_order`,
  and explicit names too; a quoted name is changed the same way and stays quoted (`"OrderItems"` is `order_items`). A subclass without its own `@Table` under
  single-table inheritance (the default) maps to its root entity's table, with `inheritedFrom`.
  A Spring Data relational `@Table` class (Spring Data JDBC, R2DBC) maps too, with `framework:
  spring-data-relational`: its given name as written (Spring Data quotes it), else the class name
  in snake case. A configured `spring.jpa.hibernate.naming.physical-strategy` is not read.
- **Repositories.** `type REPOSITORY_FOR type` for an interface extending a Spring Data repository
  interface (`Repository`, `CrudRepository`, `JpaRepository`, `ListCrudRepository`, the reactive,
  R2DBC, MongoDB, Elasticsearch, Cassandra and Neo4j ones), directly or through interfaces of the
  analyzed code, or with `@RepositoryDefinition`; `base` names the Spring Data interface. The
  object is the domain type: the base interface's first type argument, with the type variables of
  the interfaces in between replaced, by position, by the arguments each subinterface gives them
  (`OrderRepository extends BaseRepository<Long, Order>` with `BaseRepository<ID, T extends
  BaseEntity> extends JpaRepository<T, ID>` gives `Order`). To do this the analyzers also record,
  for each supertype argument, which of the declaring type's own type parameters it is. `@NoRepositoryBean` interfaces are left out. Which tables a repository touches follows
  from its entity's `MAPS_TO`; it is not repeated.
- **SQL in code.** `READS_TABLE` and `WRITES_TABLE` from the calling method (or type, for code
  outside methods) to each table the SQL names, with `statement` (`select`, `insert`, `update`,
  `delete`, `merge`, `upsert`, `truncate`) and `api` (the type whose method takes the SQL). SQL
  is the constant text passed to `JdbcTemplate`, `JdbcOperations`,
  `NamedParameterJdbcTemplate`/`Operations`, `JdbcClient.sql` and R2DBC's `DatabaseClient.sql`
  query, update, batch and execute methods; `SimpleJdbcInsert.withTableName` writes its table; and
  a repository method's `@Query` is SQL when it is Spring Data JDBC's or R2DBC's, or JPA's with
  `nativeQuery = true` (JPQL names entities, not tables, and is not read). JSqlParser (as the SQL
  extractor uses it, 10 s a text) reads the text: the target of an insert, update, delete, merge,
  upsert or truncate is written, every other table it names (joins, subqueries, `INSERT ...
  SELECT` sources) is read; common table expressions are not tables. DDL, procedure calls and
  text the parser cannot read give nothing. Calls into Spring JDBC resolve only with the build's
  classpath, so in degraded mode without one (no build, or one that failed before resolving
  dependencies) SQL in code is not found; entities, repositories and datasources are.
- **Datasources.** A `datasource` attribute on the module (`module:<groupId>:<artifactId>`), the
  object the URL without credentials, for each config key whose value is a JDBC or R2DBC URL, and
  for `spring.datasource.url`, `spring.datasource.jdbc-url`, `spring.datasource.hikari.jdbc-url`,
  `spring.r2dbc.url`, `spring.flyway.url` and `spring.liquibase.url` whatever their value. Like
  config values, the default view gives every one, a profile only those that differ, marked
  `profile`; `written` and `unresolved` as in ADR 0032. `technology` is the database the
  subprotocol names (`PostgreSQL`, `MySQL`, `Oracle`, `SQL Server`, `H2`, ...; through
  Testcontainers' `jdbc:tc:` too), else the one the driver class configured next to it names;
  `server` and `database` are read where the URL's form gives them (`//host:port/db`, SQL Server's
  `databaseName`, Oracle's service or SID, H2 and HSQLDB names). The database version is not in a
  URL and is left out.
- **Credentials never reach a fact.** Usernames and passwords are not recorded. A URL, and its
  `written` form, loses any user info before the host (`//user:password@`; the rules for where it
  ends, with a password holding `/ ? # ; @`, are in ADR 0032's amendment for #137), Oracle's
  `thin:user/password@` (a quoted password may hold `@`), and
  every property naming a user (`user`, `username`, ...) or a secret (`password`, `sslpassword`,
  `sig`, `key`, ...; the list is in ADR 0032's amendment), including in URLs
  inside placeholder defaults (`${DB_URL:jdbc:...}`). Placeholders are read whole, nested braces
  included, so their contents (`${DB_PASSWORD:changeme}` in the password, `${DB_HOST}` before
  `;user=sa`) do not end the URL early, and one in a removed part goes with it. This lives in
  `UrlCredentials` in the config package, shared with the config extractor (#137). A URL that a
  secret was substituted into is
  recorded as written, placeholders kept, credentials removed, with `masked: true`. One that still
  looks like it holds a credential (`password=` in a form not understood) is recorded as `******`,
  masked. The config extractor's config-value facts lose credentials in URLs the same way (ADR
  0032, amendment for #137).
- **New vocabulary**, applied by the app on startup after the SQL extractor's (it uses `table`),
  with source `persistence-extractor`: the attribute type `datasource` (string) and the
  relationship types `MAPS_TO`, `REPOSITORY_FOR`, `READS_TABLE` and `WRITES_TABLE`. The last two
  have no parent: a child of the built-in `DEPENDS_ON`, which refuses attributes, could not have
  any.
- **Not covered:** JPQL and Criteria queries, `EntityManager` native queries, join and collection
  tables, columns, `@SecondaryTable`, SQL in a non-final local variable or built at run time, SQL
  reached through a `@Value` (one hop), MyBatis and jOOQ, XML mappings, and datasources defined in
  code (`DataSourceBuilder`).
- **project-0.** `Project0PersistenceIT` checks DB-1 (entities and repositories, both ways), DB-2
  (`SettlementJob`, through javac) and DB-5 (the shared and the own databases, with storefront's
  `local` profile), with the repositories built in the sandbox and read through javac, and parsed
  without builds. It runs when `lantern.test.project0` is set, with the same command as
  `Project0BuildIT` (see Commands in `CLAUDE.md`) but `-pl lantern-extract` and
  `-Dit.test=Project0PersistenceIT`.

## Amendment (#387): non-relational stores, documents, MyBatis and the configured naming

Research: `docs/research/extractor-persistence.md`. A service on MongoDB, Elasticsearch, Cassandra,
Neo4j or Couchbase had no store fact; documents had no collection; MyBatis, a common way to reach
a relational database, was not read; and a configured physical naming strategy was ignored. The
extractor's version becomes 4.

- **`data-store`**, an attribute of the module like `datasource`, for each non-relational store its
  config connects it to, in the default view and where a profile differs: MongoDB
  (`spring.data.mongodb.*` and Boot 4's `spring.mongodb.*`: `uri`, else `host`, `port` and
  `database`), Elasticsearch (`spring.elasticsearch.uris`, the first one, and older keys), Cassandra
  (`contact-points`, `port`, `keyspace-name`), Neo4j (`uri`, `database`) and Couchbase
  (`connection-string`, bucket), read in any relaxed form, a YAML list by its first item. The
  value is the URI without credentials (`UrlCredentials`, before anything is split, as a password
  may hold a comma; only a key that lists several URIs, such as Elasticsearch's, is split), or one
  made of the host (with its own port, else the port key) and database; a MongoDB or Cassandra
  database key alone connects to `localhost`, as Spring Boot does, a Neo4j or Couchbase one does
  not. `technology`, `server` (the first host) and `database` (MongoDB's from the URI's path unless
  a `database` key sets it, which Spring Boot prefers) are attributes. A URI that still looks like
  it holds a credential (`password=`, a token in `authMechanismProperties`) is recorded as
  `******`, masked. A store set only by a bean of the code's own is not read. Drawing these stores is the linker's
  (#466).
- **Documents.** A MongoDB `@Document` maps to its collection (`collection` or `value`, else the
  simple class name with its first letter in lower case) and an Elasticsearch `@Document` to its
  index (`indexName`), as `MAPS_TO` to a `table` entity of the module, with `framework` `mongodb`
  or `elasticsearch`, names kept as written. A name with `#{` or `${` is made at run time and is
  not recorded.
- **MyBatis.** `@Select`, `@Insert`, `@Update` and `@Delete` on a method are SQL like a native
  `@Query` (the strings of `value` joined with spaces, `#{...}` as `?`); one with `${...}` or a
  `<script>` is not read. The statements of XML mappers (`<mapper namespace>` files anywhere in the
  repository's sources) are read from the method of the namespace's interface whose name is the
  statement's id (the interface when none), evidenced by the statement's lines, with `api`
  `org.apache.ibatis.session.SqlSession`, on every overload of that name, from the line its
  start tag opens on. A statement is read as it would run if every condition
  held: `<include>` replaced by the mapper's `<sql>` fragment (named with or without the
  namespace) with the include's `<property>` values in its `${...}`, `<where>` and `<set>` with their
  keyword and without a leading `AND`/`OR`, or a leading or trailing comma, `<trim>` with its prefix, suffix and
  overrides, `<foreach>` once between `open` and `close`, and `<choose>` by its first branch. A
  statement with `${...}`, or an include of a fragment not in the mapper, is not read.
- **The configured naming.** When a module's config sets
  `spring.jpa.hibernate.naming.physical-strategy` (or Hibernate's own key) to another strategy than
  Spring Boot's default, its entities' names are kept as written; for a strategy of the code's own,
  `namingStrategy` names it. Spring Data relational names split as Spring Data's `ParsingUtils`
  does, so an acronym stays one part (`URLMapping` is `url_mapping`).
- Still not read: binding code to one of several datasources (#463); join, collection and
  secondary tables, Cassandra and Neo4j mappings, and naming by Hibernate version (#464; the split
  rule today follows Hibernate 7 and the treatment of quoted names Hibernate 6); reads and writes
  from the calls made, JPQL, `MongoTemplate` and jOOQ (#465).

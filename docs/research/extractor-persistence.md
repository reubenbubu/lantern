# How Java services reach their data stores

Research for #387 (Review the Persistence extractor), milestone "Extractor revisit". It asks what in Java (mostly Spring Boot) code and config names the store a service uses, the tables or collections each piece of code touches, and whether it reads or writes them. It leads to an amendment of `docs/adr/0036-persistence-extractor.md` and the Persistence row of `docs/design.md` (Static analysis and extractors).

**Depth: high.** It decides which service is drawn to which store, the main data lines of the container view. Read in full:

- 20 official Spring, MyBatis and jOOQ pages;
- 7 Jakarta Persistence and Spring Framework Javadoc pages;
- 3 source files of Hibernate and Spring Boot. I fetched Hibernate's 6.6 and 7 naming strategies again to check the rule;
- 3 papers;
- files of 2 open-source projects, raw from GitHub.

The research is generic: it does not read Lantern's test sets.

## Question

How do Java services reach their data stores? JPA and Hibernate, Spring Data JPA, JDBC and R2DBC, `JdbcTemplate` and `JdbcClient`, jOOQ, MyBatis, MongoDB, Elasticsearch, Cassandra and Neo4j. For each:

- What names the store and its kind?
- What names the table or collection each piece of code touches?
- How is code bound to a store when there are several?
- How are reads told from writes?
- What do recovery tools do here?

## What we learned

### The store, in config

- **Relational stores, JDBC.**
  - The URL is `spring.datasource.url`, with the driver taken from the URL.
  - With no URL and H2, HSQL or Derby on the classpath, Boot starts an embedded database.
  - A `DataSource` bean of the code's own turns the auto-configuration off.
  - https://docs.spring.io/spring-boot/reference/data/sql.html
- **Relational stores, R2DBC.** `spring.r2dbc.url` is the URL. https://docs.spring.io/spring-boot/reference/data/sql.html
- **Custom prefixes.**
  - A source bound with `@ConfigurationProperties("app.datasource")` puts its URL under any prefix, as `jdbc-url` for Hikari.
  - A second source is a qualified bean.
  - https://docs.spring.io/spring-boot/how-to/data-access.html
- **A routing datasource** (`AbstractRoutingDataSource`) picks one of several targets per call, at run time. https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/jdbc/datasource/lookup/AbstractRoutingDataSource.html
- **Flyway and Liquibase** get their own connection when `spring.flyway.url` or `spring.liquibase.url` is set. https://docs.spring.io/spring-boot/how-to/data-initialization.html
- **Non-relational stores have keys of their own** (https://docs.spring.io/spring-boot/reference/data/nosql.html):
  - **MongoDB:** `spring.mongodb.uri`, or `host`, `port` and `database`, in Boot 4. Boot 3 uses `spring.data.mongodb.*`, as real projects still do. A `MongoClient` bean, or `getDatabaseName()` in `AbstractMongoClientConfiguration`, overrides the keys. https://docs.spring.io/spring-data/mongodb/reference/mongodb/mapping/mapping.html
  - **Elasticsearch:** `spring.elasticsearch.uris`.
  - **Cassandra:** `spring.cassandra.keyspace-name` and `contact-points`.
  - **Neo4j:** `spring.neo4j.uri`.
  - **Couchbase:** `spring.couchbase.connection-string`, with its bucket under `spring.data.couchbase.bucket-name`.

### Binding code to a store when there are several

- **JPA.** `@EnableJpaRepositories(basePackages, entityManagerFactoryRef)` scans the config class's package by default. The entity manager factory names its packages and its `DataSource`. https://docs.spring.io/spring-data/jpa/docs/current/api/org/springframework/data/jpa/repository/config/EnableJpaRepositories.html, https://docs.spring.io/spring-boot/how-to/data-access.html
- **MongoDB.** `@EnableMongoRepositories(mongoTemplateRef)`. https://docs.spring.io/spring-data/mongodb/docs/current/api/org/springframework/data/mongodb/repository/config/EnableMongoRepositories.html
- **MyBatis.** `@MapperScan(sqlSessionFactoryRef)`. Its documentation warns that autowiring "may not work" with several datasources. https://mybatis.org/spring/mappers.html
- **Several Spring Data modules.** A repository belongs to a module by its base interface, or by its domain type's annotation (`@Entity`, `@Document`). https://docs.spring.io/spring-data/commons/reference/repositories/definition.html
- **A real example.** Spring's `jpa/multiple-datasources` sample builds two entity manager factories and two embedded databases in code, with no URL in config. https://github.com/spring-projects/spring-data-examples/tree/main/jpa/multiple-datasources

### Table and collection names

- **JPA defaults.** Without `@Table`, the table is the entity name: `@Entity(name)`, else the simple class name. https://jakarta.ee/specifications/platform/10/apidocs/jakarta/persistence/Table.html
- **Spring Boot's physical strategy.**
  - Boot sets Hibernate's `CamelCaseToUnderscoresNamingStrategy`, unless `spring.jpa.hibernate.naming.physical-strategy` names another.
  - `PhysicalNamingStrategyStandardImpl` keeps names as written.
  - https://docs.spring.io/spring-boot/how-to/data-access.html; Boot's `HibernateProperties`
- **The rule differs between Hibernate versions.**
  - Hibernate 6 puts an underscore only between a lower-case letter, an upper-case one and a lower-case one, and lower-cases quoted names too.
  - Hibernate 7 deprecates that class for `PhysicalNamingStrategySnakeCaseImpl`. It also splits at digits and keeps quoted names.
  - https://raw.githubusercontent.com/hibernate/hibernate-orm/6.6/hibernate-core/src/main/java/org/hibernate/boot/model/naming/CamelCaseToUnderscoresNamingStrategy.java, https://raw.githubusercontent.com/hibernate/hibernate-orm/main/hibernate-core/src/main/java/org/hibernate/boot/model/naming/PhysicalNamingStrategySnakeCaseImpl.java
- **More tables per entity.**
  - Join tables: JPA joins the two entity tables. Boot's implicit strategy makes `<owning table>_<property>`.
  - `@ElementCollection`, `@SecondaryTable`, and the `JOINED` and `TABLE_PER_CLASS` inheritance strategies add tables. `SINGLE_TABLE`, the default, adds none.
  - https://jakarta.ee/specifications/platform/10/apidocs/jakarta/persistence/JoinTable.html, CollectionTable.html, SecondaryTable.html, InheritanceType.html; Boot's `SpringImplicitNamingStrategy`
- **Spring Data JDBC** derives table names with `ParsingUtils.reconcatenateCamelCase`, which keeps an acronym as one part (`URLMapping` is `url_mapping`). It uses a `@Table` value as given. https://docs.spring.io/spring-data/relational/reference/jdbc/mapping.html; Spring Data Commons' `ParsingUtils`
- **MongoDB.**
  - `@Document(collection | value)` names the collection, which may be a SpEL expression.
  - Without a name, the collection is the simple class name with a lower-case first letter.
  - `MongoTemplate` takes a class or a collection name.
  - https://docs.spring.io/spring-data/mongodb/reference/mongodb/mapping/mapping.html, https://docs.spring.io/spring-data/mongodb/docs/current/api/org/springframework/data/mongodb/core/mapping/Document.html, https://docs.spring.io/spring-data/mongodb/reference/mongodb/template-collection-management.html
- **Elasticsearch.** `@Document(indexName)` names the index.
- **MyBatis.**
  - SQL sits in `@Select`, `@Insert`, `@Update` and `@Delete`. MyBatis joins the strings of their value with spaces.
  - Or it sits in XML mappers: `<select|insert|update|delete id>` under `<mapper namespace>`, the namespace naming the interface and the id its method.
  - Statements use dynamic elements (`<where>`, `<set>`, `<trim>`, `<foreach>`, `<choose>`), `<include refid>` of `<sql>` fragments, `#{}` parameters, and `${}` raw text, which can name a table at run time.
  - https://mybatis.org/mybatis-3/sqlmap-xml.html, https://mybatis.org/spring-boot-starter/mybatis-spring-boot-autoconfigure/
- **jOOQ.** Code generation names the tables, and render mapping can rename schemas and tables at run time. https://www.jooq.org/doc/latest/manual/code-generation/codegen-configuration/, https://www.jooq.org/doc/latest/manual/sql-building/dsl-context/custom-settings/settings-render-mapping/

### Reads and writes

- **Spring Data repositories.** A method's subject (`find…By`, `count…By`, `exists…By`, `delete…By`) and `@Modifying` give its verb. A repository interface offers every verb, so only the calls made show what a service does. https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html, https://docs.spring.io/spring-data/commons/reference/repositories/query-methods-details.html
- **MyBatis.** The element or annotation names the verb.
- **Generated mappers.** A shared mapper offers every statement, so only its call sites show what a service does.

### Real systems

- **macrozheng/mall** uses MyBatis with generated and hand-written XML mappers, MongoDB through `spring.data.mongodb.database` and an unnamed `@Document`, and Elasticsearch through `@Document(indexName)` and `spring.elasticsearch.uris`. Several services point at one MySQL database, and only through profile files. https://github.com/macrozheng/mall

### Prior art

- **Code2DFD** takes databases from build files, compose images and config. Its errors:
  - a database missed;
  - one database drawn twice under two names;
  - flows in the wrong direction.
  - https://arxiv.org/pdf/2304.12769
- **The 2025 comparison of recovery tools** finds deployment files precise, but recall needs code analysis. https://arxiv.org/html/2412.08352v1
- **Meurice et al.** rebuild query strings across calls for JDBC, Hibernate and JPA. They recover 71.5–99% of run-time queries; strings built with `StringBuilder` are the main miss. https://loupmeurice.github.io/publications/CAISE2016.pdf
- **Which access is most common.** JPA with Spring Data is the most common, `JdbcTemplate` serves smaller needs, and MyBatis is widely used, especially in China. https://vladmihalcea.com/java-data-access-technology/

## Pitfalls for static analysis

1. **A store's URL is often only in a profile file**, so a store is better told by kind and database than by host.
2. **Custom prefixes, and datasources built in code**, hide the URL.
3. **Non-relational stores sit under prefixes that moved between versions**: `spring.data.mongodb` and `spring.mongodb`.
4. **Several datasources are bound through bean names**, and a routing datasource cannot be resolved statically.
5. **Naming depends on the configured strategy and on the Hibernate version.**
6. **Hidden tables**: inheritance, join, collection and secondary tables.
7. **Names made at run time** (`${}` in MyBatis, SpEL, string-built SQL) should be left unresolved, not guessed.
8. **Reads and writes need the calls made, not the interfaces offered.**

## What the extractor reads, and misses

ADR 0036's extractor reads:

- JPA entities and Spring Data relational `@Table` classes;
- Spring Data repositories of every store, with their domain types;
- constant SQL in `JdbcTemplate`, `JdbcClient` and `DatabaseClient` calls, and in native `@Query`;
- JDBC and R2DBC datasource URLs in config, per profile, without credentials.

It misses:

1. **Non-relational stores in config**: a MongoDB, Elasticsearch, Cassandra, Neo4j or Couchbase service shows no store.
2. **MongoDB and Elasticsearch documents' collections and indexes.**
3. **MyBatis**, in annotations and XML mappers.
4. **A configured physical naming strategy.** Also a split rule that mixes Hibernate 6 and 7, and Spring Data's acronym rule.
5. **Binding code to one of several datasources.**
6. **Join, collection and secondary tables.**
7. **Reads and writes from the calls made**, JPQL, `MongoTemplate`, and jOOQ.

## Options

- **A. Read non-relational stores from config.** Cheap and precise; drawing them is the linker's.
- **B. Read MongoDB and Elasticsearch documents as `MAPS_TO`.** This keeps the existing fact and its `table` entity, with a `framework`.
- **C. Read MyBatis annotations and XML mappers**, through the SQL reading the extractor already has.
- **D. Honour the configured naming strategy and fix Spring Data's acronym rule.**
- **E. Bind code to one of several datasources.** Precise but long chains of bean names.
- **F. More tables per entity, and naming by Hibernate version.**
- **G. Reads and writes from call sites**, and JPQL, `MongoTemplate` and jOOQ.

## Recommendation

**Now, inside the extractor: A to D.** They are common, and A to C decide whether a service is drawn to its store at all. Each later option becomes an issue, with cases first:

- **E:** #463
- **F:** #464
- **G:** #465
- **Drawing non-relational stores in the linker:** #466

## What it changes in the bar and the design

- **Design, Static analysis and extractors:** the Persistence row names non-relational stores, documents, MyBatis and the configured naming strategy. ADR 0036 is amended.
- **Quality bar:** nothing new. A store is already drawn from its evidence, and names made at run time stay unnamed.

## Sources read in full

- Spring Boot: SQL databases, NoSQL, data access how-to, database initialization: https://docs.spring.io/spring-boot/reference/data/sql.html, https://docs.spring.io/spring-boot/reference/data/nosql.html, https://docs.spring.io/spring-boot/how-to/data-access.html, https://docs.spring.io/spring-boot/how-to/data-initialization.html
- Spring Data: JPA query methods; commons repository definition, query methods and custom implementations; `@EnableJpaRepositories`; `@EnableMongoRepositories`; MongoDB mapping, `@Document` and template collection management; JDBC mapping: https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html, https://docs.spring.io/spring-data/commons/reference/repositories/definition.html, https://docs.spring.io/spring-data/commons/reference/repositories/query-methods-details.html, https://docs.spring.io/spring-data/commons/reference/repositories/custom-implementations.html, https://docs.spring.io/spring-data/jpa/docs/current/api/org/springframework/data/jpa/repository/config/EnableJpaRepositories.html, https://docs.spring.io/spring-data/mongodb/docs/current/api/org/springframework/data/mongodb/repository/config/EnableMongoRepositories.html, https://docs.spring.io/spring-data/mongodb/reference/mongodb/mapping/mapping.html, https://docs.spring.io/spring-data/mongodb/docs/current/api/org/springframework/data/mongodb/core/mapping/Document.html, https://docs.spring.io/spring-data/mongodb/reference/mongodb/template-collection-management.html, https://docs.spring.io/spring-data/relational/reference/jdbc/mapping.html
- Spring Framework, `AbstractRoutingDataSource`: https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/jdbc/datasource/lookup/AbstractRoutingDataSource.html
- Jakarta Persistence Javadoc: `Table`, `JoinTable`, `CollectionTable`, `SecondaryTable`, `Inheritance`, `InheritanceType`: https://jakarta.ee/specifications/platform/10/apidocs/jakarta/persistence/Table.html (and the same folder)
- MyBatis: mappers, factory bean, Spring Boot starter, XML mapper files: https://mybatis.org/spring/mappers.html, https://mybatis.org/spring/factorybean.html, https://mybatis.org/spring-boot-starter/mybatis-spring-boot-autoconfigure/, https://mybatis.org/mybatis-3/sqlmap-xml.html
- jOOQ: code generation configuration, render mapping: https://www.jooq.org/doc/latest/manual/code-generation/codegen-configuration/, https://www.jooq.org/doc/latest/manual/sql-building/dsl-context/custom-settings/settings-render-mapping/
- Source: Hibernate `CamelCaseToUnderscoresNamingStrategy` (6.6) and `PhysicalNamingStrategySnakeCaseImpl` (main); Spring Boot `SpringImplicitNamingStrategy` and `HibernateProperties` (3.5.x); Spring Data Commons `ParsingUtils` and Spring Data Relational `NamingStrategy` (main)
- Papers: Meurice, Nagy and Cleve, Static Analysis of Dynamic Database Usage in Java Systems, CAiSE 2016; Schneider et al., Comparison of Static Analysis Architecture Recovery Tools for Microservice Applications, EMSE 2025; Schneider and Scandariato, Code2DFD, JSS 2023 (approach, evaluation, discussion and threats)
- Open-source code, raw from GitHub: spring-projects/spring-data-examples `jpa/multiple-datasources`; macrozheng/mall (`mall-portal` config, `MyBatisConfig`, `MemberReadHistory` and its repository, `PortalOrderDao` and its XML; `mall-search` `EsProduct`, its repository and config)
- Vlad Mihalcea, Java data access technology (qualitative only): https://vladmihalcea.com/java-data-access-technology/

## Sources not read (carry no weight)

- The Hibernate ORM 6.6 user guide beyond its naming, schema and `@Subselect` parts; Nagy, Lanza and Cleve's 2023 chapter (searched only); the Spring Boot 4.0 migration guide and the Spring Data query keyword appendix (search results only); Spring Data Cassandra and Neo4j mapping pages; Code2DFD's database extractors; survey articles found by search.

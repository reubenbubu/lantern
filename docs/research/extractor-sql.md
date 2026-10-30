# Where a Java service's schema comes from

Research for #386 (Review the SQL extractor), milestone "Extractor revisit". It asks which schema sources Java (mostly Spring Boot) systems use, and what a static reader must do with each to get the tables a service's database really has. It leads to an amendment of `docs/adr/0034-sql-extractor.md` and the SQL row of `docs/design.md` (Static analysis and extractors).

**Depth: high.** The tables drawn in each service's database come from here. Read in full, 29 sources:

- 8 Flyway documentation pages;
- 2 Spring Boot pages;
- 8 Liquibase pages;
- 3 JSqlParser pages or issues;
- 2 tool pages (jOOQ, Atlas);
- 2 container pages (the postgres image, Testcontainers);
- 1 paper;
- files of 3 open-source Spring Boot projects, raw from GitHub.

The research is generic: it does not read Lantern's test sets.

## Question

Which schema sources do Java (mostly Spring Boot) systems use? For each one, what does it say about:
- (a) which database or schema a table belongs to, and which service owns it;
- (b) the final state of tables, columns and keys after a sequence of migrations;
- (c) vendor dialects, and SQL that common parsers fail on?

What do schema-evolution and architecture-recovery tools and papers do with migrations, and what are their pitfalls?

## What we learned

### Spring Boot: one mechanism per app, chosen by configuration
- Boot advises using one schema mechanism: Hibernate `ddl-auto`, `schema.sql`/`data.sql`, or Flyway/Liquibase. It warns that combining Flyway or Liquibase with `schema.sql`/`data.sql` "is not recommended" and that support will be removed. https://docs.spring.io/spring-boot/how-to/data-initialization.html
- **Hibernate DDL.** `spring.jpa.hibernate.ddl-auto` takes `none|validate|update|create|create-drop`. It defaults to `create-drop` only for an embedded database (H2, HSQLDB, Derby) when neither Flyway nor Liquibase is present, and to `none` otherwise. `import.sql` runs only when Hibernate creates the schema. (same URL; also https://docs.spring.io/spring-boot/reference/data/sql.html)
- **SQL scripts.** The defaults are `optional:classpath*:schema.sql` and `data.sql`, plus `schema-${platform}.sql`, where `${platform}` is `spring.sql.init.platform`. They are configured with `spring.sql.init.schema-locations`, `data-locations` and `mode`. The default mode initializes embedded databases only; `always` initializes any database. The same scripts serve an R2DBC `ConnectionFactory`. (how-to URL)
- **Flyway in Boot.**
  - Scripts are named `V<VERSION>__<NAME>.sql`. The default location is `classpath:db/migration`. `spring.flyway.locations` takes `classpath:` and `filesystem:` entries and supports `{vendor}` (for example `db/migration/{vendor}` resolves to `db/migration/mysql`).
  - Profile-specific locations make some migrations run only under one profile.
  - Test-only migrations go in `src/test/resources/db/migration`.
  - `JavaMigration` beans are registered automatically. SQL callbacks live in `db/migration`.
  - Flyway uses the `@Primary` DataSource, unless a bean is marked `@FlywayDataSource` or `spring.flyway.url`/`user` are set. (how-to URL)
- **Liquibase in Boot.**
  - The default change log is `db/changelog/db.changelog-master.yaml`, changed with `spring.liquibase.change-log`. YAML, JSON, XML and SQL are all accepted.
  - The DataSource is chosen the same way (`@LiquibaseDataSource`, `spring.liquibase.url`).
  - Boot's own example uses `context:@test` changesets for test data. (how-to URL)

### Flyway
- **Migration types.**
  - Versioned (V) migrations run once, in version order, and "versions are sorted numerically".
  - Undo (U) migrations reverse the same version.
  - Repeatable (R) migrations run after the versioned ones, "in the order of their description", and again whenever their content changes.
  - Applied migrations are recorded in `flyway_schema_history`.
  - https://documentation.red-gate.com/fd/migrations-271585107.html and https://documentation.red-gate.com/fd/versioned-migrations-273973333.html
- **Naming defaults.** `sqlMigrationPrefix=V`, `undoSqlMigrationPrefix=U`, `repeatableSqlMigrationPrefix=R`, `sqlMigrationSeparator=__`, `sqlMigrationSuffixes=.sql` (more can be added, for example `.pkg`). https://raw.githubusercontent.com/flyway/flywaydb.org/a157b970b15051d6c0045ceebc50a0a341468855/documentation/gradle/migrate.md
- **Locations are scanned recursively**, including non-hidden subfolders. (migrations URL)
- **Schemas.**
  - `schemas` is a "case-sensitive list of schemas managed by Flyway". The first one becomes the default during migration and holds the history table. (Gradle reference)
  - `defaultSchema` defaults to the first entry of `schemas`, or else to the connection's default schema, and it becomes the connection's default schema. https://documentation.red-gate.com/fd/flyway-default-schema-setting-277578987.html
  - So an unqualified `CREATE TABLE` lands in the first configured schema.
- **Placeholders.**
  - Ant-style `${key}`, case-insensitive. They are set from `flyway.placeholders.*`, environment variables, Maven/Gradle configuration or the API.
  - Built-in ones include `${flyway:defaultSchema}`, `${flyway:user}`, `${flyway:database}`, `${flyway:timestamp}`, `${flyway:filename}`, `${flyway:table}` and `${flyway:environment}`.
  - They work in V and R migrations and in SQL callbacks.
  - https://documentation.red-gate.com/fd/flyway-placeholders-namespace-277579022.html and https://documentation.red-gate.com/fd/migration-placeholders-275218550.html
- **Other settings.** `outOfOrder` (default false) applies a late-found lower version. `target` stops at a version. (Gradle reference)
- **Dialects Flyway itself accepts.**
  - MySQL: `DELIMITER //` blocks for stored procedures, `/*!...*/` mysqldump directives and `#` comments; "DDL exported by mysqldump can be used unchanged". https://documentation.red-gate.com/fd/mysql-277579322.html
  - PostgreSQL: `CREATE FUNCTION` with `$$` and tagged `$_$` dollar quoting, and `COPY ... FROM STDIN`, as pg_dump writes them; psql meta-commands such as `\set` are not supported. https://documentation.red-gate.com/fd/postgresql-database-277579325.html
  - In short, real Flyway scripts may be raw pg_dump or mysqldump output.

### Liquibase
- **Changelog formats.**
  - Changelogs can be XML, YAML, JSON or formatted SQL, and the formats can be mixed.
  - A root that uses `include`/`includeAll` must be XML/YAML/JSON (SQL roots only in Liquibase Secure 4.28+).
  - A changeset is identified by id + author + file path (`logicalFilePath` overrides the path).
  - Changesets and includes run "in file order".
  - https://docs.liquibase.com/concepts/changelogs/home.html
- **includeAll.**
  - "All files inside the included directory are run in alphabetical order", including subfolders.
  - XML, YAML and JSON files are read as changelogs, SQL files as single changes.
  - Options: `contextFilter`, `labels`, `endsWithFilter`, `minDepth`/`maxDepth`, `relativeToChangelogFile`, and custom `filter` or `resourceComparator` classes.
  - https://docs.liquibase.com/change-types/include-all.html
- **Formatted SQL.**
  - The file must begin with `--liquibase formatted sql`. Each change starts with `--changeset author:id`, followed by attributes such as `dbms:` (a list, `!` to exclude, `all`/`none`), `context:`, `labels:`, `runOnChange`, `splitStatements` and `endDelimiter`.
  - Rollbacks are written with `--rollback`.
  - https://docs.liquibase.com/concepts/changelogs/sql-format.html
- **Contexts.**
  - Expressions use AND/OR/!, parentheses, comma as OR, and `@` for mandatory.
  - With no context given at runtime, "every undeployed changeset ... runs", except those marked `@`.
  - A context on `include`/`includeAll` or on the root changelog is ANDed into every changeset below it.
  - https://docs.liquibase.com/concepts/changelogs/attributes/contexts.html
- **Change types carry schema explicitly.**
  - `createTable` has `catalogName`, `schemaName`, `tableName`, `tablespace` and `ifNotExists`, with nested `column`/`constraints`. https://docs.liquibase.com/change-types/create-table.html
  - `renameTable` has `schemaName`, `oldTableName` and `newTableName`. https://docs.liquibase.com/change-types/rename-table.html
  - `addForeignKeyConstraint` has `baseTableName`, `baseTableSchemaName`, `baseColumnNames`, `referencedTableName`, `referencedTableSchemaName`, `referencedColumnNames` and `constraintName`. https://docs.liquibase.com/change-types/add-foreign-key-constraint.html
- **Property substitution.**
  - `${name}` tokens come from runner attributes, JVM properties, environment variables, `-D` or `<property>` (first definition wins). An undefined token "is left as-is".
  - A property can have one value per `context` or per `dbms`.
  - https://docs.liquibase.com/concepts/changelogs/property-substitution.html

### Initialization outside the app
- **postgres image.** `*.sql`, `*.sql.gz` and `*.sh` files in `/docker-entrypoint-initdb.d` run in sorted name order, only when the data directory is empty. `POSTGRES_DB` defaults to `POSTGRES_USER`, which defaults to `postgres`. https://raw.githubusercontent.com/docker-library/docs/master/postgres/content.md
- **Testcontainers.** `jdbc:tc:...?TC_INITSCRIPT=path.sql` (classpath, or `file:`) or `TC_INITFUNCTION` runs setup before tests connect. https://java.testcontainers.org/modules/databases/jdbc/
- These scripts often create databases, users or test fixtures, not the service's own schema.

### Tools that compute a schema from migrations
- **jOOQ DDLDatabase** "applies all your DDL increments to an in-memory H2 database".
  - `sort` can be `semantic` (default), `alphanumeric`, `flyway` or `none`.
  - `unqualifiedSchema` places unqualified objects (default schema or `public`). `defaultNameCase` sets the case of unquoted names.
  - Because "RDBMS have a lot more features and syntax than are known to jOOQ", vendor SQL can be skipped with `[jooq ignore start]`/`[jooq ignore stop]` comments.
  - https://www.jooq.org/doc/latest/manual/code-generation/codegen-meta-sources/codegen-ddl/
- **Atlas** says "parsing schemas and migrations is not enough". It runs them on a temporary real database "of the same type and version" (for example `docker://postgres/15/dev`) to validate and normalize the result. https://atlasgo.io/concepts/dev-database

### Parsers (JSqlParser)
- **Multi-statement parsing.** `parseStatements()` splits on `;`, `GO`, `/` or **two empty lines**. `withBlankLinesAsSeparator(false)` turns the blank-line rule off. https://jsqlparser.github.io/JSqlParser/usage.html
- **Error handling.** `withErrorRecovery(true)` skips a broken statement and records it. `withUnsupportedStatements(true)` keeps unknown statements as opaque `UnsupportedStatement`. (same URL)
- **Dialect presets** (`Dialect.POSTGRESQL`, `Dialect.MYSQL`, `Dialect.ORACLE`).
  - Tagged dollar strings need `Dialect.POSTGRESQL` or `withDollarQuotedStringTags(true)`.
  - PostgreSQL function and `DO` bodies stay opaque text.
  - A compound body with `IF`, `DECLARE` or loops becomes `UnsupportedStatement`.
  - Backslash escapes need the MySQL preset.
  - `[brackets]` need `withSquareBracketQuotation(true)` for T-SQL.
  - Unrecognized option text is kept as one raw `OTHER` option.
  - (same URL)
- **Issue #1891.** Older versions broke on generated SQL with blank lines; the separator token cannot be configured, so the maintainer advises preprocessing. https://github.com/JSQLParser/JSqlParser/issues/1891
- **Issue #1788.** Splitting a pg_dump file on `;` broke on semicolons inside comments ("a flawed approach"). The maintainer advises letting the parser find statements with error recovery, and notes that `CREATE TYPE` was unsupported at the time. https://github.com/JSQLParser/JSqlParser/issues/1788

### Papers
- **Vassiliadis et al. (EDBT 2023)**, 195 open-source schema histories.
  - The dataset kept only single-file schema-DDL projects, MySQL or PostgreSQL. It filtered out paths containing "example, demo, test, migrat" and projects without CREATE TABLE.
  - A schema version is a version of that one DDL file, and change is measured by diffing consecutive versions (tables and attributes born, injected, ejected or deleted, type and key changes).
  - Findings: schemas change mostly early (98 of 195 reach 75% of their change in the first 20% of their life) and rarely in step with the code.
  - The dataset deliberately avoids migration chains, so these results say little about replaying migrations.
  - https://openproceedings.org/2023/conf/edbt/paper-160.pdf

### Real projects (fetched raw)
- **spring-boot-realworld-example-app.** One Flyway file, `db/migration/V1__create_tables.sql`, with seven `create table` statements and **no foreign keys**: links such as `user_id` and `article_id` are implicit. The app runs on SQLite (`jdbc:sqlite:dev.db`) and uses MyBatis XML mappers, not JPA. https://raw.githubusercontent.com/gothinkster/spring-boot-realworld-example-app/master/src/main/resources/db/migration/V1__create_tables.sql
- **jhipster-sample-app.** Liquibase, with several traps:
  - The master is `config/liquibase/master.xml`, set **in Java code** (`liquibase.setChangeLog("classpath:config/liquibase/master.xml")` in `LiquibaseConfiguration.java`), not in properties.
  - Tables and foreign keys sit in separate files (`added_entity_X.xml`, then `added_entity_constraints_X.xml`), included in a fixed order.
  - Column types are `${datetimeType}` properties defined per `dbms` (h2, postgresql).
  - Sample data uses `context="faker"`.
  - The initial schema creates a test-only table `jhi_date_time_wrapper` under `context="test"` in the main changelog.
  - The dev profile uses H2 with contexts `dev, faker`; prod uses PostgreSQL with context `prod`.
  - https://raw.githubusercontent.com/jhipster/jhipster-sample-app/main/src/main/resources/config/liquibase/master.xml (and the changelog files beside it)
- **spring-petclinic.** No migration tool.
  - It sets `spring.sql.init.schema-locations=classpath*:db/${database}/schema.sql` with a home-made property `database=h2`, overridden to `mysql` or `postgres` by profile.
  - It ships three copies of the same schema: MySQL with `INT(4) UNSIGNED AUTO_INCREMENT`, `INDEX(...)` and `engine=InnoDB`; PostgreSQL with `GENERATED BY DEFAULT AS IDENTITY`; and H2.
  - It also has a `user.sql` that the app never runs.
  - It sets `ddl-auto=none`.
  - https://raw.githubusercontent.com/spring-projects/spring-petclinic/main/src/main/resources/application.properties

## Pitfalls for static analysis
1. **Ownership is implicit.** No tool records which service owns a table. The evidence is:
   - which deployable's main resources (or jars on its classpath) hold the migrations;
   - which DataSource runs them (`@FlywayDataSource`, `spring.flyway.url`, `spring.liquibase.url`);
   - the schema settings (`spring.flyway.schemas` first entry or `default-schema`, Liquibase `defaultSchema`, `schemaName` attributes, `@Table(schema)`).

   Migrations shipped in a shared library jar reach every app that depends on it, since `classpath*:` scans jars. This is unverified for Flyway's own scanner; check before relying on it.
2. **Locations and change-log paths can live in code** (JHipster), in profile files, or behind custom properties (petclinic `${database}`). Defaults alone miss them.
3. **Order is not file order.**
   - Flyway sorts versions numerically (V10 comes after V9) and runs repeatables last, by description.
   - Liquibase follows include order; includeAll is alphabetical.
   - Lexical sorting is wrong for Flyway.
4. **Some files must not be replayed:**
   - `U__` undo migrations;
   - `src/test/resources` migrations;
   - Liquibase changesets in test or faker contexts, while untagged and non-`@` changesets always run;
   - `dbms` changesets for other vendors;
   - SQL callbacks (`afterMigrate.sql`), which sit in the same folder but usually hold grants or data;
   - `data.sql` and `import.sql`;
   - Docker and Testcontainers init scripts, which are setup or fixtures.
5. **Final state needs replay.** RENAME TABLE and RENAME COLUMN, DROP, ALTER ADD/DROP COLUMN, and foreign keys added in later files (JHipster) all change the result. Reading only CREATE TABLE draws dropped tables and old names. Complex moves (copy, merge, split a table) appear as CREATE + INSERT…SELECT + DROP, so replay gets the end state right but cannot show lineage.
6. **Duplicate vendor copies.** `{vendor}` folders, `schema-${platform}.sql` and per-vendor folders hold the same tables several times. Pick one, the one matching the production DataSource URL; do not union them. H2 copies are usually test or dev only.
7. **Placeholders and properties first.** `${schema}`, `${flyway:defaultSchema}` and Liquibase `${datetimeType}` must be substituted before parsing. Unknown ones stay literal, so record them as unresolved rather than guess.
8. **Parsers fail on real dump-style SQL:**
   - PostgreSQL dollar-quoted functions and DO blocks, `COPY FROM STDIN`, `CREATE TYPE`/`EXTENSION`, psql `\` commands;
   - MySQL `DELIMITER`, `/*!…*/` and `#` comments, `ENGINE=` and other table options;
   - Oracle PL/SQL blocks and `/` terminators;
   - T-SQL `GO` and `[brackets]`;
   - semicolons inside comments;
   - blank lines read as separators in some JSqlParser versions.

   A failure must skip one statement, not lose the file.
9. **No foreign keys is common** (RealWorld). FK-based relations will be missing; joins come from naming or the ORM.
10. **Unqualified names.** They resolve to the first Flyway schema, the Liquibase default schema or the connection's default (`public` in PostgreSQL). Unquoted names change case by vendor.
11. **No migrations at all.** Then the schema is Hibernate's (`ddl-auto` create or update, or embedded default) or managed outside the repository. Entities are the only evidence, and `ddl-auto=none` means the tables exist somewhere unseen.
12. **Java migrations** (`V2__X.java`, `JavaMigration` beans) and Liquibase `customChange` can create tables that no static SQL reading sees.

## Options for what the extractor reads
- **A. CREATE TABLE names only (regex or parser).** Cheap and robust. Wrong after renames and drops, and draws vendor copies twice. Not enough.
- **B. Static replay of SQL migrations with JSqlParser** (dialect preset from the DataSource URL, error recovery, opaque unsupported statements) into an in-memory model (schema → table → columns, keys, FKs), applying CREATE, ALTER, RENAME and DROP in each tool's real order.
  - Trade-offs: no new dependency, deterministic, fast, and shows where each fact came from. It needs per-tool ordering and filtering logic, and procedural or vendor statements are skipped (usually harmless for the table list).
- **C. Structured read of Liquibase XML/YAML/JSON change types**, with formatted SQL going through B.
  - Exact and dialect-free for `createTable`, `renameTable`, `dropTable`, `addColumn` and `addForeignKeyConstraint`, which carry `schemaName`.
  - It must follow include/includeAll and contexts/labels/dbms, and substitute properties.
- **D. jOOQ DDLDatabase-style interpretation (H2 simulation).** Mature DDL interpreter with Flyway sorting. It is a new dependency, and H2 rejects much vendor DDL, which needs ignore markers the projects will not have.
- **E. Replay on a real database container of the right vendor** (Atlas-style), running Flyway or Liquibase themselves, then reading `information_schema`.
  - Most accurate: covers Java migrations, procedural code and vendor DDL.
  - Costs: Docker time per service, the vendor image and version, Flyway/Liquibase setup (placeholders, contexts, schemas) that must be inferred anyway, and a failing migration blocks everything.
- **F. Hibernate entity model as the schema** when there are no migrations. This is the only source in that case, and it is wrong when migrations exist and disagree.

## Recommendation (the researcher's)
**Now, in order of how common each pattern is and how much it changes which tables are drawn in which service's database:**
1. **Flyway SQL migrations (B).** The most common source. Set the defaults (`db/migration`, V/R/U naming, numeric order, repeatables last, U skipped). Honour `spring.flyway.locations` with `{vendor}` resolved from the DataSource URL, profile files, `spring.flyway.schemas`/`default-schema` and `placeholders.*`. Exclude test resources.
2. **Liquibase changelogs (C plus B for formatted SQL).** Find the master from `spring.liquibase.change-log`, the default path, or a string literal passed to `setChangeLog` in code. Follow include and includeAll alphabetically. Drop changesets for other dbms. Treat test-like contexts (`test`, `faker`, mandatory `@...`) as non-production, decided by the prod profile's `spring.liquibase.contexts` when present. Substitute properties per dbms.
3. **`schema.sql` / `schema-${platform}.sql` / `spring.sql.init.schema-locations` (B)**, picking the single vendor variant that matches the production DataSource.
4. **Tolerant parsing everywhere.** Substitute placeholders first, use the vendor dialect preset, turn error recovery on, and keep unsupported statements as skipped (counted, not fatal). Record unresolved placeholders and skipped statements as gaps.
5. **Ownership.** A table belongs to the database of the deployable whose main resources (or bundled shared jar) hold the migration and whose DataSource runs it. Its schema is the explicit qualifier, else the tool's default schema, else the vendor default.

**Later:**
- Entity-based schema (F) for services with no migrations.
- Real-database replay (E) in the existing sandbox, as a check or a fallback for services where static replay skipped many statements or Java migrations exist.
- Liquibase `sqlFile` and `customChange`.
- Docker and Testcontainers init scripts, only as weak evidence of a database's existence, never of its tables.


## Decision

What the extractor already did (ADR 0034) covers most of the recommendation's first two points: Flyway's naming, numeric order, repeatables last, undo and baseline left out, test resources left out; Liquibase includes in file order, `includeAll` alphabetically, change types read as structured data, formatted SQL through the parser; and replay of `CREATE`, `ALTER`, `RENAME` and `DROP` into a schema model, where a failing statement skips only itself. What it changes now, in order of how common the pattern is and how much it changes the tables drawn:

1. **Placeholders and properties before parsing** (pitfall 7). Flyway's `spring.flyway.placeholders.*` and `${flyway:defaultSchema}`; Liquibase's `property` elements and `--property` lines, per `dbms` and `context`, and `spring.liquibase.parameters.*`, first value winning. Unknown ones stay literal.
2. **One vendor copy** (pitfall 6). Where files are kept per database (a folder such as `db/migration/postgresql/`, or `schema-<platform>.sql`), only the copy of the module's database is read: the database of its `prod` (or `production`) profile's URL, else its default URL, else `spring.sql.init.platform`. Where the config names the scripts Spring runs (`spring.sql.init.schema-locations`), only those are read. Without a known database, every copy is read, as before.
3. **Liquibase `dbms` and contexts** (pitfall 4). A change set, change, include or property for another database is skipped; contexts are matched against the `spring.liquibase.contexts` of the same profile, and without contexts every change set runs except mandatory (`@`) ones, as Liquibase does. Rather than the researcher's "treat test-like contexts as non-production", which is a guess about names, the extractor follows Liquibase's own rule with the application's configured contexts.
4. **Scripts written for a client** (pitfall 8). MySQL `DELIMITER` lines and `#` comments, and SQL Server `GO` and Oracle `/` lines.

Left for later, each as its own issue: the default schema of unqualified names (`spring.flyway.schemas`, Liquibase `defaultSchemaName`), Java migrations and `customChange` (pitfall 12), the entity model when there are no migrations (option F), replay on a real database (option E), migration locations and changelogs set in code (pitfall 2), and Docker and Testcontainers init scripts as evidence of a database (ADR 0034 already leaves Docker init scripts out of the schema).

## What it changes in the bar and the design

- **Design, Static analysis and extractors:** the SQL row says the module's database decides the vendor copy and the Liquibase change sets, and that placeholders are replaced. ADR 0034 is amended.
- **Quality bar:** nothing new. A table is already drawn from its evidence, and what cannot be resolved stays unresolved.

## Researcher's notes on the bar and the design

- **Bar:** the tables drawn in a service's database are the **final state after all production migrations, in the tool's own order**. They exclude test, dev, undo and other-vendor scripts, and they are drawn once even when several vendor copies exist. Each table shows where it came from (file and statement). What cannot be resolved (placeholders, skipped statements, Java migrations) is stated as a known gap, not guessed.
- **Design:**
  - The SQL/schema extractor gets a replay step: per-tool discovery and ordering (Flyway, Liquibase, sql.init), configuration taken from Spring properties and profiles, placeholder substitution, dialect chosen from the DataSource URL, and tolerant JSqlParser parsing that applies CREATE/ALTER/RENAME/DROP to a schema model.
  - Liquibase XML/YAML/JSON is read as structured data.
  - Ownership is linked through the deployable and its DataSource.
  - Real-database replay and entity fallback are noted as later options.

## Sources read in full
- https://documentation.red-gate.com/fd/migrations-271585107.html
- https://documentation.red-gate.com/fd/versioned-migrations-273973333.html
- https://documentation.red-gate.com/fd/flyway-placeholders-namespace-277579022.html
- https://documentation.red-gate.com/fd/migration-placeholders-275218550.html
- https://documentation.red-gate.com/fd/flyway-default-schema-setting-277578987.html
- https://raw.githubusercontent.com/flyway/flywaydb.org/a157b970b15051d6c0045ceebc50a0a341468855/documentation/gradle/migrate.md (Flyway configuration reference, older docs; its `locations` row was an unexpanded include)
- https://documentation.red-gate.com/fd/mysql-277579322.html
- https://documentation.red-gate.com/fd/postgresql-database-277579325.html
- https://docs.spring.io/spring-boot/how-to/data-initialization.html (Spring Boot 4.1)
- https://docs.spring.io/spring-boot/reference/data/sql.html
- https://docs.liquibase.com/concepts/changelogs/home.html
- https://docs.liquibase.com/change-types/include-all.html
- https://docs.liquibase.com/concepts/changelogs/sql-format.html
- https://docs.liquibase.com/change-types/rename-table.html
- https://docs.liquibase.com/change-types/create-table.html
- https://docs.liquibase.com/change-types/add-foreign-key-constraint.html
- https://docs.liquibase.com/concepts/changelogs/property-substitution.html
- https://docs.liquibase.com/concepts/changelogs/attributes/contexts.html
- https://jsqlparser.github.io/JSqlParser/usage.html (both parts)
- https://github.com/JSQLParser/JSqlParser/issues/1891
- https://github.com/JSQLParser/JSqlParser/issues/1788
- https://www.jooq.org/doc/latest/manual/code-generation/codegen-meta-sources/codegen-ddl/
- https://atlasgo.io/concepts/dev-database
- https://raw.githubusercontent.com/docker-library/docs/master/postgres/content.md
- https://java.testcontainers.org/modules/databases/jdbc/
- Vassiliadis, Shehaj, Kalampokis, Zarras, "Joint Source and Schema Evolution: Insights from a Study of 195 FOSS Projects", EDBT 2023: https://openproceedings.org/2023/conf/edbt/paper-160.pdf
- gothinkster/spring-boot-realworld-example-app (raw): `V1__create_tables.sql`, `application.properties`, `application-test.properties`
- jhipster/jhipster-sample-app (raw): `master.xml`, `20150805125054_added_entity_Operation.xml`, `..._added_entity_constraints_Operation.xml`, `00000000000000_initial_schema.xml` (changeset and context lines), `application-dev.yml`, `application-prod.yml`, `LiquibaseConfiguration.java` (changelog line)
- spring-projects/spring-petclinic (raw): `application.properties`, `application-mysql.properties`, `db/mysql/schema.sql`, `db/postgres/schema.sql`, the file list of `db/`

## Sources not read in full (carry no weight)
- Hibernate ORM 6.6 User Guide and Introduction (only the start of each was read; the `jakarta.persistence.schema-generation.*` values, `hibernate.default_schema` and `import.sql` details came from that start): https://docs.hibernate.org/orm/6.6/userguide/html_single/Hibernate_User_Guide.html, https://docs.hibernate.org/orm/6.6/introduction/html_single/Hibernate_Introduction.html
- H2 features, Compatibility section only (modes emulate "only a small subset" of vendor differences): https://www.h2database.com/html/features.html
- Curino, Moon, Zaniolo, "Graceful database schema evolution: the PRISM workbench", PVLDB 2008. Only the operator table was read: create, drop, rename, copy, merge, partition, decompose and join table; add, drop and rename column; Wikipedia's 170+ schema versions. https://www.vldb.org/pvldb/vol1/1453939.pdf
- Schneider and Scandariato, Code2DFD, arXiv 2304.12769. Only the approach and error sections were read: it detects databases as nodes from Docker Compose images and JDBC connections and extracts no tables; one error was the same database detected twice under two names. https://arxiv.org/pdf/2304.12769
- Search snippets only: Flyway callbacks (afterMigrate.sql in the migrations folder, run alphanumerically), Goeminne and Mens on ORM in Java (arXiv 1701.00416), Vassiliadis ICDE 2021 schema-evolution profiles, Meurice and Cleve (QRS 2016), and Redgate's Flyway Java-migration and callback pages (both returned 404).

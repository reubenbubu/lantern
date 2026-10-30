# 0034: SQL extractor

Status: accepted (#113)

## Context

`docs/design.md` (Static analysis and extractors) says the SQL extractor reads Flyway and
Liquibase migrations and `.sql` files with JSqlParser and produces the schema as of the commit:
tables, columns and keys. The container view draws each service's own schema as a data store, and
the report spec's database elements (#95) are built from these facts, so they must say which
module a schema belongs to and point at the migration and lines behind each table, column and key.
The design leaves open which files count, in what order they run, how the schema is named and
modelled, and what happens with SQL the parser cannot read.

## Decisions

- **Per repository, from git.** `SqlExtractor` (`lantern-extract`) reads each repository at its
  commit from git, without builds, so a failed build does not matter. Files under `src/` but not
  `src/main/` (tests, integration tests) are left out.
- **One schema per module.** Each file belongs to the Maven module whose directory holds it most
  closely (found as the Maven extractor finds modules). A module's Flyway migrations, Liquibase
  changelogs and other SQL files together build one schema, `schema:<groupId>:<artifactId>`, which
  the module `CONTAINS`; a repository without a POM has one schema keyed by its name. A module
  whose files create no table has no schema, so a `data.sql` alone adds nothing.
- **The schema is not a data store.** `schema` has no parent and no C4 level, like `library`.
  Which data store holds a schema comes from the datasource configuration (Config extractor,
  ADR 0032), and two services may share one database (project-0, DB-5), so the composer and linker
  decide that, not this extractor.
- **Which files, in what order.** Flyway first, as Flyway runs them: versioned migrations
  (`V<version>__*.sql`, anywhere in the module, so vendor folders and custom locations
  count) by version compared part by part, then repeatable ones (`R__*.sql`) by name;
  undo and baseline migrations are skipped. Then Liquibase: every XML, YAML or JSON file under
  `src/main/resources` whose root is `databaseChangeLog`, and every `.sql` file starting with
  `--liquibase formatted sql`; the root changelogs are those no other includes, applied in path
  order, each following its `include`, `includeAll` and `sqlFile` entries. This does not depend on
  `spring.liquibase.change-log` or `spring.flyway.locations`. Last, the module's other `.sql` files
  in path order (such as `schema.sql` or a Docker init script).
- **What is applied.** `CREATE TABLE`, `ALTER TABLE` (add, drop, rename and change columns; add and
  drop primary, unique and foreign keys; rename the table), `DROP TABLE` and `RENAME TABLE`, and
  the Liquibase changes that do the same (`createTable`, `addColumn`, `dropColumn`, `renameTable`,
  `renameColumn`, `modifyDataType`, `addNotNullConstraint`, `addPrimaryKey`,
  `addUniqueConstraint`, `addForeignKeyConstraint`, their drops, and SQL in `sql` and `sqlFile`).
  `CREATE TABLE IF NOT EXISTS` leaves an existing table as it is, dropping a table drops the foreign
  keys that reference it, and a key declared without a name can be dropped by the name PostgreSQL
  gives it (`orders_pkey`, `orders_code_key`, `orders_customer_id_fkey`). Indexes, views, data and
  functions do not shape tables and are skipped. Every Liquibase change
  set is applied whatever its `dbms`, `context` or `labels`; rollbacks are not.
- **Parsing.** Scripts are split into statements here (at semicolons outside quotes, dollar quotes
  and comments), so each statement keeps its lines and one the parser cannot read is skipped alone.
  A `CREATE TABLE` the parser rejects is tried again without the options after its column list
  (`PARTITION BY`, `INHERITS`, ...); `SET DATA TYPE` and MySQL's `DROP FOREIGN KEY` are rewritten
  into forms JSqlParser reads. A statement still unreadable is skipped, so its table or change is
  missing; a warning names its file and lines. Each statement may take the parser up to 10 seconds; past that it is skipped, the one
  case where the result could depend on the machine.
- **Names.** Unquoted names are folded to lower case, as PostgreSQL does; quoted ones keep their
  case without the quotes. A qualified name (`app.orders`) is kept; a change naming the table
  without its qualifier, or with one when it was created without, finds it when exactly one table
  matches, and two different qualifiers never match. The folding is PostgreSQL's only: databases
  that fold unquoted names to upper case (H2, Oracle) are not modelled, so a migration that quotes
  an upper-case name and elsewhere leaves it unquoted gives two names.
- **Entity IDs.** `table:<schema key>:<table>` and `column:<schema key>:<table>#<column>`, so two
  services' `orders` tables are different entities. A JPA entity's table (#130) is matched to these
  by name by the linker.
- **Facts.** `schema CONTAINS table`, `table CONTAINS column`; on each column `position` (integer,
  its place in the table), `data-type` (as written, lower case, single spaces: `varchar(20)`) and
  `nullable` (boolean; a primary key column is not nullable); `table PRIMARY_KEY column` and `table
  UNIQUE_KEY column`, each with the key's `columns` in order and its constraint `name` if given;
  `table REFERENCES table` for a foreign key, with `columns`, `referencedColumns` (those named, or
  the referenced table's primary key) and `name`; and `schema kind` with `flyway`, `liquibase` or
  `sql`, one fact for each way the schema is migrated. Keys are relationships with their columns
  as attributes because a built-in relationship such as `CONTAINS` takes no attributes, and a
  table-level `REFERENCES` gives the composer table-to-table links directly.
- **Evidence** is the file and lines of the statement or change that last shaped what the fact
  says: a column's type points at the `ALTER` that changed it, its existence at the `CREATE` or the
  rename. A Liquibase column points at its own `column` element; SQL inside a Liquibase `sql`
  change points at that change. The `module CONTAINS schema` and `kind` facts point at the first
  file that shaped the schema, without lines.
- **Confidence** is 1.0 for Flyway and Liquibase, which the application runs, and 0.8 for other
  `.sql` files, which may be run by hand or not at all.
- **New vocabulary**, applied by the app on startup after the README and ADR extractor's, with source
  `sql-extractor`: the entity types `schema`, `table` and `column` (no C4 level), the relationship
  types `PRIMARY_KEY`, `UNIQUE_KEY` and `REFERENCES`, and the attribute types `data-type`
  (string), `nullable` (boolean) and `position` (integer). It reuses the Java extractor's `kind`,
  whose description now mentions schemas.
- **Dependencies.** JSqlParser 5.3, pinned in the parent POM, as the design names it; SnakeYAML
  (already in `lantern-extract` for the Config extractor) reads YAML and JSON changelogs as nodes
  only, with their lines, never constructing objects; XML changelogs are read with DTDs and
  external entities disabled.
- **Not covered yet:** Flyway Java migrations and placeholders, Liquibase property substitution and
  preconditions, `CREATE TABLE ... LIKE` and `AS SELECT` columns, check constraints, indexes, views,
  and statement delimiters other than `;` (`GO`, `/`).

package io.lantern.extract.sql;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.repo.Repo;
import io.lantern.extract.Fixtures;
import io.lantern.extract.RepoBuild;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Golden-fact tests on the SQL fixtures, read from git without builds. */
class SqlExtractorTest {

  private static final long SNAPSHOT = 4;

  @TempDir Path temp;

  private List<Fact> extract(List<Repo> repos) throws IOException {
    List<RepoBuild> inputs =
        repos.stream().map(r -> new RepoBuild(r, Fixtures.head(r), null)).toList();
    List<Fact> facts = new SqlExtractor().extract(inputs, SNAPSHOT);
    Fixtures.checkEnvelope(facts, repos, SNAPSHOT, SqlExtractor.NAME, SqlExtractor.VERSION);
    return facts;
  }

  @Test
  void flywayMigrationsInVersionOrder() throws IOException {
    Repo repo = Fixtures.repo("sql/flyway", temp.resolve("orders"), "orders");

    Fixtures.assertGolden("sql/flyway.txt", extract(List.of(repo)));
  }

  @Test
  void liquibaseChangelogsAndPlainScripts() throws IOException {
    Repo repo = Fixtures.repo("sql/liquibase", temp.resolve("inventory"), "inventory");

    Fixtures.assertGolden("sql/liquibase.txt", extract(List.of(repo)));
  }

  @Test
  void eachModuleReadAsItsDatabaseAndToolsRunIt() throws IOException {
    Repo repo = Fixtures.repo("sql/vendors", temp.resolve("vendors"), "vendors");

    Fixtures.assertGolden("sql/vendors.txt", extract(List.of(repo)));
  }

  @Test
  void repositoryWithoutModulesHasOneSchemaNamedAfterIt() throws IOException {
    Repo repo =
        Fixtures.repo(
            temp.resolve("scripts"),
            "scripts",
            Map.of("db/schema.sql", "CREATE TABLE t (id INT PRIMARY KEY);\n"));

    assertThat(Fixtures.render(extract(List.of(repo))))
        .isEqualTo(
            """
            column:scripts:t#id data-type int @scripts:db/schema.sql#1-1 0.8
            column:scripts:t#id nullable false @scripts:db/schema.sql#1-1 0.8
            column:scripts:t#id position 1 @scripts:db/schema.sql#1-1 0.8
            schema:scripts CONTAINS table:scripts:t @scripts:db/schema.sql#1-1 0.8
            schema:scripts kind sql @scripts:db/schema.sql 0.8
            table:scripts:t CONTAINS column:scripts:t#id @scripts:db/schema.sql#1-1 0.8
            table:scripts:t PRIMARY_KEY column:scripts:t#id {"columns":["id"]} @scripts:db/schema.sql#1-1 0.8
            """);
  }

  @Test
  void scriptsWithoutTablesGiveNoSchema() throws IOException {
    Repo repo =
        Fixtures.repo(
            temp.resolve("data"),
            "data",
            Map.of(
                "data.sql", "INSERT INTO t VALUES (1);\n",
                "src/test/resources/schema.sql", "CREATE TABLE t (id INT);\n"));

    assertThat(extract(List.of(repo))).isEmpty();
  }
}

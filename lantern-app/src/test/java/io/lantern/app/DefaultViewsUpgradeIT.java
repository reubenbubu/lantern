package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.compose.JdbcViewDefinitionStore;
import io.lantern.compose.Scope;
import io.lantern.compose.SectionKind;
import io.lantern.compose.ViewDefinition;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Migration V12 removes the view definitions earlier runs stored with the defaults of the time, so
 * new default sections reach existing workspaces, and keeps those that differ, as overrides (ADR
 * 0047). It has a database of its own, because the shared test database cannot be rewound to an
 * older migration.
 */
class DefaultViewsUpgradeIT {

  private static final List<SectionKind> OLD_C4 =
      List.of(
          SectionKind.OVERVIEW,
          SectionKind.CONTEXT,
          SectionKind.CONTAINERS,
          SectionKind.COMPONENTS);

  private static final List<SectionKind> OLD_BRIEF =
      List.of(
          SectionKind.OVERVIEW,
          SectionKind.HEADLINES,
          SectionKind.RISKS,
          SectionKind.CONTEXT,
          SectionKind.CONTAINERS);

  private static final List<SectionKind> OLD_EXPLORER =
      List.of(
          SectionKind.OVERVIEW,
          SectionKind.CONTEXT,
          SectionKind.CONTAINERS,
          SectionKind.COMPONENTS,
          SectionKind.DEPENDENCIES,
          SectionKind.STRUCTURE);

  @Test
  void removesStoredDefaultViewsAndKeepsOverrides() {
    try (PostgreSQLContainer postgres =
        new PostgreSQLContainer(
            DockerImageName.parse(TestcontainersConfiguration.composePostgresImage()))) {
      postgres.start();
      DriverManagerDataSource dataSource =
          new DriverManagerDataSource(
              postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
      Flyway.configure().dataSource(dataSource).target("11").load().migrate();
      JdbcViewDefinitionStore store = new JdbcViewDefinitionStore(JdbcClient.create(dataSource));

      // What runs before this change stored: the defaults of the time.
      store.save(
          new ViewDefinition(
              "shop", "shop", Scope.repo("shop"), null, AnalysisRun.EXPLORER, OLD_EXPLORER));
      store.save(
          new ViewDefinition(
              "shop.brief", "shop", Scope.repo("shop"), null, AnalysisRun.BRIEF, OLD_BRIEF));
      store.save(
          new ViewDefinition(
              "shop.c4", "shop", Scope.repo("shop"), null, AnalysisRun.C4_REPORT, OLD_C4));
      store.save(
          new ViewDefinition(
              AnalysisRun.SYSTEM_C4,
              "System",
              Scope.system(),
              null,
              AnalysisRun.C4_REPORT,
              OLD_C4));
      // Views that differ from every default: overrides.
      ViewDefinition fewer =
          new ViewDefinition(
              "base.c4",
              "base",
              Scope.repo("base"),
              null,
              AnalysisRun.C4_REPORT,
              List.of(SectionKind.OVERVIEW, SectionKind.CONTAINERS));
      ViewDefinition audience =
          new ViewDefinition(
              "base.brief",
              "base",
              Scope.repo("base"),
              "executive",
              AnalysisRun.BRIEF,
              ViewDefinition.executiveBrief("x", "base", AnalysisRun.BRIEF).sections());
      store.save(fewer);
      store.save(audience);

      Flyway.configure().dataSource(dataSource).load().migrate();

      assertThat(store.list()).containsExactly(audience, fewer);
    }
  }
}

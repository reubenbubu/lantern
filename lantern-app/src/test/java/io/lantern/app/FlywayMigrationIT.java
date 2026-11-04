package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** Runs every migration against a real PostgreSQL in Testcontainers. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FlywayMigrationIT {

  @Autowired Flyway flyway;
  @Autowired JdbcTemplate jdbc;

  @Test
  void allMigrationsAreApplied() {
    MigrationInfo[] all = flyway.info().all();

    assertThat(all).isNotEmpty();
    assertThat(all).allSatisfy(m -> assertThat(m.getState()).isEqualTo(MigrationState.SUCCESS));
    assertThat(flyway.info().pending()).isEmpty();
  }

  @Test
  void theTablesTheAnswerStoreRetiredAreGone() {
    // V22 replaced the per-role tables with the one answer store (#769).
    List<String> retired = List.of("summaries", "unit_wordings", "link_resolutions");
    List<String> left =
        jdbc.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = current_schema()"
                + " AND table_name IN ('summaries', 'unit_wordings', 'link_resolutions')",
            String.class);

    assertThat(left).as("retired tables %s", retired).isEmpty();
    assertThat(
            jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables"
                    + " WHERE table_schema = current_schema() AND table_name = 'llm_answers'",
                String.class))
        .containsExactly("llm_answers");
  }
}

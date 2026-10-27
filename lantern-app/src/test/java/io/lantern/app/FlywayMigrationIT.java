package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Runs every migration against a real PostgreSQL in Testcontainers. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FlywayMigrationIT {

  @Autowired Flyway flyway;

  @Test
  void allMigrationsAreApplied() {
    MigrationInfo[] all = flyway.info().all();

    assertThat(all).isNotEmpty();
    assertThat(all).allSatisfy(m -> assertThat(m.getState()).isEqualTo(MigrationState.SUCCESS));
    assertThat(flyway.info().pending()).isEmpty();
  }
}

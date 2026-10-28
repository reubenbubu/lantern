package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lantern.core.analysis.AnalysisAlreadyRunningException;
import io.lantern.core.analysis.AnalysisLock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Shows that only one analysis can hold the lock at a time. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AnalysisLockIT {

  @Autowired AnalysisLock lock;
  @Autowired PostgreSQLContainer postgres;

  /** A lock on its own connections, as another Lantern process on the same database would have. */
  private AnalysisLock otherProcess() {
    return new AnalysisLock(
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
  }

  @Test
  void aSecondAnalysisFailsFastWhileOneIsRunning() {
    AnalysisLock other = otherProcess();
    try (AnalysisLock.Held running = lock.acquire()) {
      Instant start = Instant.now();

      assertThatThrownBy(lock::acquire)
          .isInstanceOf(AnalysisAlreadyRunningException.class)
          .hasMessage("analysis already running");
      assertThatThrownBy(other::acquire)
          .isInstanceOf(AnalysisAlreadyRunningException.class)
          .hasMessage("analysis already running");
      assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(5));
    }

    try (AnalysisLock.Held next = other.acquire()) {
      assertThatThrownBy(lock::acquire).isInstanceOf(AnalysisAlreadyRunningException.class);
    }
  }

  @Test
  void closingReleasesTheLockAndClosingAgainDoesNothing() {
    AnalysisLock.Held first = lock.acquire();
    first.close();
    first.close();

    try (AnalysisLock.Held second = lock.acquire()) {
      assertThat(second).isNotNull();
    }
  }
}

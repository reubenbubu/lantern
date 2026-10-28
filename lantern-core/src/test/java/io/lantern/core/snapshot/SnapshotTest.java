package io.lantern.core.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SnapshotTest {

  private static final String COMMIT = "a".repeat(40);

  @Test
  void commitsAreSortedByRepository() {
    Snapshot snapshot = new Snapshot(1, Map.of("b", COMMIT, "a", COMMIT), 0, "1.0", Instant.EPOCH);

    assertThat(snapshot.commits().keySet()).containsExactly("a", "b");
  }

  @Test
  void refusesInvalidSnapshots() {
    assertThatThrownBy(() -> new Snapshot(0, Map.of("a", COMMIT), 0, "1.0", Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Snapshot(1, Map.of(), 0, "1.0", Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Snapshot(1, Map.of("a", COMMIT), -1, "1.0", Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

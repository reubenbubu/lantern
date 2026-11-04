package io.lantern.harness;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** How the harness looks for the values masking marked as secret (#645). */
class SecretsTest {

  @Test
  void aSecretCountsAsAWholeWordNotInsideALongerOne() {
    assertThat(Harness.holds("It stores orders in PostgreSQL.", "Postgres")).isFalse();
    assertThat(Harness.holds("The password is Postgres.", "Postgres")).isTrue();
    assertThat(Harness.holds("token=sk-live-123456;", "sk-live-123456")).isTrue();
    assertThat(Harness.holds("xsk-live-123456", "-live-123456")).isTrue();
    assertThat(Harness.holds("anything", "")).isFalse();
  }
}

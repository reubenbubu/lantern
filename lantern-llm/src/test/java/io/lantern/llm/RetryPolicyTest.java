package io.lantern.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

  private final RetryPolicy policy =
      new RetryPolicy(6, Duration.ofSeconds(1), Duration.ofSeconds(5));

  @RepeatedTest(20)
  void backsOffExponentiallyWithJitterUpToTheMaximum() {
    assertThat(policy.delay(1, Optional.empty())).isBetween(ms(500), ms(1_000));
    assertThat(policy.delay(2, Optional.empty())).isBetween(ms(1_000), ms(2_000));
    assertThat(policy.delay(3, Optional.empty())).isBetween(ms(2_000), ms(4_000));
    assertThat(policy.delay(4, Optional.empty())).isBetween(ms(2_500), ms(5_000));
    assertThat(policy.delay(60, Optional.empty())).isBetween(ms(2_500), ms(5_000));
  }

  @RepeatedTest(20)
  void waitsAtLeastAsLongAsTheProviderAsks() {
    assertThat(policy.delay(1, Optional.of(Duration.ofSeconds(30))))
        .isBetween(ms(30_000), ms(31_000));
  }

  @Test
  void settingsAreChecked() {
    assertThatThrownBy(() -> new RetryPolicy(0, Duration.ZERO, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RetryPolicy(1, Duration.ofSeconds(2), Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CallLimits(0, null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CallLimits(1, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThat(new RetryPolicy(1, Duration.ZERO, Duration.ZERO).delay(3, Optional.empty()))
        .isZero();
  }

  private static Duration ms(long millis) {
    return Duration.ofMillis(millis);
  }
}

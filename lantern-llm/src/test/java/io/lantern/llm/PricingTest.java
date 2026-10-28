package io.lantern.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PricingTest {

  @Test
  void costAddsEveryKindOfToken() {
    Pricing pricing =
        new Pricing(
            BigDecimal.valueOf(3),
            BigDecimal.valueOf(15),
            new BigDecimal("3.75"),
            new BigDecimal("0.3"));

    assertThat(pricing.cost(new TokenUsage(1_000_000, 0, 0, 0))).isEqualByComparingTo("3");
    assertThat(pricing.cost(new TokenUsage(1000, 2000, 4000, 10000))).isEqualByComparingTo("0.051");
    assertThat(Pricing.FREE.cost(new TokenUsage(5, 5, 5, 5))).isEqualByComparingTo("0");
  }

  @Test
  void modelConfigsAreChecked() {
    assertThatThrownBy(() -> new ModelConfig("p", " ", Set.of(), 10, Pricing.FREE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ModelConfig("p", "m", Set.of(), 0, Pricing.FREE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new ModelConfig("p", "m", null, 10, Pricing.FREE).capabilities()).isEmpty();
    assertThat(
            new ModelConfig("p", "m", Set.of(Capability.TOOL_USE), 10, Pricing.FREE)
                .supports(Capability.TOOL_USE))
        .isTrue();
  }
}

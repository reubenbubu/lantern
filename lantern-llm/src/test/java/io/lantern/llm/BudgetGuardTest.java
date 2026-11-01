package io.lantern.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class BudgetGuardTest {

  @Test
  void aStopOnceSpentGuardHoldsNoWorstCaseAndRefusesOnlyOnceTheLimitIsSpent() {
    BudgetGuard share = BudgetGuard.stopOnceSpent("share", new BigDecimal("1"));
    // Worst cases far above the limit, all at once: none is refused while nothing is spent.
    BudgetGuard.Reservation a = share.reserve(10, new BigDecimal("5"));
    BudgetGuard.Reservation b = share.reserve(10, new BigDecimal("5"));
    a.settle(10, new BigDecimal("0.6"));
    BudgetGuard.Reservation c = share.reserve(10, new BigDecimal("5"));
    b.settle(10, new BigDecimal("0.6"));
    c.settle(10, new BigDecimal("0.1"));

    assertThat(share.spentCostUsd()).isEqualByComparingTo("1.3");
    assertThatThrownBy(() -> share.reserve(1, BigDecimal.ZERO))
        .isInstanceOf(BudgetExceededException.class)
        .hasMessageContaining("spent of $1");
  }

  @Test
  void reservationsCountUntilSettledOrReleased() {
    BudgetGuard budget = new BudgetGuard("run", 100L, new BigDecimal("1.00"));

    BudgetGuard.Reservation first = budget.reserve(60, new BigDecimal("0.50"));
    assertThatThrownBy(() -> budget.reserve(50, BigDecimal.ZERO))
        .isInstanceOf(BudgetExceededException.class);
    assertThatThrownBy(() -> budget.reserve(10, new BigDecimal("0.60")))
        .isInstanceOf(BudgetExceededException.class);

    first.settle(30, new BigDecimal("0.20"));
    assertThat(budget.spentTokens()).isEqualTo(30);
    assertThat(budget.spentCostUsd()).isEqualByComparingTo("0.20");

    BudgetGuard.Reservation second = budget.reserve(70, new BigDecimal("0.80"));
    second.release();
    assertThat(budget.spentTokens()).isEqualTo(30);
    assertThatThrownBy(second::release).isInstanceOf(IllegalStateException.class);
    budget.reserve(70, new BigDecimal("0.80")).settle(70, new BigDecimal("0.80"));
    assertThatThrownBy(() -> budget.reserve(1, BigDecimal.ZERO))
        .isInstanceOf(BudgetExceededException.class);
  }

  @Test
  void anUnlimitedBudgetOnlyCounts() {
    BudgetGuard budget = BudgetGuard.unlimited("run");

    budget.reserve(Long.MAX_VALUE / 2, new BigDecimal("1000000")).settle(5, BigDecimal.ONE);

    assertThat(budget.spentTokens()).isEqualTo(5);
    assertThat(budget.spentCostUsd()).isEqualByComparingTo("1");
  }

  @Test
  void limitsMustNotBeNegative() {
    assertThatThrownBy(() -> new BudgetGuard("run", -1L, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new BudgetGuard("run", null, new BigDecimal("-0.01")))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

package io.lantern.llm;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A token and cost budget, for example for one run or one scope. Before each call {@link LlmClient}
 * reserves the call's worst case (estimated input plus the most output tokens) and refuses the call
 * if that would go over; after the call the reservation is replaced by what the call really used.
 * The input estimate is rough, so spending can go slightly over the limit, but once it has, every
 * further call is refused. A guard made by {@link #stopOnceSpent} holds back no worst case: it
 * refuses only once the limit is spent.
 *
 * <p>Thread-safe.
 */
public final class BudgetGuard {

  private final String name;
  private final Long maxTokens;
  private final BigDecimal maxCostUsd;
  private final boolean stopOnceSpent;

  private long spentTokens;
  private BigDecimal spentCostUsd = BigDecimal.ZERO;
  private long reservedTokens;
  private BigDecimal reservedCostUsd = BigDecimal.ZERO;

  /**
   * A budget with the given limits.
   *
   * @param name what the budget is for, used in messages (for example "run 12")
   * @param maxTokens the most tokens, or {@code null} for no token limit
   * @param maxCostUsd the most US dollars, or {@code null} for no cost limit
   */
  public BudgetGuard(String name, Long maxTokens, BigDecimal maxCostUsd) {
    this(name, maxTokens, maxCostUsd, false);
  }

  private BudgetGuard(String name, Long maxTokens, BigDecimal maxCostUsd, boolean stopOnceSpent) {
    this.stopOnceSpent = stopOnceSpent;
    this.name = ModelConfig.nonBlank("Budget name", name);
    if (maxTokens != null && maxTokens < 0) {
      throw new IllegalArgumentException("Token limit must not be negative: " + maxTokens);
    }
    if (maxCostUsd != null && maxCostUsd.signum() < 0) {
      throw new IllegalArgumentException("Cost limit must not be negative: " + maxCostUsd);
    }
    this.maxTokens = maxTokens;
    this.maxCostUsd = maxCostUsd;
  }

  /**
   * A cost budget that refuses a call only once what finished calls spent has reached the limit,
   * without holding back any call's worst case: for a share whose items were chosen by their
   * estimates before any was asked, so which calls it refuses never depends on which thread asks
   * first while the choice holds (#657). Spending passes the limit by at most what the calls
   * running when it is reached cost.
   *
   * @param maxCostUsd the limit in US dollars, or {@code null} for none
   */
  public static BudgetGuard stopOnceSpent(String name, BigDecimal maxCostUsd) {
    return new BudgetGuard(name, null, maxCostUsd, true);
  }

  /** A budget without limits, which only counts. */
  public static BudgetGuard unlimited(String name) {
    return new BudgetGuard(name, null, null);
  }

  public String name() {
    return name;
  }

  /** Tokens used by finished calls. */
  public synchronized long spentTokens() {
    return spentTokens;
  }

  /** US dollars spent by finished calls. */
  public synchronized BigDecimal spentCostUsd() {
    return spentCostUsd;
  }

  /**
   * Holds back the worst case of a call about to be sent.
   *
   * @throws BudgetExceededException if that would go over a limit
   */
  public synchronized Reservation reserve(long tokens, BigDecimal costUsd) {
    Objects.requireNonNull(costUsd, "costUsd");
    if (tokens < 0) {
      throw new IllegalArgumentException("Tokens must not be negative: " + tokens);
    }
    long tokensAfter = saturatedAdd(saturatedAdd(spentTokens, reservedTokens), tokens);
    if (maxTokens != null && tokensAfter > maxTokens) {
      throw new BudgetExceededException(
          "Budget '%s': a call of up to %d tokens would exceed the limit of %d (%d used, %d reserved)"
              .formatted(name, tokens, maxTokens, spentTokens, reservedTokens));
    }
    if (stopOnceSpent) {
      if (maxCostUsd != null && spentCostUsd.compareTo(maxCostUsd) >= 0) {
        throw new BudgetExceededException(
            "Budget '%s': $%s spent of $%s"
                .formatted(name, spentCostUsd.toPlainString(), maxCostUsd.toPlainString()));
      }
      reservedTokens = saturatedAdd(reservedTokens, tokens);
      reservedCostUsd = reservedCostUsd.add(costUsd);
      return new Reservation(this, tokens, costUsd);
    }
    BigDecimal costAfter = spentCostUsd.add(reservedCostUsd).add(costUsd);
    if (maxCostUsd != null && costAfter.compareTo(maxCostUsd) > 0) {
      throw new BudgetExceededException(
          "Budget '%s': a call of up to $%s would exceed the limit of $%s ($%s spent, $%s reserved)"
              .formatted(
                  name,
                  costUsd.toPlainString(),
                  maxCostUsd.toPlainString(),
                  spentCostUsd.toPlainString(),
                  reservedCostUsd.toPlainString()));
    }
    reservedTokens = saturatedAdd(reservedTokens, tokens);
    reservedCostUsd = reservedCostUsd.add(costUsd);
    return new Reservation(this, tokens, costUsd);
  }

  private synchronized void close(Reservation reservation, long tokens, BigDecimal costUsd) {
    reservedTokens -= reservation.tokens;
    reservedCostUsd = reservedCostUsd.subtract(reservation.costUsd);
    spentTokens = saturatedAdd(spentTokens, tokens);
    spentCostUsd = spentCostUsd.add(costUsd);
  }

  private static long saturatedAdd(long a, long b) {
    long sum = a + b;
    return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
  }

  @Override
  public String toString() {
    return "BudgetGuard[" + name + "]";
  }

  /** Budget held back for one call. Settle or release it exactly once. */
  public static final class Reservation {

    private final BudgetGuard guard;
    private final long tokens;
    private final BigDecimal costUsd;
    private boolean closed;

    private Reservation(BudgetGuard guard, long tokens, BigDecimal costUsd) {
      this.guard = guard;
      this.tokens = tokens;
      this.costUsd = costUsd;
    }

    /** Replaces the reservation with what the call really used. */
    public void settle(long usedTokens, BigDecimal usedCostUsd) {
      Objects.requireNonNull(usedCostUsd, "usedCostUsd");
      markClosed();
      guard.close(this, usedTokens, usedCostUsd);
    }

    /** Gives the reservation back, for a call that was not made. */
    public void release() {
      markClosed();
      guard.close(this, 0, BigDecimal.ZERO);
    }

    private synchronized void markClosed() {
      if (closed) {
        throw new IllegalStateException("Reservation already settled or released");
      }
      closed = true;
    }
  }
}

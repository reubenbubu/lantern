package io.lantern.llm;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A model's prices in US dollars per million tokens.
 *
 * @param input per million normal input tokens
 * @param output per million output tokens
 * @param cacheWrite per million input tokens written to the prompt cache
 * @param cacheRead per million input tokens read from the prompt cache
 */
public record Pricing(
    BigDecimal input, BigDecimal output, BigDecimal cacheWrite, BigDecimal cacheRead) {

  private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

  /** No cost, for fakes and free models. */
  public static final Pricing FREE =
      new Pricing(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

  public Pricing {
    for (BigDecimal price : new BigDecimal[] {input, output, cacheWrite, cacheRead}) {
      Objects.requireNonNull(price, "price");
      if (price.signum() < 0) {
        throw new IllegalArgumentException("Prices must not be negative: " + price);
      }
    }
  }

  /**
   * The share of the price a request sent through a provider's batch API costs: half, on Anthropic
   * and OpenAI alike, with the cache multipliers stacking on it.
   */
  public static final BigDecimal BATCH_DISCOUNT = new BigDecimal("0.5");

  /** What the given usage costs through the provider's batch API, in US dollars. */
  public BigDecimal batchCost(TokenUsage usage) {
    return cost(usage).multiply(BATCH_DISCOUNT);
  }

  /** What the given usage costs, in US dollars. */
  public BigDecimal cost(TokenUsage usage) {
    return input
        .multiply(BigDecimal.valueOf(usage.input()))
        .add(output.multiply(BigDecimal.valueOf(usage.output())))
        .add(cacheWrite.multiply(BigDecimal.valueOf(usage.cacheWrite())))
        .add(cacheRead.multiply(BigDecimal.valueOf(usage.cacheRead())))
        .divide(MILLION);
  }
}

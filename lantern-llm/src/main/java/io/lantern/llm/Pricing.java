package io.lantern.llm;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A model's prices in US dollars per million tokens.
 *
 * @param input per million normal input tokens
 * @param output per million output tokens
 * @param cacheWrite per million input tokens written to the prompt cache with the default,
 *     five-minute lifetime
 * @param cacheRead per million input tokens read from the prompt cache
 * @param cacheWriteLong per million input tokens written to the prompt cache with the long,
 *     one-hour lifetime
 */
public record Pricing(
    BigDecimal input,
    BigDecimal output,
    BigDecimal cacheWrite,
    BigDecimal cacheRead,
    BigDecimal cacheWriteLong) {

  private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

  /** No cost, for fakes and free models. */
  public static final Pricing FREE =
      new Pricing(
          BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

  /** The multiple of the input price a long-lived cache write costs on Anthropic. */
  public static final BigDecimal LONG_WRITE_MULTIPLE = BigDecimal.TWO;

  public Pricing {
    for (BigDecimal price :
        new BigDecimal[] {input, output, cacheWrite, cacheRead, cacheWriteLong}) {
      Objects.requireNonNull(price, "price");
      if (price.signum() < 0) {
        throw new IllegalArgumentException("Prices must not be negative: " + price);
      }
    }
  }

  /**
   * Prices whose long-lived cache write costs twice the input price, as on Anthropic, the only
   * provider whose cache writes are billed.
   */
  public Pricing(BigDecimal input, BigDecimal output, BigDecimal cacheWrite, BigDecimal cacheRead) {
    this(input, output, cacheWrite, cacheRead, input.multiply(LONG_WRITE_MULTIPLE));
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
        .add(cacheWriteLong.multiply(BigDecimal.valueOf(usage.cacheWriteLong())))
        .divide(MILLION);
  }
}

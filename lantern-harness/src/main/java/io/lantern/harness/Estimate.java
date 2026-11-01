package io.lantern.harness;

import java.math.BigDecimal;

/**
 * What a role's plan expects to send before it sends anything (knowledge-build epic, section 10,
 * Plan): its items, how many the cache answers, and the tokens and dollars of the rest.
 *
 * @param items the items planned
 * @param cached how many the answer cache holds
 * @param toAsk how many will be asked
 * @param inputTokens the uncached input tokens expected
 * @param cacheWriteTokens the tokens expected to be written to the prompt cache, for an hour when
 *     the role batches
 * @param cacheReadTokens the tokens expected to be read from it
 * @param outputTokens the output tokens expected
 * @param usd the expected cost
 * @param shareUsd the role's share of the run budget, or {@code null} without a run budget
 */
public record Estimate(
    int items,
    int cached,
    int toAsk,
    long inputTokens,
    long cacheWriteTokens,
    long cacheReadTokens,
    long outputTokens,
    BigDecimal usd,
    BigDecimal shareUsd) {

  /** The line a run prints for the role. */
  public String line(String role) {
    return ("%s: %d items, %d cached, %d to ask; about %d input tokens (%d uncached, %d cache write,"
            + " %d cache read) and %d output tokens, about $%s%s")
        .formatted(
            role,
            items,
            cached,
            toAsk,
            inputTokens + cacheWriteTokens + cacheReadTokens,
            inputTokens,
            cacheWriteTokens,
            cacheReadTokens,
            outputTokens,
            usd.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(),
            shareUsd == null
                ? ""
                : " of a $"
                    + shareUsd.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
                    + " share");
  }
}

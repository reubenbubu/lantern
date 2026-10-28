package io.lantern.llm;

/**
 * How hard a model may be used at once. Every client that shares a {@link CallLimiter} shares these
 * limits for the model.
 *
 * @param maxConcurrency the most calls to the model in flight at once
 * @param requestsPerMinute the most calls started per minute, spread evenly over the minute, or
 *     {@code null} for no rate limit
 */
public record CallLimits(int maxConcurrency, Integer requestsPerMinute) {

  /** The most calls in flight at once unless configured otherwise. */
  public static final int DEFAULT_MAX_CONCURRENCY = 8;

  /** Eight calls at once and no rate limit. */
  public static final CallLimits DEFAULT = new CallLimits(DEFAULT_MAX_CONCURRENCY, null);

  public CallLimits {
    if (maxConcurrency < 1) {
      throw new IllegalArgumentException("Max concurrency must be positive: " + maxConcurrency);
    }
    if (requestsPerMinute != null && requestsPerMinute < 1) {
      throw new IllegalArgumentException(
          "Requests per minute must be positive: " + requestsPerMinute);
    }
  }
}

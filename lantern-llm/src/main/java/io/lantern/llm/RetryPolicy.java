package io.lantern.llm;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * When {@link LlmClient} sends a call again after a {@link TransientLlmException}: up to a number
 * of attempts in all, waiting longer each time (exponential backoff with jitter), or as long as the
 * provider asked.
 *
 * @param maxAttempts the most times one call is sent, the first included; 1 means no retries
 * @param initialBackoff the wait before the second attempt, doubled before each later one
 * @param maxBackoff the longest wait computed by doubling; a provider's own wait is not capped
 */
public record RetryPolicy(int maxAttempts, Duration initialBackoff, Duration maxBackoff) {

  /** Five attempts, waiting about 1, 2, 4 and 8 seconds between them, at most a minute. */
  public static final RetryPolicy DEFAULT =
      new RetryPolicy(5, Duration.ofSeconds(1), Duration.ofMinutes(1));

  public RetryPolicy {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("Max attempts must be positive: " + maxAttempts);
    }
    Objects.requireNonNull(initialBackoff, "initialBackoff");
    Objects.requireNonNull(maxBackoff, "maxBackoff");
    if (initialBackoff.isNegative() || maxBackoff.isNegative()) {
      throw new IllegalArgumentException("Backoff must not be negative");
    }
    if (maxBackoff.compareTo(initialBackoff) < 0) {
      throw new IllegalArgumentException(
          "Max backoff " + maxBackoff + " is shorter than the initial backoff " + initialBackoff);
    }
  }

  /**
   * How long to wait after the given failed attempt. Without a wait from the provider it is the
   * backoff for the attempt, doubled each time up to the maximum, of which a random part between
   * half and all is used, so calls that failed together do not all come back together. With one, it
   * is that wait plus a random part of the initial backoff.
   *
   * @param attempt the attempt that failed, starting at 1
   * @param retryAfter how long the provider asked to wait, if it said
   */
  public Duration delay(int attempt, Optional<Duration> retryAfter) {
    if (attempt < 1) {
      throw new IllegalArgumentException("Attempt must be positive: " + attempt);
    }
    ThreadLocalRandom random = ThreadLocalRandom.current();
    if (retryAfter.isPresent()) {
      long jitter = initialBackoff.toNanos() == 0 ? 0 : random.nextLong(initialBackoff.toNanos());
      return retryAfter.get().plusNanos(jitter);
    }
    long max = maxBackoff.toNanos();
    long backoff = initialBackoff.toNanos();
    for (int i = 1; i < attempt && backoff < max; i++) {
      backoff = backoff > max / 2 ? max : backoff * 2;
    }
    backoff = Math.min(backoff, max);
    return Duration.ofNanos(random.nextLong(backoff / 2, backoff + 1));
  }
}

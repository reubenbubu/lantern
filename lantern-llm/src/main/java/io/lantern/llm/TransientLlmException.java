package io.lantern.llm;

import java.time.Duration;
import java.util.Optional;

/**
 * A call failed in a way that may pass if it is sent again: the provider limited the rate (HTTP
 * 429), was overloaded or failed itself (5xx), or could not be reached. {@link LlmClient} retries
 * these by its {@link RetryPolicy}; other failures are not retried.
 */
public class TransientLlmException extends LlmException {

  private final Duration retryAfter;

  /**
   * @param message what failed
   * @param retryAfter how long the provider asked to wait before trying again, or {@code null} if
   *     it did not say
   * @param cause the provider's error, or {@code null}
   */
  public TransientLlmException(String message, Duration retryAfter, Throwable cause) {
    super(message, cause);
    if (retryAfter != null && retryAfter.isNegative()) {
      throw new IllegalArgumentException("Retry-after must not be negative: " + retryAfter);
    }
    this.retryAfter = retryAfter;
  }

  public TransientLlmException(String message, Duration retryAfter) {
    this(message, retryAfter, null);
  }

  /** How long the provider asked to wait, if it said. */
  public Optional<Duration> retryAfter() {
    return Optional.ofNullable(retryAfter);
  }
}

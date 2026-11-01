package io.lantern.llm;

import java.time.Duration;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The steps of a batch after it was created: reading its status and its results. A transient
 * failure there is retried in place, by the batch's id, never by sending the batch again, which
 * would pay for it twice (ADR 0102). What still fails is not transient, so {@link LlmClient} does
 * not resend the batch either.
 */
public final class BatchPolling {

  /** How many times one step is tried before the batch is given up. */
  public static final int ATTEMPTS = 10;

  private BatchPolling() {}

  /**
   * Runs the step, retrying a failure that {@code error} turns into a {@link TransientLlmException}
   * up to {@value #ATTEMPTS} times, waiting the given time (or the time the server asked for)
   * between tries.
   *
   * @throws LlmException a failure that is not transient, or the last transient one, as not
   *     transient
   */
  public static <T> T step(
      String what,
      Duration wait,
      Supplier<T> step,
      Function<RuntimeException, LlmException> error) {
    for (int attempt = 1; ; attempt++) {
      LlmException failure;
      try {
        return step.get();
      } catch (LlmException e) {
        failure = e;
      } catch (RuntimeException e) {
        failure = error.apply(e);
      }
      if (!(failure instanceof TransientLlmException transientFailure)) {
        throw failure;
      }
      if (attempt >= ATTEMPTS) {
        throw new LlmException(
            "Gave up on %s after %d attempts: %s".formatted(what, attempt, failure.getMessage()),
            failure);
      }
      try {
        Thread.sleep(transientFailure.retryAfter().orElse(wait));
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new LlmException("Interrupted while " + what, interrupted);
      }
    }
  }
}

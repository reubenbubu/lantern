package io.lantern.llm;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Bounds and paces the calls to each model, and says how failed calls are retried. Each model (a
 * provider and a model identifier) has a lane with its {@link ModelConfig#limits()}: at most so
 * many calls in flight, started at most so often, and held back while the provider has asked for a
 * pause. Clients that share a limiter share its lanes, so give every client of a process the same
 * one.
 *
 * <p>A lane takes its limits from the first model configuration it is asked for; configurations of
 * the same model should agree.
 *
 * <p>Thread-safe.
 */
public final class CallLimiter {

  private final RetryPolicy retryPolicy;
  private final Map<String, Lane> lanes = new ConcurrentHashMap<>();

  /** A limiter whose failed calls are retried by the given policy. */
  public CallLimiter(RetryPolicy retryPolicy) {
    this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
  }

  /** How failed calls are retried. */
  public RetryPolicy retryPolicy() {
    return retryPolicy;
  }

  Lane lane(ModelConfig model) {
    return lanes.computeIfAbsent(
        model.provider() + "\n" + model.model(), key -> new Lane(model.limits()));
  }

  /** The calls to one model. */
  static final class Lane {
    private final Semaphore permits;
    private final long intervalNanos;
    private long nextStart;
    private long pausedUntil;

    private Lane(CallLimits limits) {
      permits = new Semaphore(limits.maxConcurrency(), true);
      intervalNanos =
          limits.requestsPerMinute() == null
              ? 0
              : TimeUnit.MINUTES.toNanos(1) / limits.requestsPerMinute();
      long now = System.nanoTime();
      nextStart = now;
      pausedUntil = now;
    }

    /** Waits for a free slot; give it back with {@link #release()}. */
    void acquire() throws InterruptedException {
      permits.acquire();
    }

    void release() {
      permits.release();
    }

    /**
     * Waits until the next attempt may start: not before the end of a pause, and, with a rate
     * limit, an even share of a minute after the attempt before.
     */
    void awaitTurn() throws InterruptedException {
      long start;
      synchronized (this) {
        long now = System.nanoTime();
        start = later(now, later(nextStart, pausedUntil));
        if (intervalNanos > 0) {
          nextStart = start + intervalNanos;
        }
      }
      long wait = start - System.nanoTime();
      if (wait > 0) {
        TimeUnit.NANOSECONDS.sleep(wait);
      }
    }

    /** Holds back attempts that have not started yet for the given time, from now. */
    void pause(Duration duration) {
      long nanos;
      try {
        nanos = duration.toNanos();
      } catch (ArithmeticException tooLong) {
        nanos = Long.MAX_VALUE / 2;
      }
      long until = System.nanoTime() + Math.min(nanos, Long.MAX_VALUE / 2);
      synchronized (this) {
        pausedUntil = later(pausedUntil, until);
      }
    }

    private static long later(long a, long b) {
      return a - b >= 0 ? a : b;
    }
  }
}

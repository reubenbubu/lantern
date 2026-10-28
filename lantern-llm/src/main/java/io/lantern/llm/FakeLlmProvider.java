package io.lantern.llm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A provider for tests that never leaves the process. It answers with a function of the request (by
 * default a fixed text), reports token usage estimated from the text lengths, and remembers every
 * request it was sent. It can also take a while to answer and fail the first attempts of each
 * request, for example with a {@link TransientLlmException} like a rate limit, and it counts how
 * many calls were in flight at once.
 *
 * <p>Thread-safe if the answering function is.
 */
public final class FakeLlmProvider implements LlmProvider {

  /** The provider name fakes use unless given another. */
  public static final String NAME = "fake";

  private final String name;
  private final List<Sent> sent = new ArrayList<>();
  private volatile Function<LlmRequest, String> answer = request -> "fake answer";
  private volatile Duration latency = Duration.ZERO;
  private int failuresPerRequest;
  private Supplier<? extends RuntimeException> failure;
  private final Map<LlmRequest, Integer> attempts = new HashMap<>();
  private final AtomicInteger inFlight = new AtomicInteger();
  private final AtomicInteger maxInFlight = new AtomicInteger();

  public FakeLlmProvider() {
    this(NAME);
  }

  public FakeLlmProvider(String name) {
    this.name = ModelConfig.nonBlank("Provider name", name);
  }

  /** A free model of this provider with every capability and a large context window. */
  public ModelConfig model(String model) {
    return new ModelConfig(name, model, EnumSet.allOf(Capability.class), 1_000_000, Pricing.FREE);
  }

  /** Answers every request with the given text. */
  public FakeLlmProvider answering(String text) {
    Objects.requireNonNull(text, "text");
    return answering(request -> text);
  }

  /**
   * Answers each request with the function's result. A function that throws makes the call fail
   * with that exception.
   */
  public FakeLlmProvider answering(Function<LlmRequest, String> answer) {
    this.answer = Objects.requireNonNull(answer, "answer");
    return this;
  }

  /** Takes the given time to answer or fail each call. */
  public FakeLlmProvider withLatency(Duration latency) {
    Objects.requireNonNull(latency, "latency");
    if (latency.isNegative()) {
      throw new IllegalArgumentException("Latency must not be negative: " + latency);
    }
    this.latency = latency;
    return this;
  }

  /**
   * From now on, fails the first given number of attempts of each distinct request with an
   * exception from the supplier, then answers it. Which attempts fail depends only on the requests,
   * not on the order they arrive in.
   */
  public FakeLlmProvider failingFirst(int attempts, Supplier<? extends RuntimeException> failure) {
    if (attempts < 0) {
      throw new IllegalArgumentException("Attempts must not be negative: " + attempts);
    }
    Objects.requireNonNull(failure, "failure");
    synchronized (this) {
      this.failure = failure;
      this.failuresPerRequest = attempts;
      this.attempts.clear();
    }
    return this;
  }

  /** The most calls that were in flight at the same time so far. */
  public int maxConcurrentCalls() {
    return maxInFlight.get();
  }

  /** The requests sent so far, oldest first. */
  public synchronized List<Sent> sent() {
    return List.copyOf(sent);
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public LlmResponse complete(String model, LlmRequest request) {
    RuntimeException failed = null;
    synchronized (this) {
      sent.add(new Sent(model, request));
      if (attempts.merge(request, 1, Integer::sum) <= failuresPerRequest) {
        failed = failure.get();
      }
    }
    maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
    try {
      return answer(request, failed);
    } finally {
      inFlight.decrementAndGet();
    }
  }

  private LlmResponse answer(LlmRequest request, RuntimeException failed) {
    if (!latency.isZero()) {
      try {
        Thread.sleep(latency);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new LlmException("Interrupted", e);
      }
    }
    if (failed != null) {
      throw failed;
    }
    String text = answer.apply(request);
    long input = request.estimatedInputTokens();
    TokenUsage usage =
        request.cacheSystem()
            ? new TokenUsage(
                input - Tokens.estimate(request.system()),
                Tokens.estimate(text),
                Tokens.estimate(request.system()),
                0)
            : new TokenUsage(input, Tokens.estimate(text), 0, 0);
    return new LlmResponse(text, "end_turn", usage);
  }

  /**
   * A request the fake was sent.
   *
   * @param model the model it was sent to
   * @param request the request
   */
  public record Sent(String model, LlmRequest request) {}
}

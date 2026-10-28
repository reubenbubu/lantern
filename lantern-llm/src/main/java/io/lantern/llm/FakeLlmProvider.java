package io.lantern.llm;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * A provider for tests that never leaves the process. It answers with a function of the request (by
 * default a fixed text), reports token usage estimated from the text lengths, and remembers every
 * request it was sent.
 *
 * <p>Thread-safe if the answering function is.
 */
public final class FakeLlmProvider implements LlmProvider {

  /** The provider name fakes use unless given another. */
  public static final String NAME = "fake";

  private final String name;
  private final List<Sent> sent = new ArrayList<>();
  private volatile Function<LlmRequest, String> answer = request -> "fake answer";

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
    synchronized (this) {
      sent.add(new Sent(model, request));
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

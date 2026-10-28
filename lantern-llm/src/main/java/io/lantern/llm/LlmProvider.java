package io.lantern.llm;

/**
 * Sends requests to one LLM service. Callers use {@link LlmClient}, which picks the model, checks
 * capabilities and the budget and logs the call; providers only translate and send.
 */
public interface LlmProvider {

  /** The name that {@link ModelConfig#provider()} refers to, for example {@code anthropic}. */
  String name();

  /**
   * Sends the request to the given model and returns its answer.
   *
   * @throws LlmException if the call fails
   */
  LlmResponse complete(String model, LlmRequest request);

  /**
   * Sends the request to the given model, applying the model's settings that change what is sent.
   * {@link LlmClient} calls this one. The request's output limit already includes the model's
   * {@linkplain Thinking#headroomTokens() thinking headroom}. A provider that can turn thinking off
   * does so when {@link Thinking#disabled()} is set; the default ignores it and sends the request
   * to {@link ModelConfig#model()}.
   *
   * @throws LlmException if the call fails
   */
  default LlmResponse complete(ModelConfig model, LlmRequest request) {
    return complete(model.model(), request);
  }
}

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
}

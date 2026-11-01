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

  /** Whether this provider has a batch API ({@link #completeBatch}). */
  default boolean batches() {
    return false;
  }

  /**
   * Sends the requests through the provider's batch API, which answers them within a day at the
   * batch discount, and waits for the batch to end. Requests run in any order; each gets its own
   * result, in the order given. A request the batch failed, cancelled or let expire gets an error
   * result; a failure of the batch as a whole is thrown.
   *
   * @throws LlmException if the batch could not be sent or read back
   * @throws UnsupportedOperationException if the provider has no batch API
   */
  default java.util.List<LlmBatchResult> completeBatch(
      ModelConfig model, java.util.List<LlmRequest> requests) {
    throw new UnsupportedOperationException(name() + " has no batch API");
  }
}

package io.lantern.llm;

/**
 * The result of one request of a batch: its answer, or why it got none.
 *
 * @param response the answer, or {@code null} if it failed
 * @param error why the request failed, was refused or expired, or {@code null} if it was answered
 */
public record LlmBatchResult(LlmResponse response, LlmException error) {

  public LlmBatchResult {
    if ((response == null) == (error == null)) {
      throw new IllegalArgumentException("A batch result has either an answer or an error");
    }
  }

  /** An answered request. */
  public static LlmBatchResult answered(LlmResponse response) {
    return new LlmBatchResult(response, null);
  }

  /** A request that got no answer. */
  public static LlmBatchResult failed(LlmException error) {
    return new LlmBatchResult(null, error);
  }
}

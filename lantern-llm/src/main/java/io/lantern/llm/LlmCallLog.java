package io.lantern.llm;

/** Where {@link LlmClient} records every call, with its role and purpose. */
public interface LlmCallLog {

  /** Records one call. Must be thread-safe. */
  void record(LlmCall call);

  /**
   * Records how a conversation went, once it ended; its turns were recorded as calls before. Must
   * be thread-safe. The default records nothing beyond the calls.
   */
  default void recordConversation(LlmConversation conversation) {}
}

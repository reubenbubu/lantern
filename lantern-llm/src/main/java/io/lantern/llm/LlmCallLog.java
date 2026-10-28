package io.lantern.llm;

/** Where {@link LlmClient} records every call, with its role and purpose. */
public interface LlmCallLog {

  /** Records one call. Must be thread-safe. */
  void record(LlmCall call);
}

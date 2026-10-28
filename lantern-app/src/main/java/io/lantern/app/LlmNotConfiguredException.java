package io.lantern.app;

import io.lantern.llm.LlmException;

/** LLM work was asked for, but the LLM is not configured. The message says what is missing. */
public class LlmNotConfiguredException extends LlmException {

  public LlmNotConfiguredException(String reason) {
    super("The LLM is not configured: " + reason);
  }
}

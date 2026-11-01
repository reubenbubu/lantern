package io.lantern.llm;

/** A conversation reached one of its {@linkplain ConversationLimits caps} and ended. */
public final class ConversationCappedException extends LlmException {

  public ConversationCappedException(String message) {
    super(message);
  }
}

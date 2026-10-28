package io.lantern.llm;

import java.util.Objects;

/**
 * One turn of a conversation.
 *
 * @param author who wrote it
 * @param text what it says
 */
public record LlmMessage(Author author, String text) {

  /** Who wrote a turn. */
  public enum Author {
    USER,
    ASSISTANT
  }

  public LlmMessage {
    Objects.requireNonNull(author, "author");
    ModelConfig.nonBlank("Message text", text);
  }

  public static LlmMessage user(String text) {
    return new LlmMessage(Author.USER, text);
  }

  public static LlmMessage assistant(String text) {
    return new LlmMessage(Author.ASSISTANT, text);
  }
}

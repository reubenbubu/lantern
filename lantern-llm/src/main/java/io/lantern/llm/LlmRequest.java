package io.lantern.llm;

import java.util.List;
import java.util.Objects;

/**
 * One call to the LLM.
 *
 * @param role what the call is for, which decides the model
 * @param purpose a short description of this particular call, for the call log (for example
 *     "summarize class io.example.Order"); not sent to the model
 * @param system the system prompt, or {@code null} for none
 * @param messages the conversation, starting and ending with a user turn
 * @param maxOutputTokens the most tokens the model may write
 * @param cacheSystem whether to cache the system prompt; needs {@link Capability#PROMPT_CACHING}
 */
public record LlmRequest(
    Role role,
    String purpose,
    String system,
    List<LlmMessage> messages,
    int maxOutputTokens,
    boolean cacheSystem) {

  public LlmRequest {
    Objects.requireNonNull(role, "role");
    purpose = ModelConfig.nonBlank("Purpose", purpose);
    if (system != null && system.isBlank()) {
      throw new IllegalArgumentException("System prompt must be null or not blank");
    }
    messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
    if (messages.isEmpty()
        || messages.getFirst().author() != LlmMessage.Author.USER
        || messages.getLast().author() != LlmMessage.Author.USER) {
      throw new IllegalArgumentException("Messages must start and end with a user turn");
    }
    if (maxOutputTokens < 1) {
      throw new IllegalArgumentException("Max output tokens must be positive: " + maxOutputTokens);
    }
    if (cacheSystem && system == null) {
      throw new IllegalArgumentException("Cannot cache a missing system prompt");
    }
  }

  /** A single-turn request without prompt caching. */
  public static LlmRequest of(
      Role role, String purpose, String system, String user, int maxOutputTokens) {
    return new LlmRequest(
        role, purpose, system, List.of(LlmMessage.user(user)), maxOutputTokens, false);
  }

  /** A rough count of the input tokens, about four characters per token. */
  public long estimatedInputTokens() {
    long chars = system == null ? 0 : system.length();
    for (LlmMessage message : messages) {
      chars += message.text().length();
    }
    return Tokens.estimate(chars);
  }
}

package io.lantern.llm;

import java.util.Objects;

/**
 * What the model returned.
 *
 * @param text the text it wrote, without any thinking
 * @param stopReason why it stopped, as the provider reports it (for example {@code end_turn} or
 *     {@code max_tokens}), or {@code null} if not reported
 * @param usage the tokens the call used
 */
public record LlmResponse(String text, String stopReason, TokenUsage usage) {

  /** The stop reason of an answer cut off at its output limit, as Anthropic reports it. */
  public static final String MAX_TOKENS = "max_tokens";

  public LlmResponse {
    Objects.requireNonNull(text, "text");
    Objects.requireNonNull(usage, "usage");
  }

  /**
   * Whether the answer stopped at the output limit, so the text is cut off. Thinking tokens count
   * against the limit too, so a model that thinks can be cut off before or while it writes.
   */
  public boolean cutOff() {
    return MAX_TOKENS.equals(stopReason);
  }
}

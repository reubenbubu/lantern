package io.lantern.llm;

import java.util.Objects;

/**
 * What the model returned.
 *
 * @param text the text it wrote
 * @param stopReason why it stopped, as the provider reports it (for example {@code end_turn} or
 *     {@code max_tokens}), or {@code null} if not reported
 * @param usage the tokens the call used
 */
public record LlmResponse(String text, String stopReason, TokenUsage usage) {

  public LlmResponse {
    Objects.requireNonNull(text, "text");
    Objects.requireNonNull(usage, "usage");
  }
}

package io.lantern.llm;

import java.util.Objects;

/**
 * A block of a model's thinking, kept as the provider gave it so it can be sent back on the
 * assistant turn that asked for tools, as a provider that thinks requires (ADR 0081). It is never
 * part of an answer's text.
 *
 * @param text the thinking, or for redacted thinking the provider's opaque data
 * @param signature the provider's signature of the thinking, or {@code null} for redacted thinking
 * @param redacted whether the provider redacted it
 */
public record LlmThinking(String text, String signature, boolean redacted) {

  public LlmThinking {
    Objects.requireNonNull(text, "text");
    if (redacted && signature != null) {
      throw new IllegalArgumentException("Redacted thinking has no signature");
    }
  }
}

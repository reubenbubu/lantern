package io.lantern.llm;

/** How a role uses the LLM, which decides the default model for it. */
public enum Tier {
  /** Many calls on small inputs; a fast, cheaper model (Sonnet). */
  HIGH_VOLUME,
  /** Fewer calls that need careful reasoning; the strongest model (Opus). */
  REASONING
}

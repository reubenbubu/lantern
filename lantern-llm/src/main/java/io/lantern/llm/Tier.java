package io.lantern.llm;

/** How a role uses the LLM, which decides the default model for it. */
public enum Tier {
  /**
   * The highest-volume roles, such as summaries of types and the injection screen (knowledge-build
   * epic, section 11.0); the high-volume tier's model unless set.
   */
  BULK,
  /** Many calls on small inputs; a fast, cheaper model (Sonnet). */
  HIGH_VOLUME,
  /** Fewer calls that need careful reasoning; the strongest model (Opus). */
  REASONING
}

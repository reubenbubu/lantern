package io.lantern.llm;

/** Optional features a configured model may support. Callers check them instead of assuming. */
public enum Capability {
  TOOL_USE,
  STRUCTURED_OUTPUT,
  PROMPT_CACHING,
  /**
   * The provider's batch API, at its batch discount ({@link Pricing#batchCost}); used for a bulk
   * role's cache misses (knowledge-build epic, section 10, Run modes).
   */
  BATCH
}

package io.lantern.llm;

/** Optional features a configured model may support. Callers check them instead of assuming. */
public enum Capability {
  TOOL_USE,
  STRUCTURED_OUTPUT,
  PROMPT_CACHING
}

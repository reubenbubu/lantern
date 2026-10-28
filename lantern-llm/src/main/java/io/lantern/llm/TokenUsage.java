package io.lantern.llm;

/**
 * Tokens used by one call.
 *
 * @param input input tokens billed at the normal input price
 * @param output output tokens
 * @param cacheWrite input tokens written to the prompt cache
 * @param cacheRead input tokens read from the prompt cache
 */
public record TokenUsage(long input, long output, long cacheWrite, long cacheRead) {

  public static final TokenUsage NONE = new TokenUsage(0, 0, 0, 0);

  public TokenUsage {
    if (input < 0 || output < 0 || cacheWrite < 0 || cacheRead < 0) {
      throw new IllegalArgumentException("Token counts must not be negative");
    }
  }

  /** All tokens, input of every kind plus output. */
  public long total() {
    return input + output + cacheWrite + cacheRead;
  }
}

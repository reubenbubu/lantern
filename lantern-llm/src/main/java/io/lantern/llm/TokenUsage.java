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

  /**
   * The same usage with its cache writes and reads counted as normal input, for a model without
   * prompt caching whose server still reports part of the prompt as cache tokens.
   */
  public TokenUsage cacheAsInput() {
    return new TokenUsage(Math.addExact(Math.addExact(input, cacheWrite), cacheRead), output, 0, 0);
  }

  /** All tokens, input of every kind plus output. */
  public long total() {
    return input + output + cacheWrite + cacheRead;
  }
}

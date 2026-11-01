package io.lantern.llm;

/**
 * Tokens used by one call.
 *
 * @param input input tokens billed at the normal input price
 * @param output output tokens
 * @param cacheWrite input tokens written to the prompt cache with the default, five-minute lifetime
 * @param cacheRead input tokens read from the prompt cache
 * @param cacheWriteLong input tokens written to the prompt cache with the long, one-hour lifetime,
 *     which costs more than a default write (ADR 0102)
 */
public record TokenUsage(
    long input, long output, long cacheWrite, long cacheRead, long cacheWriteLong) {

  public static final TokenUsage NONE = new TokenUsage(0, 0, 0, 0, 0);

  public TokenUsage {
    if (input < 0 || output < 0 || cacheWrite < 0 || cacheRead < 0 || cacheWriteLong < 0) {
      throw new IllegalArgumentException("Token counts must not be negative");
    }
  }

  /** Usage with no long-lived cache write. */
  public TokenUsage(long input, long output, long cacheWrite, long cacheRead) {
    this(input, output, cacheWrite, cacheRead, 0);
  }

  /** The two usages added together. */
  public TokenUsage plus(TokenUsage other) {
    return new TokenUsage(
        Math.addExact(input, other.input),
        Math.addExact(output, other.output),
        Math.addExact(cacheWrite, other.cacheWrite),
        Math.addExact(cacheRead, other.cacheRead),
        Math.addExact(cacheWriteLong, other.cacheWriteLong));
  }

  /** The tokens written to the prompt cache, of either lifetime. */
  public long cacheWritten() {
    return cacheWrite + cacheWriteLong;
  }

  /**
   * The same usage with its cache writes and reads counted as normal input, for a model without
   * prompt caching whose server still reports part of the prompt as cache tokens.
   */
  public TokenUsage cacheAsInput() {
    return new TokenUsage(
        Math.addExact(Math.addExact(input, cacheWritten()), cacheRead), output, 0, 0, 0);
  }

  /** All tokens, input of every kind plus output. */
  public long total() {
    return input + output + cacheWrite + cacheRead + cacheWriteLong;
  }
}

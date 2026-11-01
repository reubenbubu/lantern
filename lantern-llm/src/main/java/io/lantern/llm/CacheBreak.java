package io.lantern.llm;

/**
 * A cache breakpoint of a request (knowledge-build epic, section 10, Prompt layout for caching):
 * the provider may cache everything sent up to it, tools and system prompt first. The harness marks
 * breakpoints abstractly; the Anthropic provider maps each to a {@code cache_control} block, and a
 * provider that caches by stable prefix ignores them.
 *
 * @param message the index of the turn in the request's messages, or {@link #SYSTEM} for the system
 *     prompt
 * @param offset the characters of the turn's text (or the system prompt) before the breakpoint,
 *     from 1 up to the text's length; {@link #END} for after the turn's last block, its tool calls
 *     or tool results included
 * @param longLived whether the cached prefix should outlive the provider's default lifetime, as a
 *     bulk run's warm-up needs for its batch to read it (one hour on Anthropic)
 */
public record CacheBreak(int message, int offset, boolean longLived) {

  /** The {@link #message} of a breakpoint in the system prompt. */
  public static final int SYSTEM = -1;

  /** The {@link #offset} of a breakpoint after a turn's last block. */
  public static final int END = Integer.MAX_VALUE;

  /** The most breakpoints one request may carry, Anthropic's limit. */
  public static final int MAX = 4;

  public CacheBreak {
    if (message < SYSTEM) {
      throw new IllegalArgumentException("Message index must be -1 or more: " + message);
    }
    if (offset < 1) {
      throw new IllegalArgumentException("Offset must be positive: " + offset);
    }
  }

  /** A breakpoint with the provider's default lifetime. */
  public CacheBreak(int message, int offset) {
    this(message, offset, false);
  }

  /** A breakpoint after the given turn's last block. */
  public static CacheBreak afterMessage(int message) {
    return new CacheBreak(message, END, false);
  }

  /** A breakpoint after the first characters of the system prompt. */
  public static CacheBreak inSystem(int offset) {
    return new CacheBreak(SYSTEM, offset, false);
  }

  /** The same breakpoint with a long lifetime. */
  public CacheBreak withLongLife() {
    return new CacheBreak(message, offset, true);
  }
}

package io.lantern.llm;

/**
 * How Lantern deals with a model that thinks before it answers. Thinking tokens count against a
 * call's output limit, so a model that thinks by default can use up a limit sized for the answer
 * alone.
 *
 * @param disabled whether to ask the provider to turn thinking off (for Anthropic, {@code thinking:
 *     {type: "disabled"}}); only some providers honor it
 * @param headroomTokens extra output tokens added to every call's output limit, for a model that
 *     thinks even when asked not to; counted in the budget and the context window like the rest of
 *     the limit
 */
public record Thinking(boolean disabled, int headroomTokens) {

  /** Thinking left as the provider has it, and no extra output tokens. */
  public static final Thinking DEFAULT = new Thinking(false, 0);

  public Thinking {
    if (headroomTokens < 0) {
      throw new IllegalArgumentException(
          "Thinking headroom tokens must not be negative: " + headroomTokens);
    }
  }
}

package io.lantern.llm;

import java.util.Objects;

/**
 * How Lantern deals with a model that thinks before it answers. Thinking tokens count against a
 * call's output limit, so a model that thinks by default can use up a limit sized for the answer
 * alone.
 *
 * @param disabled whether to ask the provider to turn thinking off, in the way {@code offSwitch}
 *     names; only some providers honor it
 * @param headroomTokens extra output tokens added to every call's output limit, for a model that
 *     thinks even when asked not to; counted in the budget and the context window like the rest of
 *     the limit
 * @param offSwitch how the model turns thinking off; asking a model with {@link OffSwitch#NONE} to
 *     turn it off is refused
 */
public record Thinking(boolean disabled, int headroomTokens, OffSwitch offSwitch) {

  /** Thinking left as the provider has it, and no extra output tokens. */
  public static final Thinking DEFAULT = new Thinking(false, 0);

  /** How a model turns its thinking off, which differs between models of one provider. */
  public enum OffSwitch {
    /** {@code thinking: {type: "disabled"}}, which most models that can turn thinking off take. */
    DISABLED,
    /**
     * {@code thinking: {type: "between_tools"}} with no other field, for a model that rejects
     * {@code disabled}.
     */
    BETWEEN_TOOLS,
    /** The model cannot turn thinking off. */
    NONE
  }

  public Thinking {
    if (headroomTokens < 0) {
      throw new IllegalArgumentException(
          "Thinking headroom tokens must not be negative: " + headroomTokens);
    }
    Objects.requireNonNull(offSwitch, "offSwitch");
    if (disabled && offSwitch == OffSwitch.NONE) {
      throw new IllegalArgumentException(
          "Thinking cannot be turned off for a model whose thinking off switch is none; leave"
              + " thinking on and give the model thinking headroom tokens instead");
    }
  }

  /** Thinking turned off, if asked, with {@code thinking: {type: "disabled"}}. */
  public Thinking(boolean disabled, int headroomTokens) {
    this(disabled, headroomTokens, OffSwitch.DISABLED);
  }
}

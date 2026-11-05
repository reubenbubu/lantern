package io.lantern.llm;

import java.util.Objects;
import java.util.Set;

/**
 * The caps of one conversation (knowledge-build epic, section 10, Agent loop). At {@value
 * #NUDGE_SHARE} of any cap the next tool results go back with the {@link #nudge} after them, once;
 * at a cap the conversation ends as {@link LlmConversation.Ending#CAPPED}, unless it has answer
 * turns left: then, from the nudge on, only the {@link #answerTools} run, and the conversation ends
 * when the model answers or those turns are spent (#892).
 *
 * @param toolTurns the most turns sent after the first, each answering the model's tool calls
 * @param inputTokens the most input in fresh-token equivalents over the whole conversation, where a
 *     cache read counts 0.1 and a cache write 1.25 of a token: the guard on its cost
 * @param outputTokensPerTurn the most tokens the model may write in one turn
 * @param nudge what the turn after the tool results says when a cap is near, never inside a tool
 *     result
 * @param contextTokens the most tokens one turn may send, all of the conversation so far as the
 *     model counts it, cached or not: the guard on its size, kept below a provider's price line
 * @param answerTurns how many turns the model gets to answer once a cap is near or reached, in
 *     which only the answer tools run; 0 for none
 * @param answerTools the tools that still run in those turns, such as {@code submit}
 */
public record ConversationLimits(
    int toolTurns,
    long inputTokens,
    int outputTokensPerTurn,
    String nudge,
    long contextTokens,
    int answerTurns,
    Set<String> answerTools) {

  /** The share of a cap at which the model is told to finish. */
  public static final double NUDGE_SHARE = 0.75;

  /** What the nudge says unless told otherwise. */
  public static final String DEFAULT_NUDGE =
      "You have used most of what this task allows. Stop exploring and give your answer now, with"
          + " what your inputs and tool results already show.";

  /** No caps beyond the conversation's safety cap on turns. */
  public static final ConversationLimits NONE =
      new ConversationLimits(Integer.MAX_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE, DEFAULT_NUDGE);

  public ConversationLimits {
    if (toolTurns < 0
        || inputTokens < 1
        || outputTokensPerTurn < 1
        || contextTokens < 1
        || answerTurns < 0) {
      throw new IllegalArgumentException("Caps must be positive");
    }
    nudge = ModelConfig.nonBlank("Nudge", Objects.requireNonNull(nudge, "nudge"));
    answerTools = Set.copyOf(Objects.requireNonNull(answerTools, "answerTools"));
  }

  /** The caps with no cap on a turn's size and no answer turns. */
  public ConversationLimits(
      int toolTurns, long inputTokens, int outputTokensPerTurn, String nudge) {
    this(toolTurns, inputTokens, outputTokensPerTurn, nudge, Long.MAX_VALUE, 0, Set.of());
  }

  /** The caps with the default nudge. */
  public ConversationLimits(int toolTurns, long inputTokens, int outputTokensPerTurn) {
    this(toolTurns, inputTokens, outputTokensPerTurn, DEFAULT_NUDGE);
  }

  /** These caps with a cap on a turn's size. */
  public ConversationLimits withContextTokens(long tokens) {
    return new ConversationLimits(
        toolTurns, inputTokens, outputTokensPerTurn, nudge, tokens, answerTurns, answerTools);
  }

  /** These caps with turns to answer in, near or at a cap, in which only these tools run. */
  public ConversationLimits withAnswerTurns(int turns, Set<String> tools) {
    return new ConversationLimits(
        toolTurns, inputTokens, outputTokensPerTurn, nudge, contextTokens, turns, tools);
  }

  /** These caps with another nudge. */
  public ConversationLimits withNudge(String text) {
    return new ConversationLimits(
        toolTurns, inputTokens, outputTokensPerTurn, text, contextTokens, answerTurns, answerTools);
  }

  /** The input a call used in fresh-token equivalents, rounded up. */
  public static long freshEquivalent(TokenUsage usage) {
    return usage.input()
        + (usage.cacheWrite() * 5 + 3) / 4
        + usage.cacheWriteLong() * 2
        + (usage.cacheRead() + 9) / 10;
  }

  /** What one turn sent, as the model counts it: its input, cached or not. */
  public static long sent(TokenUsage usage) {
    return usage.input() + usage.cacheWrite() + usage.cacheWriteLong() + usage.cacheRead();
  }
}

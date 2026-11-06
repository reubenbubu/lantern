package io.lantern.llm;

import java.util.Objects;

/**
 * The caps of one conversation, each cumulative over the whole conversation (knowledge-build epic,
 * section 10, Agent loop). At {@value #NUDGE_SHARE} of any cap the next tool results go back with
 * the {@link #nudge} after them, once; at a cap the conversation ends as {@link
 * LlmConversation.Ending#CAPPED}.
 *
 * @param toolTurns the most turns sent after the first, each answering the model's tool calls
 * @param inputTokens the most input in fresh-token equivalents, where a cache read counts 0.1 and a
 *     cache write 1.25 of a token
 * @param outputTokensPerTurn the most tokens the model may write in one turn
 * @param nudge what the turn after the tool results says when a cap is near, never inside a tool
 *     result
 * @param turnInputTokens the most input one turn may send, as estimated before it is sent: a guard
 *     well under the model's price line, unlike the cumulative caps
 * @param onlyTool once the nudge is given, the one tool that still runs (the answer); null for all
 * @param safetyTurns the safety stop on turns, or 0 for the conversation's default
 */
public record ConversationLimits(
    int toolTurns,
    long inputTokens,
    int outputTokensPerTurn,
    String nudge,
    long turnInputTokens,
    String onlyTool,
    int safetyTurns) {

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
        || turnInputTokens < 1
        || safetyTurns < 0) {
      throw new IllegalArgumentException("Caps must be positive");
    }
    nudge = ModelConfig.nonBlank("Nudge", Objects.requireNonNull(nudge, "nudge"));
  }

  /** The cumulative caps with a nudge, no guard on one turn's input, the default safety stop. */
  public ConversationLimits(
      int toolTurns, long inputTokens, int outputTokensPerTurn, String nudge) {
    this(toolTurns, inputTokens, outputTokensPerTurn, nudge, Long.MAX_VALUE, null, 0);
  }

  /**
   * These caps with a guard on what one turn sends, the one tool that runs once the nudge is given,
   * and a safety stop on turns.
   */
  public ConversationLimits withTurnGuard(long turnInputTokens, String onlyTool, int safetyTurns) {
    return new ConversationLimits(
        toolTurns, inputTokens, outputTokensPerTurn, nudge, turnInputTokens, onlyTool, safetyTurns);
  }

  /** These caps with another nudge. */
  public ConversationLimits withNudge(String nudge) {
    return new ConversationLimits(
        toolTurns, inputTokens, outputTokensPerTurn, nudge, turnInputTokens, onlyTool, safetyTurns);
  }

  /** The caps with the default nudge. */
  public ConversationLimits(int toolTurns, long inputTokens, int outputTokensPerTurn) {
    this(toolTurns, inputTokens, outputTokensPerTurn, DEFAULT_NUDGE);
  }

  /** The input a call used in fresh-token equivalents, rounded up. */
  public static long freshEquivalent(TokenUsage usage) {
    return usage.input()
        + (usage.cacheWrite() * 5 + 3) / 4
        + usage.cacheWriteLong() * 2
        + (usage.cacheRead() + 9) / 10;
  }
}

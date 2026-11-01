package io.lantern.llm;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * One entry of the call log for a {@link Conversation}: one request to the model with its tool
 * loop, however many turns it took. Each turn is also logged as an {@link LlmCall} with the
 * conversation's id, its turn number, tokens and cost.
 *
 * @param id the conversation's id, which its turns' calls carry
 * @param startedAt when its first turn started
 * @param duration how long it took, tool runs included
 * @param role what it was for
 * @param purpose the caller's description of it
 * @param provider the provider name
 * @param model the model identifier
 * @param turns how many calls it sent, retries of a failed call not counted
 * @param toolCalls the tool calls the model asked for, by tool name
 * @param usage the tokens of all its turns
 * @param costUsd what all its turns cost in US dollars
 * @param ending how it ended
 * @param error why it stopped, for an ending other than answered or rejected; else {@code null}
 */
public record LlmConversation(
    String id,
    Instant startedAt,
    Duration duration,
    Role role,
    String purpose,
    String provider,
    String model,
    int turns,
    Map<String, Integer> toolCalls,
    TokenUsage usage,
    BigDecimal costUsd,
    Ending ending,
    String error) {

  /** How a conversation ended. */
  public enum Ending {
    /** The model answered and the caller accepted the answer. */
    ANSWERED,
    /** The model answered but the caller rejected the answer. */
    REJECTED,
    /** A budget refused the next turn. */
    BUDGET,
    /** The conversation reached the safety cap on turns before the model answered. */
    TURN_CAP,
    /** A turn failed in the provider or was refused for another reason than a budget. */
    FAILED,
    /**
     * The conversation reached one of its {@linkplain ConversationLimits caps}; what the model
     * submitted before it is kept.
     */
    CAPPED
  }

  public LlmConversation {
    id = ModelConfig.nonBlank("Conversation id", id);
    Objects.requireNonNull(startedAt, "startedAt");
    Objects.requireNonNull(duration, "duration");
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(purpose, "purpose");
    Objects.requireNonNull(provider, "provider");
    Objects.requireNonNull(model, "model");
    if (turns < 0) {
      throw new IllegalArgumentException("Turns must not be negative: " + turns);
    }
    toolCalls = Collections.unmodifiableMap(new TreeMap<>(toolCalls));
    Objects.requireNonNull(usage, "usage");
    Objects.requireNonNull(costUsd, "costUsd");
    Objects.requireNonNull(ending, "ending");
  }

  /** How many tool calls the model asked for, of every tool. */
  public int toolCallCount() {
    return toolCalls.values().stream().mapToInt(Integer::intValue).sum();
  }
}

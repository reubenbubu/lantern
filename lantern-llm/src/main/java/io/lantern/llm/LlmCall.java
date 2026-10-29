package io.lantern.llm;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * One entry of the call log: a call that was sent, or refused before sending.
 *
 * @param startedAt when the call started
 * @param duration how long it took
 * @param role what the call was for
 * @param purpose the caller's description of this call
 * @param provider the provider name
 * @param model the model identifier
 * @param outcome how it ended
 * @param usage the tokens it used ({@link TokenUsage#NONE} unless it succeeded or was truncated)
 * @param costUsd what it cost in US dollars (zero unless it succeeded or was truncated)
 * @param error why it failed, was refused or was truncated, or {@code null} if it succeeded
 * @param conversation the {@linkplain LlmConversation#id() conversation} the call is a turn of, or
 *     {@code null} for a call on its own
 * @param turn the call's turn in its conversation, from 1; 0 for a call on its own
 * @param toolCalls the tool calls the answer asked for, by tool name; empty if none
 */
public record LlmCall(
    Instant startedAt,
    Duration duration,
    Role role,
    String purpose,
    String provider,
    String model,
    Outcome outcome,
    TokenUsage usage,
    BigDecimal costUsd,
    String error,
    String conversation,
    int turn,
    Map<String, Integer> toolCalls) {

  /** How a call ended. */
  public enum Outcome {
    /** The model answered. */
    SUCCEEDED,
    /**
     * The model answered but stopped at the output limit, so the answer is cut off; it was billed
     * like a successful call.
     */
    TRUNCATED,
    /** The provider failed; the call may have been billed. */
    FAILED,
    /** Not sent: over budget, or the model lacks a needed capability. */
    REFUSED
  }

  public LlmCall {
    Objects.requireNonNull(startedAt, "startedAt");
    Objects.requireNonNull(duration, "duration");
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(purpose, "purpose");
    Objects.requireNonNull(provider, "provider");
    Objects.requireNonNull(model, "model");
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(usage, "usage");
    Objects.requireNonNull(costUsd, "costUsd");
    if (turn < 0 || (conversation == null) != (turn == 0)) {
      throw new IllegalArgumentException(
          "A call has a turn exactly when it is part of a conversation: " + turn);
    }
    toolCalls = Collections.unmodifiableMap(new TreeMap<>(toolCalls));
  }

  /** A call on its own, not a turn of a conversation. */
  public LlmCall(
      Instant startedAt,
      Duration duration,
      Role role,
      String purpose,
      String provider,
      String model,
      Outcome outcome,
      TokenUsage usage,
      BigDecimal costUsd,
      String error) {
    this(
        startedAt, duration, role, purpose, provider, model, outcome, usage, costUsd, error, null,
        0, Map.of());
  }
}

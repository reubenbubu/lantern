package io.lantern.llm;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

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
 * @param usage the tokens it used ({@link TokenUsage#NONE} unless it succeeded)
 * @param costUsd what it cost in US dollars (zero unless it succeeded)
 * @param error why it failed or was refused, or {@code null} if it succeeded
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
    String error) {

  /** How a call ended. */
  public enum Outcome {
    /** The model answered. */
    SUCCEEDED,
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
  }
}

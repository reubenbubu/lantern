package io.lantern.llm;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * A configured model and what it can do.
 *
 * @param provider the {@linkplain LlmProvider#name() name} of the provider that serves the model
 * @param model the provider's identifier for the model
 * @param capabilities the optional features the model supports
 * @param contextWindow the most tokens, input plus output, one call may use
 * @param pricing what the model costs
 * @param limits how many calls to the model may be in flight at once and how often they may start
 * @param thinking whether to turn the model's thinking off and how many extra output tokens to give
 *     it for thinking
 */
public record ModelConfig(
    String provider,
    String model,
    Set<Capability> capabilities,
    int contextWindow,
    Pricing pricing,
    CallLimits limits,
    Thinking thinking) {

  public ModelConfig {
    provider = nonBlank("Provider", provider);
    model = nonBlank("Model", model);
    capabilities =
        capabilities == null || capabilities.isEmpty()
            ? Set.of()
            : Set.copyOf(EnumSet.copyOf(capabilities));
    if (contextWindow < 1) {
      throw new IllegalArgumentException("Context window must be positive: " + contextWindow);
    }
    Objects.requireNonNull(pricing, "pricing");
    Objects.requireNonNull(limits, "limits");
    Objects.requireNonNull(thinking, "thinking");
  }

  /** A model with thinking left as the provider has it. */
  public ModelConfig(
      String provider,
      String model,
      Set<Capability> capabilities,
      int contextWindow,
      Pricing pricing,
      CallLimits limits) {
    this(provider, model, capabilities, contextWindow, pricing, limits, Thinking.DEFAULT);
  }

  /**
   * A model with the {@linkplain CallLimits#DEFAULT default limits} and thinking left as the
   * provider has it.
   */
  public ModelConfig(
      String provider,
      String model,
      Set<Capability> capabilities,
      int contextWindow,
      Pricing pricing) {
    this(provider, model, capabilities, contextWindow, pricing, CallLimits.DEFAULT);
  }

  /** The same model with other limits. */
  public ModelConfig withLimits(CallLimits limits) {
    return new ModelConfig(provider, model, capabilities, contextWindow, pricing, limits, thinking);
  }

  /** The same model with other thinking settings. */
  public ModelConfig withThinking(Thinking thinking) {
    return new ModelConfig(provider, model, capabilities, contextWindow, pricing, limits, thinking);
  }

  /**
   * The output limit sent to the model for a request: the request's own limit plus the model's
   * {@linkplain Thinking#headroomTokens() thinking headroom}.
   */
  public int outputLimit(LlmRequest request) {
    return Math.addExact(request.maxOutputTokens(), thinking.headroomTokens());
  }

  /**
   * Whether the request's system prompt is cached on this model: always when the model supports
   * {@linkplain Capability#PROMPT_CACHING prompt caching} and the request has a system prompt, so
   * that calls of one role, which share their system prompt, read it from the cache (#303).
   */
  public boolean cachesSystem(LlmRequest request) {
    return request.system() != null
        && (request.cacheSystem() || supports(Capability.PROMPT_CACHING));
  }

  /** Whether the model supports the given feature. */
  public boolean supports(Capability capability) {
    return capabilities.contains(capability);
  }

  static String nonBlank(String what, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(what + " must not be blank");
    }
    return value;
  }
}

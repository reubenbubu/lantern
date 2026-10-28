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
 */
public record ModelConfig(
    String provider,
    String model,
    Set<Capability> capabilities,
    int contextWindow,
    Pricing pricing,
    CallLimits limits) {

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
  }

  /** A model with the {@linkplain CallLimits#DEFAULT default limits}. */
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
    return new ModelConfig(provider, model, capabilities, contextWindow, pricing, limits);
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

package io.lantern.app;

import io.lantern.llm.Capability;
import io.lantern.llm.ModelConfig;
import io.lantern.llm.ModelSettings;
import io.lantern.llm.Pricing;
import io.lantern.llm.Role;
import io.lantern.llm.Tier;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM settings under {@code lantern.llm}. Nothing here has a default in the code: model
 * identifiers, capabilities, prices and keys all come from configuration (see ADR 0009).
 *
 * @param models the models that tiers and roles can use, by a name chosen in the configuration
 * @param tiers the model name each tier uses
 * @param roles the model name a role uses instead of its tier's
 * @param anthropic how to reach Anthropic
 * @param monthlyBudgetUsd the most US dollars all calls in a calendar month (UTC) may cost, or
 *     {@code null} for no limit
 */
@ConfigurationProperties("lantern.llm")
public record LlmProperties(
    Map<String, Model> models,
    Map<Tier, String> tiers,
    Map<Role, String> roles,
    Anthropic anthropic,
    BigDecimal monthlyBudgetUsd) {

  public LlmProperties {
    models = models == null ? Map.of() : Map.copyOf(models);
    tiers = tiers == null ? Map.of() : Map.copyOf(tiers);
    roles = roles == null ? Map.of() : Map.copyOf(roles);
    anthropic = anthropic == null ? new Anthropic(null, null) : anthropic;
    if (monthlyBudgetUsd != null && monthlyBudgetUsd.signum() < 0) {
      throw new IllegalArgumentException(
          "lantern.llm.monthly-budget-usd must not be negative: " + monthlyBudgetUsd);
    }
  }

  /** Whether any tier has a model, that is whether the LLM is meant to be used at all. */
  boolean tiersConfigured() {
    return !tiers.isEmpty();
  }

  /**
   * The model each role uses.
   *
   * @throws IllegalStateException if the settings are incomplete or refer to unknown models
   */
  ModelSettings modelSettings() {
    for (Tier tier : Tier.values()) {
      if (!tiers.containsKey(tier)) {
        throw new IllegalStateException(
            "lantern.llm.tiers." + kebab(tier) + " is not set; every tier needs a model");
      }
    }
    ModelSettings settings =
        ModelSettings.byTier(
            model("lantern.llm.tiers.high-volume", tiers.get(Tier.HIGH_VOLUME)),
            model("lantern.llm.tiers.reasoning", tiers.get(Tier.REASONING)));
    for (Map.Entry<Role, String> role : roles.entrySet()) {
      settings =
          settings.with(
              role.getKey(), model("lantern.llm.roles." + kebab(role.getKey()), role.getValue()));
    }
    return settings;
  }

  /** The providers that the models in use refer to. */
  Set<String> providersInUse(ModelSettings settings) {
    Set<String> providers = new LinkedHashSet<>();
    for (Role role : Role.values()) {
      providers.add(settings.forRole(role).provider());
    }
    return providers;
  }

  private ModelConfig model(String property, String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalStateException(property + " must name a model");
    }
    Model model = models.get(name);
    if (model == null) {
      throw new IllegalStateException(
          property + " refers to model '" + name + "', which lantern.llm.models does not define");
    }
    return model.toConfig("lantern.llm.models." + name);
  }

  private static String kebab(Enum<?> value) {
    return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  /**
   * One configured model.
   *
   * @param provider the provider name, for example {@code anthropic}
   * @param id the provider's identifier for the model
   * @param capabilities the optional features it supports
   * @param contextWindow the most tokens, input plus output, one call may use
   * @param prices what it costs
   */
  public record Model(
      String provider,
      String id,
      Set<Capability> capabilities,
      Integer contextWindow,
      Prices prices) {

    ModelConfig toConfig(String property) {
      require(property + ".provider", provider);
      require(property + ".id", id);
      require(property + ".context-window", contextWindow);
      require(property + ".prices", prices);
      Set<Capability> supported = capabilities == null ? Set.of() : capabilities;
      return new ModelConfig(
          provider,
          id,
          supported,
          contextWindow,
          prices.toPricing(property + ".prices", supported.contains(Capability.PROMPT_CACHING)));
    }
  }

  /**
   * A model's prices in US dollars per million tokens. Cache prices may be left out for a model
   * without prompt caching.
   */
  public record Prices(
      BigDecimal input, BigDecimal output, BigDecimal cacheWrite, BigDecimal cacheRead) {

    Pricing toPricing(String property, boolean caching) {
      require(property + ".input", input);
      require(property + ".output", output);
      if (caching) {
        require(property + ".cache-write", cacheWrite);
        require(property + ".cache-read", cacheRead);
      }
      return new Pricing(
          input,
          output,
          cacheWrite == null ? BigDecimal.ZERO : cacheWrite,
          cacheRead == null ? BigDecimal.ZERO : cacheRead);
    }
  }

  /**
   * How to reach Anthropic.
   *
   * @param apiKey the API key, or {@code null} if none is set
   * @param baseUrl the API's base URL, or {@code null} for Anthropic's
   */
  public record Anthropic(String apiKey, String baseUrl) {

    public Anthropic {
      apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey;
      baseUrl = baseUrl == null || baseUrl.isBlank() ? null : baseUrl;
    }

    @Override
    public String toString() {
      return "Anthropic[apiKey="
          + (apiKey == null ? "unset" : "***")
          + ", baseUrl="
          + baseUrl
          + "]";
    }
  }

  private static void require(String property, Object value) {
    if (value == null) {
      throw new IllegalStateException(property + " is not set");
    }
  }
}

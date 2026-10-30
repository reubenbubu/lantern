package io.lantern.app;

import io.lantern.llm.CallLimits;
import io.lantern.llm.Capability;
import io.lantern.llm.ModelConfig;
import io.lantern.llm.ModelSettings;
import io.lantern.llm.Pricing;
import io.lantern.llm.RetryPolicy;
import io.lantern.llm.Role;
import io.lantern.llm.Thinking;
import io.lantern.llm.Tier;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM settings under {@code lantern.llm}. Model identifiers, capabilities, prices and keys have no
 * default in the code and all come from configuration (see ADR 0009); call limits and retries have
 * defaults (see ADR 0021), and thinking settings default to leaving thinking as the provider has it
 * (see ADR 0022).
 *
 * @param models the models that tiers and roles can use, by a name chosen in the configuration
 * @param tiers the model name each tier uses
 * @param roles the model name a role uses instead of its tier's
 * @param anthropic how to reach Anthropic
 * @param monthlyBudgetUsd the most US dollars all calls in a calendar month (UTC) may cost, or
 *     {@code null} for no limit
 * @param retry how calls that failed for a passing reason are retried
 * @param recordings the roles whose calls are answered from recordings, and recorded (ADR 0082)
 */
@ConfigurationProperties("lantern.llm")
public record LlmProperties(
    Map<String, Model> models,
    Map<Tier, String> tiers,
    Map<Role, String> roles,
    Anthropic anthropic,
    BigDecimal monthlyBudgetUsd,
    Retry retry,
    Recordings recordings) {

  public LlmProperties {
    models = models == null ? Map.of() : Map.copyOf(models);
    tiers = tiers == null ? Map.of() : Map.copyOf(tiers);
    roles = roles == null ? Map.of() : Map.copyOf(roles);
    anthropic = anthropic == null ? new Anthropic(null, null) : anthropic;
    retry = retry == null ? new Retry(null, null, null) : retry;
    recordings = recordings == null ? new Recordings(null, null, null) : recordings;
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
    checkLimitsAgree(settings);
    return settings;
  }

  /**
   * Calls to one provider's model share one set of limits, so all its configurations must agree.
   */
  private static void checkLimitsAgree(ModelSettings settings) {
    Map<String, ModelConfig> byModel = new HashMap<>();
    for (Role role : Role.values()) {
      ModelConfig model = settings.forRole(role);
      ModelConfig other = byModel.putIfAbsent(model.provider() + "\n" + model.model(), model);
      if (other != null && !other.limits().equals(model.limits())) {
        throw new IllegalStateException(
            "Model "
                + model.model()
                + " of provider "
                + model.provider()
                + " is configured twice with different max-concurrency or requests-per-minute");
      }
    }
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
   * @param maxConcurrency the most calls to it in flight at once, {@value
   *     CallLimits#DEFAULT_MAX_CONCURRENCY} if not set
   * @param requestsPerMinute the most calls started per minute, or {@code null} for no rate limit
   * @param disableThinking whether to ask the provider to turn the model's thinking off; off if not
   *     set
   * @param thinkingHeadroomTokens extra output tokens added to every call's output limit, for a
   *     model that thinks anyway; none if not set
   */
  public record Model(
      String provider,
      String id,
      Set<Capability> capabilities,
      Integer contextWindow,
      Prices prices,
      Integer maxConcurrency,
      Integer requestsPerMinute,
      Boolean disableThinking,
      Integer thinkingHeadroomTokens) {

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
          prices.toPricing(property + ".prices", supported.contains(Capability.PROMPT_CACHING)),
          limits(property),
          thinking(property));
    }

    private Thinking thinking(String property) {
      if (thinkingHeadroomTokens != null && thinkingHeadroomTokens < 0) {
        throw new IllegalStateException(
            property + ".thinking-headroom-tokens must not be negative: " + thinkingHeadroomTokens);
      }
      return new Thinking(
          Boolean.TRUE.equals(disableThinking),
          thinkingHeadroomTokens == null ? 0 : thinkingHeadroomTokens);
    }

    private CallLimits limits(String property) {
      if (maxConcurrency != null && maxConcurrency < 1) {
        throw new IllegalStateException(
            property + ".max-concurrency must be positive: " + maxConcurrency);
      }
      if (requestsPerMinute != null && requestsPerMinute < 1) {
        throw new IllegalStateException(
            property + ".requests-per-minute must be positive: " + requestsPerMinute);
      }
      return new CallLimits(
          maxConcurrency == null ? CallLimits.DEFAULT_MAX_CONCURRENCY : maxConcurrency,
          requestsPerMinute);
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

  /**
   * How calls that failed for a passing reason (rate limit, overload, server or connection error)
   * are retried. Unset values take {@link RetryPolicy#DEFAULT}'s.
   *
   * @param maxAttempts the most times one call is sent, the first included
   * @param initialBackoff the wait before the second attempt, doubled before each later one
   * @param maxBackoff the longest wait reached by doubling
   */
  public record Retry(Integer maxAttempts, Duration initialBackoff, Duration maxBackoff) {

    RetryPolicy toPolicy() {
      RetryPolicy defaults = RetryPolicy.DEFAULT;
      try {
        return new RetryPolicy(
            maxAttempts == null ? defaults.maxAttempts() : maxAttempts,
            initialBackoff == null ? defaults.initialBackoff() : initialBackoff,
            maxBackoff == null ? defaults.maxBackoff() : maxBackoff);
      } catch (IllegalArgumentException e) {
        throw new IllegalStateException("lantern.llm.retry: " + e.getMessage(), e);
      }
    }
  }

  /**
   * Which roles' calls go through recordings of the model's answers ({@link
   * io.lantern.llm.RecordReplayLlmProvider}), such as the Investigate role's recorded on a test set
   * (ADR 0082). Off unless a directory is set.
   *
   * @param directory where the recordings are, one JSON file per request; {@code null} for none
   * @param roles the roles whose calls use them; every other call goes to its provider as usual
   * @param mode {@code replay} answers only from recordings and fails a call that has none; {@code
   *     record} answers from a recording when there is one and otherwise calls the provider and
   *     records its answer, which costs money with a real model
   */
  public record Recordings(Path directory, Set<Role> roles, Mode mode) {

    /** How recordings are used. */
    public enum Mode {
      REPLAY,
      RECORD
    }

    public Recordings {
      roles = roles == null ? Set.of() : Set.copyOf(roles);
      mode = mode == null ? Mode.REPLAY : mode;
      if (directory != null && roles.isEmpty()) {
        throw new IllegalStateException(
            "lantern.llm.recordings.roles is not set; name the roles whose calls use the"
                + " recordings in "
                + directory);
      }
    }

    /** Whether any role's calls use recordings. */
    boolean enabled() {
      return directory != null;
    }
  }

  private static void require(String property, Object value) {
    if (value == null || (value instanceof String text && text.isBlank())) {
      throw new IllegalStateException(property + " is not set");
    }
  }
}

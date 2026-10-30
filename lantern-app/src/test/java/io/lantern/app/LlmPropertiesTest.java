package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lantern.llm.CallLimits;
import io.lantern.llm.Capability;
import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.LlmProvider;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.LlmResponse;
import io.lantern.llm.ModelConfig;
import io.lantern.llm.ModelSettings;
import io.lantern.llm.Pricing;
import io.lantern.llm.RetryPolicy;
import io.lantern.llm.Role;
import io.lantern.llm.Thinking;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Binds {@code lantern.llm} settings as Spring does and turns them into models and access. */
class LlmPropertiesTest {

  private final Map<String, String> values = new HashMap<>();
  private final JdbcLlmCallLog stored = mock(JdbcLlmCallLog.class);

  {
    when(stored.spentSince(any()))
        .thenReturn(new JdbcLlmCallLog.Spending(0, 0, BigDecimal.ZERO, Map.of()));
  }

  private LlmProperties bind() {
    return new Binder(new MapConfigurationPropertySource(values))
        .bindOrCreate("lantern.llm", LlmProperties.class);
  }

  private LlmAccess access(List<LlmProvider> providers) {
    return LlmConfiguration.create(bind(), providers, stored, Clock.systemUTC());
  }

  private void model(String name, String provider, String id) {
    String prefix = "lantern.llm.models." + name + ".";
    values.put(prefix + "provider", provider);
    values.put(prefix + "id", id);
    values.put(prefix + "context-window", "200000");
    values.put(prefix + "prices.input", "3");
    values.put(prefix + "prices.output", "15");
  }

  private void tiers(String highVolume, String reasoning) {
    values.put("lantern.llm.tiers.high-volume", highVolume);
    values.put("lantern.llm.tiers.reasoning", reasoning);
  }

  @Test
  void withoutSettingsTheLlmIsNotConfiguredAndRefusesWork() {
    LlmAccess access = access(List.of());

    assertThat(access.isConfigured()).isFalse();
    assertThatThrownBy(access::client)
        .isInstanceOf(LlmNotConfiguredException.class)
        .hasMessageContaining("The LLM is not configured")
        .hasMessageContaining("lantern.llm.tiers.high-volume");
  }

  @Test
  void bindsModelsTiersAndRoleOverrides() {
    model("fast", "fake", "fast-model");
    model("strong", "fake", "strong-model");
    model("leaf", "fake", "leaf-model");
    values.put("lantern.llm.models.fast.capabilities", "prompt-caching,tool-use");
    values.put("lantern.llm.models.fast.prices.cache-write", "3.75");
    values.put("lantern.llm.models.fast.prices.cache-read", "0.3");
    tiers("fast", "strong");
    values.put("lantern.llm.roles.summarize", "leaf");
    values.put("lantern.llm.roles.infer-intent", "fast");
    values.put("lantern.llm.monthly-budget-usd", "100");

    LlmProperties properties = bind();
    ModelSettings settings = properties.modelSettings();

    assertThat(settings.forRole(Role.COMPOSE))
        .isEqualTo(
            new ModelConfig(
                "fake",
                "fast-model",
                Set.of(Capability.PROMPT_CACHING, Capability.TOOL_USE),
                200_000,
                new Pricing(
                    BigDecimal.valueOf(3),
                    BigDecimal.valueOf(15),
                    new BigDecimal("3.75"),
                    new BigDecimal("0.3"))));
    assertThat(settings.forRole(Role.RESOLVE).model()).isEqualTo("strong-model");
    assertThat(settings.forRole(Role.RESOLVE).capabilities()).isEmpty();
    assertThat(settings.forRole(Role.RESOLVE).pricing().cacheWrite()).isZero();
    assertThat(settings.forRole(Role.SUMMARIZE).model()).isEqualTo("leaf-model");
    assertThat(settings.forRole(Role.INFER_INTENT).model()).isEqualTo("fast-model");
    assertThat(properties.monthlyBudgetUsd()).isEqualByComparingTo("100");

    LlmAccess access = access(List.of(new FakeLlmProvider()));
    assertThat(access.isConfigured()).isTrue();
    assertThat(access.client().model(Role.SUMMARIZE).model()).isEqualTo("leaf-model");
  }

  @Test
  void incompleteOrWrongSettingsStopStartup() {
    model("fast", "fake", "fast-model");
    values.put("lantern.llm.tiers.high-volume", "fast");
    assertThatThrownBy(this::bindAndBuild).hasMessageContaining("lantern.llm.tiers.reasoning");

    values.put("lantern.llm.tiers.reasoning", "missing");
    assertThatThrownBy(this::bindAndBuild)
        .hasMessageContaining("lantern.llm.tiers.reasoning refers to model 'missing'");

    values.put("lantern.llm.tiers.reasoning", "fast");
    values.remove("lantern.llm.models.fast.prices.output");
    assertThatThrownBy(this::bindAndBuild)
        .hasMessageContaining("lantern.llm.models.fast.prices.output is not set");

    values.put("lantern.llm.models.fast.prices.output", "15");
    values.put("lantern.llm.models.fast.capabilities", "prompt-caching");
    assertThatThrownBy(this::bindAndBuild)
        .hasMessageContaining("lantern.llm.models.fast.prices.cache-write is not set");
  }

  private void bindAndBuild() {
    bind().modelSettings();
  }

  @Test
  void unknownProviderStopsStartup() {
    model("fast", "nowhere", "fast-model");
    tiers("fast", "fast");

    assertThatThrownBy(() -> access(List.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("provider 'nowhere'");
  }

  @Test
  void anthropicModelsWithoutAnApiKeyAreNotConfigured() {
    model("fast", "anthropic", "some-model");
    tiers("fast", "fast");
    values.put("lantern.llm.anthropic.api-key", "");

    LlmAccess access = access(List.of());

    assertThat(access.isConfigured()).isFalse();
    assertThatThrownBy(access::client)
        .isInstanceOf(LlmNotConfiguredException.class)
        .hasMessageContaining("no API key");
  }

  @Test
  void aCallStillSucceedsWhenStoringItFails() {
    model("fast", "fake", "fast-model");
    tiers("fast", "fast");
    doThrow(new IllegalStateException("database down")).when(stored).record(any());

    LlmResponse response =
        access(List.of(new FakeLlmProvider().answering("still here")))
            .client()
            .call(LlmRequest.of(Role.COMPOSE, "storage failure test", null, "Write", 10));

    assertThat(response.text()).isEqualTo("still here");
    verify(stored).record(any());
  }

  @Test
  void anthropicModelsWithAnApiKeyGetAnAnthropicProvider() throws Exception {
    model("fast", "anthropic", "some-model");
    tiers("fast", "fast");
    // A dummy key: making the provider sends nothing.
    values.put("lantern.llm.anthropic.api-key", "test-key");
    values.put("lantern.llm.anthropic.base-url", "http://127.0.0.1:9");

    try (LlmAccess access = access(List.of())) {
      assertThat(access.isConfigured()).isTrue();
      assertThat(access.client().model(Role.RESOLVE).provider()).isEqualTo("anthropic");
    }
    assertThat(bind().anthropic().toString()).doesNotContain("test-key");
  }

  @Test
  void negativeMonthlyBudgetIsRefused() {
    values.put("lantern.llm.monthly-budget-usd", "-1");

    assertThatThrownBy(this::bind)
        .hasRootCauseMessage("lantern.llm.monthly-budget-usd must not be negative: -1");
  }

  @Test
  void callLimitsAndRetriesHaveDefaultsAndCanBeSet() {
    model("fast", "fake", "fast-model");
    model("strong", "fake", "strong-model");
    tiers("fast", "strong");
    values.put("lantern.llm.models.strong.max-concurrency", "2");
    values.put("lantern.llm.models.strong.requests-per-minute", "50");

    LlmProperties properties = bind();
    ModelSettings settings = properties.modelSettings();

    assertThat(settings.forRole(Role.COMPOSE).limits()).isEqualTo(CallLimits.DEFAULT);
    assertThat(settings.forRole(Role.RESOLVE).limits()).isEqualTo(new CallLimits(2, 50));
    assertThat(properties.retry().toPolicy()).isEqualTo(RetryPolicy.DEFAULT);

    values.put("lantern.llm.retry.max-attempts", "3");
    values.put("lantern.llm.retry.initial-backoff", "500ms");
    values.put("lantern.llm.retry.max-backoff", "10s");
    assertThat(bind().retry().toPolicy())
        .isEqualTo(new RetryPolicy(3, Duration.ofMillis(500), Duration.ofSeconds(10)));
  }

  @Test
  void wrongCallLimitsOrRetriesStopStartup() {
    model("fast", "fake", "fast-model");
    tiers("fast", "fast");
    values.put("lantern.llm.models.fast.max-concurrency", "0");
    assertThatThrownBy(this::bindAndBuild)
        .hasMessageContaining("lantern.llm.models.fast.max-concurrency must be positive");

    values.remove("lantern.llm.models.fast.max-concurrency");
    values.put("lantern.llm.retry.max-attempts", "0");
    assertThatThrownBy(() -> access(List.of(new FakeLlmProvider())))
        .hasMessageContaining("lantern.llm.retry");

    // The same model under two names must not have two sets of limits.
    values.remove("lantern.llm.retry.max-attempts");
    model("same", "fake", "fast-model");
    values.put("lantern.llm.models.same.max-concurrency", "3");
    values.put("lantern.llm.roles.summarize", "same");
    assertThatThrownBy(this::bindAndBuild)
        .hasMessageContaining("fast-model")
        .hasMessageContaining("different max-concurrency");
  }

  @Test
  void thinkingIsLeftAloneUnlessSet() {
    model("fast", "fake", "fast-model");
    model("glm", "fake", "glm-model");
    tiers("fast", "glm");
    values.put("lantern.llm.models.glm.disable-thinking", "true");
    values.put("lantern.llm.models.glm.thinking-headroom-tokens", "2000");

    ModelSettings settings = bind().modelSettings();

    assertThat(settings.forRole(Role.COMPOSE).thinking()).isEqualTo(Thinking.DEFAULT);
    assertThat(settings.forRole(Role.RESOLVE).thinking()).isEqualTo(new Thinking(true, 2000));

    values.put("lantern.llm.models.glm.thinking-headroom-tokens", "-1");
    assertThatThrownBy(this::bindAndBuild)
        .hasMessageContaining(
            "lantern.llm.models.glm.thinking-headroom-tokens must not be negative");
  }

  @Test
  void theThinkingOffSwitchIsReadAndAModelWithoutOneIsNotAskedToTurnThinkingOff() {
    model("sonnet", "fake", "sonnet-model");
    model("opus", "fake", "opus-model");
    tiers("sonnet", "opus");
    values.put("lantern.llm.models.sonnet.disable-thinking", "true");
    values.put("lantern.llm.models.sonnet.thinking-off-switch", "between-tools");
    values.put("lantern.llm.models.opus.thinking-off-switch", "none");
    values.put("lantern.llm.models.opus.thinking-headroom-tokens", "2000");

    ModelSettings settings = bind().modelSettings();

    assertThat(settings.forRole(Role.SUMMARIZE).thinking())
        .isEqualTo(new Thinking(true, 0, Thinking.OffSwitch.BETWEEN_TOOLS));
    assertThat(settings.forRole(Role.RESOLVE).thinking())
        .isEqualTo(new Thinking(false, 2000, Thinking.OffSwitch.NONE));

    values.put("lantern.llm.models.opus.disable-thinking", "true");
    assertThatThrownBy(this::bindAndBuild)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("lantern.llm.models.opus.disable-thinking is true")
        .hasMessageContaining("cannot turn thinking off");
  }
}

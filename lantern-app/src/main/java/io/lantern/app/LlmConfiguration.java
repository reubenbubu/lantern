package io.lantern.app;

import io.lantern.llm.LlmCallLog;
import io.lantern.llm.LlmProvider;
import io.lantern.llm.ModelSettings;
import io.lantern.llm.Slf4jLlmCallLog;
import io.lantern.llm.anthropic.AnthropicProvider;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Wires LLM access from the {@code lantern.llm} settings (see ADR 0009). Without any tier set, or
 * without the API key a configured model needs, the app starts and refuses LLM work. Settings that
 * are set but wrong stop the app from starting.
 *
 * <p>Providers are made here for the models in use; other {@link LlmProvider} beans, such as a fake
 * in tests, are used as they are.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LlmProperties.class)
class LlmConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(LlmConfiguration.class);

  @Bean
  JdbcLlmCallLog jdbcLlmCallLog(JdbcClient jdbcClient) {
    return new JdbcLlmCallLog(jdbcClient);
  }

  @Bean
  LlmAccess llmAccess(
      LlmProperties properties, ObjectProvider<LlmProvider> providerBeans, JdbcLlmCallLog stored) {
    return create(properties, providerBeans.orderedStream().toList(), stored, Clock.systemUTC());
  }

  static LlmAccess create(
      LlmProperties properties,
      List<LlmProvider> providerBeans,
      JdbcLlmCallLog stored,
      Clock clock) {
    if (!properties.tiersConfigured()) {
      return LlmAccess.notConfigured(
          "set lantern.llm.tiers.high-volume and lantern.llm.tiers.reasoning to models defined"
              + " under lantern.llm.models (see docs/adr/0009-llm-configuration.md)");
    }
    ModelSettings settings = properties.modelSettings();
    List<LlmProvider> providers = new ArrayList<>(providerBeans);
    Set<String> available = new HashSet<>();
    providerBeans.forEach(provider -> available.add(provider.name()));
    AnthropicProvider anthropic = null;
    for (String name : properties.providersInUse(settings)) {
      if (available.contains(name)) {
        continue;
      }
      if (!name.equals(AnthropicProvider.NAME)) {
        throw new IllegalStateException(
            "A configured model uses provider '"
                + name
                + "', which Lantern does not have; known: "
                + AnthropicProvider.NAME);
      }
      if (properties.anthropic().apiKey() == null) {
        return LlmAccess.notConfigured(
            "the configured models use Anthropic, but no API key is set (set ANTHROPIC_API_KEY or"
                + " lantern.llm.anthropic.api-key)");
      }
      anthropic =
          AnthropicProvider.create(
              properties.anthropic().apiKey(), properties.anthropic().baseUrl());
      providers.add(anthropic);
      available.add(name);
    }
    LlmCallLog slf4j = new Slf4jLlmCallLog();
    LlmCallLog log =
        call -> {
          slf4j.record(call);
          try {
            stored.record(call);
          } catch (RuntimeException e) {
            // The call has happened and may have been paid for; failing it now would lose the
            // answer or hide why it failed. The log line above still records it.
            LOG.error("Could not store LLM call {}", call, e);
          }
        };
    try {
      LlmAccess access =
          LlmAccess.configured(
              settings,
              providers,
              stored,
              log,
              properties.monthlyBudgetUsd(),
              clock,
              properties.retry().toPolicy());
      return anthropic == null ? access : access.owning(anthropic);
    } catch (RuntimeException e) {
      if (anthropic != null) {
        anthropic.close();
      }
      throw e;
    }
  }
}

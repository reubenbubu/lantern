package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lantern.llm.BudgetExceededException;
import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.LlmCall;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.ModelConfig;
import io.lantern.llm.ModelSettings;
import io.lantern.llm.Pricing;
import io.lantern.llm.RetryPolicy;
import io.lantern.llm.Role;
import io.lantern.llm.TokenUsage;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * LLM access wired from configuration, with the fake provider standing in for a real one. The
 * database is shared with other tests, so each test uses purposes, or months, of its own.
 */
@SpringBootTest(
    properties = {
      "lantern.llm.models.fast.provider=fake",
      "lantern.llm.models.fast.id=fast-model",
      "lantern.llm.models.fast.capabilities=prompt-caching",
      "lantern.llm.models.fast.context-window=100000",
      "lantern.llm.models.fast.prices.input=1",
      "lantern.llm.models.fast.prices.output=2",
      "lantern.llm.models.fast.prices.cache-write=1.25",
      "lantern.llm.models.fast.prices.cache-read=0.1",
      "lantern.llm.models.strong.provider=fake",
      "lantern.llm.models.strong.id=strong-model",
      "lantern.llm.models.strong.context-window=100000",
      "lantern.llm.models.strong.prices.input=5",
      "lantern.llm.models.strong.prices.output=25",
      "lantern.llm.tiers.high-volume=fast",
      "lantern.llm.tiers.reasoning=strong",
      "lantern.llm.anthropic.api-key="
    })
@Import({TestcontainersConfiguration.class, LlmAccessIT.Fake.class})
class LlmAccessIT {

  @TestConfiguration(proxyBeanMethods = false)
  static class Fake {
    @Bean
    FakeLlmProvider fakeLlmProvider() {
      return new FakeLlmProvider().answering("A short answer.");
    }
  }

  @Autowired LlmAccess access;
  @Autowired JdbcLlmCallLog stored;
  @Autowired FakeLlmProvider fake;
  @Autowired JdbcClient jdbc;

  @Test
  void callsGoThroughTheConfiguredModelsAndAreStored() {
    String purpose = "test " + UUID.randomUUID();

    assertThat(access.isConfigured()).isTrue();
    access
        .client()
        .call(LlmRequest.of(Role.RESOLVE, purpose, "You are a test.", "Resolve this link", 50));

    assertThat(fake.sent().getLast().model()).isEqualTo("strong-model");
    Map<String, Object> row =
        jdbc.sql("SELECT * FROM llm_calls WHERE purpose = :purpose")
            .param("purpose", purpose)
            .query()
            .singleRow();
    assertThat(row)
        .containsEntry("role", "RESOLVE")
        .containsEntry("provider", "fake")
        .containsEntry("model", "strong-model")
        .containsEntry("outcome", "SUCCEEDED")
        .containsEntry("error", null);
    // The fake estimates 8 input tokens at $5 and 4 output tokens at $25 per million.
    assertThat(row.get("input_tokens")).isEqualTo(8L);
    assertThat(row.get("output_tokens")).isEqualTo(4L);
    assertThat((BigDecimal) row.get("cost_usd")).isEqualByComparingTo("0.00014");
  }

  @Test
  void aTruncatedCallIsStoredWithItsUsage() {
    String purpose = "test " + UUID.randomUUID();

    stored.record(
        new LlmCall(
            Instant.now(),
            Duration.ofMillis(5),
            Role.SUMMARIZE,
            purpose,
            "fake",
            "fast-model",
            LlmCall.Outcome.TRUNCATED,
            new TokenUsage(20, 50, 0, 0),
            new BigDecimal("0.001"),
            "Cut off at the output limit of 50 tokens (stop reason max_tokens)"));

    Map<String, Object> row =
        jdbc.sql("SELECT * FROM llm_calls WHERE purpose = :purpose")
            .param("purpose", purpose)
            .query()
            .singleRow();
    assertThat(row).containsEntry("outcome", "TRUNCATED");
    assertThat(row.get("output_tokens")).isEqualTo(50L);
  }

  @Test
  void theMonthlyBudgetHoldsAcrossRunsFromTheStoredLog() {
    // A month no other test uses, so the stored log holds only this test's calls for it.
    int year = 2999;
    Clock october = Clock.fixed(Instant.parse(year + "-10-15T12:00:00Z"), ZoneOffset.UTC);
    Clock november = Clock.fixed(Instant.parse(year + "-11-01T00:00:00Z"), ZoneOffset.UTC);
    // Free input; each output token costs half a dollar.
    ModelConfig model =
        new ModelConfig(
            FakeLlmProvider.NAME,
            "priced-model",
            null,
            100_000,
            new Pricing(
                BigDecimal.ZERO, BigDecimal.valueOf(500_000), BigDecimal.ZERO, BigDecimal.ZERO));
    LlmRequest request = LlmRequest.of(Role.SUMMARIZE, "budget test", null, "Summarize", 1);
    FakeLlmProvider oneToken = new FakeLlmProvider().answering("ok");

    LlmAccess firstRun = run(model, oneToken, october);
    firstRun.client().call(request);
    assertThat(firstRun.monthBudget().spentCostUsd()).isEqualByComparingTo("0.5");

    // A new instance, as after a restart, reads the month's spending back from the stored log.
    LlmAccess secondRun = run(model, oneToken, october);
    assertThat(secondRun.monthBudget().spentCostUsd()).isEqualByComparingTo("0.5");
    assertThatThrownBy(() -> secondRun.client().call(request))
        .isInstanceOf(BudgetExceededException.class)
        .hasMessageContaining("month " + year + "-10");
    assertThat(
            jdbc.sql(
                    "SELECT outcome FROM llm_calls WHERE started_at >= :from AND started_at < :to"
                        + " ORDER BY id")
                .param("from", october.instant().minusSeconds(86_400).atOffset(ZoneOffset.UTC))
                .param("to", november.instant().atOffset(ZoneOffset.UTC))
                .query(String.class)
                .list())
        .containsExactly("SUCCEEDED", "REFUSED");
    JdbcLlmCallLog.Spending spent = stored.spentSince(Instant.parse(year + "-10-01T00:00:00Z"));
    assertThat(spent.calls()).isEqualTo(2);
    assertThat(spent.costUsd()).isEqualByComparingTo("0.5");

    // The next month starts from nothing.
    LlmAccess nextMonth = run(model, oneToken, november);
    nextMonth.client().call(request);
    assertThat(nextMonth.monthBudget().spentCostUsd()).isEqualByComparingTo("0.5");
  }

  private LlmAccess run(ModelConfig model, FakeLlmProvider provider, Clock clock) {
    return LlmAccess.configured(
        ModelSettings.single(model),
        List.of(provider),
        stored,
        stored,
        new BigDecimal("0.9"),
        clock,
        RetryPolicy.DEFAULT);
  }
}

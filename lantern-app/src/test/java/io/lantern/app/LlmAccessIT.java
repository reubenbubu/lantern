package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

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
    assertThat(spent.callsByRole()).containsExactly(entry(Role.SUMMARIZE, 2L));
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

  /**
   * A conversation with a tool loop, replayed from recordings, is stored turn by turn and as a
   * whole, and the run's record shows its turns and tool calls by role (ADR 0081).
   */
  @Test
  void aRecordedToolLoopIsStoredWithItsTurnsAndToolCallsAndReportedByRole(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path recordings) throws Exception {
    // A time no other test logs at, since the database is shared.
    Instant start = Instant.parse("2400-01-01T00:00:00Z");
    Clock clock = Clock.fixed(start, ZoneOffset.UTC);
    tools.jackson.databind.json.JsonMapper json =
        tools.jackson.databind.json.JsonMapper.builder().build();
    io.lantern.llm.ToolBox words =
        new io.lantern.llm.ToolBox() {
          @Override
          public List<io.lantern.llm.LlmTool> tools() {
            return List.of(
                new io.lantern.llm.LlmTool(
                    "facts_about",
                    "Facts about an entity.",
                    json.createObjectNode().put("type", "object")),
                new io.lantern.llm.LlmTool(
                    "read_file", "Reads a file.", json.createObjectNode().put("type", "object")));
          }

          @Override
          public io.lantern.llm.LlmToolResult run(io.lantern.llm.LlmToolCall call) {
            return io.lantern.llm.LlmToolResult.of(call, "result of " + call.id());
          }
        };
    FakeLlmProvider scripted =
        new FakeLlmProvider()
            .callingTools(
                r ->
                    switch (r.messages().size()) {
                      case 1 ->
                          List.of(
                              new io.lantern.llm.LlmToolCall(
                                  "t1", "facts_about", json.createObjectNode()),
                              new io.lantern.llm.LlmToolCall(
                                  "t2", "facts_about", json.createObjectNode()));
                      case 3 ->
                          List.of(
                              new io.lantern.llm.LlmToolCall(
                                  "t3", "read_file", json.createObjectNode()));
                      default -> List.of();
                    })
            .answering("The answer.");
    ModelConfig model =
        new ModelConfig(
            "fake",
            "tool-model",
            java.util.EnumSet.of(io.lantern.llm.Capability.TOOL_USE),
            100_000,
            new Pricing(BigDecimal.ONE, BigDecimal.TWO, BigDecimal.ONE, BigDecimal.ONE));
    LlmRequest request =
        LlmRequest.of(Role.RESOLVE, "resolve with tools", "You resolve.", "Which unit?", 100);
    new io.lantern.llm.LlmClient(
            ModelSettings.single(model),
            List.of(io.lantern.llm.RecordReplayLlmProvider.recording(scripted, recordings)),
            io.lantern.llm.BudgetGuard.unlimited("record"),
            new io.lantern.llm.InMemoryLlmCallLog(),
            clock)
        .converse(request, words)
        .answer();
    io.lantern.llm.LlmClient replaying =
        new io.lantern.llm.LlmClient(
            ModelSettings.single(model),
            List.of(io.lantern.llm.RecordReplayLlmProvider.replaying("fake", recordings)),
            io.lantern.llm.BudgetGuard.unlimited("replay"),
            stored,
            clock);

    io.lantern.llm.Conversation conversation = replaying.converse(request, words);
    conversation.answer();
    conversation.end(io.lantern.llm.LlmConversation.Ending.ANSWERED);

    List<JdbcLlmCallLog.ConversationSpending> logged = stored.conversationsSince(start);
    assertThat(logged).hasSize(1);
    JdbcLlmCallLog.ConversationSpending c = logged.getFirst();
    assertThat(c.id()).isEqualTo(conversation.id());
    assertThat(c.role()).isEqualTo(Role.RESOLVE);
    assertThat(c.turns()).isEqualTo(3);
    assertThat(c.toolCalls()).containsExactly(entry("facts_about", 2), entry("read_file", 1));
    assertThat(c.ending()).isEqualTo(io.lantern.llm.LlmConversation.Ending.ANSWERED);
    assertThat(c.perTurn())
        .extracting(JdbcLlmCallLog.TurnSpending::turn, JdbcLlmCallLog.TurnSpending::toolCalls)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(1, Map.of("facts_about", 2)),
            org.assertj.core.groups.Tuple.tuple(2, Map.of("read_file", 1)),
            org.assertj.core.groups.Tuple.tuple(3, Map.of()));
    assertThat(c.perTurn()).allSatisfy(t -> assertThat(t.costUsd().signum()).isPositive());
    assertThat(c.perTurn().stream().mapToLong(JdbcLlmCallLog.TurnSpending::tokens).sum())
        .isEqualTo(c.tokens());

    Map<Role, ModelConfig> models = new java.util.EnumMap<>(Role.class);
    for (Role role : Role.values()) {
      models.put(role, model);
    }
    tools.jackson.databind.JsonNode record =
        RunRecord.of("shop", 1, models, stored.spentByModelSince(start), logged);
    tools.jackson.databind.JsonNode resolve = record.get("roles").get(Role.RESOLVE.ordinal());
    assertThat(resolve.get("calls").asLong()).isEqualTo(3);
    assertThat(resolve.at("/conversations/count").asInt()).isEqualTo(1);
    assertThat(resolve.at("/conversations/turns").asInt()).isEqualTo(3);
    assertThat(resolve.at("/conversations/toolCalls/facts_about").asInt()).isEqualTo(2);
    assertThat(resolve.at("/conversations/toolCalls/read_file").asInt()).isEqualTo(1);
    assertThat(resolve.at("/conversations/endings/ANSWERED").asInt()).isEqualTo(1);
    assertThat(record.get("roles").get(Role.SUMMARIZE.ordinal()).has("conversations")).isFalse();
    tools.jackson.databind.JsonNode listed = record.get("conversations").get(0);
    assertThat(listed.get("purpose").asString()).isEqualTo("resolve with tools");
    assertThat(listed.get("turns").asInt()).isEqualTo(3);
    assertThat(listed.get("perTurn")).hasSize(3);
    assertThat(listed.at("/perTurn/1/toolCalls/read_file").asInt()).isEqualTo(1);
  }
}

package io.lantern.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LlmClientTest {

  private static final Pricing PRICED =
      new Pricing(
          BigDecimal.valueOf(3),
          BigDecimal.valueOf(15),
          new BigDecimal("3.75"),
          new BigDecimal("0.3"));

  private final FakeLlmProvider fake = new FakeLlmProvider();
  private final InMemoryLlmCallLog log = new InMemoryLlmCallLog();
  private final BudgetGuard run = BudgetGuard.unlimited("run");
  private final Clock clock = Clock.fixed(Instant.parse("2026-10-28T10:00:00Z"), ZoneOffset.UTC);

  private final ModelConfig small = fake.model("small");
  private final ModelConfig large = fake.model("large");

  private LlmClient client(ModelSettings settings) {
    return new LlmClient(settings, List.of(fake), run, log, clock);
  }

  private static LlmRequest request(Role role, String user, int maxOutput) {
    return LlmRequest.of(role, "test " + role, "You are a test.", user, maxOutput);
  }

  @Test
  void rolesUseTheirTiersModelUnlessSetOtherwise() {
    LlmClient client =
        client(ModelSettings.byTier(small, large).with(Role.SUMMARIZE, fake.model("leaf")));

    assertThat(client.model(Role.COMPOSE)).isEqualTo(small);
    assertThat(client.model(Role.RESOLVE)).isEqualTo(large);
    assertThat(client.model(Role.SUMMARIZE).model()).isEqualTo("leaf");

    client.call(request(Role.NAME_GROUPS, "Name this group", 100));
    client.call(request(Role.INFER_INTENT, "What is it for?", 100));
    client.call(request(Role.SUMMARIZE, "Summarize this", 100));

    assertThat(fake.sent())
        .extracting(FakeLlmProvider.Sent::model)
        .containsExactly("small", "large", "leaf");
  }

  @Test
  void everyRoleHasATier() {
    for (Role role : Role.values()) {
      assertThat(role.tier()).isNotNull();
    }
    assertThat(Role.SUMMARIZE.tier()).isEqualTo(Tier.HIGH_VOLUME);
    assertThat(Role.PROPOSE_RULES.tier()).isEqualTo(Tier.REASONING);
  }

  @Test
  void logsEverySuccessfulCallWithItsPurposeUsageAndCost() {
    ModelConfig priced = new ModelConfig("fake", "priced", Set.of(), 10_000, PRICED);
    fake.answering(request -> "x".repeat(40));
    LlmClient client = client(ModelSettings.single(priced));

    LlmResponse response = client.call(request(Role.COMPOSE, "y".repeat(385), 50));

    assertThat(response.text()).hasSize(40);
    // "You are a test." (15) + 385 characters = 400 characters = 100 tokens in, 10 out.
    assertThat(response.usage()).isEqualTo(new TokenUsage(100, 10, 0, 0));
    LlmCall call = log.calls().getFirst();
    assertThat(call.outcome()).isEqualTo(LlmCall.Outcome.SUCCEEDED);
    assertThat(call.purpose()).isEqualTo("test COMPOSE");
    assertThat(call.role()).isEqualTo(Role.COMPOSE);
    assertThat(call.provider()).isEqualTo("fake");
    assertThat(call.model()).isEqualTo("priced");
    assertThat(call.startedAt()).isEqualTo(clock.instant());
    assertThat(call.costUsd()).isEqualByComparingTo("0.00045");
    assertThat(call.error()).isNull();
    assertThat(run.spentTokens()).isEqualTo(110);
    assertThat(run.spentCostUsd()).isEqualByComparingTo("0.00045");
  }

  @Test
  void refusesPromptCachingOnAModelWithoutIt() {
    ModelConfig plain = new ModelConfig("fake", "plain", Set.of(), 10_000, Pricing.FREE);
    LlmClient client = client(ModelSettings.single(plain));
    LlmRequest cached =
        new LlmRequest(
            Role.SUMMARIZE, "cached", "System", List.of(LlmMessage.user("Hi")), 10, true);

    assertThatThrownBy(() -> client.call(cached))
        .isInstanceOf(UnsupportedCapabilityException.class)
        .hasMessageContaining("prompt caching");
    assertThat(fake.sent()).isEmpty();
    assertThat(log.calls().getFirst().outcome()).isEqualTo(LlmCall.Outcome.REFUSED);
  }

  @Test
  void passesPromptCachingToAModelWithIt() {
    LlmClient client = client(ModelSettings.single(small));
    LlmRequest cached =
        new LlmRequest(
            Role.SUMMARIZE, "cached", "System prompt", List.of(LlmMessage.user("Hi")), 10, true);

    client.call(cached);

    assertThat(fake.sent().getFirst().request().cacheSystem()).isTrue();
  }

  @Test
  void refusesARequestLargerThanTheContextWindow() {
    ModelConfig tiny = new ModelConfig("fake", "tiny", Set.of(), 100, Pricing.FREE);
    LlmClient client = client(ModelSettings.single(tiny));

    assertThatThrownBy(() -> client.call(request(Role.COMPOSE, "y".repeat(385), 1)))
        .isInstanceOf(UnsupportedCapabilityException.class)
        .hasMessageContaining("context window");
    assertThat(fake.sent()).isEmpty();
  }

  @Test
  void refusesACallThatCouldGoOverTheBudget() {
    BudgetGuard tight = new BudgetGuard("run", 150L, null);
    LlmClient client = new LlmClient(ModelSettings.single(small), List.of(fake), tight, log, clock);
    fake.answering("ok");

    client.call(request(Role.COMPOSE, "y".repeat(385), 10)); // 100 in, 1 out
    assertThat(tight.spentTokens()).isEqualTo(101);

    assertThatThrownBy(() -> client.call(request(Role.COMPOSE, "y".repeat(185), 10)))
        .isInstanceOf(BudgetExceededException.class)
        .hasMessageContaining("'run'");
    assertThat(fake.sent()).hasSize(1);
    assertThat(log.calls())
        .extracting(LlmCall::outcome)
        .containsExactly(LlmCall.Outcome.SUCCEEDED, LlmCall.Outcome.REFUSED);
    assertThat(tight.spentTokens()).isEqualTo(101);
  }

  @Test
  void aScopeBudgetAddsToTheRunBudget() {
    ModelConfig priced = new ModelConfig("fake", "priced", Set.of(), 10_000, PRICED);
    LlmClient client = client(ModelSettings.single(priced));
    BudgetGuard scope = new BudgetGuard("scope orders", null, new BigDecimal("0.001"));
    LlmClient scoped = client.withBudget(scope);
    fake.answering("ok");

    scoped.call(request(Role.COMPOSE, "y".repeat(385), 10)); // worst case $0.00045

    assertThat(scope.spentTokens()).isEqualTo(101);
    assertThat(run.spentTokens()).isEqualTo(101);
    // Worst case of the next call: 100 tokens in, 50 out = $0.00105; the scope has $0.000685 left.
    assertThatThrownBy(() -> scoped.call(request(Role.COMPOSE, "y".repeat(385), 50)))
        .isInstanceOf(BudgetExceededException.class)
        .hasMessageContaining("scope orders");
    // The unscoped client is only bound by the run budget.
    client.call(request(Role.COMPOSE, "y".repeat(385), 50));
    assertThat(run.spentTokens()).isEqualTo(202);
    assertThat(scope.spentTokens()).isEqualTo(101);
  }

  @Test
  void addingTheSameBudgetTwiceCountsItOnce() {
    LlmClient client = client(ModelSettings.single(small)).withBudget(run).withBudget(run);
    fake.answering("ok");

    client.call(request(Role.COMPOSE, "y".repeat(385), 10));

    assertThat(run.spentTokens()).isEqualTo(101);
  }

  @Test
  void onlyTheSystemPromptIsReservedAtTheCacheWritePrice() {
    // Cache writes cheaper than input, so pricing all input as cache writes would under-reserve.
    Pricing cheapCache =
        new Pricing(BigDecimal.valueOf(10), BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO);
    ModelConfig model =
        new ModelConfig("fake", "m", Set.of(Capability.PROMPT_CACHING), 10_000, cheapCache);
    // System "s".repeat(400) = 100 tokens; user 400 characters = 100 tokens.
    // Worst case: 100 * $1 + 100 * $10 per million = $0.0011.
    BudgetGuard budget = new BudgetGuard("run", null, new BigDecimal("0.00109"));
    LlmClient client =
        new LlmClient(ModelSettings.single(model), List.of(fake), budget, log, clock);
    LlmRequest cached =
        new LlmRequest(
            Role.SUMMARIZE,
            "cached",
            "s".repeat(400),
            List.of(LlmMessage.user("u".repeat(400))),
            1,
            true);

    assertThatThrownBy(() -> client.call(cached)).isInstanceOf(BudgetExceededException.class);
  }

  @Test
  void aFailedCallIsLoggedAndReleasesItsReservation() {
    BudgetGuard tight = new BudgetGuard("run", 200L, null);
    LlmClient client = new LlmClient(ModelSettings.single(small), List.of(fake), tight, log, clock);
    fake.answering(
        request -> {
          throw new IllegalStateException("provider down");
        });

    assertThatThrownBy(() -> client.call(request(Role.COMPOSE, "y".repeat(385), 50)))
        .isInstanceOf(LlmException.class)
        .hasMessageContaining("provider down");

    LlmCall call = log.calls().getFirst();
    assertThat(call.outcome()).isEqualTo(LlmCall.Outcome.FAILED);
    assertThat(call.error()).contains("provider down");
    assertThat(tight.spentTokens()).isZero();
    fake.answering("ok");
    client.call(request(Role.COMPOSE, "y".repeat(385), 50));
  }

  @Test
  void everyModelNeedsItsProvider() {
    ModelConfig elsewhere = new ModelConfig("other", "m", Set.of(), 1000, Pricing.FREE);

    assertThatThrownBy(() -> client(ModelSettings.byTier(small, elsewhere)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("other");
    assertThatThrownBy(
            () ->
                new LlmClient(
                    ModelSettings.single(small),
                    List.of(fake, new FakeLlmProvider()),
                    run,
                    log,
                    clock))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requestsAreChecked() {
    assertThatThrownBy(() -> LlmRequest.of(Role.COMPOSE, " ", null, "Hi", 10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> LlmRequest.of(Role.COMPOSE, "p", null, "Hi", 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new LlmRequest(
                    Role.COMPOSE, "p", null, List.of(LlmMessage.assistant("Hi")), 10, false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new LlmRequest(Role.COMPOSE, "p", null, List.of(LlmMessage.user("Hi")), 10, true))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

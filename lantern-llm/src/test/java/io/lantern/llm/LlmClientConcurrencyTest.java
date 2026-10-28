package io.lantern.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Calls from many threads: concurrency and rate limits, retries and budgets. */
class LlmClientConcurrencyTest {

  private static final Pricing PRICED =
      new Pricing(BigDecimal.valueOf(3), BigDecimal.valueOf(15), BigDecimal.ZERO, BigDecimal.ZERO);

  /** Retries quickly, so the tests do not wait. */
  private static final RetryPolicy FAST =
      new RetryPolicy(4, Duration.ofMillis(1), Duration.ofMillis(5));

  private final FakeLlmProvider fake = new FakeLlmProvider();
  private final InMemoryLlmCallLog log = new InMemoryLlmCallLog();

  private LlmClient client(CallLimits limits, BudgetGuard budget, RetryPolicy policy) {
    return new LlmClient(
        ModelSettings.single(
            new ModelConfig("fake", "m", Set.of(), 100_000, PRICED).withLimits(limits)),
        List.of(fake),
        budget,
        log,
        Clock.systemUTC(),
        new CallLimiter(policy));
  }

  private LlmClient client(CallLimits limits) {
    return client(limits, BudgetGuard.unlimited("run"), FAST);
  }

  private static LlmRequest request(int i) {
    return LlmRequest.of(Role.SUMMARIZE, "call " + i, "You are a test.", "Question " + i, 100);
  }

  /** Makes the calls at once, each on its own virtual thread, and returns each one's outcome. */
  private static List<Object> callAll(LlmClient client, int calls) throws Exception {
    List<Future<Object>> futures = new ArrayList<>();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < calls; i++) {
        LlmRequest request = request(i);
        futures.add(
            executor.submit(
                () -> {
                  try {
                    return (Object) client.call(request);
                  } catch (LlmException e) {
                    return e;
                  }
                }));
      }
    }
    List<Object> outcomes = new ArrayList<>();
    for (Future<Object> future : futures) {
      outcomes.add(future.get());
    }
    return outcomes;
  }

  private static long millis(ThrowingRunnable work) throws Exception {
    long start = System.nanoTime();
    work.run();
    return (System.nanoTime() - start) / 1_000_000;
  }

  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  @Test
  void parallelCallsFinishSoonerThanOneAtATime() throws Exception {
    fake.withLatency(Duration.ofMillis(50));

    long sequential = millis(() -> callAll(client(new CallLimits(1, null)), 24));
    long parallel = millis(() -> callAll(client(new CallLimits(8, null)), 24));

    // 24 calls of 50 ms: about 1,200 ms one at a time and 150 ms eight at a time.
    assertThat(sequential).isGreaterThanOrEqualTo(1_200);
    assertThat(parallel).isLessThan(sequential / 2);
    System.out.printf(
        "24 calls of 50 ms: %d ms one at a time, %d ms eight at a time (%.1fx)%n",
        sequential, parallel, (double) sequential / parallel);
  }

  @Test
  void neverMoreCallsInFlightThanTheLimit() throws Exception {
    fake.withLatency(Duration.ofMillis(20));

    List<Object> outcomes = callAll(client(new CallLimits(4, null)), 40);

    assertThat(outcomes).allMatch(LlmResponse.class::isInstance);
    assertThat(fake.maxConcurrentCalls()).isLessThanOrEqualTo(4).isGreaterThan(1);
  }

  @Test
  void clientsSharingALimiterShareTheLimit() throws Exception {
    fake.withLatency(Duration.ofMillis(20));
    LlmClient client = client(new CallLimits(3, null));
    LlmClient scoped = client.withBudget(BudgetGuard.unlimited("scope"));

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      executor.submit(() -> callAll(client, 20));
      executor.submit(() -> callAll(scoped, 20));
    }

    assertThat(fake.sent()).hasSize(40);
    assertThat(fake.maxConcurrentCalls()).isLessThanOrEqualTo(3).isGreaterThan(1);
  }

  @Test
  void theRateLimitSpacesCallsOut() throws Exception {
    // 600 a minute is one every 100 ms, so five calls take at least 400 ms.
    long elapsed = millis(() -> callAll(client(new CallLimits(8, 600)), 5));

    assertThat(elapsed).isGreaterThanOrEqualTo(400);
    assertThat(fake.sent()).hasSize(5);
  }

  @Test
  void rateLimitedAndOverloadedCallsAreRetried() throws Exception {
    fake.failingFirst(2, () -> new TransientLlmException("HTTP 429 rate limited", null));
    LlmClient client = client(CallLimits.DEFAULT);

    List<Object> outcomes = callAll(client, 10);

    assertThat(outcomes).allMatch(LlmResponse.class::isInstance);
    assertThat(fake.sent()).hasSize(30);
    assertThat(log.calls()).hasSize(30);
    assertThat(log.calls())
        .filteredOn(call -> call.outcome() == LlmCall.Outcome.SUCCEEDED)
        .hasSize(10);
    assertThat(log.calls())
        .filteredOn(call -> call.outcome() == LlmCall.Outcome.FAILED)
        .allSatisfy(
            call ->
                assertThat(call.error())
                    .startsWith("HTTP 429 rate limited (attempt ")
                    .contains(" of 4, retrying in "));

    fake.failingFirst(1, () -> new TransientLlmException("HTTP 529 overloaded", null));
    assertThat(client.call(request(99)).text()).isEqualTo("fake answer");
  }

  @Test
  void givesUpAfterTheLastAttemptAndReleasesTheBudget() {
    fake.failingFirst(10, () -> new TransientLlmException("HTTP 503 unavailable", null));
    BudgetGuard budget = new BudgetGuard("run", 200L, null);
    LlmClient client = client(CallLimits.DEFAULT, budget, FAST);

    assertThatThrownBy(() -> client.call(request(1)))
        .isInstanceOf(LlmException.class)
        .isNotInstanceOf(TransientLlmException.class)
        .hasMessage("Gave up after 4 attempts: HTTP 503 unavailable")
        .hasCauseInstanceOf(TransientLlmException.class);

    assertThat(fake.sent()).hasSize(4);
    assertThat(log.calls()).hasSize(4).allMatch(c -> c.outcome() == LlmCall.Outcome.FAILED);
    assertThat(log.calls().getLast().error())
        .isEqualTo("Gave up after 4 attempts: HTTP 503 unavailable");
    assertThat(budget.spentTokens()).isZero();
    // Nothing is still reserved: a call of the whole budget's size fits.
    budget.reserve(200, BigDecimal.ZERO).release();
  }

  @Test
  void otherFailuresAreNotRetried() {
    fake.failingFirst(1, () -> new LlmException("HTTP 400 bad request"));
    LlmClient client = client(CallLimits.DEFAULT);

    assertThatThrownBy(() -> client.call(request(1))).hasMessage("HTTP 400 bad request");

    assertThat(fake.sent()).hasSize(1);
  }

  @Test
  void waitsAsLongAsTheProviderAsks() throws Exception {
    fake.failingFirst(
        1, () -> new TransientLlmException("HTTP 429 rate limited", Duration.ofMillis(300)));
    LlmClient client = client(CallLimits.DEFAULT);

    long elapsed = millis(() -> client.call(request(1)));

    assertThat(elapsed).isGreaterThanOrEqualTo(300);
    assertThat(fake.sent()).hasSize(2);
  }

  @Test
  void aPauseTheProviderAskedForHoldsBackEveryCallToTheModel() throws Exception {
    CallLimiter limiter = new CallLimiter(FAST);
    ModelConfig model = fake.model("m");
    limiter.lane(model).pause(Duration.ofMillis(300));

    long elapsed = millis(() -> limiter.lane(fake.model("m")).awaitTurn());
    long otherModel = millis(() -> limiter.lane(fake.model("other")).awaitTurn());

    assertThat(elapsed).isGreaterThanOrEqualTo(250);
    assertThat(otherModel).isLessThan(100);
  }

  @Test
  void theBudgetHoldsUnderConcurrency() throws Exception {
    // Each answer uses its whole output limit, so a call costs exactly its reserved worst case:
    // "You are a test." plus "Question NN" is 7 tokens in, plus 100 out = 107 tokens, $0.001521.
    fake.withLatency(Duration.ofMillis(10))
        .answering((Function<LlmRequest, String>) r -> "x".repeat(4 * r.maxOutputTokens()));
    BigDecimal perCall = new BigDecimal("0.001521");
    BudgetGuard budget = new BudgetGuard("run", null, perCall.multiply(BigDecimal.valueOf(10)));
    LlmClient client = client(new CallLimits(16, null), budget, FAST);

    List<Object> outcomes = callAll(client, 40);

    assertThat(outcomes).filteredOn(LlmResponse.class::isInstance).hasSize(10);
    assertThat(outcomes)
        .filteredOn(o -> !(o instanceof LlmResponse))
        .allMatch(BudgetExceededException.class::isInstance)
        .hasSize(30);
    assertThat(budget.spentCostUsd()).isEqualByComparingTo(perCall.multiply(BigDecimal.TEN));
    assertThat(budget.spentTokens()).isEqualTo(1_070);
    // Every call is in the log, and the log's costs add up to what the budget counted.
    assertThat(log.calls()).hasSize(40);
    assertThat(log.calls().stream().map(LlmCall::costUsd).reduce(BigDecimal.ZERO, BigDecimal::add))
        .isEqualByComparingTo(budget.spentCostUsd());
    assertThat(fake.sent()).hasSize(10);
  }

  @Test
  void retriesKeepTheirReservation() throws Exception {
    // Every call fails once; a budget of exactly one call lets one through and refuses the rest
    // while it is being retried, so a retried call is never left without its budget.
    fake.withLatency(Duration.ofMillis(5))
        .answering((Function<LlmRequest, String>) r -> "x".repeat(4 * r.maxOutputTokens()))
        .failingFirst(1, () -> new TransientLlmException("HTTP 429 rate limited", null));
    BudgetGuard budget = new BudgetGuard("run", 107L, null);
    LlmClient client = client(new CallLimits(8, null), budget, FAST);

    List<Object> outcomes = callAll(client, 8);

    assertThat(outcomes).filteredOn(LlmResponse.class::isInstance).hasSize(1);
    assertThat(budget.spentTokens()).isEqualTo(107);
  }

  @Test
  void anInterruptedRetryWaitIsLoggedAndGivesBackBudgetAndSlot() throws Exception {
    fake.failingFirst(1, () -> new TransientLlmException("HTTP 503 unavailable", null));
    BudgetGuard budget = new BudgetGuard("run", 200L, null);
    RetryPolicy slow = new RetryPolicy(3, Duration.ofSeconds(30), Duration.ofSeconds(60));
    LlmClient client = client(new CallLimits(1, null), budget, slow);
    AtomicReference<Throwable> thrown = new AtomicReference<>();
    Thread caller =
        Thread.ofVirtual().start(() -> thrown.set(catchThrowable(() -> client.call(request(1)))));
    while (log.calls().isEmpty()) {
      Thread.sleep(5);
    }

    caller.interrupt();
    caller.join(5_000);

    assertThat(thrown.get())
        .isInstanceOf(LlmException.class)
        .hasMessageContaining("Interrupted while waiting to retry");
    assertThat(log.calls())
        .extracting(LlmCall::outcome)
        .containsExactly(LlmCall.Outcome.FAILED, LlmCall.Outcome.REFUSED);
    budget.reserve(200, BigDecimal.ZERO).release();
    // The one slot is free again: the request's injected failure is used up, so it now answers.
    long elapsed = millis(() -> client.call(request(1)));
    assertThat(elapsed).isLessThan(5_000);
  }

  @Test
  void anErrorFromTheProviderGivesBackBudgetAndSlot() throws Exception {
    fake.answering(
        (Function<LlmRequest, String>)
            r -> {
              throw new AssertionError("provider bug");
            });
    BudgetGuard budget = new BudgetGuard("run", 200L, null);
    LlmClient client = client(new CallLimits(1, null), budget, FAST);

    assertThatThrownBy(() -> client.call(request(1))).isInstanceOf(AssertionError.class);

    budget.reserve(200, BigDecimal.ZERO).release();
    fake.answering("ok");
    long elapsed = millis(() -> client.call(request(2)));
    assertThat(elapsed).isLessThan(5_000);
  }
}

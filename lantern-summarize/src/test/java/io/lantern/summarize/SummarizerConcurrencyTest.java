package io.lantern.summarize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactObject;
import io.lantern.llm.BudgetExceededException;
import io.lantern.llm.BudgetGuard;
import io.lantern.llm.CallLimiter;
import io.lantern.llm.CallLimits;
import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.InMemoryLlmCallLog;
import io.lantern.llm.LlmCall;
import io.lantern.llm.LlmClient;
import io.lantern.llm.LlmException;
import io.lantern.llm.ModelSettings;
import io.lantern.llm.RetryPolicy;
import io.lantern.llm.TransientLlmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Summarizing units in parallel gives the same summaries as one at a time, only sooner. */
class SummarizerConcurrencyTest {

  private static final Duration LATENCY = Duration.ofMillis(20);
  private static final RetryPolicy FAST =
      new RetryPolicy(3, Duration.ofMillis(1), Duration.ofMillis(5));

  private final FakeLlmProvider fake =
      new FakeLlmProvider().answering(SummarizerTest::citingAll).withLatency(LATENCY);
  private final InMemoryLlmCallLog log = new InMemoryLlmCallLog();

  private LlmClient client(int maxConcurrency, BudgetGuard budget) {
    return new LlmClient(
        ModelSettings.single(
            fake.model("model-a").withLimits(new CallLimits(maxConcurrency, null))),
        List.of(fake),
        budget,
        log,
        Clock.systemUTC(),
        new CallLimiter(FAST));
  }

  private Summarizer summarizer(int maxConcurrency) {
    return new Summarizer(
        client(maxConcurrency, BudgetGuard.unlimited("run")), new InMemorySummaryStore());
  }

  /**
   * A repository with 2 modules of 3 packages of 8 types each, 57 units in all, so most of the work
   * is independent leaves, as in a real repository.
   */
  private static List<SummaryNode> wideRepo() {
    List<SummaryNode> modules = new ArrayList<>();
    for (int m = 0; m < 2; m++) {
      String module = "module:com.example:m" + m;
      List<SummaryNode> packages = new ArrayList<>();
      for (int p = 0; p < 3; p++) {
        String pkg = "package:com.example:m" + m + ":com.example.p" + p;
        List<SummaryNode> types = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
          String type = "type:com.example.m" + m + ".p" + p + ".T" + t;
          types.add(node(type, SummaryLevel.TYPE, List.of(fact(type, "kind", "class")), List.of()));
        }
        packages.add(node(pkg, SummaryLevel.PACKAGE, List.of(), types));
      }
      modules.add(
          node(module, SummaryLevel.MODULE, List.of(fact(module, "packaging", "jar")), packages));
    }
    return List.of(node("repo:wide", SummaryLevel.REPO, List.of(), modules));
  }

  private static SummaryNode node(
      String entity, SummaryLevel level, List<Fact> facts, List<SummaryNode> children) {
    return new SummaryNode(EntityId.parse(entity), level, facts, children);
  }

  private static Fact fact(String subject, String predicate, String value) {
    return TestFacts.fact("wide", subject, predicate, FactObject.string(value), "pom.xml");
  }

  private static long millis(Runnable work) {
    long start = System.nanoTime();
    work.run();
    return (System.nanoTime() - start) / 1_000_000;
  }

  @Test
  void parallelSummariesAreTheSameAsSequentialOnesAndComeSooner() {
    List<SummaryNode> tree = wideRepo();
    Summarizer.Result[] results = new Summarizer.Result[2];

    long sequential = millis(() -> results[0] = summarizer(1).summarize(tree));
    assertThat(fake.maxConcurrentCalls()).isEqualTo(1);
    long parallel = millis(() -> results[1] = summarizer(8).summarize(tree));

    assertThat(results[0].generated()).isEqualTo(57);
    assertThat(results[1].generated()).isEqualTo(57);
    // Same texts, keys and citations, in the same order.
    assertThat(results[1].summaries()).containsExactlyEntriesOf(results[0].summaries());
    // 57 calls of 20 ms one at a time take at least 1,140 ms; eight at a time, the 48 types take
    // six rounds and the packages, modules and repository one each, about 180 ms.
    assertThat(sequential).isGreaterThanOrEqualTo(1_140);
    assertThat(parallel).isLessThan(sequential / 3);
    assertThat(fake.maxConcurrentCalls()).isEqualTo(8);
    System.out.printf(
        "Summarizing 57 units at 20 ms a call: %d ms one at a time, %d ms eight at a time (%.1fx)%n",
        sequential, parallel, (double) sequential / parallel);
  }

  @Test
  void retriedRateLimitsDoNotChangeTheSummaries() {
    List<SummaryNode> tree = wideRepo();
    Summarizer.Result clean = summarizer(8).summarize(tree);

    fake.failingFirst(2, () -> new TransientLlmException("HTTP 429 rate limited", null));
    Summarizer.Result retried = summarizer(8).summarize(tree);

    assertThat(retried.summaries()).containsExactlyEntriesOf(clean.summaries());
    assertThat(log.calls())
        .filteredOn(call -> call.outcome() == LlmCall.Outcome.FAILED)
        .hasSize(2 * 57);
  }

  @Test
  void theBudgetHoldsAndEveryCallIsLogged() {
    // Enough for some of the types, not for the whole repository.
    BudgetGuard budget = new BudgetGuard("run", 5_000L, null);
    InMemorySummaryStore store = new InMemorySummaryStore();
    Summarizer summarizer = new Summarizer(client(8, budget), store);

    assertThatThrownBy(() -> summarizer.summarize(wideRepo()))
        .isInstanceOf(BudgetExceededException.class);

    int sent = fake.sent().size();
    assertThat(budget.spentTokens()).isPositive().isLessThanOrEqualTo(5_000);
    assertThat(log.calls())
        .filteredOn(call -> call.outcome() == LlmCall.Outcome.SUCCEEDED)
        .hasSize(sent);
    assertThat(log.calls())
        .filteredOn(call -> call.outcome() == LlmCall.Outcome.REFUSED)
        .isNotEmpty();
    assertThat(store.size()).isEqualTo(sent);
    // Nothing still runs once the summarizer has thrown.
    assertThat(budget.spentTokens())
        .isEqualTo(log.calls().stream().mapToLong(call -> call.usage().total()).sum());
    assertThat(fake.sent().stream().map(s -> s.request().purpose()))
        .noneMatch(purpose -> purpose.contains("repo:wide"));
  }

  @Test
  void aFailedUnitStopsTheRunAndIsWhatIsThrown() {
    fake.answering(
        request -> {
          if (request.purpose().endsWith("type:com.example.m0.p1.T3")) {
            throw new LlmException("HTTP 400 bad request");
          }
          return SummarizerTest.citingAll(request);
        });

    assertThatThrownBy(() -> summarizer(8).summarize(wideRepo()))
        .isInstanceOf(LlmException.class)
        .hasMessage("HTTP 400 bad request");

    assertThat(fake.sent().stream().map(s -> s.request().purpose()))
        .noneMatch(purpose -> purpose.contains("package:com.example:m0:com.example.p1"))
        .noneMatch(purpose -> purpose.contains("repo:wide"));
  }
}

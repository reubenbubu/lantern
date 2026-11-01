package io.lantern.summarize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactObject;
import io.lantern.harness.BlobLookup;
import io.lantern.harness.Harness;
import io.lantern.harness.InMemoryAnswerStore;
import io.lantern.harness.RunBudget;
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
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Summarizing units in parallel gives the same summaries as one at a time. The tests wait on
 * latches and threads, not on the clock; the timeout is only there so that a broken summarizer
 * fails the test instead of hanging it.
 */
class SummarizerConcurrencyTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);
  private static final RetryPolicy FAST =
      new RetryPolicy(3, Duration.ofMillis(1), Duration.ofMillis(5));

  private final FakeLlmProvider fake = new FakeLlmProvider().answering(SummarizerTest::citingAll);
  private final InMemoryLlmCallLog log = new InMemoryLlmCallLog();
  private final CallLimiter limiter = limiterAllowing(64);
  private final AtomicBoolean joinTimedOut = new AtomicBoolean();

  private LlmClient client(int maxConcurrency, BudgetGuard budget) {
    return client(fake, maxConcurrency, budget, log, limiter);
  }

  private static LlmClient client(
      FakeLlmProvider provider,
      int maxConcurrency,
      BudgetGuard budget,
      InMemoryLlmCallLog log,
      CallLimiter limiter) {
    return new LlmClient(
        ModelSettings.single(
            provider.model("model-a").withLimits(new CallLimits(maxConcurrency, null))),
        List.of(provider),
        budget,
        log,
        Clock.systemUTC(),
        limiter);
  }

  /**
   * A limiter whose lane for {@code model-a} allows the given number of calls at once, more than
   * the summarizers under test run, so that only the summarizer's own limit bounds them. A lane
   * takes its limits from the first configuration it is asked for, so one call through another fake
   * of the same name sets it.
   */
  private static CallLimiter limiterAllowing(int calls) {
    CallLimiter limiter = new CallLimiter(FAST);
    FakeLlmProvider warmUp = new FakeLlmProvider().answering(SummarizerTest::citingAll);
    String type = "type:com.example.WarmUp";
    new Summarizer(
            Harness.inMemory(
                client(
                    warmUp,
                    calls,
                    BudgetGuard.unlimited("warm-up"),
                    new InMemoryLlmCallLog(),
                    limiter)))
        .summarize(
            List.of(
                node(type, SummaryLevel.TYPE, List.of(fact(type, "kind", "class")), List.of())));
    return limiter;
  }

  private Summarizer summarizer(int maxConcurrency) {
    return new Summarizer(Harness.inMemory(client(maxConcurrency, BudgetGuard.unlimited("run"))));
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

  @Test
  void parallelSummariesAreTheSameAsSequentialOnesAndRunEightAtOnce() {
    List<SummaryNode> tree = wideRepo();
    Summarizer.Result sequential = summarizer(1).summarize(tree);
    assertThat(fake.maxConcurrentCalls()).isOne();

    // The first eight calls answer only once all eight are in flight, which they can only be if the
    // summarizer runs eight units at once.
    CountDownLatch eightInFlight = new CountDownLatch(8);
    fake.answering(
        request -> {
          eightInFlight.countDown();
          awaitEightInFlight(eightInFlight);
          return SummarizerTest.citingAll(request);
        });
    Summarizer.Result parallel = summarizer(8).summarize(tree);

    assertThat(sequential.generated()).isEqualTo(57);
    assertThat(parallel.generated()).isEqualTo(57);
    // Same texts, keys and citations, in the same order.
    assertThat(parallel.summaries()).containsExactlyEntriesOf(sequential.summaries());
    assertThat(fake.maxConcurrentCalls()).isEqualTo(8);
  }

  private static void awaitEightInFlight(CountDownLatch latch) {
    try {
      if (!latch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
        throw new AssertionError("Fewer than eight calls were ever in flight at once");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted", e);
    }
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
    InMemoryAnswerStore store = new InMemoryAnswerStore();
    Summarizer summarizer =
        new Summarizer(
            new Harness(client(8, budget), store, RunBudget.NONE, BlobLookup.NONE, Set.of()));

    // A budget that refuses a call leaves the unit not asked (ADR 0100); the run goes on.
    Summarizer.Result result = summarizer.summarize(wideRepo());
    assertThat(result.notAsked()).isNotEmpty();

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
    // The first call fails, whichever type it is for, so the run must stop with most units unsent.
    List<String> calls = new ArrayList<>();
    Thread[] failingThread = new Thread[1];
    fake.answering(
        request -> {
          Thread failing;
          synchronized (calls) {
            calls.add(request.purpose());
            if (failingThread[0] == null) {
              failingThread[0] = Thread.currentThread();
              throw new LlmException("HTTP 400 bad request");
            }
            failing = failingThread[0];
          }
          // Every later call is held until the failed unit is done, which it only is once the run
          // knows it failed: each unit runs on its own virtual thread, which then ends. So no call
          // frees its place for another unit before the run has failed.
          join(failing);
          return SummarizerTest.citingAll(request);
        });

    assertThatThrownBy(() -> summarizer(8).summarize(wideRepo()))
        .isInstanceOf(LlmException.class)
        .hasMessage("HTTP 400 bad request");

    Matcher failed =
        Pattern.compile("summarize type type:com\\.example\\.(m\\d)\\.(p\\d)\\.T\\d")
            .matcher(calls.getFirst());
    assertThat(failed.matches()).as(calls.getFirst()).isTrue();
    String failedPackage =
        "package:com.example:" + failed.group(1) + ":com.example." + failed.group(2);
    assertThat(fake.sent().stream().map(s -> s.request().purpose()))
        .hasSize(calls.size())
        .noneMatch(purpose -> purpose.endsWith(failedPackage))
        .noneMatch(purpose -> purpose.contains("repo:wide"));
    // Besides the failed call, at most the seven units that may run with it make theirs; no unit
    // starts after the run has failed.
    assertThat(calls).hasSizeLessThanOrEqualTo(8);
    // A held call that gave up waiting would fail its own unit, which the run may not report.
    assertThat(joinTimedOut).as("a held call timed out").isFalse();
  }

  private void join(Thread thread) {
    try {
      if (!thread.join(TIMEOUT)) {
        joinTimedOut.set(true);
        throw new AssertionError("The failed unit's thread did not end");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted", e);
    }
  }

  @Test
  void aUnitSharedByTwoRootsIsSummarizedOnce() {
    SummaryNode shared =
        node(
            "type:com.example.Shared",
            SummaryLevel.TYPE,
            List.of(fact("type:com.example.Shared", "kind", "class")),
            List.of());
    SummaryNode first =
        node("package:com.example:a:com.example", SummaryLevel.PACKAGE, List.of(), List.of(shared));
    SummaryNode second =
        node("package:com.example:b:com.example", SummaryLevel.PACKAGE, List.of(), List.of(shared));

    Summarizer.Result result = summarizer(8).summarize(List.of(first, second));

    assertThat(result.summaries().keySet())
        .extracting(EntityId::toString)
        .containsExactly(
            "type:com.example.Shared",
            "package:com.example:a:com.example",
            "package:com.example:b:com.example");
    assertThat(result.generated()).isEqualTo(3);
    assertThat(fake.sent()).hasSize(3);
  }

  @Test
  void unitsThatContainEachOtherAreRefusedBeforeAnyCall() {
    String a = "package:com.example:a:com.example";
    SummaryNode inner = node(a, SummaryLevel.PACKAGE, List.of(fact(a, "kind", "x")), List.of());
    SummaryNode middle = node("type:com.example.B", SummaryLevel.TYPE, List.of(), List.of(inner));
    SummaryNode outer = node(a, SummaryLevel.PACKAGE, List.of(), List.of(middle));

    assertThatThrownBy(() -> summarizer(8).summarize(List.of(outer)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Units contain each other");
    assertThat(fake.sent()).isEmpty();
  }
}

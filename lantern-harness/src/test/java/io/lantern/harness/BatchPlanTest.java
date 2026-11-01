package io.lantern.harness;

import static io.lantern.harness.TestHarness.harness;
import static io.lantern.harness.TestHarness.item;
import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.ModelConfig;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** When a bulk role batches, and what its plan then estimates (ADR 0102). */
class BatchPlanTest {

  private static List<Item> items(int count) {
    List<Item> items = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      items.add(item("unit-" + i, "File" + i + ".java"));
    }
    return items;
  }

  private static ModelConfig batching(FakeLlmProvider fake) {
    ModelConfig priced = TestHarness.priced(fake);
    return new ModelConfig(
        priced.provider(),
        priced.model(),
        fake.batchingModel("x").capabilities(),
        priced.contextWindow(),
        priced.pricing());
  }

  @Test
  void aBulkRoleBatchesPastFiftyMissesAndItsPlanIsAtTheBatchPrice() {
    FakeLlmProvider fake = new FakeLlmProvider();
    RoleSession fifty =
        harness(TestHarness.client(fake, batching(fake)), new InMemoryAnswerStore())
            .session(TestHarness.role());
    Estimate atFifty = fifty.plan(items(50), 100);
    assertThat(fifty.batching()).isFalse();

    RoleSession more =
        harness(TestHarness.client(fake, batching(fake)), new InMemoryAnswerStore())
            .session(TestHarness.role());
    Estimate past = more.plan(items(51), 100);
    assertThat(more.batching()).isTrue();

    RoleSession interactive =
        harness(TestHarness.client(fake, batching(fake)), new InMemoryAnswerStore())
            .batchingAbove(1000)
            .session(TestHarness.role());
    Estimate same = interactive.plan(items(51), 100);
    assertThat(interactive.batching()).isFalse();
    // Everything at half price but the warm-up's cache write of the 100-token prompt start, which
    // goes at the full one-hour write price ($2 a million) instead of the five-minute one ($1.25).
    BigDecimal write = new BigDecimal("0.000125");
    BigDecimal writeLong = new BigDecimal("0.0002");
    assertThat(past.usd())
        .isEqualByComparingTo(
            same.usd().subtract(write).multiply(new BigDecimal("0.5")).add(writeLong));
    assertThat(atFifty.usd()).isPositive();
  }

  @Test
  void aBatchingRoleSpendsMostOfItsShare() throws Exception {
    // Each answer is about 100 output tokens, as the role expects, of up to 1,000 the request
    // allows: about $0.00025 an item at the batch price, a worst case of about $0.0025.
    FakeLlmProvider fake = new FakeLlmProvider().answering("\"" + "x".repeat(400) + "\"");
    RunBudget budget =
        new RunBudget(new BigDecimal("0.02"), Map.of("summarize", 50, "describe", 50));
    Harness harness =
        new Harness(
            TestHarness.client(fake, batching(fake)),
            new InMemoryAnswerStore(),
            budget,
            BlobLookup.NONE,
            Set.of());
    RoleSession session = harness.session(TestHarness.role());
    List<Item> items = items(60);
    session.plan(items, 0);
    assertThat(session.batching()).isTrue();

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Outcome>> asked = new ArrayList<>();
      for (Item item : items) {
        asked.add(executor.submit(() -> session.ask(item, askingUpTo(item, 1_000))));
      }
      for (Future<Outcome> outcome : asked) {
        outcome.get();
      }
    }

    // The $0.01 share holds about 40 of the 60 items, which are asked and spend most of it.
    RoleRecord record = session.record();
    BigDecimal spent = new BigDecimal(record.toJson().get("costUsd").asString());
    assertThat(spent).isGreaterThan(new BigDecimal("0.008")).isLessThan(new BigDecimal("0.0105"));
    assertThat(record.toJson().get("items").get("notAsked").asInt()).isBetween(15, 25);
    assertThat(record.settled()).isEqualTo(record.planned()).isEqualTo(60);
  }

  @Test
  void underABudgetTheSameItemsAreAskedAndTheSameGoesFirstWhateverTheThreadTiming()
      throws Exception {
    // 40 items of about $0.0005 each (100 output tokens at $5 a million), a share of $0.0075:
    // the plan takes the first 14 or so in its order, whatever order the threads ask them in.
    List<Item> items = items(40);
    java.util.Set<List<String>> askedSets = new java.util.HashSet<>();
    java.util.Set<String> firstSent = new java.util.HashSet<>();
    for (int run = 0; run < 25; run++) {
      FakeLlmProvider fake = new FakeLlmProvider().answering("\"" + "x".repeat(400) + "\"");
      RunBudget budget =
          new RunBudget(new BigDecimal("0.015"), Map.of("summarize", 50, "describe", 50));
      Harness harness =
          new Harness(
              TestHarness.client(fake, TestHarness.priced(fake)),
              new InMemoryAnswerStore(),
              budget,
              BlobLookup.NONE,
              Set.of());
      RoleSession session = harness.session(TestHarness.role());
      session.plan(items, 0);
      List<Item> shuffled = new ArrayList<>(items);
      java.util.Collections.shuffle(shuffled, new java.util.Random(run));
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        List<Future<Outcome>> asked = new ArrayList<>();
        for (Item item : shuffled) {
          asked.add(executor.submit(() -> session.ask(item, TestHarness.asking(item))));
        }
        for (Future<Outcome> outcome : asked) {
          outcome.get();
        }
      }
      List<String> subjects = new ArrayList<>(session.record().asked());
      java.util.Collections.sort(subjects);
      askedSets.add(subjects);
      firstSent.add(fake.sent().getFirst().request().purpose());
      assertThat(session.record().settled()).isEqualTo(40);
    }

    // One set every run: the first items in the plan's order, as many as fit.
    assertThat(askedSets).hasSize(1);
    List<String> asked = askedSets.iterator().next();
    assertThat(asked.size()).isBetween(10, 20);
    List<String> expected = new ArrayList<>();
    items.subList(0, asked.size()).forEach(item -> expected.add(item.subject()));
    java.util.Collections.sort(expected);
    assertThat(asked).isEqualTo(expected);
    assertThat(firstSent).containsExactly("describe unit-0");
  }

  @Test
  void itemsOfOneSubjectAreEachCountedAgainstTheShare() {
    // A flow shown in two documents is two items of one subject. Each costs about $0.0005; a share
    // of $0.0008 holds one of them, not both.
    FakeLlmProvider fake = new FakeLlmProvider().answering("\"" + "x".repeat(400) + "\"");
    RunBudget budget =
        new RunBudget(new BigDecimal("0.0016"), Map.of("summarize", 50, "describe", 50));
    Harness harness =
        new Harness(
            TestHarness.client(fake, TestHarness.priced(fake)),
            new InMemoryAnswerStore(),
            budget,
            BlobLookup.NONE,
            Set.of());
    RoleSession session = harness.session(TestHarness.role());
    Item first = new Item("flow", "repo/a", List.of(), "Narrate the flow, system document.");
    Item second = new Item("flow", "repo/a", List.of(), "Narrate the flow, repository document.");
    session.plan(List.of(first, second), 0);

    assertThat(session.ask(first, TestHarness.asking(first)).kind())
        .isEqualTo(Outcome.Kind.ANSWERED);
    assertThat(session.ask(second, TestHarness.asking(second)).kind())
        .isEqualTo(Outcome.Kind.NOT_ASKED);
  }

  @Test
  void withRoomForEverythingEveryItemIsAskedAndTheFirstInOrderGoesFirst() throws Exception {
    List<Item> items = items(30);
    FakeLlmProvider fake = new FakeLlmProvider();
    RoleSession session =
        harness(TestHarness.client(fake), new InMemoryAnswerStore()).session(TestHarness.role());
    session.plan(items, 0);
    List<Item> reversed = new ArrayList<>(items);
    java.util.Collections.reverse(reversed);
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Outcome>> asked = new ArrayList<>();
      for (Item item : reversed) {
        asked.add(executor.submit(() -> session.ask(item, TestHarness.asking(item))));
      }
      for (Future<Outcome> outcome : asked) {
        assertThat(outcome.get().kind()).isEqualTo(Outcome.Kind.ANSWERED);
      }
    }
    assertThat(fake.sent()).hasSize(30);
    assertThat(fake.sent().getFirst().request().purpose()).isEqualTo("describe unit-0");
  }

  /** Asks one request of up to the given output for the item, and accepts any answer. */
  private static Asker askingUpTo(Item item, int maxOutput) {
    return calls ->
        Answer.of(
            calls
                .call(
                    LlmRequest.of(
                        io.lantern.llm.Role.SUMMARIZE,
                        "describe " + item.subject(),
                        "System.",
                        item.rendering(),
                        maxOutput))
                .text());
  }

  @Test
  void aModelThatDoesNotBatchOrAnInteractiveRoleNeverBatches() {
    FakeLlmProvider fake = new FakeLlmProvider();
    RoleSession plain =
        harness(TestHarness.client(fake), new InMemoryAnswerStore()).session(TestHarness.role());
    plain.plan(items(80), 100);
    assertThat(plain.batching()).isFalse();

    RoleSession agentic =
        harness(TestHarness.client(fake, batching(fake)), new InMemoryAnswerStore())
            .session(
                RoleSpec.single(
                    "resolve",
                    io.lantern.llm.Role.RESOLVE,
                    "1",
                    "resolve",
                    RunMode.INTERACTIVE,
                    100));
    agentic.plan(items(80), 100);
    assertThat(agentic.batching()).isFalse();
  }
}

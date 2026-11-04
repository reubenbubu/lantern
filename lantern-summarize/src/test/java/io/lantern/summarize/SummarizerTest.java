package io.lantern.summarize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.harness.BlobLookup;
import io.lantern.harness.Harness;
import io.lantern.harness.InMemoryAnswerStore;
import io.lantern.harness.RunBudget;
import io.lantern.llm.BudgetGuard;
import io.lantern.llm.Capability;
import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.InMemoryLlmCallLog;
import io.lantern.llm.LlmClient;
import io.lantern.llm.LlmException;
import io.lantern.llm.LlmMessage;
import io.lantern.llm.LlmProvider;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.LlmResponse;
import io.lantern.llm.ModelConfig;
import io.lantern.llm.ModelSettings;
import io.lantern.llm.Pricing;
import io.lantern.llm.Role;
import io.lantern.llm.Thinking;
import io.lantern.llm.TokenUsage;
import io.lantern.llm.Tokens;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class SummarizerTest {

  private static final Pattern REF =
      Pattern.compile("^\\[([FES]\\d+)\\]|<code ref=\"(E\\d+)\"", Pattern.MULTILINE);
  private static final Pattern UNIT = Pattern.compile("^summarize \\S+ (\\S+:\\S+)");

  private final FakeLlmProvider fake =
      new FakeLlmProvider().answering(Answers.epic(SummarizerTest::citingAll));
  private final InMemoryAnswerStore store = new InMemoryAnswerStore();

  /** A harness with the test's answer cache, so later runs reuse earlier answers. */
  private Harness harness(LlmClient client) {
    return new Harness(client, store, RunBudget.NONE, BlobLookup.NONE, Set.of());
  }

  /** What the summarizer logs during a test. */
  private final ListAppender<ILoggingEvent> log = new ListAppender<>();

  @BeforeEach
  void captureLog() {
    log.start();
    ((Logger) LoggerFactory.getLogger(Summarizer.class)).addAppender(log);
    ((Logger) LoggerFactory.getLogger(io.lantern.harness.Calls.class)).addAppender(log);
  }

  @AfterEach
  void releaseLog() {
    ((Logger) LoggerFactory.getLogger(Summarizer.class)).detachAppender(log);
    ((Logger) LoggerFactory.getLogger(io.lantern.harness.Calls.class)).detachAppender(log);
  }

  /**
   * A well-behaved model: names the unit, includes a fingerprint of its prompt so the text changes
   * whenever the inputs do, and cites every reference it was given.
   */
  static String citingAll(LlmRequest request) {
    String prompt = request.messages().getFirst().text();
    List<String> refs = new ArrayList<>();
    Matcher ref = REF.matcher(prompt);
    while (ref.find()) {
      refs.add(ref.group(1) != null ? ref.group(1) : ref.group(2));
    }
    return "Summary of %s (%08x), as a well-behaved model words it from its facts. [%s]"
        .formatted(unit(request), prompt.hashCode(), String.join(", ", refs));
  }

  /** The entity a request summarizes, from its purpose (the prompt names it without its ID). */
  private static String unit(LlmRequest request) {
    Matcher unit = UNIT.matcher(request.purpose());
    assertThat(unit.find()).isTrue();
    return unit.group(1);
  }

  private LlmClient client(String model, LlmProvider provider) {
    return new LlmClient(
        ModelSettings.single(fake.model(model)),
        List.of(provider),
        BudgetGuard.unlimited("test"),
        new InMemoryLlmCallLog(),
        Clock.systemUTC());
  }

  private Summarizer summarizer() {
    return new Summarizer(harness(client("model-a", fake)));
  }

  private List<String> summarizedUnits() {
    return fake.sent().stream().map(s -> unit(s.request())).toList();
  }

  @Test
  void underAnyBudgetEveryPlannedUnitLandsInExactlyOneCount() {
    ModelConfig priced =
        new ModelConfig(
            FakeLlmProvider.NAME,
            "priced",
            EnumSet.complementOf(EnumSet.of(Capability.BATCH)),
            1_000_000,
            new Pricing(
                new java.math.BigDecimal("1000"),
                new java.math.BigDecimal("1000"),
                java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ZERO));
    // From a budget that asks nothing to one that asks everything: whatever is not asked, a
    // package whose parts were all not asked included, is counted once.
    for (String usd : List.of("0", "0.5", "2", "5", "20", "1000")) {
      Harness harness =
          new Harness(
              new LlmClient(
                  ModelSettings.single(priced),
                  List.of(fake),
                  BudgetGuard.unlimited("test"),
                  new InMemoryLlmCallLog(),
                  Clock.systemUTC()),
              new InMemoryAnswerStore(),
              new RunBudget(new java.math.BigDecimal(usd), java.util.Map.of("summarize", 100)),
              BlobLookup.NONE,
              Set.of());
      new Summarizer(harness).summarize(SummaryTree.fromFacts(TestFacts.shop().list()));

      // Types and the units above them are two roles of one budget group.
      assertThat(harness.records()).hasSize(2);
      for (io.lantern.harness.RoleRecord record : harness.records()) {
        assertThat(record.settled()).as("$" + usd).isEqualTo(record.planned());
      }
    }
  }

  @Test
  void underABudgetNoUnitIsAskedWithoutItsPartsAndSmallerUnitsBehindALargeOneAreAsked() {
    ModelConfig priced =
        new ModelConfig(
            FakeLlmProvider.NAME,
            "priced",
            EnumSet.complementOf(EnumSet.of(Capability.BATCH)),
            1_000_000,
            new Pricing(
                new java.math.BigDecimal("1000"),
                new java.math.BigDecimal("1000"),
                java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ZERO));
    // A unit that does not fit waits while smaller ones behind it are asked; a unit whose part
    // waits waits with it, since its prompt would lack the part's summary (#661). Whatever the
    // budget, the run ends and every unit asked had each of its parts asked first.
    int skippedPast = 0;
    for (int cents = 5; cents <= 2000; cents += 15) {
      String usd = java.math.BigDecimal.valueOf(cents, 2).toPlainString();
      Harness harness =
          new Harness(
              new LlmClient(
                  ModelSettings.single(priced),
                  List.of(fake),
                  BudgetGuard.unlimited("test"),
                  new InMemoryLlmCallLog(),
                  Clock.systemUTC()),
              new InMemoryAnswerStore(),
              new RunBudget(new java.math.BigDecimal(usd), java.util.Map.of("summarize", 100)),
              BlobLookup.NONE,
              Set.of());
      List<SummaryNode> roots = SummaryTree.fromFacts(TestFacts.shop().list());
      org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
          java.time.Duration.ofSeconds(30), () -> new Summarizer(harness).summarize(roots));

      Set<String> asked = new java.util.HashSet<>();
      harness.records().forEach(record -> asked.addAll(record.asked()));
      List<String> order = new ArrayList<>();
      postOrder(roots, order);
      List<String> planned = order.stream().filter(e -> anything(roots, e)).toList();
      for (SummaryNode node : nodes(roots)) {
        if (asked.contains(node.entity().toString())) {
          for (SummaryNode part : node.children()) {
            if (anything(roots, part.entity().toString())) {
              assertThat(asked)
                  .as("$" + usd + ", " + node.entity())
                  .contains(part.entity().toString());
            }
          }
        }
      }
      // Asked units that come after one not asked, in the tree's order.
      int firstNotAsked = -1;
      for (int i = 0; i < planned.size(); i++) {
        if (!asked.contains(planned.get(i))) {
          firstNotAsked = i;
          break;
        }
      }
      if (firstNotAsked >= 0
          && planned.subList(firstNotAsked, planned.size()).stream().anyMatch(asked::contains)) {
        skippedPast++;
      }
      for (io.lantern.harness.RoleRecord record : harness.records()) {
        assertThat(record.settled()).as("$" + usd).isEqualTo(record.planned());
      }
    }
    assertThat(skippedPast)
        .as("budgets where a smaller unit was asked past a larger one")
        .isPositive();
  }

  private static void postOrder(List<SummaryNode> nodes, List<String> order) {
    for (SummaryNode node : nodes) {
      postOrder(node.children(), order);
      order.add(node.entity().toString());
    }
  }

  private static List<SummaryNode> nodes(List<SummaryNode> roots) {
    List<SummaryNode> all = new ArrayList<>();
    for (SummaryNode node : roots) {
      all.add(node);
      all.addAll(nodes(node.children()));
    }
    return all;
  }

  /** Whether the unit has anything to summarize: facts of its own or of a part below it. */
  private static boolean anything(List<SummaryNode> roots, String entity) {
    return nodes(roots).stream()
        .filter(node -> node.entity().toString().equals(entity))
        .anyMatch(SummarizerTest::anything);
  }

  private static boolean anything(SummaryNode node) {
    return !node.facts().isEmpty() || node.children().stream().anyMatch(SummarizerTest::anything);
  }

  @Test
  void aRerunAfterABudgetPlansEveryUnitAsItWillBeAskedAndDecidesItInOrder() {
    ModelConfig priced =
        new ModelConfig(
            FakeLlmProvider.NAME,
            "priced",
            EnumSet.complementOf(EnumSet.of(Capability.BATCH)),
            1_000_000,
            new Pricing(
                new java.math.BigDecimal("1000"),
                new java.math.BigDecimal("1000"),
                java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ZERO));
    for (String usd : List.of("0.5", "2", "5", "20")) {
      InMemoryAnswerStore store = new InMemoryAnswerStore();
      java.util.function.Supplier<Harness> harness =
          () ->
              new Harness(
                  new LlmClient(
                      ModelSettings.single(priced),
                      List.of(fake),
                      BudgetGuard.unlimited("test"),
                      new InMemoryLlmCallLog(),
                      Clock.systemUTC()),
                  store,
                  new RunBudget(new java.math.BigDecimal(usd), java.util.Map.of("summarize", 100)),
                  BlobLookup.NONE,
                  Set.of());
      Harness first = harness.get();
      new Summarizer(first).summarize(SummaryTree.fromFacts(TestFacts.shop().list()));
      int stored = accepted(first);

      Harness again = harness.get();
      new Summarizer(again).summarize(SummaryTree.fromFacts(TestFacts.shop().list()));

      // A unit whose parts are all cached but which was not asked itself is planned as a miss,
      // not counted as cached: only the units whose own summaries are stored are.
      assertThat(again.records().stream().mapToInt(r -> r.estimate().cached()).sum())
          .as("$" + usd)
          .isEqualTo(stored);
      for (io.lantern.harness.RoleRecord record : again.records()) {
        assertThat(record.settled()).as("$" + usd).isEqualTo(record.planned());
      }
    }
  }

  @Test
  void aWarmUpUnitWhosePartFailsReleasesTheOthersAndTheRunEnds() {
    // A rerun after a budget-limited run: the first unit to ask, which goes first, is one whose
    // parts are cached. The store fails when the units ask it for their stored summaries, so a part
    // of the warm-up unit fails and the warm-up unit never runs. The units waiting for it must go
    // on, record why they were not asked, and the run end with the failure (#658).
    ModelConfig priced =
        new ModelConfig(
            FakeLlmProvider.NAME,
            "priced",
            EnumSet.complementOf(EnumSet.of(Capability.BATCH)),
            1_000_000,
            new Pricing(
                new java.math.BigDecimal("1000"),
                new java.math.BigDecimal("1000"),
                java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ZERO));
    int scenarios = 0;
    // A sweep of budgets for the first run and a rerun at the same or a larger one: the scenario
    // needs a rerun whose first unit to ask has parts while units off its path are left to ask too,
    // which depends on where each budget's rounds end.
    for (int pair = 0; pair < 36; pair++) {
      int tenths = 3 + pair / 3;
      String firstUsd = java.math.BigDecimal.valueOf(tenths, 1).toPlainString();
      String usd = java.math.BigDecimal.valueOf(tenths + 3 * (pair % 3), 1).toPlainString();
      InMemoryAnswerStore store = new InMemoryAnswerStore();
      io.lantern.harness.AnswerStore failing =
          new io.lantern.harness.AnswerStore() {
            @Override
            public java.util.Optional<io.lantern.harness.StoredAnswer> find(String fingerprint) {
              java.util.Optional<io.lantern.harness.StoredAnswer> found = store.find(fingerprint);
              // The plan reads the store on the caller's thread; units ask it on virtual threads.
              if (found.isPresent() && Thread.currentThread().isVirtual()) {
                throw new IllegalStateException("The answer store is unreachable");
              }
              return found;
            }

            @Override
            public java.util.Optional<io.lantern.harness.StoredAnswer> latest(
                String role, String subject) {
              return store.latest(role, subject);
            }

            @Override
            public void save(io.lantern.harness.StoredAnswer answer) {
              store.save(answer);
            }
          };
      java.util.function.BiFunction<io.lantern.harness.AnswerStore, String, Harness> harness =
          (answers, budget) ->
              new Harness(
                  new LlmClient(
                      ModelSettings.single(priced),
                      List.of(fake),
                      BudgetGuard.unlimited("test"),
                      new InMemoryLlmCallLog(),
                      Clock.systemUTC()),
                  answers,
                  new RunBudget(
                      new java.math.BigDecimal(budget), java.util.Map.of("summarize", 100)),
                  BlobLookup.NONE,
                  Set.of());
      Harness first = harness.apply(store, firstUsd);
      new Summarizer(first).summarize(SummaryTree.fromFacts(TestFacts.shop().list()));
      if (accepted(first) == 0) {
        // Nothing stored, nothing for the store to fail on.
        continue;
      }

      Harness again = harness.apply(failing, usd);
      List<SummaryNode> roots = SummaryTree.fromFacts(TestFacts.shop().list());
      org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
          java.time.Duration.ofSeconds(30),
          () ->
              org.assertj.core.api.Assertions.assertThatThrownBy(
                      () -> new Summarizer(again).summarize(roots))
                  .hasMessageContaining("unreachable"));

      // A budget that let the first run summarize everything leaves nothing to ask.
      // Each role, types and the units above them, has its own warm-up unit.
      for (io.lantern.harness.RoleSpec spec : List.of(Summarizer.TYPES, Summarizer.ROLE)) {
        String warmUp = again.session(spec).warmUpItem().orElse(null);
        if (warmUp == null || !hasParts(roots, warmUp)) {
          continue;
        }
        // Only the warm-up unit and the units that contain it never run (and the cached unit that
        // failed has no outcome): every other unit of its role to ask, the ones that waited for
        // the warm-up unit, was let go and has its outcome.
        io.lantern.harness.RoleRecord record =
            again.records().stream()
                .filter(r -> r.role().equals(spec.name()))
                .findFirst()
                .orElseThrow();
        int waiting = record.estimate().toAsk() - pathTo(roots, warmUp, spec);
        if (waiting > 0) {
          scenarios++;
          assertThat(record.settled()).as("$" + usd).isGreaterThanOrEqualTo(waiting);
        }
        assertThat(record.asked()).as("$" + usd).doesNotContain(warmUp);
      }
    }
    assertThat(scenarios).isPositive();
  }

  /** How many answers the run's roles accepted. */
  private static int accepted(Harness harness) {
    return harness.records().stream()
        .mapToInt(r -> r.toJson().get("items").get("accepted").asInt())
        .sum();
  }

  /**
   * How many units of the role the path from a root to the unit holds, the unit included; 0 if
   * none.
   */
  private static int pathTo(
      List<SummaryNode> nodes, String entity, io.lantern.harness.RoleSpec role) {
    for (SummaryNode node : nodes) {
      int own = Summarizer.role(node.level()).equals(role) ? 1 : 0;
      if (node.entity().toString().equals(entity)) {
        return own;
      }
      if (contains(node.children(), entity)) {
        return pathTo(node.children(), entity, role) + own;
      }
    }
    return 0;
  }

  private static boolean contains(List<SummaryNode> nodes, String entity) {
    return nodes.stream()
        .anyMatch(n -> n.entity().toString().equals(entity) || contains(n.children(), entity));
  }

  private static boolean hasParts(List<SummaryNode> nodes, String entity) {
    for (SummaryNode node : nodes) {
      if (node.entity().toString().equals(entity)) {
        return !node.children().isEmpty();
      }
      if (hasParts(node.children(), entity)) {
        return true;
      }
    }
    return false;
  }

  @Test
  void aBatchedRunStoresTheSameSummariesAsAnInteractiveOne() {
    // Interactive: the default threshold of 50 misses is not reached.
    Summarizer.Result interactive =
        summarizer().summarize(SummaryTree.fromFacts(TestFacts.shop().list()));
    assertThat(fake.batchSizes()).isEmpty();

    // The same tree on a fresh cache, batching every miss: the first unit warms the prefix on its
    // own, every other one goes through the batch API, level by level.
    FakeLlmProvider batching =
        new FakeLlmProvider().answering(Answers.epic(SummarizerTest::citingAll));
    Harness harness =
        new Harness(
                new LlmClient(
                    ModelSettings.single(batching.batchingModel("model-a")),
                    List.of(batching),
                    BudgetGuard.unlimited("test"),
                    new InMemoryLlmCallLog(),
                    Clock.systemUTC()),
                new io.lantern.harness.InMemoryAnswerStore(),
                RunBudget.NONE,
                BlobLookup.NONE,
                Set.of())
            .batchingAbove(0);
    Summarizer.Result batched =
        new Summarizer(harness).summarize(SummaryTree.fromFacts(TestFacts.shop().list()));

    assertThat(batched.summaries()).isEqualTo(interactive.summaries());
    assertThat(batching.batchSizes()).isNotEmpty();
    // Each of the two roles, types and the units above them, warms its own prefix.
    assertThat(batching.batchSizes().stream().mapToInt(Integer::intValue).sum())
        .isEqualTo(batching.sent().size() - 2);
    assertThat(harness.records().stream().mapToInt(r -> r.batchCalls()).sum())
        .isEqualTo(batching.sent().size() - 2);
    // Every request carries a long-lived cache, the warm-up's included.
    assertThat(batching.sent())
        .allSatisfy(
            sent ->
                assertThat(sent.request().cacheBreaks())
                    .isNotEmpty()
                    .allSatisfy(at -> assertThat(at.longLived()).isTrue()));
  }

  @Test
  void theSameFactsMakeTheSameRequestsEveryTime() {
    FakeLlmProvider one = new FakeLlmProvider().answering(Answers.epic(SummarizerTest::citingAll));
    FakeLlmProvider two = new FakeLlmProvider().answering(Answers.epic(SummarizerTest::citingAll));
    List<io.lantern.core.fact.Fact> reversed = new ArrayList<>(TestFacts.shop().list());
    java.util.Collections.reverse(reversed);

    new Summarizer(
            new Harness(
                client("model-a", one),
                new InMemoryAnswerStore(),
                RunBudget.NONE,
                BlobLookup.NONE,
                Set.of()))
        .summarize(SummaryTree.fromFacts(TestFacts.shop().list()));
    new Summarizer(
            new Harness(
                client("model-a", two),
                new InMemoryAnswerStore(),
                RunBudget.NONE,
                BlobLookup.NONE,
                Set.of()))
        .summarize(SummaryTree.fromFacts(reversed));

    // Byte for byte, whatever order the facts come in (#769).
    Function<FakeLlmProvider, List<String>> requests =
        fake ->
            fake.sent().stream()
                .map(FakeLlmProvider.Sent::request)
                .map(r -> r.purpose() + "\n" + r.system() + "\n" + r.messages())
                .sorted()
                .toList();
    assertThat(requests.apply(two)).isEqualTo(requests.apply(one)).hasSize(7);
  }

  @Test
  void eachLevelIsSummarizedAfterTheLevelBelowAndFromIt() {
    Summarizer.Result result =
        summarizer().summarize(SummaryTree.fromFacts(TestFacts.shop().list()));

    List<String> units = summarizedUnits();
    assertThat(units)
        .containsExactlyInAnyOrder(
            "type:com.example.shop.Order$Line",
            "type:com.example.shop.Order",
            "type:com.example.shop.PriceService",
            "package:com.example:shop-core:com.example.shop",
            "module:com.example:shop-core",
            "module:com.example:shop",
            "repo:shop");
    // Siblings may run in any order, but every unit comes after the units it contains.
    assertThat(units.indexOf("type:com.example.shop.Order$Line"))
        .isLessThan(units.indexOf("type:com.example.shop.Order"));
    assertThat(units.indexOf("type:com.example.shop.Order"))
        .isLessThan(units.indexOf("package:com.example:shop-core:com.example.shop"));
    assertThat(units.indexOf("type:com.example.shop.PriceService"))
        .isLessThan(units.indexOf("package:com.example:shop-core:com.example.shop"));
    assertThat(units.indexOf("package:com.example:shop-core:com.example.shop"))
        .isLessThan(units.indexOf("module:com.example:shop-core"));
    assertThat(units.indexOf("module:com.example:shop-core"))
        .isLessThan(units.indexOf("module:com.example:shop"));
    assertThat(units.indexOf("module:com.example:shop")).isLessThan(units.indexOf("repo:shop"));
    Summary pkg =
        result.summaries().get(EntityId.parse("package:com.example:shop-core:com.example.shop"));
    Summary order = result.summaries().get(EntityId.parse("type:com.example.shop.Order"));
    Summary priceService =
        result.summaries().get(EntityId.parse("type:com.example.shop.PriceService"));
    assertThat(pkg.inputSummaries()).containsExactly(order.key(), priceService.key());
    assertThat(pkg.citedSummaries()).containsExactly(order.key(), priceService.key());
    assertThat(pkg.text()).contains("[summary:type:com.example.shop.Order]");
    // The package's prompt shows its types' summaries without their own citations.
    String packagePrompt =
        fake.sent()
            .get(units.indexOf("package:com.example:shop-core:com.example.shop"))
            .request()
            .messages()
            .getFirst()
            .text();
    assertThat(packagePrompt)
        .contains(
            "[S1] type Order in package com.example.shop: Summary of"
                + " type:com.example.shop.Order")
        .doesNotContain("[fact:");
  }

  @Test
  void summariesCiteTheFactIdsTheyUsed() {
    List<Fact> facts = TestFacts.shop().list();
    Summarizer.Result result = summarizer().summarize(SummaryTree.fromFacts(facts));

    Summary order = result.summaries().get(EntityId.parse("type:com.example.shop.Order"));
    List<String> orderFacts =
        facts.stream()
            .filter(
                f ->
                    f.subject().toString().equals("type:com.example.shop.Order")
                        || f.subject().toString().equals("method:com.example.shop.Order#total()"))
            .map(Fact::id)
            .toList();
    assertThat(order.inputFacts()).containsExactlyInAnyOrderElementsOf(orderFacts);
    assertThat(order.citedFacts()).containsExactlyInAnyOrderElementsOf(orderFacts);
    for (String id : orderFacts) {
      assertThat(order.text()).contains("[fact:" + id + "]");
    }
    assertThat(order.promptVersion()).isEqualTo(Prompt.VERSION);
    assertThat(order.provider()).isEqualTo("fake");
    assertThat(order.model()).isEqualTo("model-a");
    // The repository has no facts of its own and cites its module's summary.
    Summary repo = result.summaries().get(new EntityId("repo", "shop"));
    assertThat(repo.citedFacts()).isEmpty();
    assertThat(repo.citedSummaries())
        .containsExactly(result.summaries().get(EntityId.parse("module:com.example:shop")).key());
  }

  @Test
  void unchangedUnitsAreNotSummarizedAgain() {
    List<SummaryNode> tree = SummaryTree.fromFacts(TestFacts.shop().list());
    Summarizer.Result first = summarizer().summarize(tree);

    Summarizer.Result second = summarizer().summarize(tree);

    assertThat(fake.sent()).hasSize(7);
    assertThat(second.generated()).isZero();
    assertThat(second.reused()).isEqualTo(7);
    assertThat(second.summaries()).isEqualTo(first.summaries());
  }

  @Test
  void aChangedUnitAndItsParentsAreSummarizedAgainAndNothingElse() {
    Summarizer.Result first =
        summarizer().summarize(SummaryTree.fromFacts(TestFacts.shop().list()));
    int calls = fake.sent().size();

    // PriceService gains a method.
    List<Fact> changed =
        TestFacts.shop()
            .relate(
                "shop",
                "type:com.example.shop.PriceService",
                "CONTAINS",
                "method:com.example.shop.PriceService#discount()")
            .list();
    Summarizer.Result second = summarizer().summarize(SummaryTree.fromFacts(changed));

    assertThat(fake.sent().subList(calls, fake.sent().size()))
        .extracting(s -> unit(s.request()))
        .containsExactly(
            "type:com.example.shop.PriceService",
            "package:com.example:shop-core:com.example.shop",
            "module:com.example:shop-core",
            "module:com.example:shop",
            "repo:shop");
    assertThat(second.generated()).isEqualTo(5);
    assertThat(second.reused()).isEqualTo(2);
    EntityId order = EntityId.parse("type:com.example.shop.Order");
    assertThat(second.summaries().get(order)).isEqualTo(first.summaries().get(order));
    EntityId repo = new EntityId("repo", "shop");
    assertThat(second.summaries().get(repo).key()).isNotEqualTo(first.summaries().get(repo).key());
  }

  /**
   * The facts as observed again with nothing the prompt shows changed, but under new IDs: a newer
   * version of the extractors that found them. Evidence names no commit (ADR 0091), so a new commit
   * alone keeps the IDs; a new extractor version, or lineage, still changes them.
   */
  private static List<Fact> atAnotherCommit(List<Fact> facts) {
    return facts.stream()
        .map(
            f ->
                Fact.create(
                    f.subject(),
                    f.predicate(),
                    f.object(),
                    f.attributes(),
                    new Origin(f.origin().kind(), f.origin().name(), "next"),
                    f.evidence(),
                    f.confidence(),
                    f.validity()))
        .toList();
  }

  /** A fact as the prompt shows it, the same at every commit. */
  private static String content(String id, List<Fact> facts) {
    return Prompt.fact(facts.stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow());
  }

  @Test
  void aCommitThatChangesNothingMakesNoCallsAndCitesTheNewFacts() {
    List<Fact> earlier = TestFacts.shop().list();
    Summarizer.Result first = summarizer().summarize(SummaryTree.fromFacts(earlier));
    int calls = fake.sent().size();
    List<Fact> later = atAnotherCommit(TestFacts.shop().list());

    Summarizer.Result second = summarizer().summarize(SummaryTree.fromFacts(later));

    assertThat(fake.sent()).hasSize(calls);
    assertThat(second.generated()).isZero();
    assertThat(second.reused()).isEqualTo(7);
    // The reused summaries cite the facts of the new commit, not those of the first.
    Set<String> ids = later.stream().map(Fact::id).collect(Collectors.toSet());
    for (Summary summary : second.summaries().values()) {
      assertThat(ids).containsAll(summary.inputFacts()).containsAll(summary.citedFacts());
      for (String id : summary.citedFacts()) {
        assertThat(summary.text()).contains("[fact:" + id + "]");
      }
    }
    // Each citation now names the same fact, observed at the new commit.
    for (var entry : second.summaries().entrySet()) {
      Summary before = first.summaries().get(entry.getKey());
      Summary after = entry.getValue();
      assertThat(after.key()).isEqualTo(before.key());
      assertThat(after.citedFacts().stream().map(id -> content(id, later)))
          .containsExactlyElementsOf(
              before.citedFacts().stream().map(id -> content(id, earlier)).toList());
      assertThat(after.inputFacts().stream().map(id -> content(id, later)))
          .containsExactlyElementsOf(
              before.inputFacts().stream().map(id -> content(id, earlier)).toList());
      String text = before.text();
      for (int i = 0; i < before.inputFacts().size(); i++) {
        text = text.replace(before.inputFacts().get(i), after.inputFacts().get(i));
      }
      assertThat(after.text()).isEqualTo(text);
    }
    Summary order = second.summaries().get(EntityId.parse("type:com.example.shop.Order"));
    assertThat(order.citedFacts()).isNotEmpty();
    // A parent cites its children's summaries as they are now.
    Summary repo = second.summaries().get(new EntityId("repo", "shop"));
    assertThat(repo.citedSummaries())
        .containsExactly(second.summaries().get(EntityId.parse("module:com.example:shop")).key());
  }

  @Test
  void aModuleCitesItsFactSheetAndARerunAtAnotherCommitCitesTheSheetsNewFacts() {
    List<Fact> earlier = FactSheetTest.service(2).list();
    EntityId module = EntityId.parse("module:com.example:orders");
    String endpoint =
        "method:com.example.orders.OrderController#get0() endpoint \"GET /orders/0\""
            + " (orders:pom.xml)";

    Summary first = summarizer().summarize(SummaryTree.fromFacts(earlier)).summaries().get(module);
    int calls = fake.sent().size();
    List<Fact> later = atAnotherCommit(FactSheetTest.service(2).list());
    Summarizer.Result second = summarizer().summarize(SummaryTree.fromFacts(later));

    assertThat(first.citedFacts().stream().map(id -> content(id, earlier))).contains(endpoint);
    assertThat(fake.sent()).hasSize(calls);
    Summary rebound = second.summaries().get(module);
    assertThat(rebound.key()).isEqualTo(first.key());
    assertThat(rebound.citedFacts().stream().map(id -> content(id, later))).contains(endpoint);
    Set<String> ids = later.stream().map(Fact::id).collect(Collectors.toSet());
    assertThat(ids).containsAll(rebound.inputFacts()).containsAll(rebound.citedFacts());
    Summary repo = second.summaries().get(new EntityId("repo", "orders"));
    assertThat(repo.citedFacts().stream().map(id -> content(id, later)))
        .contains(
            "service:com.example:orders USES_DATABASE database:orders-db:5432/orders"
                + " (orders:pom.xml)");
  }

  @Test
  void aChangedEntryPointSummarizesItsModuleAgainThoughNoSummaryTextChanges() {
    fake.answering(
        Answers.epic(request -> "The same words for the unit whatever its facts say. [F1]"));
    summarizer().summarize(SummaryTree.fromFacts(FactSheetTest.service(1).list()));
    int before = fake.sent().size();

    summarizer().summarize(SummaryTree.fromFacts(FactSheetTest.service(2).list()));

    assertThat(summarizedUnits().subList(before, fake.sent().size()))
        .contains("module:com.example:orders", "repo:orders")
        .doesNotContain("package:com.example:orders:com.example.orders");
  }

  @Test
  void aCommitThatChangesNothingReusesATypeSummaryCitingItsSourceFile() throws Exception {
    SourceFiles anyCommit =
        TestFacts.files(
            (repo, path) ->
                repo.equals("r") && path.equals(ExcerptsTest.PATH)
                    ? Optional.of(ExcerptsTest.ORDER)
                    : Optional.empty());
    SummaryNode order = orderWithSource(blob(ExcerptsTest.ORDER));
    new Summarizer(harness(client("model-a", fake)), anyCommit).summarize(List.of(order));
    SummaryNode later =
        new SummaryNode(order.entity(), order.level(), atAnotherCommit(order.facts()), List.of());

    Summarizer.Result second =
        new Summarizer(harness(client("model-a", fake)), anyCommit).summarize(List.of(later));

    assertThat(fake.sent()).hasSize(1);
    assertThat(second.reused()).isEqualTo(1);
    Fact kind = later.facts().stream().filter(f -> f.predicate().equals("kind")).findFirst().get();
    Summary summary = second.summaries().get(order.entity());
    assertThat(summary.text()).contains("[fact:" + kind.id() + "]");
    assertThat(summary.citedFacts())
        .containsExactlyInAnyOrderElementsOf(later.facts().stream().map(Fact::id).toList());
  }

  /**
   * A model that rewords every answer (#722): one sentence per reference it is shown, up to four,
   * each carrying the number of the call, so no two answers say anything the same way.
   */
  private static Function<LlmRequest, String> rewording() {
    java.util.concurrent.atomic.AtomicInteger calls =
        new java.util.concurrent.atomic.AtomicInteger();
    return request -> {
      int call = calls.incrementAndGet();
      String prompt = request.messages().getFirst().text();
      List<String> sentences = new ArrayList<>();
      Matcher ref = REF.matcher(prompt);
      while (ref.find() && sentences.size() < 4) {
        String cited = ref.group(1) != null ? ref.group(1) : ref.group(2);
        sentences.add(
            "Rests on what %s shows, as worded in answer number %d of this model. [%s]"
                .formatted(cited, call, cited));
      }
      return Answers.epic(String.join(" ", sentences));
    };
  }

  /** The sentences of a summary, without their citations. */
  private static List<String> sentences(Summary summary) {
    return java.util.Arrays.stream(summary.text().split("\\s*\\[[^\\]]*\\]\\s*"))
        .filter(t -> !t.isBlank())
        .toList();
  }

  @Test
  void aRewordingModelKeepsEverySentenceWhoseEvidenceDidNotChange() {
    fake.answering(rewording());
    Summarizer.Result first =
        summarizer().summarize(SummaryTree.fromFacts(TestFacts.shop().list()));
    int calls = fake.sent().size();
    // PriceService gains a method.
    List<Fact> changed =
        TestFacts.shop()
            .relate(
                "shop",
                "type:com.example.shop.PriceService",
                "CONTAINS",
                "method:com.example.shop.PriceService#discount()")
            .list();

    Summarizer.Result second = summarizer().summarize(SummaryTree.fromFacts(changed));

    // Asked: the changed type, and each unit that shows a summary with a sentence that changed.
    // The module's sentences cite none of what changed, so it keeps them all, and the units
    // above it, which show only its summary, are not asked: the rewording stops there.
    assertThat(fake.sent().subList(calls, fake.sent().size()))
        .extracting(s -> unit(s.request()))
        .containsExactly(
            "type:com.example.shop.PriceService",
            "package:com.example:shop-core:com.example.shop",
            "module:com.example:shop-core");
    EntityId module = EntityId.parse("module:com.example:shop-core");
    assertThat(second.summaries().get(module).text())
        .isEqualTo(first.summaries().get(module).text());
    EntityId price = EntityId.parse("type:com.example.shop.PriceService");
    EntityId pkg = EntityId.parse("package:com.example:shop-core:com.example.shop");
    // The type keeps its sentences about the facts it had, and adds one about the new method.
    List<String> was = sentences(first.summaries().get(price));
    List<String> now = sentences(second.summaries().get(price));
    assertThat(now).containsAll(was).hasSize(was.size() + 1);
    // The package rewords only its sentence about PriceService's summary; the rest stay as they
    // were, word for word.
    List<String> pkgWas = sentences(first.summaries().get(pkg));
    List<String> pkgNow = sentences(second.summaries().get(pkg));
    assertThat(pkgNow).hasSameSizeAs(pkgWas);
    List<String> kept = new ArrayList<>(pkgNow);
    kept.retainAll(pkgWas);
    assertThat(kept).hasSize(pkgWas.size() - 1);
    // An unchanged rerun asks nothing.
    int after = fake.sent().size();
    summarizer().summarize(SummaryTree.fromFacts(changed));
    assertThat(fake.sent()).hasSize(after);
  }

  @Test
  void textAfterTheAnswersObjectIsLeftOutWithoutARetry() {
    fake.answering(r -> Answers.epic(citingAll(r)) + "\nLet me know if you need more.");

    Summarizer.Result result =
        summarizer().summarize(SummaryTree.fromFacts(TestFacts.shop().list()));

    assertThat(result.generated()).isEqualTo(7);
    assertThat(fake.sent()).hasSize(7);
  }

  @Test
  void aChangedChildWhoseSummaryIsUnchangedLeavesItsParentAlone() {
    // The model writes the same text for PriceService whatever its facts.
    fake.answering(
        Answers.epic(
            request ->
                unit(request).equals("type:com.example.shop.PriceService")
                    ? "Prices the orders of the shop for each of its customers. [F1]"
                    : citingAll(request)));
    summarizer().summarize(SummaryTree.fromFacts(TestFacts.shop().list()));
    int calls = fake.sent().size();
    List<Fact> changed =
        TestFacts.shop()
            .relate(
                "shop",
                "type:com.example.shop.PriceService",
                "CONTAINS",
                "method:com.example.shop.PriceService#discount()")
            .list();

    Summarizer.Result second = summarizer().summarize(SummaryTree.fromFacts(changed));

    assertThat(fake.sent().subList(calls, fake.sent().size()))
        .extracting(s -> unit(s.request()))
        .containsExactly("type:com.example.shop.PriceService");
    assertThat(second.generated()).isEqualTo(1);
    assertThat(second.reused()).isEqualTo(6);
  }

  @Test
  void anotherModelDoesNotReuseSummaries() {
    List<SummaryNode> tree = SummaryTree.fromFacts(TestFacts.shop().list());
    summarizer().summarize(tree);

    Summarizer.Result other = new Summarizer(harness(client("model-b", fake))).summarize(tree);

    assertThat(other.generated()).isEqualTo(7);
    assertThat(other.reused()).isZero();
    assertThat(store.size()).isEqualTo(14);
  }

  @Test
  void theCacheKeyCoversEveryInput() {
    SummaryNode order =
        SummaryTree.fromFacts(TestFacts.shop().list())
            .getFirst()
            .children()
            .getFirst()
            .children()
            .getFirst()
            .children()
            .getFirst()
            .children()
            .getFirst();
    Summary child =
        summarizer()
            .summarize(List.of(order.children().getFirst()))
            .summaries()
            .values()
            .iterator()
            .next();
    String key = Summarizer.key(order, List.of(), List.of(child), fake.model("m"));

    assertThat(Summarizer.key(order, List.of(), List.of(child), fake.model("m"))).isEqualTo(key);
    assertThat(Summarizer.key(order, List.of(), List.of(child), fake.model("n"))).isNotEqualTo(key);
    assertThat(
            Summarizer.key(
                order, List.of(), List.of(child), new FakeLlmProvider("other").model("m")))
        .isNotEqualTo(key);
    assertThat(Summarizer.key(order, List.of(), List.of(), fake.model("m"))).isNotEqualTo(key);
    Summary editedChild =
        new Summary(
            child.key(),
            child.entity(),
            child.level(),
            child.text() + " More.",
            child.purpose(),
            child.confidence(),
            child.citedFacts(),
            child.citedSummaries(),
            child.inputFacts(),
            child.inputSummaries(),
            child.promptVersion(),
            child.provider(),
            child.model());
    assertThat(Summarizer.key(order, List.of(), List.of(editedChild), fake.model("m")))
        .isNotEqualTo(key);
    Summary rekeyedChild =
        new Summary(
            "f".repeat(64),
            child.entity(),
            child.level(),
            child.text(),
            Summary.Purpose.INFERRED,
            0.35,
            child.citedFacts(),
            child.citedSummaries(),
            child.inputFacts(),
            child.inputSummaries(),
            child.promptVersion(),
            child.provider(),
            child.model());
    // A child is cited by its entity ID, which the prompt does not show in full: two packages of
    // the same name in different modules read the same.
    Function<String, Summary> pkg =
        id ->
            new Summary(
                child.key(),
                EntityId.parse(id),
                SummaryLevel.PACKAGE,
                child.text(),
                child.purpose(),
                child.confidence(),
                child.citedFacts(),
                child.citedSummaries(),
                child.inputFacts(),
                child.inputSummaries(),
                child.promptVersion(),
                child.provider(),
                child.model());
    Summary inCore = pkg.apply("package:com.example:shop-core:com.example.shop");
    Summary inApi = pkg.apply("package:com.example:shop-api:com.example.shop");
    assertThat(Prompt.children(List.of(inApi))).isEqualTo(Prompt.children(List.of(inCore)));
    assertThat(Summarizer.key(order, List.of(), List.of(inApi), fake.model("m")))
        .isNotEqualTo(Summarizer.key(order, List.of(), List.of(inCore), fake.model("m")));
    // The key holds what the model sees: a child's key and citations are not shown.
    assertThat(Summarizer.key(order, List.of(), List.of(rekeyedChild), fake.model("m")))
        .isEqualTo(key);
    Summary recitedChild =
        new Summary(
            child.key(),
            child.entity(),
            child.level(),
            child.text().replace(child.citedFacts().getFirst(), "e".repeat(64)),
            child.purpose(),
            child.confidence(),
            List.of("e".repeat(64)),
            List.of(),
            List.of("e".repeat(64)),
            List.of(),
            child.promptVersion(),
            child.provider(),
            child.model());
    assertThat(Summarizer.key(order, List.of(), List.of(recitedChild), fake.model("m")))
        .isEqualTo(key);
    SummaryNode laterCommit =
        new SummaryNode(
            order.entity(), order.level(), atAnotherCommit(order.facts()), order.children());
    assertThat(Summarizer.key(laterCommit, List.of(), List.of(child), fake.model("m")))
        .isEqualTo(key);
    SummaryNode fewerFacts =
        new SummaryNode(
            order.entity(),
            order.level(),
            order.facts().subList(1, order.facts().size()),
            order.children());
    assertThat(Summarizer.key(fewerFacts, List.of(), List.of(child), fake.model("m")))
        .isNotEqualTo(key);
  }

  @Test
  void uncitedAnswerIsSentBackOnceThenTheUnitHasNoSummary() {
    fake.answering(Answers.epic("Order holds the lines of an order."));
    SummaryNode line =
        new SummaryNode(
            EntityId.parse("type:p.Line"),
            SummaryLevel.TYPE,
            List.of(
                TestFacts.fact(
                    "r", "type:p.Line", "kind", FactObject.string("record"), "Line.java")),
            List.of());

    Summarizer.Result result = summarizer().summarize(List.of(line));

    assertThat(result.summaries()).isEmpty();
    assertThat(result.rejected()).containsExactly(line.entity());
    assertThat(fake.sent()).hasSize(2);
    List<LlmMessage> retry = fake.sent().get(1).request().messages();
    assertThat(retry).hasSize(3);
    assertThat(retry.get(1))
        .isEqualTo(LlmMessage.assistant(Answers.of("Order holds the lines of an order.")));
    assertThat(retry.get(2).text())
        .startsWith("Some parts of your answer were rejected.")
        .contains(
            "<rejection item=\"I1\" field=\"sentences.1\">Cites nothing; every sentence cites"
                + " the references it rests on.</rejection>");
    assertThat(fake.sent().get(1).request().purpose()).endsWith("(retry)");
    assertThat(store.size()).isEqualTo(1); // the drop, repeated by a rerun (#682)
  }

  @Test
  void sentencesWithoutCitationsAreLeftOutWithoutAskingAgain() {
    fake.answering(
        Answers.epic(
            "Line holds the quantity of one product in an order. [F1] Line is part of an order and is saved with it. It is very important to every order the shop takes."));
    SummaryNode line =
        new SummaryNode(
            EntityId.parse("type:p.Line"),
            SummaryLevel.TYPE,
            List.of(
                TestFacts.fact(
                    "r", "type:p.Line", "kind", FactObject.string("record"), "Line.java")),
            List.of());
    String fact = line.facts().getFirst().id();

    Summary summary = summarizer().summarize(List.of(line)).summaries().get(line.entity());

    assertThat(summary.text())
        .isEqualTo("Line holds the quantity of one product in an order. [fact:" + fact + "]");
    assertThat(summary.purpose()).isEqualTo(Summary.Purpose.INFERRED);
    // One sentence of three is kept, so it is trusted a third as much as an inferred summary.
    assertThat(summary.confidence()).isEqualTo(0.12);
    assertThat(fake.sent()).hasSize(1);
    assertThat(log.list)
        .extracting(ILoggingEvent::getFormattedMessage)
        .contains(
            "Left out 2 sentences from the answer to summarize type type:p.Line: sentences.2:"
                + " Cites nothing; every sentence cites the references it rests on. | sentences.3:"
                + " Cites nothing; every sentence cites the references it rests on.");
    // What is stored, and so reused, is the summary without them.
    assertThat(SummaryJson.read(store.find(summary.key()).orElseThrow().answer()).text())
        .isEqualTo(summary.text());
    assertThat(summarizer().summarize(List.of(line)).summaries().get(line.entity()))
        .extracting(Summary::text, Summary::confidence)
        .containsExactly(summary.text(), 0.12);
    assertThat(fake.sent()).hasSize(1);
  }

  @Test
  void sentencesNamingWhatTheirCitationsDoNotMentionAreLeftOutWithoutAskingAgain() {
    fake.answering(
        Answers.epic(
            "OrderLine holds the quantity of one product in an order. [F1] It is saved with JPA by LineRepository with its order. [F1]"));
    SummaryNode line =
        new SummaryNode(
            EntityId.parse("type:p.OrderLine"),
            SummaryLevel.TYPE,
            List.of(
                TestFacts.fact(
                    "r", "type:p.OrderLine", "kind", FactObject.string("record"), "Line.java")),
            List.of());
    String fact = line.facts().getFirst().id();

    Summary summary = summarizer().summarize(List.of(line)).summaries().get(line.entity());

    // The unit's own name needs no citation; what neither it nor the fact mentions is left out.
    assertThat(summary.text())
        .isEqualTo("OrderLine holds the quantity of one product in an order. [fact:" + fact + "]");
    assertThat(fake.sent()).hasSize(1);
    // One sentence of two is kept, so the inferred base of 0.35 is halved (ADR 0066).
    assertThat(summary.confidence()).isEqualTo(0.18);
    assertThat(log.list)
        .extracting(ILoggingEvent::getFormattedMessage)
        .contains(
            "Left out 1 sentences from the answer to summarize type type:p.OrderLine: sentences.2:"
                + " Names JPA, LineRepository, which none of its citations show.");
  }

  @Test
  void anAnswerWhoseEverySentenceNamesWhatItsCitationsDoNotMentionIsSentBack() {
    firstLineAnswer("It is kept in Redis by LineCache for a short while. [F1]");

    Summarizer.Result result =
        summarizer().summarize(SummaryTree.fromFacts(TestFacts.shop().list()));

    assertThat(retried().messages().get(2).text())
        .contains(
            "<rejection item=\"I1\" field=\"sentences.1\">Names Redis, LineCache, which none of"
                + " its citations show.</rejection>");
    assertThat(result.rejected()).isEmpty();
  }

  @Test
  void anEvidencedPurposeWhoseFirstSentenceNamesWhatItsCitationDoesNotMentionIsInferred() {
    repoAnswerAgain(
        "Purpose: evidenced\nShop sells books with Kafka. [F1] Shop sells books to readers who order them online. [F1]");

    Summary repo =
        summarizer()
            .summarize(SummaryTree.fromFacts(shopWithReadme()))
            .summaries()
            .get(EntityId.parse("repo:shop"));

    assertThat(repo.purpose()).isEqualTo(Summary.Purpose.INFERRED);
    assertThat(repo.text()).startsWith("Shop sells books to readers who order them online. [fact:");
  }

  @Test
  void anAnswerWhoseEverySentenceFailsIsSentBackWithEveryReason() {
    firstLineAnswer("It is a line. It is a record [F99].");

    Summarizer.Result result =
        summarizer().summarize(SummaryTree.fromFacts(TestFacts.shop().list()));

    assertThat(retried().messages().get(2).text())
        .startsWith("Some parts of your answer were rejected.")
        .contains("<rejection item=\"I1\" field=\"sentences.1\">Cites nothing;")
        .contains(
            "<rejection item=\"I1\" field=\"sentences.2\">Cites F99, which is not in your"
                + " input.</rejection>");
    assertThat(result.rejected()).isEmpty();
  }

  @Test
  void aSummaryThatLosesItsFirstSentenceIsAskedAgainOnce() {
    // A first sentence opening with a pronoun is left out, which would leave a fragment (#275).
    firstLineAnswer(
        "It holds a quantity of one product in an order [F1]. Lines belong to an order and are"
            + " saved with it [F1].");

    Summarizer.Result result =
        summarizer().summarize(SummaryTree.fromFacts(TestFacts.shop().list()));

    assertThat(retried().messages().get(2).text())
        .contains(
            "<rejection item=\"I1\" field=\"sentences.1\">\"It holds a quantity of one product in"
                + " an order\" opens with a"
                + " pronoun or restates what kind of part it is;")
        .contains(
            "The first sentence was left out, so the others would stand without it; write the"
                + " whole summary again, opening with what the unit is for.");
    Summary line = result.summaries().get(EntityId.parse("type:com.example.shop.Order$Line"));
    assertThat(line.text()).startsWith("Summary of ");
  }

  @Test
  void aRepairedAnswerThatStillLosesItsFirstSentenceIsTakenAsItIs() {
    fake.answering(
        Answers.epic(
            request ->
                request.purpose().contains("type:com.example.shop.Order$Line")
                    ? "The class is a Spring bean that the shop's context creates [F1]. Lines belong to an"
                        + " order and are saved with it [F1]."
                    : citingAll(request)));

    Summarizer.Result result =
        summarizer().summarize(SummaryTree.fromFacts(TestFacts.shop().list()));

    Summary line = result.summaries().get(EntityId.parse("type:com.example.shop.Order$Line"));
    assertThat(line.text()).startsWith("Lines belong to an order and are saved with it [fact:");
    assertThat(fake.sent()).filteredOn(s -> s.request().purpose().endsWith("(retry)")).hasSize(1);
  }

  @Test
  void aRepairedAnswerLedByASentenceWrittenToFollowLosesItToo() {
    fake.answering(
        Answers.epic(
            request ->
                request.purpose().contains("type:com.example.shop.Order$Line")
                    ? "The class is a Spring bean that the shop's context creates [F1]. It holds a"
                        + " quantity of one product in an order [F1]. Lines belong to an order and"
                        + " are saved with it [F1]."
                    : citingAll(request)));

    Summarizer.Result result =
        summarizer().summarize(SummaryTree.fromFacts(TestFacts.shop().list()));

    Summary line = result.summaries().get(EntityId.parse("type:com.example.shop.Order$Line"));
    assertThat(line.text())
        .startsWith("Lines belong to an order and are saved with it [fact:")
        .doesNotContain("It ");
  }

  @Test
  void aLaterSentenceTrueOfAnySpringApplicationIsLeftOutWithoutAskingAgain() {
    firstLineAnswer(
        "Lines hold the quantity of one book in an order [F1]. It is available for injection"
            + " [F1].");

    Summarizer.Result result =
        summarizer().summarize(SummaryTree.fromFacts(TestFacts.shop().list()));

    Summary line = result.summaries().get(EntityId.parse("type:com.example.shop.Order$Line"));
    assertThat(line.text())
        .startsWith("Lines hold the quantity of one book in an order [fact:")
        .doesNotContain("injection");
    assertThat(fake.sent()).noneMatch(s -> s.request().purpose().endsWith("(retry)"));
  }

  @Test
  void aSentenceCitingAStoredFormMarkerIsLeftOut() {
    firstLineAnswer(
        "Line is a record of one product and its quantity in an order [F1]. Line is a line of an"
            + " order kept with it [fact:abc].");

    Summarizer.Result result =
        summarizer().summarize(SummaryTree.fromFacts(TestFacts.shop().list()));

    Summary line = result.summaries().get(EntityId.parse("type:com.example.shop.Order$Line"));
    assertThat(line.text())
        .startsWith("Line is a record of one product and its quantity in an order [fact:")
        .doesNotContain("abc");
    assertThat(fake.sent()).noneMatch(s -> s.request().purpose().endsWith("(retry)"));
    assertThat(log.list)
        .extracting(ILoggingEvent::getFormattedMessage)
        .anyMatch(m -> m.contains("sentences.2: Cites fact:abc, which is not a reference."));
  }

  /** Answers the first prompt for {@code Order$Line} with the given text, and others well. */
  private void firstLineAnswer(String text) {
    fake.answering(
        Answers.epic(
            request ->
                request.purpose().endsWith("type:com.example.shop.Order$Line")
                    ? text
                    : citingAll(request)));
  }

  /** The one request sent again with the reason its answer was rejected. */
  private LlmRequest retried() {
    List<LlmRequest> retries =
        fake.sent().stream()
            .map(FakeLlmProvider.Sent::request)
            .filter(request -> request.purpose().endsWith("(retry)"))
            .toList();
    assertThat(retries).hasSize(1);
    return retries.getFirst();
  }

  @Test
  void aUnitRejectedTwiceHasNoSummaryAndItsParentIsSummarizedWithoutIt() {
    fake.answering(
        Answers.epic(
            request ->
                request.purpose().contains("type:com.example.shop.Order$Line")
                    ? "Line is a line of an order."
                    : citingAll(request)));

    Summarizer.Result result =
        summarizer().summarize(SummaryTree.fromFacts(TestFacts.shop().list()));

    EntityId line = EntityId.parse("type:com.example.shop.Order$Line");
    assertThat(result.rejected()).containsExactly(line);
    assertThat(result.summaries()).doesNotContainKey(line).hasSize(6);
    assertThat(result.generated()).isEqualTo(6);
    assertThat(store.size()).isEqualTo(7);
    Summary order = result.summaries().get(EntityId.parse("type:com.example.shop.Order"));
    assertThat(order.inputSummaries()).isEmpty();
    assertThat(result.summaries().get(EntityId.parse("repo:shop"))).isNotNull();

    List<String> rejections =
        log.list.stream()
            .filter(e -> e.getLevel() == Level.WARN)
            .map(ILoggingEvent::getFormattedMessage)
            .filter(m -> m.startsWith("Rejected the answer"))
            .toList();
    assertThat(rejections).hasSize(2);
    assertThat(rejections)
        .allSatisfy(
            m ->
                assertThat(m)
                    .contains("type:com.example.shop.Order$Line")
                    .contains("Cites nothing; every sentence cites the references it rests on.")
                    .contains("Line is a line of an order."));
    assertThat(rejections.get(1)).contains("(retry)");

    // A rerun on the same inputs repeats the drop without a call, even with a model that would
    // now answer, so the document stays the same (#682).
    fake.answering(Answers.epic(SummarizerTest::citingAll));
    int before = fake.sent().size();
    Summarizer.Result rerun =
        summarizer().summarize(SummaryTree.fromFacts(TestFacts.shop().list()));

    assertThat(rerun.rejected()).containsExactly(line);
    assertThat(rerun.summaries()).hasSize(6);
    assertThat(fake.sent()).hasSize(before);
  }

  /** A type with one fact and the given member types. */
  private static SummaryNode type(String name, List<SummaryNode> members) {
    return new SummaryNode(
        EntityId.parse("type:p." + name),
        SummaryLevel.TYPE,
        List.of(
            TestFacts.fact(
                "r", "type:p." + name, "kind", FactObject.string("class"), name + ".java")),
        members);
  }

  @Test
  void theRunFailsIfItsFirstUnitsAreAllRejected() {
    fake.answering(Answers.epic("It is a class."));
    List<SummaryNode> types = new ArrayList<>();
    for (int i = 0; i < Summarizer.EARLY_STOP; i++) {
      types.add(type("T" + i, List.of()));
    }

    assertThatThrownBy(() -> summarizer().summarize(types))
        .isInstanceOf(SummaryRejectedException.class)
        .hasMessageStartingWith("The first " + Summarizer.EARLY_STOP + " units were all rejected.")
        .hasMessageContaining("Cites nothing");
    assertThat(store.size()).isEqualTo(Summarizer.EARLY_STOP); // the drops (#682)
  }

  @Test
  void unitsRejectedAfterOneWasSummarizedDoNotStopTheRun() {
    fake.answering(
        Answers.epic(
            request ->
                request.purpose().endsWith("type:p.Good") ? citingAll(request) : "It is a class."));
    // Each type contains the good one, so the good one is done before any of them starts.
    SummaryNode good = type("Good", List.of());
    List<SummaryNode> types = new ArrayList<>();
    for (int i = 0; i <= Summarizer.EARLY_STOP; i++) {
      types.add(type("T" + i, List.of(good)));
    }

    Summarizer.Result result = summarizer().summarize(types);

    assertThat(result.summaries()).containsOnlyKeys(good.entity());
    assertThat(result.rejected()).hasSize(Summarizer.EARLY_STOP + 1);
  }

  @Test
  void aProviderErrorStillFailsTheRunWhenAnotherUnitIsRejected() {
    fake.answering(
        Answers.epic(
            request -> {
              if (request.purpose().endsWith("type:com.example.shop.PriceService")) {
                throw new LlmException("HTTP 400 bad request");
              }
              return request.purpose().contains("type:com.example.shop.Order$Line")
                  ? "Line is a line of an order."
                  : citingAll(request);
            }));

    assertThatThrownBy(() -> summarizer().summarize(SummaryTree.fromFacts(TestFacts.shop().list())))
        .isInstanceOf(LlmException.class)
        .hasMessage("HTTP 400 bad request");
  }

  @Test
  void aLongRejectedAnswerIsLoggedTruncated() {
    // Short enough that the fake does not cut it off at the type's output limit.
    String answer = "x".repeat(1200);
    fake.answering(Answers.epic(answer));
    SummaryNode line =
        new SummaryNode(
            EntityId.parse("type:p.Line"),
            SummaryLevel.TYPE,
            List.of(
                TestFacts.fact(
                    "r", "type:p.Line", "kind", FactObject.string("record"), "Line.java")),
            List.of());

    summarizer().summarize(List.of(line));

    String sent = Answers.of(answer);
    assertThat(log.list)
        .filteredOn(e -> e.getFormattedMessage().startsWith("Rejected the answer"))
        .hasSize(2)
        .allSatisfy(
            e ->
                assertThat(e.getFormattedMessage())
                    .doesNotContain(answer)
                    .contains(sent.substring(0, 1000) + "... (" + sent.length() + " characters)"));
  }

  @Test
  void anEmptyAnswerIsRetriedWithTheReason() {
    firstLineAnswer(" ");

    summarizer().summarize(SummaryTree.fromFacts(TestFacts.shop().list()));

    List<LlmMessage> retry = retried().messages();
    assertThat(retry).hasSize(1);
    assertThat(retry.getFirst().text())
        .contains(
            "Your answer is not one JSON object with an entry for I1 under \"items\": the answer"
                + " is empty.");
  }

  @Test
  void resultsAreInVisitOrder() {
    Summarizer.Result result =
        summarizer().summarize(SummaryTree.fromFacts(TestFacts.shop().list()));

    assertThat(result.summaries().keySet())
        .extracting(EntityId::toString)
        .containsExactly(
            "type:com.example.shop.Order$Line",
            "type:com.example.shop.Order",
            "type:com.example.shop.PriceService",
            "package:com.example:shop-core:com.example.shop",
            "module:com.example:shop-core",
            "module:com.example:shop",
            "repo:shop");
  }

  @Test
  void aSummaryFixedOnRetryIsKept() {
    firstLineAnswer("It is a record [F7].");

    Summarizer.Result result =
        summarizer().summarize(SummaryTree.fromFacts(TestFacts.shop().list()));

    assertThat(result.generated()).isEqualTo(7);
    assertThat(retried().messages().get(2).text())
        .contains(
            "<rejection item=\"I1\" field=\"sentences.1\">Cites F7, which is not in your"
                + " input.</rejection>");
    assertThat(store.size()).isEqualTo(7);
  }

  @Test
  void anAnswerCutOffAtTheLimitIsRejected() {
    Function<LlmRequest, LlmResponse> cutOff =
        request -> new LlmResponse("It is a record [F1] and it", "max_tokens", TokenUsage.NONE);
    LlmProvider provider =
        new LlmProvider() {
          @Override
          public String name() {
            return FakeLlmProvider.NAME;
          }

          @Override
          public LlmResponse complete(String model, LlmRequest request) {
            return cutOff.apply(request);
          }
        };

    Summarizer.Result result =
        new Summarizer(harness(client("model-a", provider)))
            .summarize(SummaryTree.fromFacts(TestFacts.shop().list()));

    assertThat(result.summaries()).isEmpty();
    // The repository has no facts of its own, so with all its parts rejected it has nothing.
    assertThat(result.rejected()).hasSize(6).doesNotContain(EntityId.parse("repo:shop"));
    assertThat(log.list).anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("cut off"));
    assertThat(store.size()).isZero();
  }

  @Test
  void aModelThatThinksIsCutOffUnlessItHasHeadroom() {
    fake.thinking(1000, false);

    Summarizer.Result cutOff =
        summarizer().summarize(SummaryTree.fromFacts(TestFacts.shop().list()));
    assertThat(cutOff.summaries()).isEmpty();
    assertThat(cutOff.rejected()).hasSize(6);
    assertThat(store.size()).isZero();

    LlmClient roomy =
        new LlmClient(
            ModelSettings.single(fake.model("model-a").withThinking(new Thinking(false, 1000))),
            List.of(fake),
            BudgetGuard.unlimited("test"),
            new InMemoryLlmCallLog(),
            Clock.systemUTC());
    Summarizer.Result result =
        new Summarizer(harness(roomy)).summarize(SummaryTree.fromFacts(TestFacts.shop().list()));

    assertThat(result.generated()).isEqualTo(7);
    assertThat(result.summaries().values())
        .allSatisfy(summary -> assertThat(summary.text()).startsWith("Summary of "));
  }

  @Test
  void aUnitWithNothingToSummarizeHasNoSummary() {
    SummaryNode empty =
        new SummaryNode(new EntityId("repo", "empty"), SummaryLevel.REPO, List.of(), List.of());

    Summarizer.Result result = summarizer().summarize(List.of(empty));

    assertThat(result.summaries()).isEmpty();
    assertThat(fake.sent()).isEmpty();
  }

  @Test
  void typesAreSummarizedOnTheBulkTierAndTheUnitsAboveThemOnHighVolume() {
    summarizer().summarize(SummaryTree.fromFacts(TestFacts.shop().list()));

    assertThat(fake.sent())
        .allSatisfy(
            s ->
                assertThat(s.request().role())
                    .isEqualTo(
                        s.request().purpose().startsWith("summarize type ")
                            ? Role.SUMMARIZE_TYPES
                            : Role.SUMMARIZE));
    assertThat(fake.sent()).extracting(s -> s.request().role()).contains(Role.SUMMARIZE);
    assertThat(Role.SUMMARIZE_TYPES.tier()).isEqualTo(io.lantern.llm.Tier.BULK);
    assertThat(Role.SUMMARIZE.tier()).isEqualTo(io.lantern.llm.Tier.HIGH_VOLUME);
    assertThat(fake.sent())
        .allSatisfy(s -> assertThat(s.request().system()).isEqualTo(Prompt.SYSTEM));
  }

  /** Facts about {@code type:p.Order} whose evidence is {@link ExcerptsTest#ORDER}. */
  private static SummaryNode orderWithSource(String blob) {
    List<Fact> facts = new ArrayList<>();
    for (Fact fact :
        List.of(
            ExcerptsTest.fact("kind", FactObject.string("class")),
            ExcerptsTest.member("method:p.Order#total()"))) {
      facts.add(
          Fact.create(
              fact.subject(),
              fact.predicate(),
              fact.object(),
              fact.attributes(),
              fact.origin(),
              Evidence.of(
                  new Evidence.Location(
                      fact.evidence().repo(),
                      fact.evidence().path(),
                      fact.evidence().lines(),
                      blob,
                      null,
                      Evidence.Role.DECLARED)),
              fact.confidence(),
              fact.validity()));
    }
    return new SummaryNode(EntityId.parse("type:p.Order"), SummaryLevel.TYPE, facts, List.of());
  }

  /** The git blob SHA of a file with this text. */
  private static String blob(String text) {
    return SourceFiles.blobSha(text.getBytes(StandardCharsets.UTF_8), 40);
  }

  private static final SourceFiles ORDER_SOURCE =
      TestFacts.files(
          (repo, path) ->
              repo.equals("r") && path.equals(ExcerptsTest.PATH)
                  ? Optional.of(ExcerptsTest.ORDER)
                  : Optional.empty());

  @Test
  void typePromptsShowSourceExcerptsCitedAsTheirFacts() throws Exception {
    SummaryNode order = orderWithSource(blob(ExcerptsTest.ORDER));

    Summary summary =
        new Summarizer(harness(client("model-a", fake)), ORDER_SOURCE)
            .summarize(List.of(order))
            .summaries()
            .get(order.entity());

    String prompt = fake.sent().getFirst().request().messages().getFirst().text();
    assertThat(prompt)
        .contains(
            "\nIts source code:\n<code ref=\"E1\" repo=\"r\" path=\"src/main/java/p/Order.java\""
                + " lines=\"1-")
        .contains("\n 1  " + ExcerptsTest.ORDER.lines().findFirst().orElseThrow() + "\n");
    // The fake cites [F1, F2, E1]; the file is cited as the fact it comes from.
    Fact kind = order.facts().stream().filter(f -> f.predicate().equals("kind")).findFirst().get();
    assertThat(summary.citedFacts()).containsExactlyInAnyOrderElementsOf(summary.inputFacts());
    assertThat(summary.text()).contains("[fact:" + kind.id() + "]").doesNotContain("[E");
    assertThat(summary.key())
        .isNotEqualTo(Summarizer.key(order, List.of(), List.of(), fake.model("model-a")));
  }

  /** The shop's facts with a README whose first claim says what the shop is for. */
  private static List<Fact> shopWithReadme() {
    List<Fact> facts = new ArrayList<>(TestFacts.shop().list());
    facts.add(TestFacts.claim("shop", "README.md", 3, "Shop sells books to readers."));
    return facts;
  }

  /** Answers the repository with the given text, and other units well, without a purpose line. */
  /**
   * Answers the prompt for {@code repo:shop} with the given text, and its repair turn with the same
   * text again; others well.
   */
  private void repoAnswerAgain(String text) {
    fake.answering(
        Answers.epic(
            request -> request.purpose().contains(" repo:shop") ? text : citingAll(request)));
  }

  private void repoAnswer(String text) {
    fake.answering(
        Answers.epic(
            request -> request.purpose().endsWith(" repo:shop") ? text : citingAll(request)));
  }

  @Test
  void aPurposeTakenFromTheReadmeIsEvidenced() {
    repoAnswer(
        "Purpose: evidenced\nShop sells books to readers who order them online. [F1] It is built on the shop module and its parts [S1].");

    Summarizer.Result result = summarizer().summarize(SummaryTree.fromFacts(shopWithReadme()));

    Summary repo = result.summaries().get(EntityId.parse("repo:shop"));
    assertThat(repo.purpose()).isEqualTo(Summary.Purpose.EVIDENCED);
    assertThat(repo.confidence()).isEqualTo(Confidence.EVIDENCED);
    assertThat(repo.text()).startsWith("Shop sells books to readers who order them online. [fact:");
    String prompt = fake.sent().getLast().request().messages().getFirst().text();
    assertThat(prompt)
        .contains(
            "\nIts facts:\n<facts>\n[F1] readme:shop:README.md claim \"Shop sells books to"
                + " readers.\" (shop:README.md)\n");
    // A summary that cites nothing people wrote is inferred.
    assertThat(result.summaries().get(EntityId.parse("module:com.example:shop")))
        .extracting(Summary::purpose, Summary::confidence)
        .containsExactly(Summary.Purpose.INFERRED, Confidence.INFERRED);
  }

  @Test
  void aReusedSummaryKeepsItsPurpose() {
    repoAnswer(
        "Purpose: evidenced\nShop sells books to readers who order them online. [F1] It is built on the shop module and its parts [S1].");
    summarizer().summarize(SummaryTree.fromFacts(shopWithReadme()));
    int calls = fake.sent().size();

    Summarizer.Result again = summarizer().summarize(SummaryTree.fromFacts(shopWithReadme()));

    assertThat(fake.sent()).hasSize(calls);
    assertThat(again.summaries().get(EntityId.parse("repo:shop")))
        .extracting(Summary::purpose, Summary::confidence)
        .containsExactly(Summary.Purpose.EVIDENCED, Confidence.EVIDENCED);
  }

  @Test
  void anEvidencedPurposeWhoseFirstSentenceCitesNoEvidenceIsInferred() {
    // The module it cites was inferred, and the README claim is cited only later.
    repoAnswer(
        "Purpose: evidenced\nShop sells books to readers who order online. [S1] Its README says so"
            + " in its opening lines. [F1]");

    Summary repo =
        summarizer()
            .summarize(SummaryTree.fromFacts(shopWithReadme()))
            .summaries()
            .get(EntityId.parse("repo:shop"));

    assertThat(repo.purpose()).isEqualTo(Summary.Purpose.INFERRED);
    assertThat(repo.text()).startsWith("Shop sells books to readers who order online. [summary:");
  }

  @Test
  void anEvidencedPurposeWhoseFirstSentenceIsLeftOutIsInferred() {
    // The first sentence cites nothing, so the one now first is not the one the mark is about.
    repoAnswerAgain(
        "Purpose: evidenced\nShop is the best shop. Shop sells books to readers who order them online. [F1]");

    Summary repo =
        summarizer()
            .summarize(SummaryTree.fromFacts(shopWithReadme()))
            .summaries()
            .get(EntityId.parse("repo:shop"));

    assertThat(repo.purpose()).isEqualTo(Summary.Purpose.INFERRED);
    assertThat(repo.text()).startsWith("Shop sells books to readers who order them online. [fact:");
  }

  @Test
  void aFirstSentenceCitingTheReadmeIsEvidencedWhateverTheModelWrites() {
    // The basis is worked out from what the first sentence cites, never asked (epic, 11.0).
    repoAnswer("Purpose: inferred\nShop sells books to readers who order them online. [F1]");

    Summary repo =
        summarizer()
            .summarize(SummaryTree.fromFacts(shopWithReadme()))
            .summaries()
            .get(EntityId.parse("repo:shop"));

    assertThat(repo.purpose()).isEqualTo(Summary.Purpose.EVIDENCED);
  }

  @Test
  void aPurposeFromJavadocIsEvidencedAndAPartsEvidencedPurposeCarriesUp() throws Exception {
    SummaryNode order = orderWithSource(blob(ExcerptsTest.ORDER));
    SummaryNode pkg =
        new SummaryNode(
            EntityId.parse("package:g:a:p"), SummaryLevel.PACKAGE, List.of(), List.of(order));
    fake.answering(
        Answers.epic(
            request ->
                request.purpose().endsWith("type:p.Order")
                    ? "Purpose: evidenced\nAn order a customer placed for one or more products. [E1]"
                    : "**Purpose: evidenced** Orders customers place, and the lines each of them holds. [S1]"));

    Summarizer.Result result =
        new Summarizer(harness(client("model-a", fake)), ORDER_SOURCE).summarize(List.of(pkg));

    assertThat(result.summaries().get(order.entity()).purpose())
        .isEqualTo(Summary.Purpose.EVIDENCED);
    assertThat(result.summaries().get(pkg.entity()).purpose()).isEqualTo(Summary.Purpose.EVIDENCED);
    assertThat(result.summaries().get(pkg.entity()).text())
        .isEqualTo(
            "Orders customers place, and the lines each of them holds. [summary:type:p.Order]");
    assertThat(fake.sent().getLast().request().messages().getFirst().text())
        .contains(
            "[S1] type Order in package p: An order a customer placed for one or more products.");
  }

  @Test
  void anEvidencedPurposeCitingOnlyCodeIsInferred() throws Exception {
    SummaryNode order = orderWithSource(blob(ExcerptsTest.ORDER));
    fake.answering(
        Answers.epic(
            request ->
                "Purpose: evidenced\nAn order a customer placed for one or more products. [F1]"));

    Summary summary =
        new Summarizer(harness(client("model-a", fake)), ORDER_SOURCE)
            .summarize(List.of(order))
            .summaries()
            .get(order.entity());

    assertThat(summary.purpose()).isEqualTo(Summary.Purpose.INFERRED);
  }

  @Test
  void aFileThatIsNotTheVersionObservedShowsNoExcerpt() {
    SummaryNode order = orderWithSource("0".repeat(64));

    Summary summary =
        new Summarizer(harness(client("model-a", fake)), ORDER_SOURCE)
            .summarize(List.of(order))
            .summaries()
            .get(order.entity());

    assertThat(fake.sent().getFirst().request().messages().getFirst().text())
        .doesNotContain("Source files:");
    assertThat(summary.key())
        .isEqualTo(Summarizer.key(order, List.of(), List.of(), fake.model("model-a")));
  }

  @Test
  void secretsInSourceFilesAreNotSentToTheModel() throws Exception {
    String source =
        """
        package p;

        class Order {
          private static final String API_KEY = "sk-live-123";
          private final String url = "jdbc:postgresql://app:s3cret@db/shop";
        }
        """;
    // The file is checked against the evidence as read, and masked only after.
    SummaryNode order = orderWithSource(blob(source));
    SourceFiles files = TestFacts.files((repo, path) -> Optional.of(source));

    new Summarizer(harness(client("model-a", fake)), files).summarize(List.of(order));

    assertThat(fake.sent().getFirst().request().messages().getFirst().text())
        .contains("API_KEY = \"******\"", "\"jdbc:postgresql://db/shop\"")
        .doesNotContain("sk-live-123", "s3cret");
  }

  /** A type with many methods: about 60 characters a fact, so about 15 tokens. */
  private static SummaryNode largeType(int methods) {
    TestFacts facts = new TestFacts().attribute("r", "type:p.Big", "kind", "class");
    for (int i = 0; i < methods; i++) {
      facts.relate("r", "type:p.Big", "CONTAINS", "method:p.Big#method%03d()".formatted(i));
    }
    return new SummaryNode(
        EntityId.parse("type:p.Big"), SummaryLevel.TYPE, facts.list(), List.of());
  }

  /**
   * A model that cites the first and last reference its prompt lists, or for a merge every
   * reference the parts' summaries cite, with a filler of the given length; the answer stays within
   * a type's output limit.
   */
  private static String citingWhatItSees(LlmRequest request, int filler) {
    String prompt = request.messages().getFirst().text();
    List<String> refs = new ArrayList<>();
    if (prompt.contains("Part 1: ")) {
      refs.addAll(Citations.references(prompt));
    } else {
      Matcher ref = REF.matcher(prompt);
      while (ref.find()) {
        refs.add(ref.group(1) != null ? ref.group(1) : ref.group(2));
      }
    }
    List<String> cited = refs.size() <= 2 ? refs : List.of(refs.getFirst(), refs.getLast());
    // A sentence holds at most 300 characters: a long filler takes up to three.
    StringBuilder answer = new StringBuilder();
    int left = filler;
    do {
      int chunk = Math.min(left, 250);
      answer
          .append(answer.isEmpty() ? "" : " ")
          .append(
              "Part of it%s, from what the part shows. [%s]"
                  .formatted("x".repeat(chunk), String.join(", ", cited)));
      left -= chunk;
    } while (left > 0 && answer.length() < 900);
    return answer.toString();
  }

  @Test
  void aMergeIsShownThePartsMarksAndKeepsAnEvidencedPurpose() {
    SummaryNode large = largeType(400);
    List<Fact> facts = new ArrayList<>(large.facts());
    facts.add(TestFacts.claim("r", "README.md", 1, "Big does everything."));
    SummaryNode big = new SummaryNode(large.entity(), large.level(), facts, List.of());
    fake.answering(Answers.epic(request -> "Purpose: evidenced\n" + citingWhatItSees(request, 0)));

    Summary summary =
        new Summarizer(harness(smallWindowClient(4_000)))
            .summarize(List.of(big))
            .summaries()
            .get(big.entity());

    // Only the first part cites the README claim [F1] in its first sentence.
    String merge = fake.sent().getLast().request().messages().getFirst().text();
    assertThat(fake.sent().getLast().request().purpose()).endsWith("(merge)");
    assertThat(merge).contains("\nPart 1: ", "\nPart 2: ");
    assertThat(summary.purpose()).isEqualTo(Summary.Purpose.EVIDENCED);
  }

  @Test
  void partsAndTheMergeLeaveOutSentencesWithoutCitations() {
    SummaryNode big = largeType(400);
    fake.answering(Answers.epic(request -> citingWhatItSees(request, 0) + " It is big."));

    Summary summary =
        new Summarizer(harness(smallWindowClient(4_000)))
            .summarize(List.of(big))
            .summaries()
            .get(big.entity());

    String merge = fake.sent().getLast().request().messages().getFirst().text();
    assertThat(fake.sent().getLast().request().purpose()).endsWith("(merge)");
    assertThat(merge)
        .contains("Part of it, from what the part shows. [")
        .doesNotContain("It is big.");
    assertThat(summary.text())
        .startsWith("Part of it, from what the part shows. [fact:")
        .doesNotContain("It is big.");
    // The merged answer left a sentence out, so its confidence is below an inferred summary's.
    assertThat(summary.confidence()).isPositive().isLessThan(Confidence.INFERRED);
  }

  private LlmClient smallWindowClient(int contextWindow) {
    ModelConfig small =
        new ModelConfig(
            FakeLlmProvider.NAME,
            "small",
            EnumSet.allOf(Capability.class),
            contextWindow,
            Pricing.FREE);
    return new LlmClient(
        ModelSettings.single(small),
        List.of(fake),
        BudgetGuard.unlimited("test"),
        new InMemoryLlmCallLog(),
        Clock.systemUTC());
  }

  @Test
  void aModuleWhoseFactSheetRunsOverPartsCitesTheSheetsFactsThroughThem() {
    TestFacts facts = new TestFacts().attribute("r", "type:p.Api", "kind", "class");
    for (int i = 0; i < 12; i++) {
      String method = "method:p.Api#get%02d()".formatted(i);
      facts
          .relate("r", "type:p.Api", "CONTAINS", method)
          .attribute("r", method, "endpoint", "GET /" + i);
    }
    SummaryNode api =
        new SummaryNode(EntityId.parse("type:p.Api"), SummaryLevel.TYPE, facts.list(), List.of());
    TestFacts own = new TestFacts();
    for (int i = 0; i < 200; i++) {
      own.attribute("r", "module:g:m", "property-%03d".formatted(i), "value " + i);
    }
    SummaryNode module =
        new SummaryNode(
            EntityId.parse("module:g:m"), SummaryLevel.MODULE, own.list(), List.of(api));
    fake.answering(
        Answers.epic(
            request -> {
              String prompt = request.messages().getFirst().text();
              List<String> refs = new ArrayList<>();
              if (prompt.contains("Part 1: ")) {
                refs.addAll(Citations.references(prompt));
              } else {
                Matcher ref = REF.matcher(prompt);
                while (ref.find()) {
                  refs.add(ref.group(1) != null ? ref.group(1) : ref.group(2));
                }
              }
              return "Part of it, from what the part shows. [" + String.join(", ", refs) + "]";
            }));

    Summary summary =
        new Summarizer(harness(smallWindowClient(4_000)))
            .summarize(List.of(module))
            .summaries()
            .get(module.entity());

    List<String> prompts =
        fake.sent().stream()
            .filter(s -> s.request().purpose().startsWith("summarize module"))
            .map(s -> s.request().messages().getFirst().text())
            .toList();
    assertThat(prompts.stream().filter(p -> p.contains("This is part "))).hasSizeGreaterThan(1);
    assertThat(prompts)
        .anyMatch(p -> p.contains("\nOf the units it contains, entry points:\n<facts>\n[F201] "));
    for (String prompt : prompts) {
      assertThat(Tokens.estimate(Prompt.SYSTEM + prompt)).isLessThanOrEqualTo(4_000);
    }
    List<String> sheet = FactSheet.facts(FactSheet.of(module)).stream().map(Fact::id).toList();
    assertThat(sheet).hasSize(12);
    assertThat(summary.citedFacts()).containsAll(sheet);
    assertThat(summary.inputFacts()).endsWith(sheet.toArray(String[]::new));
  }

  @Test
  void aUnitLargerThanTheContextWindowIsSummarizedInPartsAndMerged() {
    SummaryNode big = largeType(400);
    LlmClient client = smallWindowClient(4_000);
    fake.answering(Answers.epic(request -> citingWhatItSees(request, 0)));
    assertThat(Tokens.estimate(Prompt.SYSTEM + Prompt.user(big, List.of(), List.of())))
        .as("the whole prompt does not fit")
        .isGreaterThan(4_000);

    Summary summary =
        new Summarizer(harness(client)).summarize(List.of(big)).summaries().get(big.entity());

    List<String> purposes = fake.sent().stream().map(s -> s.request().purpose()).toList();
    assertThat(purposes.size()).isGreaterThan(2);
    assertThat(purposes.getFirst())
        .isEqualTo("summarize type type:p.Big (part 1 of " + (purposes.size() - 1) + ")");
    assertThat(purposes.getLast()).isEqualTo("summarize type type:p.Big (merge)");
    assertThat(fake.sent())
        .allSatisfy(
            s ->
                assertThat(s.request().estimatedInputTokens() + s.request().maxOutputTokens())
                    .isLessThanOrEqualTo(4_000));
    // Each part shows its own facts under the references of the whole unit.
    assertThat(fake.sent().get(1).request().messages().getFirst().text())
        .startsWith(
            "<items>\n<item id=\"I1\">\nUnit: type Big in package p\nWhat is known about this"
                + " unit is too long for one request")
        .doesNotContain("[F1] ");
    // The merge keeps the citations of the first and the last part.
    assertThat(summary.citedFacts())
        .contains(summary.inputFacts().getFirst(), summary.inputFacts().getLast());
    assertThat(store.find(summary.key()).map(stored -> SummaryJson.read(stored.answer())))
        .contains(summary);
  }

  @Test
  void aSplitUnitWhosePartIsRejectedTwiceHasNoSummary() {
    SummaryNode big = largeType(400);
    fake.answering(
        Answers.epic(
            request ->
                request.purpose().contains("(part 1 of ")
                    ? "It has many methods."
                    : citingWhatItSees(request, 0)));

    Summarizer.Result result =
        new Summarizer(harness(smallWindowClient(4_000))).summarize(List.of(big));

    assertThat(result.summaries()).isEmpty();
    assertThat(result.rejected()).containsExactly(big.entity());
    assertThat(store.size()).isEqualTo(1); // the drop (#682)
  }

  @Test
  void partsThatDoNotFitOneMergeAreMergedInRounds() {
    SummaryNode big = largeType(1_500);
    fake.answering(Answers.epic(request -> citingWhatItSees(request, 1_400)));

    Summary summary =
        new Summarizer(harness(smallWindowClient(4_000)))
            .summarize(List.of(big))
            .summaries()
            .get(big.entity());

    List<String> purposes = fake.sent().stream().map(s -> s.request().purpose()).toList();
    assertThat(purposes).anyMatch(p -> p.contains("(merge 1.1 of "));
    assertThat(purposes.getLast()).isEqualTo("summarize type type:p.Big (merge)");
    assertThat(summary.citedFacts())
        .contains(summary.inputFacts().getFirst(), summary.inputFacts().getLast());
  }

  @Test
  void partsAndTheMergeMayCiteRanges() {
    SummaryNode big = largeType(400);
    Pattern range = Pattern.compile("\\[(F\\d+-F\\d+)\\]");
    fake.answering(
        Answers.epic(
            request -> {
              String prompt = request.messages().getFirst().text();
              if (prompt.contains("Part 1: ")) {
                List<String> ranges = new ArrayList<>();
                Matcher matcher = range.matcher(prompt);
                while (matcher.find()) {
                  ranges.add(matcher.group(1));
                }
                return "All of it, from what every part of the type shows. ["
                    + String.join(", ", ranges)
                    + "]";
              }
              List<String> refs = new ArrayList<>();
              Matcher ref = REF.matcher(prompt);
              while (ref.find()) {
                refs.add(ref.group(1) != null ? ref.group(1) : ref.group(2));
              }
              return "Part of it, from what the part shows. ["
                  + refs.getFirst()
                  + "-"
                  + refs.getLast()
                  + "]";
            }));

    Summary summary =
        new Summarizer(harness(smallWindowClient(4_000)))
            .summarize(List.of(big))
            .summaries()
            .get(big.entity());

    assertThat(fake.sent()).noneMatch(s -> s.request().purpose().endsWith("(retry)"));
    assertThat(fake.sent().getLast().request().purpose())
        .isEqualTo("summarize type type:p.Big (merge)");
    assertThat(summary.citedFacts()).containsExactlyInAnyOrderElementsOf(summary.inputFacts());
    assertThat(summary.text())
        .startsWith("All of it, from what every part of the type shows. [fact:")
        .doesNotContain("-F");
  }

  @Test
  void aPartCitingAReferenceItWasNotShownIsSentBack() {
    SummaryNode big = largeType(400);
    AtomicInteger calls = new AtomicInteger();
    fake.answering(
        Answers.epic(
            request ->
                calls.getAndIncrement() == 0
                    ? "It has many methods. [F400]"
                    : citingWhatItSees(request, 0)));

    new Summarizer(harness(smallWindowClient(4_000))).summarize(List.of(big));

    assertThat(fake.sent().get(1).request().purpose())
        .startsWith("summarize type type:p.Big (part 1 of ")
        .endsWith(") (retry)");
    assertThat(fake.sent().get(1).request().messages().get(2).text())
        .contains("Cites F400, which is not in your input.");
  }

  @Test
  void partsCarryTheContainedUnitsSummariesTheyAreGiven() {
    TestFacts facts = new TestFacts();
    List<SummaryNode> types = new ArrayList<>();
    for (int i = 0; i < 150; i++) {
      String type = "type:p.Type%03d".formatted(i);
      facts.relate("r", "package:g:a:p", "CONTAINS", type);
      types.add(
          new SummaryNode(
              EntityId.parse(type),
              SummaryLevel.TYPE,
              new TestFacts().attribute("r", type, "kind", "class").list(),
              List.of()));
    }
    SummaryNode pkg =
        new SummaryNode(EntityId.parse("package:g:a:p"), SummaryLevel.PACKAGE, facts.list(), types);
    fake.answering(Answers.epic(request -> citingWhatItSees(request, 0)));

    Summary summary =
        new Summarizer(harness(smallWindowClient(4_000)))
            .summarize(List.of(pkg))
            .summaries()
            .get(pkg.entity());

    List<String> partPrompts =
        fake.sent().stream()
            .filter(s -> s.request().purpose().startsWith("summarize package package:g:a:p (part"))
            .map(s -> s.request().messages().getFirst().text())
            .toList();
    assertThat(partPrompts.size()).isGreaterThan(1);
    assertThat(partPrompts.getLast())
        .contains("Summaries of the units it contains:\n<summaries>\n[S")
        .doesNotContain("[S1] ");
    assertThat(summary.citedFacts()).contains(summary.inputFacts().getFirst());
    assertThat(summary.citedSummaries()).contains(summary.inputSummaries().getLast());
  }

  @Test
  void aSplitTypeShowsItsSourceFileInOnePartAndKeepsItsCitation() throws Exception {
    String source = ExcerptsTest.ORDER + "// filler\n".repeat(500);
    List<Fact> facts = new ArrayList<>(orderWithSource(blob(source)).facts());
    TestFacts methods = new TestFacts();
    for (int i = 0; i < 200; i++) {
      methods.relate("r", "type:p.Order", "CONTAINS", "method:p.Order#method%03d()".formatted(i));
    }
    facts.addAll(methods.list());
    SummaryNode order =
        new SummaryNode(EntityId.parse("type:p.Order"), SummaryLevel.TYPE, facts, List.of());
    SourceFiles files = TestFacts.files((repo, path) -> Optional.of(source));
    fake.answering(Answers.epic(request -> citingWhatItSees(request, 0)));

    Summary summary =
        new Summarizer(harness(smallWindowClient(4_000)), files)
            .summarize(List.of(order))
            .summaries()
            .get(order.entity());

    List<String> prompts =
        fake.sent().stream().map(s -> s.request().messages().getFirst().text()).toList();
    assertThat(prompts.size()).isGreaterThan(2);
    assertThat(prompts)
        .filteredOn(
            p -> p.contains("\n<code ref=\"E1\" repo=\"r\" path=\"src/main/java/p/Order.java\""))
        .hasSize(1);
    assertThat(prompts.getLast()).contains("Part 1: ").contains("E1");
    Fact kind = facts.stream().filter(f -> f.predicate().equals("kind")).findFirst().get();
    assertThat(summary.citedFacts()).contains(kind.id());
  }

  @Test
  void thePromptBudgetLeavesRoomForThinking() {
    SummaryNode big = largeType(1);
    ModelConfig model = fake.model("m");

    assertThat(Summarizer.promptBudget(big, model.withThinking(new Thinking(false, 1_000))))
        .isEqualTo(Summarizer.promptBudget(big, model) - 1_000 - 1_000 * 3 / 2);
  }

  @Test
  void promptsMaskValuesUnderKnownSecretKeys() throws Exception {
    String source =
        ExcerptsTest.ORDER.replace(
            "@Id private Long id;",
            "@Value(\"${jwt.key:literal-secret-default}\") private String key;");
    SourceFiles files =
        new SourceFiles() {
          @Override
          public Optional<byte[]> read(String repo, String commit, String path) {
            return Optional.of(source.getBytes(StandardCharsets.UTF_8));
          }

          @Override
          public Optional<byte[]> blob(String repo, String blob, String path) {
            return Optional.of(source.getBytes(StandardCharsets.UTF_8));
          }

          @Override
          public java.util.Set<String> secretKeys() {
            return java.util.Set.of("jwt.key");
          }
        };
    SummaryNode order = orderWithSource(blob(source));

    new Summarizer(harness(client("model-a", fake)), files).summarize(List.of(order));

    String prompt = fake.sent().getFirst().request().messages().getFirst().text();
    assertThat(prompt).contains("${jwt.key:******}").doesNotContain("literal-secret-default");
  }

  @Test
  void anAnswerRepeatingAValueTheMaskingTookOutIsSentBackOnceThenDropped() {
    // The masking hands what it took out of the shown file to the harness's Secrets check (#645).
    String secret = "hunter2-very-secret";
    String source =
        ExcerptsTest.ORDER.replace(
            "@Id private Long id;", "private static final String PASSWORD = \"" + secret + "\";");
    Set<String> secrets = java.util.concurrent.ConcurrentHashMap.newKeySet();
    SourceFiles files =
        new SourceFiles() {
          @Override
          public Optional<byte[]> read(String repo, String commit, String path) {
            return Optional.of(source.getBytes(StandardCharsets.UTF_8));
          }

          @Override
          public Optional<byte[]> blob(String repo, String blob, String path) {
            return Optional.of(source.getBytes(StandardCharsets.UTF_8));
          }

          @Override
          public java.util.function.Consumer<String> maskedValues() {
            return secrets::add;
          }
        };
    FakeLlmProvider echoing =
        new FakeLlmProvider()
            .answering(
                Answers.epic(
                    r ->
                        citingAll(r)
                            .replace(
                                "as a well-behaved model words it",
                                "guarded by the password " + secret + ",")));
    SummaryNode order = orderWithSource(blob(source));

    Harness harness =
        new Harness(client("model-a", echoing), store, RunBudget.NONE, BlobLookup.NONE, secrets);
    Summarizer.Result result = new Summarizer(harness, files).summarize(List.of(order));

    assertThat(secrets).contains(secret);
    // What is kept of the rejected answers never holds the value.
    assertThat(harness.rejections()).isNotEmpty();
    assertThat(harness.rejections())
        .allSatisfy(r -> assertThat(String.valueOf(r.answer())).doesNotContain(secret));
    assertThat(echoing.sent().getFirst().request().messages().getFirst().text())
        .doesNotContain(secret);
    // Asked, sent back once with the reason, then dropped.
    assertThat(echoing.sent()).hasSize(2);
    assertThat(echoing.sent().get(1).request().messages().getLast().text())
        .contains("a value marked as secret");
    assertThat(result.summaries()).isEmpty();
  }

  @Test
  void everySummaryIsStoredAsAClaimOfItsLevelWithItsSentencesAndTheirFacts() {
    List<Fact> facts = TestFacts.shop().list();
    Summarizer.Result result = summarizer().summarize(SummaryTree.fromFacts(facts));

    java.util.Map<io.lantern.core.fact.Origin, List<Fact>> claims =
        Summarizer.facts(result, facts, 7);

    // Types by the bulk role, the units above them by the other.
    assertThat(claims.keySet())
        .containsExactly(
            io.lantern.harness.LlmFacts.origin(Summarizer.TYPES),
            io.lantern.harness.LlmFacts.origin(Summarizer.ROLE));
    List<Fact> all = claims.values().stream().flatMap(List::stream).toList();
    assertThat(all).hasSize(result.summaries().size());
    assertThat(all)
        .allSatisfy(
            fact -> {
              assertThat(fact.predicate()).isEqualTo("summary");
              assertThat(fact.origin().kind()).isEqualTo(io.lantern.core.fact.Origin.Kind.LLM);
              assertThat(fact.validity().from()).isEqualTo(7);
              assertThat(fact.attributes().get("sentences")).isNotEmpty();
            });
    Fact pkg =
        all.stream()
            .filter(
                f ->
                    f.subject().toString().equals("package:com.example:shop-core:com.example.shop"))
            .findFirst()
            .orElseThrow();
    assertThat(pkg.object().value()).isEqualTo("package");
    assertThat(pkg.attributes().get("basis").asString()).isEqualTo("inferred");
    assertThat(pkg.confidence()).isEqualTo(result.summaries().get(pkg.subject()).confidence());
    // A citation of a type's summary resolves to the facts that summary cites.
    Summary order = result.summaries().get(EntityId.parse("type:com.example.shop.Order"));
    List<String> cited = new ArrayList<>();
    pkg.attributes()
        .get("sentences")
        .forEach(sentence -> sentence.get("cites").forEach(c -> cited.add(c.asString())));
    assertThat(cited).containsAll(order.citedFacts());
    assertThat(
            all.stream()
                .filter(f -> f.subject().toString().equals("repo:shop"))
                .findFirst()
                .orElseThrow()
                .object()
                .value())
        .isEqualTo("repo");
  }

  @Test
  void aUnitTheModelOmitsHasNoSummaryAndIsNotAskedAgain() {
    fake.answering(
        Answers.epic(
            request ->
                request.purpose().endsWith("type:com.example.shop.Order$Line")
                    ? "{\"items\": {\"I1\": {\"omitted\": true}}}"
                    : citingAll(request)));

    Summarizer.Result result =
        summarizer().summarize(SummaryTree.fromFacts(TestFacts.shop().list()));

    EntityId line = EntityId.parse("type:com.example.shop.Order$Line");
    assertThat(result.omitted()).containsExactly(line);
    assertThat(result.rejected()).isEmpty();
    assertThat(result.summaries()).doesNotContainKey(line).hasSize(6);
    int calls = fake.sent().size();

    Summarizer.Result again =
        summarizer().summarize(SummaryTree.fromFacts(TestFacts.shop().list()));

    assertThat(fake.sent()).hasSize(calls);
    assertThat(again.omitted()).containsExactly(line);
    assertThat(again.summaries()).isEqualTo(result.summaries());
  }

  @Test
  void sentencesPastTheCountTheItemAsksForAreLeftOut() {
    fake.answering(
        Answers.epic(
            "One: the first sentence says a thing about it [F1]. Two: the second sentence says"
                + " a thing about it [F1]. Three: the third sentence says a thing about it [F1]. Four:"
                + " the fourth sentence says a thing about it [F1]. Five: the fifth sentence says a"
                + " thing about it [F1]."));
    SummaryNode line =
        new SummaryNode(
            EntityId.parse("type:p.Line"),
            SummaryLevel.TYPE,
            List.of(
                TestFacts.fact(
                    "r", "type:p.Line", "kind", FactObject.string("record"), "Line.java")),
            List.of());

    Summary summary = summarizer().summarize(List.of(line)).summaries().get(line.entity());

    // A type keeps at most four sentences.
    assertThat(summary.text().split("\\[fact:")).hasSize(5);
    assertThat(summary.text()).doesNotContain("Five");
    assertThat(fake.sent()).hasSize(1);
  }

  @Test
  void anAnswerWithOmittedFalseKeepsItsSentencesAndBracketedCitesAreRead() {
    fake.answering(
        "{\"items\": {\"I1\": {\"omitted\": false, \"sentences\": [{\"text\": \"Line holds the"
            + " quantity of one product in an order.\", \"cites\": [\"[F1]\"]}, {\"text\": \"It is"
            + " a record that the order keeps with its lines.\", \"cites\": [\"F1\"]}, {\"text\":"
            + " \"Three: the third sentence says one more thing about it.\", \"cites\": [\"F1\"]},"
            + " {\"text\": \"Four: the fourth sentence says one more thing about it.\", \"cites\":"
            + " [\"F1\"]}, {\"text\": \"Five: the fifth sentence says one more thing about it.\","
            + " \"cites\": [\"F1\"]}]}}}");
    SummaryNode line =
        new SummaryNode(
            EntityId.parse("type:p.Line"),
            SummaryLevel.TYPE,
            List.of(
                TestFacts.fact(
                    "r", "type:p.Line", "kind", FactObject.string("record"), "Line.java")),
            List.of());
    String fact = line.facts().getFirst().id();

    Summary summary = summarizer().summarize(List.of(line)).summaries().get(line.entity());

    assertThat(summary.text())
        .startsWith(
            "Line holds the quantity of one product in an order. [fact:" + fact + "] It is a");
    assertThat(summary.text()).doesNotContain("[[").doesNotContain("Five");
    // The fifth sentence is past the four a type asks for: left out, without lowering confidence.
    assertThat(summary.confidence()).isEqualTo(Confidence.INFERRED);
  }
}

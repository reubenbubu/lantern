package io.lantern.summarize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactObject;
import io.lantern.llm.BudgetGuard;
import io.lantern.llm.Capability;
import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.InMemoryLlmCallLog;
import io.lantern.llm.LlmClient;
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
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class SummarizerTest {

  private static final Pattern REF = Pattern.compile("^\\[([FES]\\d+)\\]", Pattern.MULTILINE);
  private static final Pattern UNIT = Pattern.compile("^summarize \\S+ (\\S+:\\S+)");

  private final FakeLlmProvider fake = new FakeLlmProvider().answering(SummarizerTest::citingAll);
  private final InMemorySummaryStore store = new InMemorySummaryStore();

  /**
   * A well-behaved model: names the unit, includes a fingerprint of its prompt so the text changes
   * whenever the inputs do, and cites every reference it was given.
   */
  static String citingAll(LlmRequest request) {
    String prompt = request.messages().getFirst().text();
    List<String> refs = new ArrayList<>();
    Matcher ref = REF.matcher(prompt);
    while (ref.find()) {
      refs.add(ref.group(1));
    }
    return "Summary of %s (%08x). [%s]"
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
    return new Summarizer(client("model-a", fake), store);
  }

  private List<String> summarizedUnits() {
    return fake.sent().stream().map(s -> unit(s.request())).toList();
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
            "[S1] type Order in package com.example.shop: Summary of type:com.example.shop.Order")
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
            .sorted()
            .toList();
    assertThat(order.inputFacts()).isEqualTo(orderFacts);
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

  @Test
  void anotherModelDoesNotReuseSummaries() {
    List<SummaryNode> tree = SummaryTree.fromFacts(TestFacts.shop().list());
    summarizer().summarize(tree);

    Summarizer.Result other = new Summarizer(client("model-b", fake), store).summarize(tree);

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
            child.citedFacts(),
            child.citedSummaries(),
            child.inputFacts(),
            child.inputSummaries(),
            child.promptVersion(),
            child.provider(),
            child.model());
    assertThat(Summarizer.key(order, List.of(), List.of(rekeyedChild), fake.model("m")))
        .isNotEqualTo(key);
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
  void uncitedAnswerIsSentBackOnceThenRejected() {
    fake.answering("Order holds the lines of an order.");
    SummaryNode line =
        new SummaryNode(
            EntityId.parse("type:p.Line"),
            SummaryLevel.TYPE,
            List.of(
                TestFacts.fact(
                    "r", "type:p.Line", "kind", FactObject.string("record"), "Line.java")),
            List.of());

    assertThatThrownBy(() -> summarizer().summarize(List.of(line)))
        .isInstanceOf(SummaryRejectedException.class)
        .hasMessageContaining("type:p.Line")
        .hasMessageContaining("cites no references");
    assertThat(fake.sent()).hasSize(2);
    List<LlmMessage> retry = fake.sent().get(1).request().messages();
    assertThat(retry).hasSize(3);
    assertThat(retry.get(1)).isEqualTo(LlmMessage.assistant("Order holds the lines of an order."));
    assertThat(retry.get(2).text())
        .startsWith("Your summary was rejected: it cites no references.");
    assertThat(fake.sent().get(1).request().purpose()).endsWith("(retry)");
    assertThat(store.size()).isZero();
  }

  /** Answers the first prompt for {@code Order$Line} with the given text, and others well. */
  private void firstLineAnswer(String text) {
    fake.answering(
        request ->
            request.purpose().endsWith("type:com.example.shop.Order$Line")
                ? text
                : citingAll(request));
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
  void anEmptyAnswerIsRetriedWithTheReason() {
    firstLineAnswer(" ");

    summarizer().summarize(SummaryTree.fromFacts(TestFacts.shop().list()));

    List<LlmMessage> retry = retried().messages();
    assertThat(retry).hasSize(1);
    assertThat(retry.getFirst().text()).contains("Your summary was rejected: it is empty.");
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
        .contains("it cites references that are not in the input: [F7].");
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

    assertThatThrownBy(
            () ->
                new Summarizer(client("model-a", provider), store)
                    .summarize(SummaryTree.fromFacts(TestFacts.shop().list())))
        .isInstanceOf(SummaryRejectedException.class)
        .hasMessageContaining("cut off");
    assertThat(store.size()).isZero();
  }

  @Test
  void aModelThatThinksIsCutOffUnlessItHasHeadroom() {
    fake.thinking(1000, false);

    assertThatThrownBy(() -> summarizer().summarize(SummaryTree.fromFacts(TestFacts.shop().list())))
        .isInstanceOf(SummaryRejectedException.class)
        .hasMessageContaining("cut off");
    assertThat(store.size()).isZero();

    LlmClient roomy =
        new LlmClient(
            ModelSettings.single(fake.model("model-a").withThinking(new Thinking(false, 1000))),
            List.of(fake),
            BudgetGuard.unlimited("test"),
            new InMemoryLlmCallLog(),
            Clock.systemUTC());
    Summarizer.Result result =
        new Summarizer(roomy, store).summarize(SummaryTree.fromFacts(TestFacts.shop().list()));

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
  void requestsUseTheSummarizeRole() {
    summarizer().summarize(SummaryTree.fromFacts(TestFacts.shop().list()));

    assertThat(fake.sent())
        .allSatisfy(s -> assertThat(s.request().role()).isEqualTo(Role.SUMMARIZE));
    assertThat(fake.sent().getFirst().request().system()).isEqualTo(Prompt.SYSTEM);
  }

  /** Facts about {@code type:p.Order} whose evidence is {@link ExcerptsTest#ORDER}. */
  private static SummaryNode orderWithSource(String contentHash) {
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
              new Evidence(
                  fact.evidence().repo(),
                  fact.evidence().commit(),
                  fact.evidence().path(),
                  fact.evidence().lines(),
                  contentHash),
              fact.confidence(),
              fact.validity()));
    }
    return new SummaryNode(EntityId.parse("type:p.Order"), SummaryLevel.TYPE, facts, List.of());
  }

  private static String sha256(String text) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
  }

  private static final SourceFiles ORDER_SOURCE =
      (repo, commit, path) ->
          repo.equals("r") && commit.equals(TestFacts.COMMIT) && path.equals(ExcerptsTest.PATH)
              ? Optional.of(ExcerptsTest.ORDER.getBytes(StandardCharsets.UTF_8))
              : Optional.empty();

  @Test
  void typePromptsShowSourceExcerptsCitedAsTheirFacts() throws Exception {
    SummaryNode order = orderWithSource(sha256(ExcerptsTest.ORDER));

    Summary summary =
        new Summarizer(client("model-a", fake), store, ORDER_SOURCE)
            .summarize(List.of(order))
            .summaries()
            .get(order.entity());

    String prompt = fake.sent().getFirst().request().messages().getFirst().text();
    assertThat(prompt)
        .contains(
            "\nSource files:\n[E1] src/main/java/p/Order.java:\n```java\n"
                + ExcerptsTest.ORDER.stripTrailing()
                + "\n```\n");
    // The fake cites [F1, F2, E1]; the file is cited as the fact it comes from.
    Fact kind = order.facts().stream().filter(f -> f.predicate().equals("kind")).findFirst().get();
    assertThat(summary.citedFacts()).containsExactlyInAnyOrderElementsOf(summary.inputFacts());
    assertThat(summary.text()).contains("[fact:" + kind.id() + "]").doesNotContain("[E");
    assertThat(summary.key())
        .isNotEqualTo(Summarizer.key(order, List.of(), List.of(), fake.model("model-a")));
  }

  @Test
  void aFileThatIsNotTheVersionObservedShowsNoExcerpt() {
    SummaryNode order = orderWithSource("0".repeat(64));

    Summary summary =
        new Summarizer(client("model-a", fake), store, ORDER_SOURCE)
            .summarize(List.of(order))
            .summaries()
            .get(order.entity());

    assertThat(fake.sent().getFirst().request().messages().getFirst().text())
        .doesNotContain("Source files:");
    assertThat(summary.key())
        .isEqualTo(Summarizer.key(order, List.of(), List.of(), fake.model("model-a")));
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
    if (prompt.contains("Summaries of the parts:")) {
      refs.addAll(Citations.references(prompt));
    } else {
      Matcher ref = REF.matcher(prompt);
      while (ref.find()) {
        refs.add(ref.group(1));
      }
    }
    List<String> cited = refs.size() <= 2 ? refs : List.of(refs.getFirst(), refs.getLast());
    return "Part of it%s. [%s]".formatted("x".repeat(filler), String.join(", ", cited));
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
  void aUnitLargerThanTheContextWindowIsSummarizedInPartsAndMerged() {
    SummaryNode big = largeType(400);
    LlmClient client = smallWindowClient(4_000);
    fake.answering(request -> citingWhatItSees(request, 0));
    assertThat(Tokens.estimate(Prompt.SYSTEM + Prompt.user(big, List.of(), List.of())))
        .as("the whole prompt does not fit")
        .isGreaterThan(4_000);

    Summary summary =
        new Summarizer(client, store).summarize(List.of(big)).summaries().get(big.entity());

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
        .startsWith("What is known about the type Big in package p is too long for one request")
        .doesNotContain("[F1] ");
    // The merge keeps the citations of the first and the last part.
    assertThat(summary.citedFacts())
        .contains(summary.inputFacts().getFirst(), summary.inputFacts().getLast());
    assertThat(store.find(summary.key())).contains(summary);
  }

  @Test
  void partsThatDoNotFitOneMergeAreMergedInRounds() {
    SummaryNode big = largeType(1_500);
    fake.answering(request -> citingWhatItSees(request, 1_400));

    Summary summary =
        new Summarizer(smallWindowClient(4_000), store)
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
  void aPartCitingAReferenceItWasNotShownIsSentBack() {
    SummaryNode big = largeType(400);
    AtomicInteger calls = new AtomicInteger();
    fake.answering(
        request ->
            calls.getAndIncrement() == 0
                ? "It has many methods. [F400]"
                : citingWhatItSees(request, 0));

    new Summarizer(smallWindowClient(4_000), store).summarize(List.of(big));

    assertThat(fake.sent().get(1).request().purpose())
        .startsWith("summarize type type:p.Big (part 1 of ")
        .endsWith(") (retry)");
    assertThat(fake.sent().get(1).request().messages().get(2).text())
        .contains("it cites references that are not in the input: [F400].");
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
    fake.answering(request -> citingWhatItSees(request, 0));

    Summary summary =
        new Summarizer(smallWindowClient(4_000), store)
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
        .contains("Summaries of the parts it contains:\n[S")
        .doesNotContain("[S1] ");
    assertThat(summary.citedFacts()).contains(summary.inputFacts().getFirst());
    assertThat(summary.citedSummaries()).contains(summary.inputSummaries().getLast());
  }

  @Test
  void aSplitTypeShowsItsSourceFileInOnePartAndKeepsItsCitation() throws Exception {
    String source = ExcerptsTest.ORDER + "// filler\n".repeat(500);
    List<Fact> facts = new ArrayList<>(orderWithSource(sha256(source)).facts());
    TestFacts methods = new TestFacts();
    for (int i = 0; i < 200; i++) {
      methods.relate("r", "type:p.Order", "CONTAINS", "method:p.Order#method%03d()".formatted(i));
    }
    facts.addAll(methods.list());
    SummaryNode order =
        new SummaryNode(EntityId.parse("type:p.Order"), SummaryLevel.TYPE, facts, List.of());
    SourceFiles files =
        (repo, commit, path) -> Optional.of(source.getBytes(StandardCharsets.UTF_8));
    fake.answering(request -> citingWhatItSees(request, 0));

    Summary summary =
        new Summarizer(smallWindowClient(4_000), store, files)
            .summarize(List.of(order))
            .summaries()
            .get(order.entity());

    List<String> prompts =
        fake.sent().stream().map(s -> s.request().messages().getFirst().text()).toList();
    assertThat(prompts.size()).isGreaterThan(2);
    assertThat(prompts)
        .filteredOn(p -> p.contains("\n[E1] src/main/java/p/Order.java:"))
        .hasSize(1);
    assertThat(prompts.getLast()).contains("Summaries of the parts:").contains("E1");
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
}

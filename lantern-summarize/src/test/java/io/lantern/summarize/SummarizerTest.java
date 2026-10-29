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
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
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

  private static final Pattern REF = Pattern.compile("^\\[([FES]\\d+)\\]", Pattern.MULTILINE);
  private static final Pattern UNIT = Pattern.compile("^summarize \\S+ (\\S+:\\S+)");

  private final FakeLlmProvider fake = new FakeLlmProvider().answering(SummarizerTest::citingAll);
  private final InMemorySummaryStore store = new InMemorySummaryStore();

  /** What the summarizer logs during a test. */
  private final ListAppender<ILoggingEvent> log = new ListAppender<>();

  @BeforeEach
  void captureLog() {
    log.start();
    ((Logger) LoggerFactory.getLogger(Summarizer.class)).addAppender(log);
  }

  @AfterEach
  void releaseLog() {
    ((Logger) LoggerFactory.getLogger(Summarizer.class)).detachAppender(log);
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
            "[S1] type Order in package com.example.shop (purpose inferred): Summary of"
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

  /** The facts as observed again at another commit, with nothing else changed. */
  private static List<Fact> atCommit(List<Fact> facts, String commit) {
    return facts.stream()
        .map(
            f ->
                Fact.create(
                    f.subject(),
                    f.predicate(),
                    f.object(),
                    f.attributes(),
                    f.origin(),
                    new Evidence(
                        f.evidence().repo(),
                        commit,
                        f.evidence().path(),
                        f.evidence().lines(),
                        f.evidence().contentHash()),
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
    List<Fact> later = atCommit(TestFacts.shop().list(), "b".repeat(40));

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
    String endpoint = "method:com.example.orders.OrderController#get0() endpoint \"GET /orders/0\"";

    Summary first = summarizer().summarize(SummaryTree.fromFacts(earlier)).summaries().get(module);
    int calls = fake.sent().size();
    List<Fact> later = atCommit(FactSheetTest.service(2).list(), "b".repeat(40));
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
        .contains("service:com.example:orders USES_DATABASE database:orders-db:5432/orders");
  }

  @Test
  void aChangedEntryPointSummarizesItsModuleAgainThoughNoSummaryTextChanges() {
    fake.answering(request -> "Same words. [F1]");
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
        (repo, commit, path) ->
            repo.equals("r") && path.equals(ExcerptsTest.PATH)
                ? Optional.of(ExcerptsTest.ORDER.getBytes(StandardCharsets.UTF_8))
                : Optional.empty();
    SummaryNode order = orderWithSource(sha256(ExcerptsTest.ORDER));
    new Summarizer(client("model-a", fake), store, anyCommit).summarize(List.of(order));
    SummaryNode later =
        new SummaryNode(
            order.entity(), order.level(), atCommit(order.facts(), "b".repeat(40)), List.of());

    Summarizer.Result second =
        new Summarizer(client("model-a", fake), store, anyCommit).summarize(List.of(later));

    assertThat(fake.sent()).hasSize(1);
    assertThat(second.reused()).isEqualTo(1);
    Fact kind = later.facts().stream().filter(f -> f.predicate().equals("kind")).findFirst().get();
    Summary summary = second.summaries().get(order.entity());
    assertThat(summary.text()).contains("[fact:" + kind.id() + "]");
    assertThat(summary.citedFacts())
        .containsExactlyInAnyOrderElementsOf(later.facts().stream().map(Fact::id).toList());
  }

  @Test
  void aChangedChildWhoseSummaryIsUnchangedLeavesItsParentAlone() {
    // The model writes the same text for PriceService whatever its facts.
    fake.answering(
        request ->
            unit(request).equals("type:com.example.shop.PriceService")
                ? "Prices orders. [F1]"
                : citingAll(request));
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
            child.purpose(),
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
            order.entity(),
            order.level(),
            atCommit(order.facts(), "b".repeat(40)),
            order.children());
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
    fake.answering("Order holds the lines of an order.");
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
    assertThat(retry.get(1)).isEqualTo(LlmMessage.assistant("Order holds the lines of an order."));
    assertThat(retry.get(2).text())
        .startsWith("Your summary was rejected: it cites no references.");
    assertThat(fake.sent().get(1).request().purpose()).endsWith("(retry)");
    assertThat(store.size()).isZero();
  }

  @Test
  void sentencesWithoutCitationsAreLeftOutWithoutAskingAgain() {
    fake.answering("Line is part of an order. Line holds a quantity. [F1] It is very important.");
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

    assertThat(summary.text()).isEqualTo("Line holds a quantity. [fact:" + fact + "]");
    assertThat(summary.purpose()).isEqualTo(Summary.Purpose.INFERRED);
    assertThat(fake.sent()).hasSize(1);
    assertThat(log.list)
        .extracting(ILoggingEvent::getFormattedMessage)
        .contains(
            "Left out 2 sentences without references from the answer to summarize type"
                + " type:p.Line: Line is part of an order. | It is very important.");
    // What is stored, and so reused, is the summary without them.
    assertThat(store.find(summary.key()).orElseThrow().text()).isEqualTo(summary.text());
    assertThat(summarizer().summarize(List.of(line)).summaries().get(line.entity()).text())
        .isEqualTo(summary.text());
    assertThat(fake.sent()).hasSize(1);
  }

  @Test
  void anUnknownReferenceNextToAnUncitedSentenceIsStillSentBack() {
    firstLineAnswer("It is a line. It is a record [F99].");

    Summarizer.Result result =
        summarizer().summarize(SummaryTree.fromFacts(TestFacts.shop().list()));

    assertThat(retried().messages().get(2).text())
        .startsWith("Your summary was rejected: it cites references that are not in the input:");
    assertThat(result.rejected()).isEmpty();
  }

  @Test
  void aMarkerInASentenceThatWouldBeLeftOutIsStillSentBack() {
    firstLineAnswer("It is a line [fact:abc]. It is a record [F1].");

    Summarizer.Result result =
        summarizer().summarize(SummaryTree.fromFacts(TestFacts.shop().list()));

    assertThat(retried().messages().get(2).text())
        .startsWith("Your summary was rejected: it contains [fact:...] or [summary:...] markers");
    assertThat(result.rejected()).isEmpty();
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
  void aUnitRejectedTwiceHasNoSummaryAndItsParentIsSummarizedWithoutIt() {
    fake.answering(
        request ->
            request.purpose().contains("type:com.example.shop.Order$Line")
                ? "Line is a line of an order."
                : citingAll(request));

    Summarizer.Result result =
        summarizer().summarize(SummaryTree.fromFacts(TestFacts.shop().list()));

    EntityId line = EntityId.parse("type:com.example.shop.Order$Line");
    assertThat(result.rejected()).containsExactly(line);
    assertThat(result.summaries()).doesNotContainKey(line).hasSize(6);
    assertThat(result.generated()).isEqualTo(6);
    assertThat(store.size()).isEqualTo(6);
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
                    .contains("it cites no references.")
                    .contains("The answer: Line is a line of an order."));
    assertThat(rejections.get(1)).contains("(retry)");

    // A rerun tries the unit again, and summarizes again what its new summary changes.
    fake.answering(SummarizerTest::citingAll);
    int before = fake.sent().size();
    Summarizer.Result rerun =
        summarizer().summarize(SummaryTree.fromFacts(TestFacts.shop().list()));

    assertThat(rerun.rejected()).isEmpty();
    assertThat(rerun.summaries()).hasSize(7);
    assertThat(fake.sent().subList(before, fake.sent().size()))
        .extracting(s -> unit(s.request()))
        .containsExactly(
            "type:com.example.shop.Order$Line",
            "type:com.example.shop.Order",
            "package:com.example:shop-core:com.example.shop",
            "module:com.example:shop-core",
            "module:com.example:shop",
            "repo:shop");
    assertThat(rerun.reused()).isEqualTo(1);
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
    fake.answering("It is a class.");
    List<SummaryNode> types = new ArrayList<>();
    for (int i = 0; i < Summarizer.EARLY_STOP; i++) {
      types.add(type("T" + i, List.of()));
    }

    assertThatThrownBy(() -> summarizer().summarize(types))
        .isInstanceOf(SummaryRejectedException.class)
        .hasMessageStartingWith("The first " + Summarizer.EARLY_STOP + " units were all rejected.")
        .hasMessageContaining("cites no references");
    assertThat(store.size()).isZero();
  }

  @Test
  void unitsRejectedAfterOneWasSummarizedDoNotStopTheRun() {
    fake.answering(
        request ->
            request.purpose().endsWith("type:p.Good") ? citingAll(request) : "It is a class.");
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
        request -> {
          if (request.purpose().endsWith("type:com.example.shop.PriceService")) {
            throw new LlmException("HTTP 400 bad request");
          }
          return request.purpose().contains("type:com.example.shop.Order$Line")
              ? "Line is a line of an order."
              : citingAll(request);
        });

    assertThatThrownBy(() -> summarizer().summarize(SummaryTree.fromFacts(TestFacts.shop().list())))
        .isInstanceOf(LlmException.class)
        .hasMessage("HTTP 400 bad request");
  }

  @Test
  void aLongRejectedAnswerIsLoggedTruncated() {
    // Short enough that the fake does not cut it off at the type's output limit.
    String answer = "x".repeat(1200);
    fake.answering(answer);
    SummaryNode line =
        new SummaryNode(
            EntityId.parse("type:p.Line"),
            SummaryLevel.TYPE,
            List.of(
                TestFacts.fact(
                    "r", "type:p.Line", "kind", FactObject.string("record"), "Line.java")),
            List.of());

    summarizer().summarize(List.of(line));

    assertThat(log.list)
        .filteredOn(e -> e.getFormattedMessage().startsWith("Rejected the answer"))
        .hasSize(2)
        .allSatisfy(
            e ->
                assertThat(e.getFormattedMessage())
                    .doesNotContain(answer)
                    .contains("x".repeat(1000) + "... (1200 characters)"));
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

    Summarizer.Result result =
        new Summarizer(client("model-a", provider), store)
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

  /** The shop's facts with a README whose first claim says what the shop is for. */
  private static List<Fact> shopWithReadme() {
    List<Fact> facts = new ArrayList<>(TestFacts.shop().list());
    facts.add(TestFacts.claim("shop", "README.md", 3, "Shop sells books to readers."));
    return facts;
  }

  /** Answers the repository with the given text, and other units well, without a purpose line. */
  private void repoAnswer(String text) {
    fake.answering(request -> request.purpose().endsWith(" repo:shop") ? text : citingAll(request));
  }

  @Test
  void aPurposeTakenFromTheReadmeIsEvidenced() {
    repoAnswer("Purpose: evidenced\nShop sells books to readers. [F1] It is built on [S1].");

    Summarizer.Result result = summarizer().summarize(SummaryTree.fromFacts(shopWithReadme()));

    Summary repo = result.summaries().get(EntityId.parse("repo:shop"));
    assertThat(repo.purpose()).isEqualTo(Summary.Purpose.EVIDENCED);
    assertThat(repo.text()).startsWith("Shop sells books to readers. [fact:");
    String prompt = fake.sent().getLast().request().messages().getFirst().text();
    assertThat(prompt)
        .contains("\nFacts:\n[F1] readme:shop:README.md claim \"Shop sells books to readers.\"\n");
    // Answers without a purpose line count as inferred.
    assertThat(result.summaries().get(EntityId.parse("module:com.example:shop")).purpose())
        .isEqualTo(Summary.Purpose.INFERRED);
  }

  @Test
  void aReusedSummaryKeepsItsPurpose() {
    repoAnswer("Purpose: evidenced\nShop sells books to readers. [F1] It is built on [S1].");
    summarizer().summarize(SummaryTree.fromFacts(shopWithReadme()));
    int calls = fake.sent().size();

    Summarizer.Result again = summarizer().summarize(SummaryTree.fromFacts(shopWithReadme()));

    assertThat(fake.sent()).hasSize(calls);
    assertThat(again.summaries().get(EntityId.parse("repo:shop")).purpose())
        .isEqualTo(Summary.Purpose.EVIDENCED);
  }

  @Test
  void anEvidencedPurposeWhoseFirstSentenceCitesNoEvidenceIsInferred() {
    // The module it cites was inferred, and the README claim is cited only later.
    repoAnswer("Purpose: evidenced\nShop sells books. [S1] Its README says so. [F1]");

    Summary repo =
        summarizer()
            .summarize(SummaryTree.fromFacts(shopWithReadme()))
            .summaries()
            .get(EntityId.parse("repo:shop"));

    assertThat(repo.purpose()).isEqualTo(Summary.Purpose.INFERRED);
    assertThat(repo.text()).startsWith("Shop sells books. [summary:");
  }

  @Test
  void anEvidencedPurposeWhoseFirstSentenceIsLeftOutIsInferred() {
    // The first sentence cites nothing, so the one now first is not the one the mark is about.
    repoAnswer("Purpose: evidenced\nShop is the best shop. Shop sells books to readers. [F1]");

    Summary repo =
        summarizer()
            .summarize(SummaryTree.fromFacts(shopWithReadme()))
            .summaries()
            .get(EntityId.parse("repo:shop"));

    assertThat(repo.purpose()).isEqualTo(Summary.Purpose.INFERRED);
    assertThat(repo.text()).startsWith("Shop sells books to readers. [fact:");
  }

  @Test
  void anInferredPurposeIsInferredThoughItCitesTheReadme() {
    repoAnswer("Purpose: inferred\nShop sells books to readers. [F1]");

    Summary repo =
        summarizer()
            .summarize(SummaryTree.fromFacts(shopWithReadme()))
            .summaries()
            .get(EntityId.parse("repo:shop"));

    assertThat(repo.purpose()).isEqualTo(Summary.Purpose.INFERRED);
  }

  @Test
  void aPurposeFromJavadocIsEvidencedAndAPartsEvidencedPurposeCarriesUp() throws Exception {
    SummaryNode order = orderWithSource(sha256(ExcerptsTest.ORDER));
    SummaryNode pkg =
        new SummaryNode(
            EntityId.parse("package:g:a:p"), SummaryLevel.PACKAGE, List.of(), List.of(order));
    fake.answering(
        request ->
            request.purpose().endsWith("type:p.Order")
                ? "Purpose: evidenced\nAn order a customer placed. [E1]"
                : "**Purpose: evidenced** Orders customers place. [S1]");

    Summarizer.Result result =
        new Summarizer(client("model-a", fake), store, ORDER_SOURCE).summarize(List.of(pkg));

    assertThat(result.summaries().get(order.entity()).purpose())
        .isEqualTo(Summary.Purpose.EVIDENCED);
    assertThat(result.summaries().get(pkg.entity()).purpose()).isEqualTo(Summary.Purpose.EVIDENCED);
    assertThat(result.summaries().get(pkg.entity()).text())
        .isEqualTo("Orders customers place. [summary:type:p.Order]");
    assertThat(fake.sent().getLast().request().messages().getFirst().text())
        .contains("[S1] type Order in package p (purpose evidenced): An order a customer placed.");
  }

  @Test
  void anEvidencedPurposeCitingOnlyCodeIsInferred() throws Exception {
    SummaryNode order = orderWithSource(sha256(ExcerptsTest.ORDER));
    fake.answering(request -> "Purpose: evidenced\nAn order. [F1]");

    Summary summary =
        new Summarizer(client("model-a", fake), store, ORDER_SOURCE)
            .summarize(List.of(order))
            .summaries()
            .get(order.entity());

    assertThat(summary.purpose()).isEqualTo(Summary.Purpose.INFERRED);
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
    SummaryNode order = orderWithSource(sha256(source));
    SourceFiles files =
        (repo, commit, path) -> Optional.of(source.getBytes(StandardCharsets.UTF_8));

    new Summarizer(client("model-a", fake), store, files).summarize(List.of(order));

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

  @Test
  void aMergeIsShownThePartsMarksAndKeepsAnEvidencedPurpose() {
    SummaryNode large = largeType(400);
    List<Fact> facts = new ArrayList<>(large.facts());
    facts.add(TestFacts.claim("r", "README.md", 1, "Big does everything."));
    SummaryNode big = new SummaryNode(large.entity(), large.level(), facts, List.of());
    fake.answering(request -> "Purpose: evidenced\n" + citingWhatItSees(request, 0));

    Summary summary =
        new Summarizer(smallWindowClient(4_000), store)
            .summarize(List.of(big))
            .summaries()
            .get(big.entity());

    // Only the first part cites the README claim [F1] in its first sentence.
    String merge = fake.sent().getLast().request().messages().getFirst().text();
    assertThat(fake.sent().getLast().request().purpose()).endsWith("(merge)");
    assertThat(merge).contains("\nPart 1 (purpose evidenced): ", "\nPart 2 (purpose inferred): ");
    assertThat(summary.purpose()).isEqualTo(Summary.Purpose.EVIDENCED);
  }

  @Test
  void partsAndTheMergeLeaveOutSentencesWithoutCitations() {
    SummaryNode big = largeType(400);
    fake.answering(request -> "It is big. " + citingWhatItSees(request, 0));

    Summary summary =
        new Summarizer(smallWindowClient(4_000), store)
            .summarize(List.of(big))
            .summaries()
            .get(big.entity());

    String merge = fake.sent().getLast().request().messages().getFirst().text();
    assertThat(fake.sent().getLast().request().purpose()).endsWith("(merge)");
    assertThat(merge).contains("Part of it. [").doesNotContain("It is big.");
    assertThat(summary.text()).startsWith("Part of it. [fact:").doesNotContain("It is big.");
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
        request -> {
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
          return "Part of it. [" + String.join(", ", refs) + "]";
        });

    Summary summary =
        new Summarizer(smallWindowClient(4_000), store)
            .summarize(List.of(module))
            .summaries()
            .get(module.entity());

    List<String> prompts =
        fake.sent().stream()
            .filter(s -> s.request().purpose().startsWith("summarize module"))
            .map(s -> s.request().messages().getFirst().text())
            .toList();
    assertThat(prompts.stream().filter(p -> p.contains("This is part "))).hasSizeGreaterThan(1);
    assertThat(prompts).anyMatch(p -> p.contains("\nEntry points (10 of 12):\n[F201] "));
    for (String prompt : prompts) {
      assertThat(Tokens.estimate(Prompt.SYSTEM + prompt)).isLessThanOrEqualTo(4_000);
    }
    List<String> sheet = FactSheet.facts(FactSheet.of(module)).stream().map(Fact::id).toList();
    assertThat(sheet).hasSize(10);
    assertThat(summary.citedFacts()).containsAll(sheet);
    assertThat(summary.inputFacts()).endsWith(sheet.toArray(String[]::new));
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
  void aSplitUnitWhosePartIsRejectedTwiceHasNoSummary() {
    SummaryNode big = largeType(400);
    fake.answering(
        request ->
            request.purpose().contains("(part 1 of ")
                ? "It has many methods."
                : citingWhatItSees(request, 0));

    Summarizer.Result result =
        new Summarizer(smallWindowClient(4_000), store).summarize(List.of(big));

    assertThat(result.summaries()).isEmpty();
    assertThat(result.rejected()).containsExactly(big.entity());
    assertThat(store.size()).isZero();
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
  void partsAndTheMergeMayCiteRanges() {
    SummaryNode big = largeType(400);
    Pattern range = Pattern.compile("\\[(F\\d+-F\\d+)\\]");
    fake.answering(
        request -> {
          String prompt = request.messages().getFirst().text();
          if (prompt.contains("Summaries of the parts:")) {
            List<String> ranges = new ArrayList<>();
            Matcher matcher = range.matcher(prompt);
            while (matcher.find()) {
              ranges.add(matcher.group(1));
            }
            return "All of it. [" + String.join(", ", ranges) + "]";
          }
          List<String> refs = new ArrayList<>();
          Matcher ref = REF.matcher(prompt);
          while (ref.find()) {
            refs.add(ref.group(1));
          }
          return "Part of it. [" + refs.getFirst() + "-" + refs.getLast() + "]";
        });

    Summary summary =
        new Summarizer(smallWindowClient(4_000), store)
            .summarize(List.of(big))
            .summaries()
            .get(big.entity());

    assertThat(fake.sent()).noneMatch(s -> s.request().purpose().endsWith("(retry)"));
    assertThat(fake.sent().getLast().request().purpose())
        .isEqualTo("summarize type type:p.Big (merge)");
    assertThat(summary.citedFacts()).containsExactlyInAnyOrderElementsOf(summary.inputFacts());
    assertThat(summary.text()).startsWith("All of it. [fact:").doesNotContain("-F");
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

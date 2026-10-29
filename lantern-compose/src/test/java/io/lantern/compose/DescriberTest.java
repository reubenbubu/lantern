package io.lantern.compose;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.document.Citation;
import io.lantern.core.document.Diagram;
import io.lantern.core.document.Document;
import io.lantern.core.document.DocumentEntity;
import io.lantern.core.document.Inline;
import io.lantern.core.document.Section;
import io.lantern.core.document.SequenceDiagram;
import io.lantern.core.document.TemplateRef;
import io.lantern.core.document.TextRun;
import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.snapshot.Snapshot;
import io.lantern.llm.BudgetGuard;
import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.InMemoryLlmCallLog;
import io.lantern.llm.LlmClient;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.ModelSettings;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The LLM's responsibility sentences and intent phrases, and how documents use them (ADR 0050). */
class DescriberTest {

  private static final TemplateRef TEMPLATE = new TemplateRef("c4-model-sequence-diagrams", "1");
  private static final Snapshot SNAPSHOT =
      new Snapshot(
          2, Map.of("shop", TestFacts.COMMIT, "base", TestFacts.COMMIT), 5, "0.1.0", Instant.EPOCH);
  private static final EntityId APP = EntityId.parse("service:com.example:app");
  private static final EntityId WORKER = EntityId.parse("service:com.example:worker");

  private final TestFacts facts = TestFacts.runtime();
  private final InMemoryWordingStore store = new InMemoryWordingStore();

  /**
   * Words each unit, its components and relationships from what its prompt shows: the shop app's
   * components and its send, the worker's call, each citing the first fact.
   */
  private static String answer(LlmRequest request) {
    String prompt = request.messages().getFirst().text();
    if (prompt.startsWith("The deployable unit shop-app")) {
      return """
          {"unit": {"text": "Takes and lists the shop's orders.", "cites": ["F1", "F2"]},
           "components": {"C1": {"text": "Lists orders for the web shop.", "cites": ["F5"]}},
           "relationships": {"R1": {"text": "Charges customers' cards", "cites": ["F%s"]},
                             "R2": {"text": "Announces new orders.", "cites": ["F%s"]}}}
          """
          .formatted(ref(prompt, "CALLS_HTTP"), ref(prompt, "PUBLISHES"));
    }
    return """
        {"unit": {"text": "Processes orders in the background.", "cites": ["F1"]},
         "relationships": {"R1": {"text": "Fetches the order list", "cites": ["F%s"]}}}
        """
        .formatted(ref(prompt, "CALLS_HTTP"));
  }

  /** The number of the reference of the first fact line in the prompt with the predicate. */
  private static String ref(String prompt, String predicate) {
    for (String line : prompt.split("\n")) {
      if (line.contains(" " + predicate + " ") && line.strip().startsWith("[F")) {
        String stripped = line.strip();
        return stripped.substring(2, stripped.indexOf(']'));
      }
    }
    throw new IllegalStateException("No " + predicate + " in the prompt:\n" + prompt);
  }

  private static LlmClient client(FakeLlmProvider fake) {
    return new LlmClient(
        ModelSettings.single(fake.model("fake-model")),
        List.of(fake),
        BudgetGuard.unlimited("test"),
        new InMemoryLlmCallLog(),
        Clock.systemUTC());
  }

  private Describer.Result describe(FakeLlmProvider fake) {
    return new Describer(client(fake), store).describe(facts.list());
  }

  private Document system(Wordings wordings) {
    return new Composer("1")
        .compose(
            ViewDefinition.c4System("system:c4", "System", TEMPLATE),
            SNAPSHOT,
            facts.list(),
            Map.of(),
            wordings);
  }

  private static String text(List<Inline> content) {
    return content.stream()
        .map(i -> i instanceof TextRun t ? t.text() : "")
        .collect(Collectors.joining());
  }

  private static Stream<Section> all(List<Section> sections) {
    return sections.stream().flatMap(s -> Stream.concat(Stream.of(s), all(s.sections())));
  }

  private static List<Diagram> diagrams(Document document, Diagram.Kind kind) {
    return all(document.sections())
        .flatMap(s -> s.blocks().stream())
        .filter(b -> b instanceof Diagram d && d.diagramKind() == kind)
        .map(Diagram.class::cast)
        .toList();
  }

  private static List<String> edges(Diagram diagram) {
    return diagram.edges().stream()
        .map(e -> e.from() + " -> " + e.to() + ": " + e.label())
        .toList();
  }

  @Test
  void asksOncePerUnitShowingOnlyFactsAndTheirReferences() {
    FakeLlmProvider fake = new FakeLlmProvider().answering(DescriberTest::answer);

    Describer.Result result = describe(fake);

    assertThat(result.asked()).isEqualTo(2);
    assertThat(result.reused()).isZero();
    assertThat(result.rejected()).isEmpty();
    assertThat(result.skipped()).isEmpty();
    assertThat(fake.sent())
        .extracting(s -> s.request().role() + " " + s.request().purpose())
        .containsExactlyInAnyOrder(
            "INFER_INTENT word unit " + APP, "INFER_INTENT word unit " + WORKER);
    String app =
        fake.sent().stream()
            .map(s -> s.request().messages().getFirst().text())
            .filter(p -> p.startsWith("The deployable unit shop-app"))
            .findFirst()
            .orElseThrow();
    assertThat(app)
        .startsWith(
            "The deployable unit shop-app, built with Spring Boot 3.5.0 / Java 21.\n\n"
                + "Facts about the unit:\n"
                + "[F1] service:com.example:app application-name \"shop-app\" (shop:pom.xml)\n"
                + "[F2] service:com.example:app deployable-unit \"spring-boot\" (shop:pom.xml)\n")
        .contains("\nComponents:\n[C1] Controller, Spring MVC REST controller\n")
        .contains("\n[C2] Jobs, Scheduled job\n")
        .contains("\n[C3] JpaOrders, Spring repository\n")
        .contains("\nRelationships:\n[R1] calls pay.example.com (an external system)\n")
        .contains("[R2] sends messages to orders (a topic)\n")
        .contains("[R3] connects to shop on db (a database)\n")
        // No fact IDs: they change with every commit.
        .doesNotContain(facts.find(APP.toString(), "CALLS_HTTP").id());
  }

  @Test
  void documentsUseTheSentencesAsDescriptionsAndThePhrasesAsLabels() {
    Wordings wordings = describe(new FakeLlmProvider().answering(DescriberTest::answer)).wordings();

    Document document = system(wordings);

    DocumentEntity app =
        document.entities().stream().filter(e -> e.id().equals(APP)).findFirst().orElseThrow();
    assertThat(text(app.description())).isEqualTo("Takes and lists the shop's orders.");
    assertThat(app.description().getLast())
        .isEqualTo(
            new Citation(
                List.of(
                        facts.find(APP.toString(), "deployable-unit").id(),
                        facts.find(APP.toString(), "application-name").id())
                    .stream()
                    .sorted()
                    .toList()));
    DocumentEntity controller =
        document.entities().stream()
            .filter(e -> e.id().toString().equals("type:com.example.web.Controller"))
            .findFirst()
            .orElseThrow();
    assertThat(text(controller.description())).isEqualTo("Lists orders for the web shop.");

    Diagram containers = diagrams(document, Diagram.Kind.C4_CONTAINER).getFirst();
    assertThat(edges(containers))
        .contains(
            APP + " -> external-system:pay.example.com: Charges customers' cards",
            APP + " -> topic:kafka:orders: Announces new orders",
            WORKER + " -> " + APP + ": Fetches the order list",
            // Not worded: the static label stays.
            APP + " -> database:db:5432/shop: Stores its data in",
            "topic:kafka:orders -> " + WORKER + ": Delivers messages");
    Diagram context = diagrams(document, Diagram.Kind.C4_CONTEXT).getFirst();
    assertThat(edges(context))
        .containsExactly(
            "system:snapshot -> external-system:pay.example.com: Charges customers' cards");
    // The components' lines to other containers carry the phrase too; calls between components
    // keep theirs.
    Diagram components = diagrams(document, Diagram.Kind.C4_COMPONENT).getFirst();
    assertThat(edges(components))
        .contains(
            "type:com.example.web.Controller -> external-system:pay.example.com: Charges"
                + " customers' cards");

    List<String> messages =
        all(document.sections())
            .flatMap(s -> s.blocks().stream())
            .filter(SequenceDiagram.class::isInstance)
            .map(SequenceDiagram.class::cast)
            .flatMap(d -> d.steps().stream())
            .filter(SequenceDiagram.Message.class::isInstance)
            .map(SequenceDiagram.Message.class::cast)
            .map(m -> m.label() + (m.code() == null ? "" : " [" + m.code() + "]"))
            .toList();
    assertThat(messages).contains("Announces new orders [Kafka]");
  }

  @Test
  void aSecondRunOverTheSameFactsMakesNoCall() {
    FakeLlmProvider fake = new FakeLlmProvider().answering(DescriberTest::answer);
    Wordings first = describe(fake).wordings();
    int calls = fake.sent().size();

    Describer.Result second = describe(fake);

    assertThat(fake.sent()).hasSize(calls);
    assertThat(second.asked()).isZero();
    assertThat(second.reused()).isEqualTo(2);
    assertThat(second.wordings()).isEqualTo(first);
  }

  @Test
  void anAnswerRejectedTwiceLeavesTheUnitAsItWasAndCachesNothing() {
    FakeLlmProvider fake =
        new FakeLlmProvider()
            .answering(
                request ->
                    """
                    {"unit": {"text": "Uses things.", "cites": ["F99"]}}""");

    Describer.Result result = describe(fake);

    assertThat(fake.sent()).hasSize(4);
    assertThat(fake.sent())
        .extracting(s -> s.request().purpose())
        .contains("word unit " + APP + " (retry)");
    assertThat(fake.sent())
        .filteredOn(s -> s.request().purpose().endsWith("(retry)"))
        .allSatisfy(
            s ->
                assertThat(s.request().messages().getLast().text())
                    .startsWith(
                        "Your answer was rejected: the unit cites F99, which is not a fact in the"
                            + " input."));
    assertThat(result.rejected()).containsExactlyInAnyOrder(APP, WORKER);
    assertThat(result.wordings()).isEqualTo(Wordings.NONE);
    assertThat(store.size()).isZero();
    // The documents keep summaries and static labels.
    assertThat(edges(diagrams(system(result.wordings()), Diagram.Kind.C4_CONTEXT).getFirst()))
        .containsExactly("system:snapshot -> external-system:pay.example.com: Calls POST /charges");
  }

  @Test
  void aFailingProviderSkipsTheUnitWithoutFailingTheRun() {
    FakeLlmProvider fake =
        new FakeLlmProvider()
            .answering(
                (Function<LlmRequest, String>)
                    request -> {
                      throw new io.lantern.llm.LlmException("down");
                    });

    Describer.Result result = describe(fake);

    assertThat(result.skipped()).containsExactlyInAnyOrder(APP, WORKER);
    assertThat(result.wordings()).isEqualTo(Wordings.NONE);
  }

  @Test
  void aCitationOfAFactNotInTheDocumentsFactsIsLeftOutAndWithoutAnyTheSummaryStays() {
    Fact deployable = facts.find(APP.toString(), "deployable-unit");
    Wordings wordings =
        new Wordings(
            Map.of(APP, "Takes orders. [fact:" + "f".repeat(64) + "]"),
            Map.of(
                facts.find(APP.toString(), "PUBLISHES").id(),
                new Wordings.Phrase("Announces orders", List.of("e".repeat(64)))));

    Document document = system(wordings);

    DocumentEntity app =
        document.entities().stream().filter(e -> e.id().equals(APP)).findFirst().orElseThrow();
    assertThat(app.description()).isEmpty();
    Diagram containers = diagrams(document, Diagram.Kind.C4_CONTAINER).getFirst();
    Diagram.Edge send =
        containers.edges().stream()
            .filter(e -> e.to().toString().equals("topic:kafka:orders") && e.from().equals(APP))
            .findFirst()
            .orElseThrow();
    assertThat(send.label()).isEqualTo("Announces orders");
    assertThat(send.facts()).doesNotContain("e".repeat(64));
    assertThat(deployable).isNotNull();
  }

  @Test
  void aUnitWithNothingToWordIsNotAskedAbout() {
    TestFacts bare =
        new TestFacts()
            .attribute("shop", "service:g:lonely", "deployable-unit", "spring-boot", "{}");
    FakeLlmProvider fake = new FakeLlmProvider().answering(DescriberTest::answer);

    Describer.Result result = new Describer(client(fake), store).describe(bare.list());

    assertThat(fake.sent()).isEmpty();
    assertThat(result.asked()).isZero();
    assertThat(new ArrayList<>(result.wordings().responsibilities().keySet())).isEmpty();
  }
}

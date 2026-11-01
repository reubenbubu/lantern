package io.lantern.compose;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.document.Callout;
import io.lantern.core.document.Citation;
import io.lantern.core.document.Diagram;
import io.lantern.core.document.Document;
import io.lantern.core.document.DocumentEntity;
import io.lantern.core.document.Inline;
import io.lantern.core.document.Paragraph;
import io.lantern.core.document.Section;
import io.lantern.core.document.SequenceDiagram;
import io.lantern.core.document.Table;
import io.lantern.core.document.TemplateRef;
import io.lantern.core.document.TextRun;
import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.snapshot.Snapshot;
import io.lantern.harness.BlobLookup;
import io.lantern.harness.Harness;
import io.lantern.harness.InMemoryAnswerStore;
import io.lantern.harness.RunBudget;
import io.lantern.llm.BudgetGuard;
import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.InMemoryLlmCallLog;
import io.lantern.llm.LlmClient;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.ModelSettings;
import io.lantern.llm.Role;
import io.lantern.summarize.SourceFiles;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The LLM's responsibility sentences, intent phrases and flow narration, and how documents use them
 * (ADR 0050, ADR 0056, ADR 0058, ADR 0069).
 */
class DescriberTest {

  private static final TemplateRef TEMPLATE = new TemplateRef("c4-model-sequence-diagrams", "1");
  private static final Snapshot SNAPSHOT =
      new Snapshot(
          2, Map.of("shop", TestFacts.COMMIT, "base", TestFacts.COMMIT), 5, "0.1.0", Instant.EPOCH);
  private static final EntityId APP = EntityId.parse("service:com.example:app");
  private static final EntityId WORKER = EntityId.parse("service:com.example:worker");
  private static final EntityId DATABASE = EntityId.parse("database:db:5432/shop");
  private static final EntityId TOPIC = EntityId.parse("topic:kafka:orders");
  private static final EntityId PAY = EntityId.parse("external-system:pay.example.com");
  private static final EntityId LIST = EntityId.parse("method:com.example.web.Controller#list()");
  private static final EntityId RUN = EntityId.parse("method:com.example.web.Jobs#run()");
  private static final String LIST_FLOW = "GET /orders on shop-app";
  private static final String RUN_FLOW = "Jobs.run on its schedule in shop-app";

  private final TestFacts facts = TestFacts.runtime();
  private final InMemoryAnswerStore store = new InMemoryAnswerStore();

  /** A harness with the test's answer cache, so later runs reuse earlier answers. */
  private Harness harness(LlmClient client) {
    return new Harness(client, store, RunBudget.NONE, BlobLookup.NONE, Set.of());
  }

  /**
   * Words each unit, its components and relationships from what its prompt shows: the shop app's
   * components and its send, the worker's call, each citing the first fact; and the database and
   * the topic, citing their uses, leaving the external system out; and each flow's title,
   * walkthrough and post-conditions, citing its entry point and send, leaving its pre-conditions
   * out; and the system's purpose, citing its README's first claim where it shows one, else the
   * first fact.
   */
  private static String answer(LlmRequest request) {
    String prompt = request.messages().getFirst().text();
    if (prompt.startsWith("The software system")) {
      return """
          {"purpose": {"text": "Runs an online shop's orders.", "cites": ["F%s"]}}"""
          .formatted(prompt.contains(" claim ") ? ref(prompt, "claim") : "1");
    }
    if (prompt.startsWith("The flow")) {
      return """
          {"title": {"text": "Shop lists its orders.", "cites": ["F1"]},
           "walkthrough": {"text": "Lists the orders and announces them for background work.",
                           "cites": ["F1", "F%1$s"]},
           "postconditions": {"text": "The orders are announced.", "cites": ["F%1$s"]}}"""
          .formatted(ref(prompt, "PUBLISHES"));
    }
    if (prompt.startsWith("The database")) {
      return """
          {"element": {"text": "Keeps the shop's orders.", "cites": ["F%s"]}}"""
          .formatted(ref(prompt, "USES_DATABASE"));
    }
    if (prompt.startsWith("The topic")) {
      return """
          {"element": {"text": "Carries new orders to background processing.",
                       "cites": ["F%s", "F%s"]}}"""
          .formatted(ref(prompt, "PUBLISHES"), ref(prompt, "CONSUMES"));
    }
    if (prompt.startsWith("The external system")) {
      return "{}";
    }
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
    return new Describer(harness(client(fake))).describe(facts.list());
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

    assertThat(result.asked()).isEqualTo(8);
    assertThat(result.reused()).isZero();
    assertThat(result.rejected()).isEmpty();
    assertThat(result.skipped()).isEmpty();
    assertThat(fake.sent())
        .extracting(s -> s.request().role() + " " + s.request().purpose())
        .containsExactlyInAnyOrder(
            "INFER_INTENT word unit " + APP,
            "INFER_INTENT word unit " + WORKER,
            "INFER_INTENT word element " + DATABASE,
            "INFER_INTENT word element " + TOPIC,
            "INFER_INTENT word element " + PAY,
            "COMPOSE narrate flow " + LIST_FLOW,
            "COMPOSE narrate flow " + RUN_FLOW,
            "INFER_INTENT word the system's purpose");
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
  void asksOncePerDataStoreAndDestinationShowingItsFactsTablesAndUses() {
    TestFacts withTables =
        TestFacts.runtime()
            .relate("shop", "schema:com.example:app", "CONTAINS", "table:com.example:app:orders");
    FakeLlmProvider fake = new FakeLlmProvider().answering(DescriberTest::answer);

    new Describer(harness(client(fake))).describe(withTables.list());

    Function<String, LlmRequest> request =
        purpose ->
            fake.sent().stream()
                .map(FakeLlmProvider.Sent::request)
                .filter(r -> r.purpose().equals(purpose))
                .findFirst()
                .orElseThrow();
    LlmRequest database = request.apply("word element " + DATABASE);
    assertThat(database.system()).isEqualTo(WordingPrompt.ELEMENT_SYSTEM);
    assertThat(database.maxOutputTokens()).isEqualTo(Describer.MAX_ELEMENT_OUTPUT_TOKENS);
    assertThat(database.messages().getFirst().text())
        .isEqualTo(
            """
            The database shop on db, PostgreSQL.

            Facts about it:
            [F1] database:db:5432/shop technology "PostgreSQL" (shop:pom.xml)

            Tables of the schemas built in it:
            [F2] schema:com.example:app CONTAINS table:com.example:app:orders (shop:pom.xml)

            How it is used:
            shop-app (a deployable unit) connects to it:
              [F3] service:com.example:app USES_DATABASE database:db:5432/shop\
             {"protocol":"JDBC","schemas":["schema:com.example:app"]} (shop:pom.xml)
            """);
    assertThat(request.apply("word element " + TOPIC).messages().getFirst().text())
        .startsWith("The topic orders, Kafka topic.\n\nHow it is used:\n")
        .contains("\nshop-app (a deployable unit) sends messages to it:\n  [F1] ")
        .contains("\nworker (a deployable unit) receives messages from it:\n  [F2] ");
    assertThat(request.apply("word element " + PAY).messages().getFirst().text())
        .startsWith(
            "The external system pay.example.com.\n\nHow it is used:\n"
                + "shop-app (a deployable unit) calls it:\n");
  }

  @Test
  void anExchangeAndQueueJoinedOnlyByARouteAreNotAskedAbout() {
    TestFacts routed =
        TestFacts.runtime()
            .relate(
                "shop",
                "exchange:rabbitmq:audit",
                "ROUTES_TO",
                "queue:rabbitmq:audit.log",
                "{\"protocol\": \"AMQP\", \"bindingKey\": \"#\"}");
    FakeLlmProvider fake = new FakeLlmProvider().answering(DescriberTest::answer);

    Describer.Result result = new Describer(harness(client(fake))).describe(routed.list());

    assertThat(result.asked()).isEqualTo(8);
    assertThat(fake.sent())
        .extracting(s -> s.request().purpose())
        .noneMatch(p -> p.contains("audit"));
  }

  @Test
  void aSentenceTheSupportJudgeFoundUnsupportedIsMarkedAndKept() {
    Wordings wordings =
        describe(new FakeLlmProvider().answering(DescriberTest::answer))
            .wordings()
            .withUnsupported(Set.of(APP));

    Document document = system(wordings);

    DocumentEntity app =
        document.entities().stream().filter(e -> e.id().equals(APP)).findFirst().orElseThrow();
    assertThat(app.description().getFirst())
        .isEqualTo(new TextRun(Assembler.UNSUPPORTED, TextRun.Style.EMPHASIS));
    assertThat(text(app.description()))
        .isEqualTo(
            Assembler.UNSUPPORTED
                + " "
                + Assembler.INFERRED_SENTENCE
                + " Takes and lists the shop's orders.");
    DocumentEntity database =
        document.entities().stream().filter(e -> e.id().equals(DATABASE)).findFirst().orElseThrow();
    assertThat(text(database.description()))
        .isEqualTo(Assembler.INFERRED_SENTENCE + " Keeps the shop's orders.");
  }

  @Test
  void aSentenceTheJudgeChecksAndFindsUnsupportedReachesTheDocumentWithItsNote() {
    // Describe writes its sentences; the judge, told to check the shop app's, finds no cited
    // input supports it.
    Pattern item = Pattern.compile("<item id=\"(I[0-9]+)\">\\n(.*?)\\n</item>", Pattern.DOTALL);
    FakeLlmProvider fake =
        new FakeLlmProvider()
            .answering(
                request -> {
                  if (request.role() != Role.JUDGE) {
                    return answer(request);
                  }
                  Matcher items = item.matcher(request.messages().getFirst().text());
                  List<String> entries = new ArrayList<>();
                  while (items.find()) {
                    String verdict =
                        items.group(2).contains("Takes and lists the shop's orders.")
                            ? "unsupported"
                            : "supported";
                    entries.add(
                        "\"%s\": {\"claims\": [{\"claim\": \"It takes orders.\", \"verdict\": \"%s\"}]}"
                            .formatted(items.group(1), verdict));
                  }
                  return "{\"items\": {" + String.join(", ", entries) + "}}";
                });

    Describer.Result result =
        new Describer(harness(client(fake))).alsoJudging(Set.of(APP)).describe(facts.list());
    Document document = system(result.wordings());

    LlmRequest judged =
        fake.sent().stream()
            .map(FakeLlmProvider.Sent::request)
            .filter(r -> r.role() == Role.JUDGE)
            .findFirst()
            .orElseThrow();
    assertThat(judged.messages().getFirst().text())
        .contains("<text>Takes and lists the shop's orders.</text>")
        .contains("[F1] service:com.example:app application-name \"shop-app\"");
    assertThat(result.wordings().unsupported()).containsExactly(APP);
    DocumentEntity app =
        document.entities().stream().filter(e -> e.id().equals(APP)).findFirst().orElseThrow();
    assertThat(app.description().getFirst())
        .isEqualTo(new TextRun(Assembler.UNSUPPORTED, TextRun.Style.EMPHASIS));
    assertThat(text(app.description()))
        .isEqualTo(
            Assembler.UNSUPPORTED
                + " "
                + Assembler.INFERRED_SENTENCE
                + " Takes and lists the shop's orders.");
  }

  @Test
  void documentsUseTheSentencesAsDescriptionsAndThePhrasesAsLabels() {
    Wordings wordings = describe(new FakeLlmProvider().answering(DescriberTest::answer)).wordings();

    Document document = system(wordings);

    DocumentEntity app =
        document.entities().stream().filter(e -> e.id().equals(APP)).findFirst().orElseThrow();
    // Citing no README claim, the sentences are marked as inferred (ADR 0065).
    assertThat(app.description().getFirst())
        .isEqualTo(new TextRun(Assembler.INFERRED_SENTENCE, TextRun.Style.EMPHASIS));
    assertThat(text(app.description()))
        .isEqualTo(Assembler.INFERRED_SENTENCE + " Takes and lists the shop's orders.");
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
    assertThat(text(controller.description()))
        .isEqualTo(Assembler.INFERRED_SENTENCE + " Lists orders for the web shop.");
    DocumentEntity database =
        document.entities().stream().filter(e -> e.id().equals(DATABASE)).findFirst().orElseThrow();
    assertThat(text(database.description()))
        .isEqualTo(Assembler.INFERRED_SENTENCE + " Keeps the shop's orders.");
    assertThat(database.description().getLast())
        .isEqualTo(new Citation(List.of(facts.find(APP.toString(), "USES_DATABASE").id())));
    DocumentEntity topic =
        document.entities().stream().filter(e -> e.id().equals(TOPIC)).findFirst().orElseThrow();
    assertThat(text(topic.description()))
        .isEqualTo(Assembler.INFERRED_SENTENCE + " Carries new orders to background processing.");
    // Left out by the model: no description, as before.
    assertThat(
            document.entities().stream()
                .filter(e -> e.id().equals(PAY))
                .findFirst()
                .orElseThrow()
                .description())
        .isEmpty();

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

  private static Section flowSection(Document document, String title) {
    // A flow's title names its scenario, then its trigger in brackets.
    return all(document.sections())
        .filter(s -> s.title().equals(title) || s.title().endsWith(" (" + title + ")"))
        .findFirst()
        .orElseThrow();
  }

  private static Table.Row overview(Section flow) {
    return flow.blocks().stream()
        .filter(Table.class::isInstance)
        .map(Table.class::cast)
        .findFirst()
        .orElseThrow()
        .rows()
        .getFirst();
  }

  @Test
  void eachFlowIsNarratedFromWhatStartsItAndTheFactsOfItsSteps() {
    FakeLlmProvider fake = new FakeLlmProvider().answering(DescriberTest::answer);

    describe(fake);

    LlmRequest list =
        fake.sent().stream()
            .map(FakeLlmProvider.Sent::request)
            .filter(r -> r.purpose().equals("narrate flow " + LIST_FLOW))
            .findFirst()
            .orElseThrow();
    assertThat(list.system()).isEqualTo(WordingPrompt.FLOW_SYSTEM);
    assertThat(list.maxOutputTokens()).isEqualTo(Describer.MAX_FLOW_OUTPUT_TOKENS);
    // The steps as the facts give them: no intent phrases, no fact IDs.
    assertThat(list.messages().getFirst().text())
        .isEqualTo(
            """
            The flow "GET /orders on shop-app".
            It starts when shop-app receives GET /orders (Controller.list).
            [F1] method:com.example.web.Controller#list() endpoint "GET /orders" (shop:pom.xml)

            Its steps, in order:
            - shop-app to orders (a topic), async: Sends a message [Kafka]
              [F2] service:com.example:app PUBLISHES topic:kafka:orders\
             {"protocol":"Kafka","source":"method:com.example.domain.JpaOrders#all()"} (shop:pom.xml)
            - Note: shop-app does not wait: worker takes the message later.
              [F3] service:com.example:app SENDS_MESSAGES_TO service:com.example:worker\
             {"destination":"topic:kafka:orders","protocol":"Kafka",\
            "source":"method:com.example.domain.JpaOrders#all()",\
            "target":"method:com.example.worker.L#on()"} (shop:pom.xml)
            - shop-app to shop on db (a database), sync: Reads orders [JDBC]
              [F4] method:com.example.domain.JpaOrders#all() READS_TABLE\
             table:com.example:app:orders (shop:pom.xml)
              [F5] service:com.example:app USES_DATABASE database:db:5432/shop\
             {"protocol":"JDBC","schemas":["schema:com.example:app"]} (shop:pom.xml)
            - shop-app to pay.example.com (an external system), sync: Calls pay.example.com\
             [POST /charges]. Over HTTPS. Only under the Spring profile prod.
              [F6] service:com.example:app CALLS_HTTP external-system:pay.example.com\
             {"httpMethod":"POST","path":"/charges","profile":"prod","protocol":"HTTPS",\
            "source":"method:com.example.web.Controller#list()"} (shop:pom.xml)
            - orders to worker (a deployable unit), async: Delivers to L.on [Kafka]
              [F3] service:com.example:app SENDS_MESSAGES_TO service:com.example:worker\
             {"destination":"topic:kafka:orders","protocol":"Kafka",\
            "source":"method:com.example.domain.JpaOrders#all()",\
            "target":"method:com.example.worker.L#on()"} (shop:pom.xml)
            """);
  }

  @Test
  void documentsUseTheWalkthroughAndConditionsOfAFlowTracedAsTheModelSawIt() {
    Wordings wordings = describe(new FakeLlmProvider().answering(DescriberTest::answer)).wordings();

    Document document = system(wordings);

    Section flow = flowSection(document, LIST_FLOW);
    // The model's title names the scenario, before the trigger (ADR 0047, amendment for #272).
    assertThat(flow.title()).isEqualTo("Shop lists its orders (" + LIST_FLOW + ")");
    Paragraph prose = (Paragraph) flow.blocks().getFirst();
    String endpoint = facts.find(LIST.toString(), "endpoint").id();
    String send = facts.find(APP.toString(), "PUBLISHES").id();
    assertThat(text(prose.content()))
        .isEqualTo("Lists the orders and announces them for background work.");
    assertThat(prose.content().getLast())
        .isEqualTo(new Citation(Stream.of(endpoint, send).sorted().toList()));
    assertThat(prose.facts()).contains(endpoint, send);
    Table.Row row = overview(flow);
    // Left out by the model: not known, as before.
    assertThat(text(row.cells().get(1))).isEqualTo("Not known from the code.");
    assertThat(text(row.cells().get(2))).isEqualTo("The orders are announced.");
    assertThat(row.cells().get(2).getLast()).isEqualTo(new Citation(List.of(send)));
    assertThat(row.facts()).contains(endpoint, send);
    assertThat(text(((Paragraph) flowSection(document, RUN_FLOW).blocks().getFirst()).content()))
        .isEqualTo("Lists the orders and announces them for background work.");
  }

  @Test
  void aRepositoryDrawsAStoredFlowWholeOrNotAtAll() {
    // The repository's documents leave out the worker's listener, which the stored flow rests on
    // (#618): they cannot cite it, so they do not draw the flow, and no prompt asks about a flow
    // traced from their facts alone.
    List<Fact> repoFacts =
        facts.list().stream().filter(f -> !f.predicate().equals("SENDS_MESSAGES_TO")).toList();
    FakeLlmProvider fake = new FakeLlmProvider().answering(DescriberTest::answer);
    Wordings both =
        new Describer(harness(client(fake))).describe(facts.list(), repoFacts).wordings();

    Document repo =
        new Composer("1")
            .compose(
                ViewDefinition.c4Report("shop.c4", "shop", TEMPLATE),
                SNAPSHOT,
                repoFacts,
                Map.of(),
                both);
    assertThat(all(repo.sections())).noneMatch(s -> s.title().endsWith(" (" + LIST_FLOW + ")"));
    assertThat(fake.sent())
        .extracting(s -> s.request().messages().getFirst().text())
        .noneMatch(p -> p.contains("No listener for it was found"));
  }

  @Test
  void aFlowNotWordedAsTracedOrCitingNoFactOfTheSnapshotKeepsItsProse() {
    Wordings worded = describe(new FakeLlmProvider().answering(DescriberTest::answer)).wordings();
    String unknown = " [fact:" + "f".repeat(64) + "]";
    Map<String, Wordings.Flow> citingNothing = new java.util.HashMap<>();
    worded
        .flows()
        .keySet()
        .forEach(
            key ->
                citingNothing.put(
                    key,
                    new Wordings.Flow(
                        "Lists orders." + unknown, null, "Orders announced." + unknown)));
    Map<String, Wordings.Flow> otherTrace =
        Map.of("0".repeat(64), worded.flows().values().iterator().next());

    for (Map<String, Wordings.Flow> flows : List.of(citingNothing, otherTrace)) {
      Section flow = flowSection(system(new Wordings(Map.of(), Map.of(), flows)), LIST_FLOW);

      assertThat(text(((Paragraph) flow.blocks().getFirst()).content()))
          .startsWith("Starts when shop-app receives GET /orders, handled by Controller.list.");
      assertThat(overview(flow).cells().subList(1, 3))
          .extracting(DescriberTest::text)
          .containsExactly("Not known from the code.", "Not known from the code.");
    }
  }

  @Test
  void theSystemsPurposeIsAskedFromTheUnitsSentencesAndTheirFactsAndTheReadmeClaims() {
    List<Fact> withClaim = TestFacts.runtimeWithContradictedClaim();
    FakeLlmProvider fake = new FakeLlmProvider().answering(DescriberTest::answer);

    Describer.Result result = new Describer(harness(client(fake))).describe(withClaim);

    LlmRequest purpose =
        fake.sent().stream()
            .map(FakeLlmProvider.Sent::request)
            .filter(r -> r.purpose().equals("word the system's purpose"))
            .findFirst()
            .orElseThrow();
    assertThat(purpose.role()).isEqualTo(io.lantern.llm.Role.INFER_INTENT);
    assertThat(purpose.system()).isEqualTo(WordingPrompt.PURPOSE_SYSTEM);
    assertThat(purpose.maxOutputTokens()).isEqualTo(Describer.MAX_PURPOSE_OUTPUT_TOKENS);
    assertThat(purpose.messages().getFirst().text())
        .isEqualTo(
            """
            The software system.

            Its deployable units, each with what it does as worded from its facts:
            - shop-app: Takes and lists the shop's orders.
              [F1] service:com.example:app application-name "shop-app" (shop:pom.xml)
              [F2] service:com.example:app deployable-unit "spring-boot" (shop:pom.xml)
            - worker: Processes orders in the background.
              [F3] service:com.example:worker deployable-unit "spring-boot" (base:pom.xml)

            What the READMEs of its repositories claim:
            shop (README.md):
              [F4] readme:shop:README.md claim "The app keeps its orders in MySQL." (shop:README.md)
            """);
    assertThat(result.wordings().responsibilities().get(RepoSections.SYSTEM))
        .isEqualTo(
            "Runs an online shop's orders. [fact:"
                + withClaim.stream()
                    .filter(f -> f.predicate().equals("claim"))
                    .findFirst()
                    .orElseThrow()
                    .id()
                + "]");
  }

  @Test
  void withoutUnitSentencesThePurposeIsAskedFromTheReadmeClaimsAlone() {
    List<Fact> withClaim = TestFacts.runtimeWithContradictedClaim();
    FakeLlmProvider fake =
        new FakeLlmProvider()
            .answering(
                request -> {
                  String prompt = request.messages().getFirst().text();
                  if (prompt.startsWith("The software system")) {
                    return answer(request);
                  }
                  // Units, data stores and destinations left out; flows as usual.
                  return prompt.startsWith("The flow") ? answer(request) : "{}";
                });

    Describer.Result result = new Describer(harness(client(fake))).describe(withClaim);

    assertThat(
            fake.sent().stream()
                .map(FakeLlmProvider.Sent::request)
                .filter(r -> r.purpose().equals("word the system's purpose"))
                .findFirst()
                .orElseThrow()
                .messages()
                .getFirst()
                .text())
        .isEqualTo(
            """
            The software system.

            What the READMEs of its repositories claim:
            shop (README.md):
              [F1] readme:shop:README.md claim "The app keeps its orders in MySQL." (shop:README.md)
            """);
    assertThat(result.wordings().responsibilities()).containsKey(RepoSections.SYSTEM);
  }

  @Test
  void aPurposeRejectedTwiceIsCountedCachesNothingAndLeavesItUnknown() {
    FakeLlmProvider fake =
        new FakeLlmProvider()
            .answering(
                request ->
                    request.messages().getFirst().text().startsWith("The software system")
                        ? "{\"purpose\": {\"text\": \"Sells things.\", \"cites\": [\"F99\"]}}"
                        : answer(request));

    Describer.Result result = describe(fake);

    assertThat(result.rejected()).containsExactly(RepoSections.SYSTEM);
    assertThat(fake.sent())
        .extracting(s -> s.request().purpose())
        .contains("word the system's purpose (retry)");
    assertThat(result.wordings().responsibilities()).doesNotContainKey(RepoSections.SYSTEM);
    assertThat(store.size()).isEqualTo(7);
    Callout first = (Callout) system(result.wordings()).sections().getFirst().blocks().getFirst();
    assertThat(text(first.content())).isEqualTo(RepoSections.UNKNOWN_PURPOSE);
  }

  @Test
  void aSentenceCitingAReadmeClaimIsShownAsWrittenAndOneCitingNoneAsInferred() {
    List<Fact> withClaim = TestFacts.runtimeWithContradictedClaim();
    Fact claim =
        withClaim.stream().filter(f -> f.predicate().equals("claim")).findFirst().orElseThrow();
    Wordings wordings =
        new Wordings(
            Map.of(
                APP,
                "Takes the shop's orders. [fact:"
                    + facts.find(APP.toString(), "application-name").id()
                    + "] [fact:"
                    + claim.id()
                    + "]",
                EntityId.parse("type:com.example.web.Controller"),
                "Lists the shop's orders. [fact:" + claim.id() + "]",
                // The claim it cited is not in the snapshot, so only code is left.
                WORKER,
                "Processes new orders. [fact:"
                    + facts.find(WORKER.toString(), "deployable-unit").id()
                    + "] [fact:gone-claim]",
                DATABASE,
                "Keeps the shop's orders. [fact:"
                    + facts.find(APP.toString(), "USES_DATABASE").id()
                    + "]"),
            Map.of());

    Document document =
        new Composer("1")
            .compose(
                ViewDefinition.c4System("system:c4", "System", TEMPLATE),
                SNAPSHOT,
                withClaim,
                Map.of(),
                wordings);

    Function<EntityId, List<Inline>> description =
        id ->
            document.entities().stream()
                .filter(e -> e.id().equals(id))
                .findFirst()
                .orElseThrow()
                .description();
    assertThat(text(description.apply(APP))).isEqualTo("Takes the shop's orders.");
    assertThat(text(description.apply(EntityId.parse("type:com.example.web.Controller"))))
        .isEqualTo("Lists the shop's orders.");
    // Leaving out a citation leaves the space before it.
    assertThat(text(description.apply(WORKER)).strip())
        .isEqualTo(Assembler.INFERRED_SENTENCE + " Processes new orders.");
    assertThat(text(description.apply(DATABASE)))
        .isEqualTo(Assembler.INFERRED_SENTENCE + " Keeps the shop's orders.");
  }

  @Test
  void aPurposeCitingAReadmeClaimAndUnitFactsIsShownAsWritten() {
    List<Fact> withClaim = TestFacts.runtimeWithContradictedClaim();
    Fact claim =
        withClaim.stream().filter(f -> f.predicate().equals("claim")).findFirst().orElseThrow();
    Wordings wordings =
        new Wordings(
            Map.of(
                RepoSections.SYSTEM,
                "Runs an online shop's orders. [fact:"
                    + facts.find(APP.toString(), "application-name").id()
                    + "] [fact:"
                    + claim.id()
                    + "]"),
            Map.of());

    Document document =
        new Composer("1")
            .compose(
                ViewDefinition.c4System("system:c4", "System", TEMPLATE),
                SNAPSHOT,
                withClaim,
                Map.of(),
                wordings);

    Paragraph first = (Paragraph) document.sections().getFirst().blocks().getFirst();
    assertThat(text(first.content())).isEqualTo("Runs an online shop's orders.");
    assertThat(first.facts()).contains(claim.id());
  }

  @Test
  void aPurposeFromAReadmeClaimOpensTheSystemsOverviewAndDescribesTheSystem() {
    List<Fact> withClaim = TestFacts.runtimeWithContradictedClaim();
    Wordings wordings =
        new Describer(harness(client(new FakeLlmProvider().answering(DescriberTest::answer))))
            .describe(withClaim)
            .wordings();

    Document document =
        new Composer("1")
            .compose(
                ViewDefinition.c4System("system:c4", "System", TEMPLATE),
                SNAPSHOT,
                withClaim,
                Map.of(),
                wordings);

    Paragraph first = (Paragraph) document.sections().getFirst().blocks().getFirst();
    assertThat(text(first.content())).isEqualTo("Runs an online shop's orders.");
    assertThat(first.content().getLast()).isInstanceOf(Citation.class);
    DocumentEntity system =
        document.entities().stream()
            .filter(e -> e.id().equals(RepoSections.SYSTEM))
            .findFirst()
            .orElseThrow();
    assertThat(text(system.description())).isEqualTo("Runs an online shop's orders.");
  }

  @Test
  void aPurposeCitingNoReadmeClaimIsMarkedAsInferred() {
    Wordings wordings = describe(new FakeLlmProvider().answering(DescriberTest::answer)).wordings();

    Document document = system(wordings);

    // Marked as an inferred summary is (ADR 0059), in the overview and the system's description.
    Paragraph first = (Paragraph) document.sections().getFirst().blocks().getFirst();
    assertThat(first.content().getFirst())
        .isEqualTo(new TextRun(Assembler.INFERRED, TextRun.Style.EMPHASIS));
    assertThat(text(first.content()))
        .isEqualTo(Assembler.INFERRED + " Runs an online shop's orders.");
    assertThat(
            text(
                document.entities().stream()
                    .filter(e -> e.id().equals(RepoSections.SYSTEM))
                    .findFirst()
                    .orElseThrow()
                    .description()))
        .isEqualTo(Assembler.INFERRED + " Runs an online shop's orders.");
    assertThat(first.facts()).containsExactly(facts.find(APP.toString(), "application-name").id());
  }

  @Test
  void withoutAPurposeTheSystemsOverviewStatesItAsUnknown() {
    FakeLlmProvider leavesItOut =
        new FakeLlmProvider()
            .answering(
                request ->
                    request.messages().getFirst().text().startsWith("The software system")
                        ? "{}"
                        : answer(request));
    Describer.Result result = describe(leavesItOut);

    Document document = system(result.wordings());

    assertThat(result.asked()).isEqualTo(8);
    assertThat(result.wordings().responsibilities()).doesNotContainKey(RepoSections.SYSTEM);
    Callout first = (Callout) document.sections().getFirst().blocks().getFirst();
    assertThat(first.variant()).isEqualTo(Callout.Variant.NOTE);
    assertThat(text(first.content())).isEqualTo(RepoSections.UNKNOWN_PURPOSE);
    assertThat(
            document.entities().stream()
                .filter(e -> e.id().equals(RepoSections.SYSTEM))
                .findFirst()
                .orElseThrow()
                .description())
        .isEmpty();
  }

  @Test
  void aSecondRunOverTheSameFactsMakesNoCall() {
    FakeLlmProvider fake = new FakeLlmProvider().answering(DescriberTest::answer);
    Wordings first = describe(fake).wordings();
    int calls = fake.sent().size();

    Describer.Result second = describe(fake);

    assertThat(fake.sent()).hasSize(calls);
    assertThat(second.asked()).isZero();
    assertThat(second.reused()).isEqualTo(8);
    assertThat(second.wordings()).isEqualTo(first);
  }

  @Test
  void anAnswerRejectedTwiceLeavesTheElementAsItWasAndCachesNothing() {
    FakeLlmProvider fake =
        new FakeLlmProvider()
            .answering(
                request ->
                    """
                    {"unit": {"text": "Uses things.", "cites": ["F99"]},
                     "element": {"text": "Holds things.", "cites": ["F99"]},
                     "walkthrough": {"text": "Does things.", "cites": ["F99"]}}""");

    Describer.Result result = describe(fake);

    assertThat(fake.sent()).hasSize(14);
    assertThat(fake.sent())
        .extracting(s -> s.request().purpose())
        .contains(
            "word unit " + APP + " (retry)",
            "word element " + DATABASE + " (retry)",
            "narrate flow " + LIST_FLOW + " (retry)");
    assertThat(fake.sent())
        .filteredOn(s -> s.request().purpose().endsWith("(retry)"))
        .allSatisfy(
            s ->
                assertThat(s.request().messages().getLast().text())
                    .startsWith(
                        "Your answer was rejected: the "
                            + (s.request().purpose().startsWith("word unit")
                                ? "unit"
                                : s.request().purpose().startsWith("narrate")
                                    ? "walkthrough"
                                    : "element")
                            + " cites F99, which is not a fact in the input."));
    assertThat(result.rejected())
        .containsExactlyInAnyOrder(APP, WORKER, DATABASE, TOPIC, PAY, LIST, RUN);
    // Without a unit's sentence or a README claim, the system's purpose is not asked about.
    assertThat(fake.sent())
        .extracting(s -> s.request().purpose())
        .noneMatch(p -> p.contains("purpose"));
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

    assertThat(result.skipped())
        .containsExactlyInAnyOrder(APP, WORKER, DATABASE, TOPIC, PAY, LIST, RUN);
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
  void leftOutCitationsLeaveNoLeadingTrailingOrDoubledSpace() {
    String bad = "[fact:" + "f".repeat(64) + "]";
    String good = "[fact:" + facts.find(APP.toString(), "deployable-unit").id() + "]";
    Wordings wordings =
        new Wordings(
            Map.of(
                APP, "Takes orders " + bad + " " + good + " " + bad + ".",
                WORKER,
                    bad + " Stores orders " + bad + " and ships them " + bad + " " + good + " "),
            Map.of());

    Document document = system(wordings);

    for (EntityId id : List.of(APP, WORKER)) {
      DocumentEntity entity =
          document.entities().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
      String description = text(entity.description());
      assertThat(description).isNotEmpty().isEqualTo(description.strip());
      assertThat(description).doesNotContain("  ").doesNotContain(" .");
    }
    DocumentEntity appEntity =
        document.entities().stream().filter(e -> e.id().equals(APP)).findFirst().orElseThrow();
    assertThat(text(appEntity.description())).contains("Takes orders").doesNotContain("orders .");
    DocumentEntity worker =
        document.entities().stream().filter(e -> e.id().equals(WORKER)).findFirst().orElseThrow();
    assertThat(text(worker.description()))
        .endsWith("Stores orders and ships them")
        .doesNotStartWith(" ");
  }

  @Test
  void aUnitWithNothingToWordIsNotAskedAbout() {
    TestFacts bare =
        new TestFacts()
            .attribute("shop", "service:g:lonely", "deployable-unit", "spring-boot", "{}");
    FakeLlmProvider fake = new FakeLlmProvider().answering(DescriberTest::answer);

    Describer.Result result = new Describer(harness(client(fake))).describe(bare.list());

    assertThat(fake.sent()).isEmpty();
    assertThat(result.asked()).isZero();
    assertThat(new ArrayList<>(result.wordings().responsibilities().keySet())).isEmpty();
  }

  @Test
  void aNewCommitThatChangesNothingShownMakesNoCall() {
    FakeLlmProvider fake = new FakeLlmProvider().answering(DescriberTest::answer);
    describe(fake);
    int calls = fake.sent().size();
    List<Fact> moved =
        facts.list().stream()
            .map(
                f ->
                    Fact.create(
                        f.subject(),
                        f.predicate(),
                        f.object(),
                        f.attributes(),
                        f.origin(),
                        // The file changed elsewhere, so the fact is another observation.
                        io.lantern.core.fact.Evidence.of(
                            f.evidence().repo(),
                            f.evidence().path(),
                            f.evidence().lines(),
                            f.evidence().path() == null ? null : "b".repeat(64)),
                        f.confidence(),
                        f.validity()))
            .toList();

    Describer.Result second =
        new Describer(harness(client(fake)))
            .describe(io.lantern.derive.Derivation.withModel(moved, 1));

    assertThat(fake.sent()).hasSize(calls);
    assertThat(second.reused()).isEqualTo(8);
    // The answers cite the new commit's facts.
    assertThat(second.wordings().phrases())
        .containsKey(
            moved.stream()
                .filter(f -> f.subject().equals(APP) && f.predicate().equals("PUBLISHES"))
                .findFirst()
                .orElseThrow()
                .id());
  }

  @Test
  void aLineIsLabelledWithOneIntentOnlyWhenEveryLinkHasAPhrase() {
    String other =
        """
        {"protocol": "Kafka", "routingKey": "order.paid", "source": \
        "method:com.example.web.Controller#list()"}""";
    TestFacts more =
        TestFacts.runtime()
            .relate("shop", APP.toString(), "PUBLISHES", "topic:kafka:orders", other);
    List<Fact> sends =
        more.list().stream()
            .filter(f -> f.subject().equals(APP) && f.predicate().equals("PUBLISHES"))
            .toList();
    assertThat(sends).hasSize(2);
    Function<Wordings, String> label =
        wordings ->
            diagrams(
                    new Composer("1")
                        .compose(
                            ViewDefinition.c4System("system:c4", "System", TEMPLATE),
                            SNAPSHOT,
                            more.list(),
                            Map.of(),
                            wordings),
                    Diagram.Kind.C4_CONTAINER)
                .getFirst()
                .edges()
                .stream()
                .filter(e -> e.from().equals(APP) && e.to().toString().equals("topic:kafka:orders"))
                .findFirst()
                .orElseThrow()
                .label();

    assertThat(
            label.apply(
                new Wordings(
                    Map.of(),
                    Map.of(sends.get(0).id(), new Wordings.Phrase("Announces orders", List.of())))))
        .isEqualTo("Sends order.paid");
    assertThat(
            label.apply(
                new Wordings(
                    Map.of(),
                    Map.of(
                        sends.get(0).id(),
                        new Wordings.Phrase("Announces orders", List.of()),
                        sends.get(1).id(),
                        new Wordings.Phrase("Reports payments", List.of())))))
        .isEqualTo("Announces orders");
  }

  @Test
  void aLowerFirstLetterKeepsAcronyms() {
    assertThat(C4Views.lowerFirst("Reports payments")).isEqualTo("reports payments");
    assertThat(C4Views.lowerFirst("SQL dumps")).isEqualTo("SQL dumps");
  }

  private static final EntityId CONTROLLER = EntityId.parse("type:com.example.web.Controller");
  private static final String CONTROLLER_PATH = "src/main/java/com/example/web/Controller.java";
  private static final String JOBS_PATH = "src/main/java/com/example/web/Jobs.java";
  private static final String CONTROLLER_CODE =
      """
      /*
       * Licensed under the Apache License.
       */
      package com.example.web;

      import java.util.List;

      /** Serves the shop's order list to the web shop. */
      @RestController
      class Controller {
        private String password = "hunter2";

        List<String> list() {
          return List.of();
        }
      }
      """;

  /**
   * The type's {@code kind} fact, observed in the file with the content, named by its blob; the
   * commit no longer matters (ADR 0091).
   */
  private static Fact kind(EntityId type, String path, String content, String commit) {
    return Fact.create(
        type,
        "kind",
        FactObject.string("class"),
        FactJson.newObject(),
        new Origin(Origin.Kind.STATIC, "java", "1"),
        Evidence.of(
            new Evidence.Location(
                "shop",
                path,
                new Evidence.LineRange(1, 10),
                SourceFiles.blobSha(content.getBytes(StandardCharsets.UTF_8), 40),
                null,
                Evidence.Role.DECLARED)),
        1.0,
        Validity.from(1));
  }

  /** Reads the files by path, whatever the commit or blob asked for. */
  private static SourceFiles files(Map<String, String> files) {
    return new SourceFiles() {
      @Override
      public Optional<byte[]> read(String repo, String commit, String path) {
        return Optional.ofNullable(files.get(path)).map(c -> c.getBytes(StandardCharsets.UTF_8));
      }

      @Override
      public Optional<byte[]> blob(String repo, String blob, String path) {
        return read(repo, blob, path);
      }
    };
  }

  private static String appPrompt(FakeLlmProvider fake) {
    return fake.sent().stream()
        .map(s -> s.request().messages().getFirst().text())
        .filter(p -> p.startsWith("The deployable unit shop-app"))
        .reduce((a, b) -> b)
        .orElseThrow();
  }

  @Test
  void aComponentShowsTheStartOfItsMainTypesCodeWithSecretsMasked() {
    FakeLlmProvider fake = new FakeLlmProvider().answering(DescriberTest::answer);
    Fact controller = kind(CONTROLLER, CONTROLLER_PATH, CONTROLLER_CODE, TestFacts.COMMIT);
    // The file read is not the version the fact was observed in, so it is not shown.
    Fact jobs =
        kind(
            EntityId.parse("type:com.example.web.Jobs"),
            JOBS_PATH,
            "class Jobs {}",
            TestFacts.COMMIT);
    List<Fact> withCode = new ArrayList<>(facts.list());
    withCode.add(controller);
    withCode.add(jobs);
    SourceFiles sources =
        files(Map.of(CONTROLLER_PATH, CONTROLLER_CODE, JOBS_PATH, "class Jobs { int changed; }"));

    Describer.Result result = new Describer(harness(client(fake)), sources).describe(withCode);

    String app = appPrompt(fake);
    String declared =
        "type:com.example.web.Controller kind \"class\" (shop:" + CONTROLLER_PATH + ")";
    assertThat(app)
        .contains(
            "] "
                + declared
                + "\n  Code of "
                + CONTROLLER_PATH
                + ", without its package and imports:\n"
                + "```java\n"
                + "/** Serves the shop's order list to the web shop. */\n"
                + "@RestController\n"
                + "class Controller {\n"
                + "  private String password = \"******\";\n"
                + "\n"
                + "  List<String> list() {\n"
                + "    return List.of();\n"
                + "  }\n"
                + "}\n"
                + "```\n[C2] Jobs")
        .doesNotContain("hunter2")
        .doesNotContain("Licensed")
        .doesNotContain("import java.util.List")
        .doesNotContain("Code of " + JOBS_PATH);
    assertThat(result.wordings().documented()).containsExactly(controller.id());
  }

  @Test
  void codeIsCutAtALineOnceTheComponentsShareIsReached() {
    FakeLlmProvider fake = new FakeLlmProvider().answering(DescriberTest::answer);
    StringBuilder code = new StringBuilder("class Controller {\n");
    int lines = Describer.MAX_CODE_CHARS / 20 + 10;
    for (int i = 0; i < lines; i++) {
      code.append("  int field").append(String.format("%05d", i)).append(" = 1;\n");
    }
    code.append("}\n");
    List<Fact> withCode = new ArrayList<>(facts.list());
    withCode.add(kind(CONTROLLER, CONTROLLER_PATH, code.toString(), TestFacts.COMMIT));

    new Describer(harness(client(fake)), files(Map.of(CONTROLLER_PATH, code.toString())))
        .describe(withCode);

    String app = appPrompt(fake);
    String shown =
        app.substring(app.indexOf("```java\n") + 8, app.indexOf("\n```", app.indexOf("```java")));
    assertThat(shown.length()).isLessThanOrEqualTo(Describer.MAX_CODE_CHARS);
    int left = lines + 2 - shown.split("\n").length;
    assertThat(left).isPositive();
    assertThat(app)
        .contains(
            "Code of "
                + CONTROLLER_PATH
                + ", without its package and imports, the last "
                + left
                + " lines left out:\n");
    assertThat(shown).endsWith(" = 1;");
  }

  @Test
  void theSameCodeAtANewCommitMakesNoCallAndChangedCodeAsksAgain() {
    FakeLlmProvider fake = new FakeLlmProvider().answering(DescriberTest::answer);
    List<Fact> first = new ArrayList<>(facts.list());
    first.add(kind(CONTROLLER, CONTROLLER_PATH, CONTROLLER_CODE, TestFacts.COMMIT));
    Map<String, String> files = new HashMap<>(Map.of(CONTROLLER_PATH, CONTROLLER_CODE));
    new Describer(harness(client(fake)), files(files)).describe(first);
    int calls = fake.sent().size();

    List<Fact> moved = new ArrayList<>(facts.list());
    moved.add(kind(CONTROLLER, CONTROLLER_PATH, CONTROLLER_CODE, "b".repeat(40)));
    Describer.Result same = new Describer(harness(client(fake)), files(files)).describe(moved);

    assertThat(fake.sent()).hasSize(calls);
    assertThat(same.wordings().documented())
        .containsExactly(
            moved.stream()
                .filter(f -> f.predicate().equals("kind"))
                .findFirst()
                .orElseThrow()
                .id());

    String changed = CONTROLLER_CODE.replace("list()", "orders()");
    List<Fact> edited = new ArrayList<>(facts.list());
    edited.add(kind(CONTROLLER, CONTROLLER_PATH, changed, "c".repeat(40)));
    files.put(CONTROLLER_PATH, changed);
    new Describer(harness(client(fake)), files(files)).describe(edited);

    assertThat(fake.sent())
        .hasSize(calls + 1)
        .last()
        .extracting(s -> s.request().purpose())
        .isEqualTo("word unit " + APP);
    assertThat(appPrompt(fake)).contains("orders()");
  }

  @Test
  void aSentenceCitingCodeWithJavadocIsShownAsWrittenAndThePurposeStaysInferred() {
    Fact controller = kind(CONTROLLER, CONTROLLER_PATH, CONTROLLER_CODE, TestFacts.COMMIT);
    List<Fact> withCode = new ArrayList<>(facts.list());
    withCode.add(controller);
    Map<EntityId, String> sentences =
        Map.of(
            CONTROLLER,
            "Lists the shop's orders. [fact:" + controller.id() + "]",
            RepoSections.SYSTEM,
            "Runs an online shop. [fact:" + controller.id() + "]");
    Function<Wordings, Document> compose =
        wordings ->
            new Composer("1")
                .compose(
                    ViewDefinition.c4System("system:c4", "System", TEMPLATE),
                    SNAPSHOT,
                    withCode,
                    Map.of(),
                    wordings);
    Function<Document, String> description =
        document ->
            text(
                document.entities().stream()
                    .filter(e -> e.id().equals(CONTROLLER))
                    .findFirst()
                    .orElseThrow()
                    .description());

    Document documented =
        compose.apply(
            new Wordings(sentences, Map.of(), Map.of(), java.util.Set.of(controller.id())));
    Document undocumented = compose.apply(new Wordings(sentences, Map.of()));

    assertThat(description.apply(documented)).isEqualTo("Lists the shop's orders.");
    assertThat(description.apply(undocumented))
        .isEqualTo(Assembler.INFERRED_SENTENCE + " Lists the shop's orders.");
    // The prompt about the system's purpose shows the fact without its code.
    Paragraph first = (Paragraph) documented.sections().getFirst().blocks().getFirst();
    assertThat(text(first.content())).isEqualTo(Assembler.INFERRED + " Runs an online shop.");
  }
}

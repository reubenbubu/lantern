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
   * Words each unit, its components and interactions from what its item shows: the shop app, its
   * first component, its call and send, the worker and its call, each citing a fact of the item;
   * and the database and the topic, citing their uses, leaving the external system out; and each
   * flow's title, walkthrough and post-conditions, citing its entry point and send, leaving its
   * pre-conditions out; and the system's purpose, citing its README's first claim where it shows
   * one, else the first fact.
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
    java.util.regex.Matcher items =
        java.util.regex.Pattern.compile(
                "<item id=\"(I[0-9]+)\">\n(.*?)\n</item>", java.util.regex.Pattern.DOTALL)
            .matcher(prompt);
    List<String> entries = new java.util.ArrayList<>();
    while (items.find()) {
      String answer = item(items.group(2));
      entries.add("\"" + items.group(1) + "\": " + answer);
    }
    return "{\"items\": {" + String.join(", ", entries) + "}}";
  }

  /** The answer about one item. */
  private static String item(String body) {
    if (body.startsWith("The database")) {
      return """
          {"responsibility": {"text": "Keeps the shop's orders.", "cites": ["F%s"]}}"""
          .formatted(ref(body, "USES_DATABASE"));
    }
    if (body.startsWith("The topic")) {
      return """
          {"responsibility": {"text": "Carries each new purchase to background processing.",
                              "cites": ["F%s", "F%s"]}}"""
          .formatted(ref(body, "PUBLISHES"), ref(body, "CONSUMES"));
    }
    if (!body.startsWith("The deployable unit")) {
      return "{\"omitted\": true}";
    }
    if (body.startsWith("The deployable unit shop-app")) {
      return """
          {"unit": {"text": "Takes and lists the shop's orders.", "cites": ["F1", "F2"]},
           "components": {"C1": {"text": "Lists orders for the web shop.", "cites": [%s]}},
           "interactions": {"%s": {"text": "Charges customers' cards", "cites": ["F%s"]},
                            "%s": {"text": "Announces new orders.", "cites": ["F%s"]}}}"""
          .formatted(
              componentCites(body, "C1"),
              interaction(body, "CALLS_HTTP"),
              ref(body, "CALLS_HTTP"),
              interaction(body, "PUBLISHES"),
              ref(body, "PUBLISHES"));
    }
    return """
        {"unit": {"text": "Processes orders in the background.", "cites": ["F1"]},
         "interactions": {"%s": {"text": "Fetches the order list", "cites": ["F%s"]}}}"""
        .formatted(interaction(body, "CALLS_HTTP"), ref(body, "CALLS_HTTP"));
  }

  /** The key of the first interaction whose fact has the predicate. */
  private static String interaction(String body, String predicate) {
    String key = null;
    for (String line : body.split("\n")) {
      if (line.startsWith("[R")) {
        key = line.substring(1, line.indexOf(']'));
      } else if (key != null && line.contains(" " + predicate + " ")) {
        return key;
      }
    }
    throw new IllegalStateException("No interaction with " + predicate + " in:\n" + body);
  }

  /** The first fact shown under the component and its code excerpt, if it shows one. */
  private static String componentCites(String body, String component) {
    boolean in = false;
    List<String> cites = new java.util.ArrayList<>();
    for (String line : body.split("\n")) {
      String stripped = line.strip();
      if (line.startsWith("[" + component + "] ")) {
        in = true;
      } else if (in && (line.startsWith("[C") || line.equals("Interactions:"))) {
        break;
      } else if (in && cites.isEmpty() && stripped.startsWith("[F")) {
        cites.add("\"" + stripped.substring(1, stripped.indexOf(']')) + "\"");
      } else if (in && stripped.startsWith("<code ref=\"")) {
        cites.add("\"" + stripped.substring(11, stripped.indexOf('"', 11)) + "\"");
      }
    }
    if (cites.isEmpty()) {
      throw new IllegalStateException("No fact under " + component + " in:\n" + body);
    }
    return String.join(", ", cites);
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

  /** A client of a priced model, $1,000 a million tokens, in and out. */
  private static LlmClient priced(FakeLlmProvider fake) {
    io.lantern.llm.ModelConfig free = fake.model("priced");
    return new LlmClient(
        ModelSettings.single(
            new io.lantern.llm.ModelConfig(
                free.provider(),
                free.model(),
                free.capabilities(),
                free.contextWindow(),
                new io.lantern.llm.Pricing(
                    new java.math.BigDecimal("1000"),
                    new java.math.BigDecimal("1000"),
                    java.math.BigDecimal.ZERO,
                    java.math.BigDecimal.ZERO))),
        List.of(fake),
        BudgetGuard.unlimited("test"),
        new InMemoryLlmCallLog(),
        Clock.systemUTC());
  }

  /** Shares that give the flows and the purpose 1% of the budget, and the other groups plenty. */
  private static Map<String, Integer> shares() {
    return Map.of("describe", 90, "flows-and-purpose", 1, "model-checks", 9);
  }

  private static io.lantern.harness.RoleRecord record(Harness harness, String role) {
    return harness.records().stream().filter(r -> r.role().equals(role)).findFirst().orElseThrow();
  }

  @Test
  void underABudgetThePurposeHasFirstClaimOnItsShareAheadOfTheFlows() {
    // With room for everything: what the purpose and one flow are expected to cost.
    Harness room =
        new Harness(
            priced(new FakeLlmProvider().answering(DescriberTest::answer)),
            new InMemoryAnswerStore(),
            new RunBudget(new java.math.BigDecimal("1000000"), shares()),
            BlobLookup.NONE,
            Set.of());
    new Describer(room).describe(facts.list());
    java.math.BigDecimal purpose = record(room, "purpose").estimate().usd();
    io.lantern.harness.Estimate flows = record(room, "flow-narrative").estimate();
    java.math.BigDecimal flow =
        flows
            .usd()
            .divide(java.math.BigDecimal.valueOf(flows.toAsk()), java.math.MathContext.DECIMAL64);
    assertThat(flows.toAsk()).isEqualTo(2);
    assertThat(flow).isGreaterThan(purpose);

    // A share that holds one flow and half the purpose: in the flows' order a flow would take it
    // and leave the purpose nothing; claimed first, the purpose is asked and no flow fits after.
    Harness budget =
        new Harness(
            priced(new FakeLlmProvider().answering(DescriberTest::answer)),
            new InMemoryAnswerStore(),
            new RunBudget(
                flow.add(purpose.divide(java.math.BigDecimal.TWO))
                    .multiply(java.math.BigDecimal.valueOf(100)),
                shares()),
            BlobLookup.NONE,
            Set.of());
    new Describer(budget).describe(facts.list());
    assertThat(record(budget, "purpose").asked()).hasSize(1);
    assertThat(record(budget, "flow-narrative").asked()).isEmpty();
    assertThat(record(budget, "flow-narrative").settled()).isEqualTo(2);
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
            "INFER_INTENT describe-unit 1 items: " + APP,
            "INFER_INTENT describe-unit 1 items: " + WORKER,
            "DESCRIBE_ELEMENTS describe-element 1 items: " + DATABASE,
            "DESCRIBE_ELEMENTS describe-element 1 items: " + TOPIC,
            "DESCRIBE_ELEMENTS describe-element 1 items: " + PAY,
            "COMPOSE narrate flow " + LIST_FLOW,
            "COMPOSE narrate flow " + RUN_FLOW,
            "INFER_INTENT word the system's purpose");
    String app =
        fake.sent().stream()
            .map(s -> s.request().messages().getFirst().text())
            .filter(p -> p.contains("The deployable unit shop-app"))
            .findFirst()
            .orElseThrow();
    assertThat(app)
        .startsWith(
            "<items>\n<item id=\"I1\">\n"
                + "The deployable unit shop-app, built with Spring Boot 3.5.0 / Java 21.\n"
                + "Facts that make it a unit:\n<facts>\n"
                + "[F1] service:com.example:app application-name \"shop-app\" (shop:pom.xml)\n"
                + "[F2] service:com.example:app deployable-unit \"spring-boot\" (shop:pom.xml)\n")
        .contains("\nComponents:\n[C1] Controller, Spring MVC REST controller\n")
        .contains("\n[C2] Jobs, Scheduled job\n")
        .contains("\n[C3] JpaOrders, Spring repository\n")
        .contains("\nInteractions:\n[R1] calls pay.example.com (an external system)\n")
        .contains("\n[R2] sends messages to orders (a topic)\n")
        .contains("\n[R3] connects to shop on db (a database)\n")
        .contains("\n[R4] Controller calls JpaOrders (a component)\n")
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
    LlmRequest database = request.apply("describe-element 1 items: " + DATABASE);
    assertThat(database.system()).contains(DescribeRole.ELEMENT_TASK);
    assertThat(database.messages().getFirst().text())
        .isEqualTo(
            """
            <items>
            <item id="I1">
            The database shop on db, PostgreSQL.
            Facts about it:
            <facts>
            [F1] database:db:5432/shop technology "PostgreSQL" (shop:pom.xml)
            </facts>
            What is built in it:
            <facts>
            [F2] schema:com.example:app CONTAINS table:com.example:app:orders (shop:pom.xml)
            </facts>
            How the units use it:
            shop-app (a deployable unit) connects to it:
            <facts>
            [F3] service:com.example:app USES_DATABASE database:db:5432/shop\
             {"protocol":"JDBC","schemas":["schema:com.example:app"]} (shop:pom.xml)
            </facts>
            What the units that use it do:
            <answers>
            [A1] shop-app: Takes and lists the shop's orders.
            </answers>
            </item>
            </items>""");
    assertThat(request.apply("describe-element 1 items: " + TOPIC).messages().getFirst().text())
        .contains("The topic orders, Kafka topic.\nHow the units use it:\n")
        .contains("\nshop-app (a deployable unit) sends messages to it:\n<facts>\n[F1] ")
        .contains("\nworker (a deployable unit) receives messages from it:\n<facts>\n[F2] ");
    assertThat(request.apply("describe-element 1 items: " + PAY).messages().getFirst().text())
        .contains(
            "The external system pay.example.com.\nHow the units use it:\n"
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
        .isEqualTo(
            Assembler.INFERRED_SENTENCE + " Carries each new purchase to background processing.");
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
                    request.system().contains(DescribeRole.UNIT_TASK)
                        ? """
                        {"items": {"I1": {"unit": {"text": "Uses things.", "cites": ["F99"]}}}}"""
                        : request.system().contains(DescribeRole.ELEMENT_TASK)
                            ? """
                            {"items": {"I1":
                              {"responsibility": {"text": "Holds things.", "cites": ["F99"]}}}}"""
                            : """
                            {"walkthrough": {"text": "Does things.", "cites": ["F99"]}}""");

    Describer.Result result = describe(fake);

    assertThat(fake.sent()).hasSize(14);
    assertThat(fake.sent())
        .extracting(s -> s.request().purpose())
        .contains(
            "describe-unit 1 items: " + APP + " (repair)",
            "describe-element 1 items: " + DATABASE + " (repair)",
            "narrate flow " + LIST_FLOW + " (retry)");
    assertThat(fake.sent())
        .filteredOn(s -> s.request().purpose().endsWith("(retry)"))
        .allSatisfy(
            s ->
                assertThat(s.request().messages().getLast().text())
                    .startsWith(
                        "Your answer was rejected: the walkthrough cites F99, which is not a fact"
                            + " in the input."));
    assertThat(fake.sent())
        .filteredOn(s -> s.request().purpose().endsWith("(repair)"))
        .allSatisfy(
            s ->
                assertThat(s.request().messages().getLast().text())
                    .contains(
                        "<rejection item=\"I1\" field=\""
                            + (s.request().purpose().startsWith("describe-unit")
                                ? "unit"
                                : "responsibility")
                            + "\">")
                    .contains("F99"));
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
    assertThat(result.unknown()).containsExactly("service:g:lonely");
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
        .filter(p -> p.contains("The deployable unit shop-app"))
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
    assertThat(app)
        .contains(
            "</facts>\n<code ref=\"E1\" repo=\"shop\" path=\""
                + CONTROLLER_PATH
                + "\" lines=\"8-16\">\n"
                + " 8  /** Serves the shop's order list to the web shop. */\n"
                + " 9  @RestController\n"
                + "10  class Controller {\n"
                + "11    private String password = \"******\";\n"
                + "12  \n"
                + "13    List<String> list() {\n"
                + "14      return List.of();\n"
                + "15    }\n"
                + "16  }\n"
                + "</code>\n[C2] Jobs")
        .doesNotContain("hunter2")
        .doesNotContain("Licensed")
        .doesNotContain("import java.util.List")
        .doesNotContain("path=\"" + JOBS_PATH);
    assertThat(result.wordings().documented()).containsExactly(controller.id());
  }

  @Test
  void codeIsCutAtALineOnceTheComponentsShareIsReached() {
    FakeLlmProvider fake = new FakeLlmProvider().answering(DescriberTest::answer);
    StringBuilder code = new StringBuilder("class Controller {\n");
    int lines = DescribeItems.MAX_CODE_CHARS / 20 + 10;
    for (int i = 0; i < lines; i++) {
      code.append("  int field").append(String.format("%05d", i)).append(" = 1;\n");
    }
    code.append("}\n");
    List<Fact> withCode = new ArrayList<>(facts.list());
    withCode.add(kind(CONTROLLER, CONTROLLER_PATH, code.toString(), TestFacts.COMMIT));

    new Describer(harness(client(fake)), files(Map.of(CONTROLLER_PATH, code.toString())))
        .describe(withCode);

    String app = appPrompt(fake);
    int open = app.indexOf("<code ref=\"E1\"");
    String shown = app.substring(app.indexOf('\n', open) + 1, app.indexOf("\n</code>", open));
    String range = app.substring(app.indexOf("lines=\"", open) + 7, app.indexOf("\">", open));
    int last = Integer.parseInt(range.substring(range.indexOf('-') + 1));
    // The file has the class line, the fields and the closing brace; the excerpt stops before.
    assertThat(last).isLessThan(lines + 2);
    assertThat(shown.lines().count()).isEqualTo(last);
    int width = String.valueOf(last).length() + 2;
    assertThat(shown.lines().map(l -> l.substring(width)).collect(Collectors.joining("\n")))
        .hasSizeLessThanOrEqualTo(DescribeItems.MAX_CODE_CHARS);
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
        .isEqualTo("describe-unit 1 items: " + APP);
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

  /**
   * The common prompt and Describe's are the epic's, character for character (knowledge-build epic,
   * sections 11.0 and 11.4).
   */
  @Test
  void theDescribePromptsAreTheEpics() throws Exception {
    String epic =
        java.nio.file.Files.readString(
            java.nio.file.Path.of("../docs/epics/001-knowledge-build.md"));

    for (String prompt :
        List.of(
            io.lantern.harness.checks.CommonPrompt.SYSTEM,
            DescribeRole.UNIT_TASK,
            DescribeRole.UNIT_SCHEMA,
            DescribeRole.ELEMENT_TASK,
            DescribeRole.ELEMENT_SCHEMA)) {
      assertThat(epic).contains(prompt.strip());
    }
  }

  @Test
  void theSameFactsMakeTheSameRequestsEveryTime() {
    FakeLlmProvider one = new FakeLlmProvider().answering(DescriberTest::answer);
    FakeLlmProvider two = new FakeLlmProvider().answering(DescriberTest::answer);
    List<Fact> shuffled = new ArrayList<>(facts.list());
    java.util.Collections.reverse(shuffled);

    new Describer(
            new Harness(
                client(one), new InMemoryAnswerStore(), RunBudget.NONE, BlobLookup.NONE, Set.of()))
        .describe(facts.list());
    new Describer(
            new Harness(
                client(two), new InMemoryAnswerStore(), RunBudget.NONE, BlobLookup.NONE, Set.of()))
        .describe(shuffled);

    Function<FakeLlmProvider, List<String>> requests =
        fake ->
            fake.sent().stream()
                .map(FakeLlmProvider.Sent::request)
                .filter(r -> r.purpose().startsWith("describe-"))
                .map(r -> r.purpose() + "\n" + r.system() + "\n" + r.messages())
                .sorted()
                .toList();
    assertThat(requests.apply(two)).isEqualTo(requests.apply(one)).hasSize(5);
  }

  @Test
  void aUnitsTextsAreStoredAsResponsibilityAndIntentFacts() {
    Describer.Result result = describe(new FakeLlmProvider().answering(DescriberTest::answer));

    Map<io.lantern.core.fact.Origin, List<Fact>> stored = result.facts(SNAPSHOT.number());
    List<Fact> units = stored.get(io.lantern.harness.LlmFacts.origin(DescribeRole.UNITS));
    List<Fact> elements = stored.get(io.lantern.harness.LlmFacts.origin(DescribeRole.ELEMENTS));

    assertThat(units)
        .extracting(
            f -> f.subject() + " " + f.predicate() + " " + f.attributes().get("text").asString())
        .contains(
            APP + " responsibility Takes and lists the shop's orders.",
            "component:"
                + APP
                + "/type:com.example.web.Controller responsibility Lists orders for the web shop.",
            APP + " intent Charges customers' cards",
            WORKER + " intent Fetches the order list");
    Fact charge =
        units.stream()
            .filter(f -> f.attributes().get("text").asString().equals("Charges customers' cards"))
            .findFirst()
            .orElseThrow();
    Fact call = facts.find(APP.toString(), "CALLS_HTTP");
    assertThat(charge.object().value())
        .isEqualTo(call.claimId() != null ? call.claimId() : call.id());
    assertThat(charge.attributes().get("cites").get(0).asString()).isEqualTo(call.id());
    assertThat(charge.attributes().get("basis").asString()).isEqualTo("inferred");
    assertThat(charge.origin().kind()).isEqualTo(io.lantern.core.fact.Origin.Kind.LLM);
    assertThat(elements)
        .extracting(f -> f.subject() + " " + f.attributes().get("text").asString())
        .containsExactlyInAnyOrder(
            DATABASE + " Keeps the shop's orders.",
            TOPIC + " Carries each new purchase to background processing.");
  }

  /** The unit's answer, with the given texts for the unit and its first interaction. */
  private static Function<LlmRequest, String> unitSaying(String unit, String intent) {
    return request -> {
      if (!request.system().contains(DescribeRole.UNIT_TASK)) {
        return answer(request);
      }
      if (request.messages().size() > 1) {
        return "{\"items\": {\"I1\": {\"omitted\": true}}}";
      }
      String body = request.messages().getFirst().text();
      return """
          {"items": {"I1": {"unit": {"text": "%s", "cites": ["F1"]},
            "interactions": {"R1": {"text": "%s", "cites": ["F%s"]}}}}}"""
          .formatted(unit, intent, ref(body, "CALLS_HTTP"));
    };
  }

  private static String repair(FakeLlmProvider fake, EntityId unit) {
    return fake.sent().stream()
        .filter(s -> s.request().purpose().equals("describe-unit 1 items: " + unit + " (repair)"))
        .map(s -> s.request().messages().getLast().text())
        .findFirst()
        .orElseThrow(
            () ->
                new AssertionError(
                    fake.sent().stream()
                        .map(
                            sent ->
                                sent.request().purpose()
                                    + " -> "
                                    + sent.request().messages().size())
                        .toList()
                        .toString()));
  }

  @Test
  void aBannedOpeningAProtocolWordAndARestatedNameAreSentBackForRepair() {
    FakeLlmProvider opening =
        new FakeLlmProvider()
            .answering(unitSaying("Shop app for the shop.", "Calls the payment provider"));
    describe(opening);
    assertThat(repair(opening, APP))
        .contains("<rejection item=\"I1\" field=\"unit\">Restates its subject's name")
        .contains("<rejection item=\"I1\" field=\"interactions.R1\">")
        .contains("starts with an opening that says nothing about intent");

    FakeLlmProvider protocol =
        new FakeLlmProvider()
            .answering(unitSaying("Takes the shop's orders.", "Charges cards over HTTPS"));
    new Describer(
            new Harness(
                client(protocol),
                new InMemoryAnswerStore(),
                RunBudget.NONE,
                BlobLookup.NONE,
                Set.of()))
        .describe(facts.list());
    assertThat(repair(protocol, APP))
        .contains("names the protocol HTTPS")
        .doesNotContain("field=\"unit\"");
  }

  @Test
  void aComponentMayNameWhatItsCodeShowsWhenItCitesTheCode() {
    Fact controller = kind(CONTROLLER, CONTROLLER_PATH, CONTROLLER_CODE, TestFacts.COMMIT);
    List<Fact> withCode = new ArrayList<>(facts.list());
    withCode.add(controller);
    SourceFiles sources = files(Map.of(CONTROLLER_PATH, CONTROLLER_CODE));
    Function<String, Function<LlmRequest, String>> citing =
        cites ->
            request -> {
              if (!request.system().contains(DescribeRole.UNIT_TASK)
                  || !request.messages().getFirst().text().contains("shop-app")) {
                return answer(request);
              }
              if (request.messages().size() > 1) {
                return "{\"items\": {\"I1\": {\"omitted\": true}}}";
              }
              return """
                  {"items": {"I1": {"components":
                    {"C1": {"text": "Serves the list() of orders to the web shop.", "cites": [%s]}}}}}"""
                  .formatted(cites);
            };

    FakeLlmProvider withExcerpt = new FakeLlmProvider().answering(citing.apply("\"F5\", \"E1\""));
    Describer.Result shown =
        new Describer(harness(client(withExcerpt)), sources).describe(withCode);
    assertThat(withExcerpt.sent())
        .noneMatch(s -> s.request().purpose().endsWith(APP + " (repair)"));
    assertThat(
            shown
                .facts(SNAPSHOT.number())
                .get(io.lantern.harness.LlmFacts.origin(DescribeRole.UNITS)))
        .anySatisfy(
            f -> {
              assertThat(f.subject().toString()).endsWith("/" + CONTROLLER);
              // Code with Javadoc counts as what people wrote.
              assertThat(f.attributes().get("basis").asString()).isEqualTo("evidenced");
            });

    FakeLlmProvider factsOnly = new FakeLlmProvider().answering(citing.apply("\"F5\""));
    new Describer(
            new Harness(
                client(factsOnly),
                new InMemoryAnswerStore(),
                RunBudget.NONE,
                BlobLookup.NONE,
                Set.of()),
            sources)
        .describe(withCode);
    assertThat(repair(factsOnly, APP)).contains("field=\"components.C1\">Names list,");
  }

  @Test
  void aUnitWithMoreThan25ComponentsIsSplitIntoItemsOverTheSameUnit() {
    TestFacts many = TestFacts.runtime();
    for (int i = 0; i < 30; i++) {
      String type = "type:com.example.web.Service" + i;
      String component = "component:" + APP + "/" + type;
      many.relate("shop", "package:com.example:app:com.example.web", "CONTAINS", type)
          .attribute("shop", type, "stereotype", "service", "{}")
          .relate("shop", component, "PART_OF", APP.toString(), "{\"role\": \"shared\"}")
          .relate("shop", component, "ANCHORED_ON", type);
    }
    FakeLlmProvider fake =
        new FakeLlmProvider()
            .answering(
                request ->
                    request.system().contains(DescribeRole.UNIT_TASK)
                        ? "{\"items\": {\"I1\": {\"omitted\": true}}}"
                        : answer(request));

    new Describer(harness(client(fake))).describe(many.list());

    Function<String, String> part =
        subject ->
            fake.sent().stream()
                .filter(s -> s.request().purpose().equals("describe-unit 1 items: " + subject))
                .map(s -> s.request().messages().getFirst().text())
                .findFirst()
                .orElseThrow();
    assertThat(part.apply(APP.toString()))
        .contains("\n[C25] ")
        .doesNotContain("[C26] ")
        .doesNotContain("Interactions:")
        .doesNotContain("Part ");
    assertThat(part.apply(APP + "#2"))
        .contains("Part 2 of the unit: say nothing about the unit itself.")
        .contains("\n[C8] ")
        .doesNotContain("[C9] ")
        .doesNotContain("Interactions:");
    assertThat(part.apply(APP + "#3"))
        .contains("Part 3 of the unit")
        .contains("Interactions:\n[R1] ")
        .doesNotContain("Components:");
  }

  @Test
  void aRerunOverTheStoredTextsMakesNoCall() {
    FakeLlmProvider fake = new FakeLlmProvider().answering(DescriberTest::answer);
    Describer.Result first = describe(fake);
    int calls = fake.sent().size();
    List<Fact> withTexts = new ArrayList<>(facts.list());
    first.facts(SNAPSHOT.number()).values().forEach(withTexts::addAll);

    Describer.Result again = new Describer(harness(client(fake))).describe(withTexts);

    assertThat(fake.sent()).hasSize(calls);
    assertThat(again.reused()).isEqualTo(first.asked());
  }
}

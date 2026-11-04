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
import io.lantern.derive.ScopeFlows;
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
import io.lantern.summarize.describe.DescribeItems;
import io.lantern.summarize.describe.DescribeRole;
import io.lantern.summarize.describe.Describer;
import io.lantern.summarize.describe.FlowJudgements;
import io.lantern.summarize.describe.PurposeRole;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
  private static final String RUN_FLOW = "The schedule 0 0 * * * * in shop-app";

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
    if (request.system().contains(PurposeRole.TASK)) {
      return """
          {"items": {"I1": {"purpose": {"text": "Runs an online shop's orders from the cart to the card payment.", "cites": ["%s"]},
           "keyFact": {"text": "Card payments go to pay.example.com, the one outside service the shop calls.", "cites": ["F%s"]}}}}"""
          .formatted(
              prompt.contains(" claim ") ? "F" + ref(prompt, "claim") : "A1",
              // The external system's line: "- pay.example.com: [F4] ... CALLS_HTTP ...".
              java.util.regex.Pattern.compile("\\[F([0-9]+)\\] [^\\n]* CALLS_HTTP ")
                  .matcher(prompt)
                  .results()
                  .findFirst()
                  .orElseThrow()
                  .group(1));
    }
    java.util.regex.Matcher flows =
        java.util.regex.Pattern.compile(
                "<item id=\"(I[0-9]+)\">\n(.*?)\n</item>", java.util.regex.Pattern.DOTALL)
            .matcher(prompt);
    if (request.system().contains("Task: choose the flows worth drawing.")) {
      List<String> entries = new java.util.ArrayList<>();
      while (flows.find()) {
        entries.add("\"" + flows.group(1) + "\": " + significance(flows.group(2)));
      }
      return "{\"items\": {" + String.join(", ", entries) + "}}";
    }
    if (request.system().contains("Task: narrate flows.")) {
      List<String> entries = new java.util.ArrayList<>();
      while (flows.find()) {
        String body = flows.group(2);
        String send = body.contains(" PUBLISHES ") ? ref(body, "PUBLISHES") : "1";
        entries.add(
            "\""
                + flows.group(1)
                + "\": "
                + """
                {"title": {"text": "Shop lists its orders.", "cites": ["F1"]},
                 "walkthrough": {"text": "Lists the orders the shop has taken so far, then announces each new order so that the work on it can carry on later in the background.",
                                 "cites": ["F1", "F%1$s"]},
                 "postconditions": {"text": "The orders are announced for the work that follows in the background.", "cites": ["F%1$s"]}}"""
                    .formatted(send));
      }
      return "{\"items\": {" + String.join(", ", entries) + "}}";
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

  /**
   * The answer about a scope's flows: every flow significant, in the order shown, each for the
   * reason of its entry fact.
   */
  private static String significance(String body) {
    List<String> flows = new java.util.ArrayList<>();
    List<String> order = new java.util.ArrayList<>();
    String[] lines = body.split("\n");
    for (int i = 0; i < lines.length; i++) {
      if (lines[i].startsWith("[W")) {
        String w = lines[i].substring(1, lines[i].indexOf(']'));
        String fact = lines[i + 1].strip();
        fact = fact.substring(1, fact.indexOf(']'));
        flows.add(
            "\"%s\": {\"significant\": true, \"category\": \"cross-service\", \"reason\": {\"text\": \"It shows how the shop works.\", \"cites\": [\"%s\"]}}"
                .formatted(w, fact));
        order.add("\"" + w + "\"");
      }
    }
    return "{\"flows\": {"
        + String.join(", ", flows)
        + "}, \"order\": ["
        + String.join(", ", order)
        + "]}";
  }

  /** The answer about one item. */
  private static String item(String body) {
    if (body.startsWith("The database")) {
      return """
          {"responsibility": {"text": "Keeps the shop's orders and their lines.", "cites": ["F%s"]}}"""
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
          {"unit": {"text": "Takes and lists the shop's orders for its web customers.", "cites": ["F1", "F2"]},
           "components": {"C1": {"text": "Lists the orders the web shop has taken so far.", "cites": [%s]}},
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
        {"unit": {"text": "Processes the shop's orders in the background after they are announced.", "cites": ["F1"]},
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
    // With room for everything: what the purpose and each flow item are expected to cost.
    Harness room =
        new Harness(
            priced(new FakeLlmProvider().answering(DescriberTest::answer)),
            new InMemoryAnswerStore(),
            new RunBudget(new java.math.BigDecimal("1000000"), shares()),
            BlobLookup.NONE,
            Set.of());
    new Describer(room).describe(facts.list());
    java.math.BigDecimal purpose = record(room, "purpose").estimate().usd();
    io.lantern.harness.Estimate chosen = record(room, "flow-significance").estimate();
    io.lantern.harness.Estimate narrated = record(room, "flow-narrative").estimate();
    assertThat(chosen.toAsk()).isEqualTo(3);
    assertThat(narrated.toAsk()).isEqualTo(2);
    java.math.BigDecimal half = purpose.divide(java.math.BigDecimal.TWO);
    for (io.lantern.harness.Estimate flows : List.of(chosen, narrated)) {
      assertThat(
              flows
                  .usd()
                  .divide(
                      java.math.BigDecimal.valueOf(flows.toAsk()), java.math.MathContext.DECIMAL64))
          .isGreaterThan(half);
    }

    // A share that holds the purpose and half of it again: in the flows' order, the choice of
    // flows asked before it would take the share and leave the purpose too little; claimed first,
    // the purpose is asked and no flow item fits after it.
    Harness budget =
        new Harness(
            priced(new FakeLlmProvider().answering(DescriberTest::answer)),
            new InMemoryAnswerStore(),
            new RunBudget(purpose.add(half).multiply(java.math.BigDecimal.valueOf(100)), shares()),
            BlobLookup.NONE,
            Set.of());
    new Describer(budget).describe(facts.list());
    assertThat(record(budget, "purpose").asked()).hasSize(1);
    assertThat(record(budget, "flow-significance").asked()).isEmpty();
    assertThat(record(budget, "flow-narrative").asked()).isEmpty();
    assertThat(record(budget, "flow-narrative").settled()).isEqualTo(2);
  }

  private Describer.Result describe(FakeLlmProvider fake) {
    return new Describer(harness(client(fake))).describe(facts.list());
  }

  /** The system's document composed from the facts and what the Describe run stored. */
  private Document system(Describer.Result result) {
    return new Composer("1")
        .compose(
            ViewDefinition.c4System("system:c4", "System", TEMPLATE),
            SNAPSHOT,
            stored(facts.list(), result));
  }

  /** The facts with what the run's roles stored, as the analysis run records them. */
  static List<Fact> stored(List<Fact> facts, Describer.Result result) {
    List<Fact> all = new ArrayList<>(facts);
    result.facts(1).values().forEach(all::addAll);
    return all;
  }

  private Document system(TestTexts.Written wordings) {
    return new Composer("1")
        .compose(
            ViewDefinition.c4System("system:c4", "System", TEMPLATE),
            SNAPSHOT,
            TestTexts.with(facts.list(), wordings));
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

    assertThat(result.asked()).isEqualTo(11);
    assertThat(result.reused()).isZero();
    assertThat(result.rejected()).isEmpty();
    assertThat(result.skipped()).isEmpty();
    assertThat(fake.sent())
        .extracting(
            s -> (s.request().role() + " " + s.request().purpose()).replaceAll("[0-9a-f]{64}", "F"))
        .containsExactlyInAnyOrder(
            "INFER_INTENT describe-unit 1 items: " + APP,
            "INFER_INTENT describe-unit 1 items: " + WORKER,
            "DESCRIBE_ELEMENTS describe-element 1 items: " + DATABASE,
            "DESCRIBE_ELEMENTS describe-element 1 items: " + TOPIC,
            "DESCRIBE_ELEMENTS describe-element 1 items: " + PAY,
            // Which flows each scope draws, the system's and each repository's, then the
            // narrative of each flow drawn.
            "FLOW_SIGNIFICANCE flow-significance 1 items: system",
            "FLOW_SIGNIFICANCE flow-significance 1 items: base",
            "FLOW_SIGNIFICANCE flow-significance 1 items: shop",
            "COMPOSE flow-narrative 1 items: F",
            "COMPOSE flow-narrative 1 items: F",
            "INFER_INTENT purpose 1 items: " + RepoSections.SYSTEM,
            // The support judge's deterministic 10% sample takes the topic's sentence (ADR 0101).
            "JUDGE support-judge 1 items: " + TOPIC);
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
            [A1] shop-app: Takes and lists the shop's orders for its web customers.
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

    assertThat(result.asked()).isEqualTo(11);
    assertThat(fake.sent())
        .extracting(s -> s.request().purpose())
        .noneMatch(p -> p.contains("audit"));
  }

  @Test
  void aSentenceTheSupportJudgeFoundUnsupportedIsMarkedAndKept() {
    TestTexts.Written wordings =
        TestTexts.written(describe(new FakeLlmProvider().answering(DescriberTest::answer)))
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
                + " Takes and lists the shop's orders for its web customers.");
    DocumentEntity database =
        document.entities().stream().filter(e -> e.id().equals(DATABASE)).findFirst().orElseThrow();
    assertThat(text(database.description()))
        .isEqualTo(Assembler.INFERRED_SENTENCE + " Keeps the shop's orders and their lines.");
  }

  @Test
  void theModelReadsBackFromTheStoredFactsWhatDescribeWorded() {
    // Documents read only what is stored (knowledge-build epic, section 12): every sentence,
    // phrase, choice of flows and key fact Describe worded is read back from its facts.
    Describer.Result result = describe(new FakeLlmProvider().answering(DescriberTest::answer));
    TestTexts.Written worded = TestTexts.written(result);

    io.lantern.derive.ArchitectureModel model =
        new io.lantern.derive.ArchitectureModel(stored(facts.list(), result));

    assertThat(worded.responsibilities()).isNotEmpty();
    worded
        .responsibilities()
        .forEach(
            (element, sentence) ->
                assertThat(
                        (element.equals(RepoSections.SYSTEM)
                                ? model.purpose()
                                : model.responsibility(element))
                            .map(io.lantern.derive.ArchitectureModel.Text::marked))
                    .as(element.toString())
                    .contains(sentence));
    assertThat(worded.phrases()).isNotEmpty();
    worded
        .phrases()
        .forEach(
            (link, phrase) ->
                assertThat(
                        model.intent(
                            facts.list().stream()
                                .filter(f -> f.id().equals(link))
                                .findFirst()
                                .orElseThrow()))
                    .map(io.lantern.derive.ArchitectureModel.Text::text)
                    .as(link)
                    .contains(phrase.text()));
    assertThat(worded.chosen()).isNotEmpty();
    worded
        .chosen()
        .forEach((scope, flows) -> assertThat(model.significantFlows(scope)).contains(flows));
    assertThat(model.keyFact().map(io.lantern.derive.ArchitectureModel.Text::marked))
        .isEqualTo(java.util.Optional.ofNullable(worded.keyFact()));
    assertThat(model.documented()).isEqualTo(worded.documented());
  }

  @Test
  void composingTheSameStoredFactsTwiceInAnyOrderGivesTheSameDocument() {
    Describer.Result result = describe(new FakeLlmProvider().answering(DescriberTest::answer));
    List<Fact> stored = stored(facts.list(), result);
    List<Fact> shuffled = new ArrayList<>(stored);
    java.util.Collections.shuffle(shuffled, new java.util.Random(7));
    ViewDefinition view = ViewDefinition.c4System("system:c4", "System", TEMPLATE);

    String first =
        io.lantern.core.document.DocumentJson.write(
            new Composer("1").compose(view, SNAPSHOT, stored));

    assertThat(
            io.lantern.core.document.DocumentJson.write(
                new Composer("1").compose(view, SNAPSHOT, stored)))
        .isEqualTo(first);
    assertThat(
            io.lantern.core.document.DocumentJson.write(
                new Composer("1").compose(view, SNAPSHOT, shuffled)))
        .isEqualTo(first);
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
                        items
                                .group(2)
                                .contains(
                                    "Takes and lists the shop's orders for its web customers.")
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
    Document document = system(result);

    LlmRequest judged =
        fake.sent().stream()
            .map(FakeLlmProvider.Sent::request)
            .filter(r -> r.role() == Role.JUDGE)
            .findFirst()
            .orElseThrow();
    assertThat(judged.messages().getFirst().text())
        .contains("<text>Takes and lists the shop's orders for its web customers.</text>")
        .contains("[F1] service:com.example:app application-name \"shop-app\"");
    assertThat(TestTexts.written(result).unsupported()).containsExactly(APP);
    // Stored on the sentence's observation, its confidence halved (epic, section 11.7).
    Fact sentence =
        result.facts(1).values().stream()
            .flatMap(List::stream)
            .filter(f -> f.subject().equals(APP) && f.predicate().equals("responsibility"))
            .findFirst()
            .orElseThrow();
    assertThat(sentence.attributes().path("support").asString()).isEqualTo("unsupported");
    assertThat(sentence.confidence()).isEqualTo(io.lantern.harness.LlmFacts.CONFIDENCE * 0.5);
    DocumentEntity app =
        document.entities().stream().filter(e -> e.id().equals(APP)).findFirst().orElseThrow();
    assertThat(app.description().getFirst())
        .isEqualTo(new TextRun(Assembler.UNSUPPORTED, TextRun.Style.EMPHASIS));
    assertThat(text(app.description()))
        .isEqualTo(
            Assembler.UNSUPPORTED
                + " "
                + Assembler.INFERRED_SENTENCE
                + " Takes and lists the shop's orders for its web customers.");
  }

  @Test
  void documentsUseTheSentencesAsDescriptionsAndThePhrasesAsLabels() {
    TestTexts.Written wordings =
        TestTexts.written(describe(new FakeLlmProvider().answering(DescriberTest::answer)));

    Document document = system(wordings);

    DocumentEntity app =
        document.entities().stream().filter(e -> e.id().equals(APP)).findFirst().orElseThrow();
    // Citing no README claim, the sentences are marked as inferred (ADR 0065).
    assertThat(app.description().getFirst())
        .isEqualTo(new TextRun(Assembler.INFERRED_SENTENCE, TextRun.Style.EMPHASIS));
    assertThat(text(app.description()))
        .isEqualTo(
            Assembler.INFERRED_SENTENCE
                + " Takes and lists the shop's orders for its web customers.");
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
        .isEqualTo(
            Assembler.INFERRED_SENTENCE + " Lists the orders the web shop has taken so far.");
    DocumentEntity database =
        document.entities().stream().filter(e -> e.id().equals(DATABASE)).findFirst().orElseThrow();
    assertThat(text(database.description()))
        .isEqualTo(Assembler.INFERRED_SENTENCE + " Keeps the shop's orders and their lines.");
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
    // A flow's title is its scenario; its Overview table names its trigger (#767).
    return all(document.sections())
        .filter(s -> s.title().equals(title) || trigger(s).equals(title))
        .findFirst()
        .orElseThrow();
  }

  /** The trigger a flow's Overview table names, without its handler; empty for another section. */
  private static String trigger(Section section) {
    String trigger =
        section.blocks().stream()
            .filter(Table.class::isInstance)
            .map(Table.class::cast)
            .filter(t -> "Overview".equals(t.title()) && !t.rows().isEmpty())
            .map(t -> text(t.rows().getFirst().cells().getFirst()))
            .findFirst()
            .orElse("");
    int open = trigger.lastIndexOf(" (");
    return open < 0 || !trigger.endsWith(")") ? trigger : trigger.substring(0, open);
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
            .filter(r -> r.purpose().startsWith("flow-narrative"))
            .filter(r -> r.messages().getFirst().text().contains("receives GET /orders"))
            .findFirst()
            .orElseThrow();
    assertThat(list.role()).isEqualTo(io.lantern.llm.Role.COMPOSE);
    assertThat(list.system()).contains(FlowJudgements.NARRATIVE_TASK);
    assertThat(list.maxOutputTokens()).isEqualTo(1_500);
    // The steps as the facts give them, then the units' sentences and the interactions' phrases
    // Describe wrote, which the narrative may cite.
    assertThat(list.messages().getFirst().text())
        .isEqualTo(
            """
            <items>
            <item id="I1">
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
                [F3] (as above)
            What its units do, and what its interactions are for:
            - shop-app: [A1] Takes and lists the shop's orders for its web customers.
            - worker: [A2] Processes the shop's orders in the background after they are announced.
            - [A3] shop-app to orders (a topic), async: Announces new orders
            - [A4] shop-app to pay.example.com (an external system), sync: Charges customers' cards

            </item>
            </items>""");
  }

  @Test
  void documentsUseTheWalkthroughAndConditionsOfAFlowTracedAsTheModelSawIt() {
    Document document = system(describe(new FakeLlmProvider().answering(DescriberTest::answer)));

    Section flow = flowSection(document, LIST_FLOW);
    // The model's title is the page's title; the trigger is in its Overview table (ADR 0122).
    assertThat(flow.title()).isEqualTo("Shop lists its orders");
    Paragraph prose = (Paragraph) flow.blocks().getFirst();
    String endpoint = facts.find(LIST.toString(), "endpoint").id();
    String send = facts.find(APP.toString(), "PUBLISHES").id();
    assertThat(text(prose.content()))
        .isEqualTo(
            "Lists the orders the shop has taken so far, then announces each new order so that the work on it can carry on later in the background.");
    assertThat(prose.content().getLast())
        .isEqualTo(new Citation(Stream.of(endpoint, send).sorted().toList()));
    assertThat(prose.facts()).contains(endpoint, send);
    Table.Row row = overview(flow);
    // Left out by the model: not known, as before.
    assertThat(text(row.cells().get(1))).isEqualTo("Not known from the code.");
    assertThat(text(row.cells().get(2)))
        .isEqualTo("The orders are announced for the work that follows in the background.");
    assertThat(row.cells().get(2).getLast()).isEqualTo(new Citation(List.of(send)));
    assertThat(row.facts()).contains(endpoint, send);
    assertThat(text(((Paragraph) flowSection(document, RUN_FLOW).blocks().getFirst()).content()))
        .isEqualTo(
            "Lists the orders the shop has taken so far, then announces each new order so that the work on it can carry on later in the background.");
  }

  @Test
  void aRepositoryDrawsAStoredFlowWholeOrNotAtAll() {
    // The repository's documents leave out the worker's listener, which the stored flow rests on
    // (#618): they cannot cite it, so they do not draw the flow, and no prompt asks about a flow
    // traced from their facts alone.
    List<Fact> repoFacts =
        facts.list().stream().filter(f -> !f.predicate().equals("SENDS_MESSAGES_TO")).toList();
    FakeLlmProvider fake = new FakeLlmProvider().answering(DescriberTest::answer);
    TestTexts.Written both =
        TestTexts.written(new Describer(harness(client(fake))).describe(facts.list(), repoFacts));

    Document repo =
        new Composer("1")
            .compose(
                ViewDefinition.c4Report("shop.c4", "shop", TEMPLATE),
                SNAPSHOT,
                TestTexts.with(repoFacts, both));
    assertThat(all(repo.sections()))
        .noneMatch(s -> s.title().equals("Shop lists its orders") || trigger(s).equals(LIST_FLOW));
    assertThat(fake.sent())
        .extracting(s -> s.request().messages().getFirst().text())
        .noneMatch(p -> p.contains("No listener for it was found"));
  }

  @Test
  void aFlowNotWordedAsTracedOrCitingNoFactOfTheSnapshotKeepsItsProse() {
    TestTexts.Written worded =
        TestTexts.written(describe(new FakeLlmProvider().answering(DescriberTest::answer)));
    String unknown = " [fact:" + "f".repeat(64) + "]";
    Map<String, FlowJudgements.Flow> citingNothing = new java.util.HashMap<>();
    worded
        .flows()
        .keySet()
        .forEach(
            key ->
                citingNothing.put(
                    key,
                    new FlowJudgements.Flow(
                        "Lists orders." + unknown, null, "Orders announced." + unknown)));
    Map<String, FlowJudgements.Flow> otherTrace =
        Map.of("0".repeat(64), worded.flows().values().iterator().next());

    for (Map<String, FlowJudgements.Flow> flows : List.of(citingNothing, otherTrace)) {
      Section flow =
          flowSection(system(new TestTexts.Written(Map.of(), Map.of(), flows)), LIST_FLOW);

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
            .filter(r -> r.purpose().startsWith("purpose 1 items"))
            .findFirst()
            .orElseThrow();
    assertThat(purpose.role()).isEqualTo(io.lantern.llm.Role.INFER_INTENT);
    assertThat(purpose.system()).contains(PurposeRole.TASK);
    assertThat(purpose.maxOutputTokens()).isEqualTo(1_000);
    assertThat(purpose.messages().getFirst().text())
        .isEqualTo(
            """
            <items>
            <item id="I1">
            The software system.
            Its deployable units, each with what it does:
            - shop-app: [A1] Takes and lists the shop's orders for its web customers.
              [F1] service:com.example:app application-name "shop-app" (shop:pom.xml)
              [F2] service:com.example:app deployable-unit "spring-boot" (shop:pom.xml)
            - worker: [A2] Processes the shop's orders in the background after they are announced.
              [F3] service:com.example:worker deployable-unit "spring-boot" (base:pom.xml)
            The external systems it uses:
            - pay.example.com: [F4] service:com.example:app CALLS_HTTP\
             external-system:pay.example.com {"httpMethod":"POST","path":"/charges",\
            "profile":"prod","protocol":"HTTPS","source":"method:com.example.web.Controller#list()"}\
             (shop:pom.xml)
            Its main flows:
            - Shop lists its orders
            What the READMEs of its repositories claim:
            shop (README.md):
              [F5] readme:shop:README.md claim "The app keeps its orders in MySQL." (shop:README.md)
            </item>
            </items>""");
    assertThat(TestTexts.written(result).responsibilities().get(RepoSections.SYSTEM))
        .isEqualTo(
            "Runs an online shop's orders from the cart to the card payment. [fact:"
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
                  if (request.system().contains(PurposeRole.TASK)) {
                    return answer(request);
                  }
                  // Units, data stores, destinations and flows left out.
                  return "{\"items\": {\"I1\": {\"omitted\": true}}}";
                });

    Describer.Result result = new Describer(harness(client(fake))).describe(withClaim);

    assertThat(
            fake.sent().stream()
                .map(FakeLlmProvider.Sent::request)
                .filter(r -> r.purpose().startsWith("purpose 1 items"))
                .findFirst()
                .orElseThrow()
                .messages()
                .getFirst()
                .text())
        .isEqualTo(
            """
            <items>
            <item id="I1">
            The software system.
            The external systems it uses:
            - pay.example.com: [F1] service:com.example:app CALLS_HTTP\
             external-system:pay.example.com {"httpMethod":"POST","path":"/charges",\
            "profile":"prod","protocol":"HTTPS","source":"method:com.example.web.Controller#list()"}\
             (shop:pom.xml)
            What the READMEs of its repositories claim:
            shop (README.md):
              [F2] readme:shop:README.md claim "The app keeps its orders in MySQL." (shop:README.md)
            </item>
            </items>""");
    assertThat(TestTexts.written(result).responsibilities()).containsKey(RepoSections.SYSTEM);
  }

  @Test
  void aPurposeRejectedTwiceIsCountedStoredAsDroppedAndLeavesItUnknown() {
    FakeLlmProvider fake =
        new FakeLlmProvider()
            .answering(
                request ->
                    request.system().contains(PurposeRole.TASK)
                        ? "{\"items\": {\"I1\": {\"purpose\": {\"text\": \"Sells things.\","
                            + " \"cites\": [\"F99\"]}}}}"
                        : answer(request));

    Describer.Result result = describe(fake);

    assertThat(result.rejected()).containsExactly(RepoSections.SYSTEM);
    assertThat(fake.sent())
        .extracting(s -> s.request().purpose())
        .contains("purpose 1 items: " + RepoSections.SYSTEM + " (repair)");
    assertThat(TestTexts.written(result).responsibilities()).doesNotContainKey(RepoSections.SYSTEM);
    // Ten answers, the support judge's on its sample and the purpose's drop, which a rerun repeats
    // without a call (#682).
    assertThat(store.size()).isEqualTo(12);
    Callout first = (Callout) system(result).sections().getFirst().blocks().getFirst();
    assertThat(text(first.content())).isEqualTo(RepoSections.UNKNOWN_PURPOSE);
  }

  @Test
  void aSentenceCitingAReadmeClaimIsShownAsWrittenAndOneCitingNoneAsInferred() {
    List<Fact> withClaim = TestFacts.runtimeWithContradictedClaim();
    Fact claim =
        withClaim.stream().filter(f -> f.predicate().equals("claim")).findFirst().orElseThrow();
    TestTexts.Written wordings =
        new TestTexts.Written(
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
                "Keeps the shop's orders and their lines. [fact:"
                    + facts.find(APP.toString(), "USES_DATABASE").id()
                    + "]"),
            Map.of());

    Document document =
        new Composer("1")
            .compose(
                ViewDefinition.c4System("system:c4", "System", TEMPLATE),
                SNAPSHOT,
                TestTexts.with(withClaim, wordings));

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
        .isEqualTo(Assembler.INFERRED_SENTENCE + " Keeps the shop's orders and their lines.");
  }

  @Test
  void whetherASentenceIsInferredIsTheBasisItsRoleStoredNotItsCitations() {
    // The role stored "inferred" for a sentence citing a README claim, and "evidenced" for one
    // citing only code: the document shows what was stored (#767).
    List<Fact> withClaim = TestFacts.runtimeWithContradictedClaim();
    Fact claim =
        withClaim.stream().filter(f -> f.predicate().equals("claim")).findFirst().orElseThrow();
    List<Fact> all = new ArrayList<>(withClaim);
    all.add(
        io.lantern.harness.LlmFacts.text(
            DescribeRole.UNITS,
            "test/test",
            APP,
            io.lantern.core.types.Types.RESPONSIBILITY,
            io.lantern.harness.LlmFacts.C4,
            "Takes the shop's orders.",
            List.of(claim),
            io.lantern.harness.LlmFacts.Basis.INFERRED,
            1));
    all.add(
        io.lantern.harness.LlmFacts.text(
            DescribeRole.UNITS,
            "test/test",
            WORKER,
            io.lantern.core.types.Types.RESPONSIBILITY,
            io.lantern.harness.LlmFacts.C4,
            "Processes new orders.",
            List.of(facts.find(WORKER.toString(), "deployable-unit")),
            io.lantern.harness.LlmFacts.Basis.EVIDENCED,
            1));

    Document document =
        new Composer("1")
            .compose(
                ViewDefinition.c4System("system:c4", "System", TEMPLATE),
                SNAPSHOT,
                io.lantern.derive.Derivation.withModel(all, 1));

    Function<EntityId, String> description =
        id ->
            text(document.entities().stream()
                    .filter(e -> e.id().equals(id))
                    .findFirst()
                    .orElseThrow()
                    .description())
                .strip();
    assertThat(description.apply(APP))
        .isEqualTo(Assembler.INFERRED_SENTENCE + " Takes the shop's orders.");
    assertThat(description.apply(WORKER)).isEqualTo("Processes new orders.");
  }

  @Test
  void aPurposeCitingAReadmeClaimAndUnitFactsIsShownAsWritten() {
    List<Fact> withClaim = TestFacts.runtimeWithContradictedClaim();
    Fact claim =
        withClaim.stream().filter(f -> f.predicate().equals("claim")).findFirst().orElseThrow();
    TestTexts.Written wordings =
        new TestTexts.Written(
            Map.of(
                RepoSections.SYSTEM,
                "Runs an online shop's orders from the cart to the card payment. [fact:"
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
                TestTexts.with(withClaim, wordings));

    Paragraph first = (Paragraph) document.sections().getFirst().blocks().getFirst();
    assertThat(text(first.content()))
        .isEqualTo("Runs an online shop's orders from the cart to the card payment.");
    assertThat(first.facts()).contains(claim.id());
  }

  @Test
  void aPurposeFromAReadmeClaimOpensTheSystemsOverviewAndDescribesTheSystem() {
    List<Fact> withClaim = TestFacts.runtimeWithContradictedClaim();
    TestTexts.Written wordings =
        TestTexts.written(
            new Describer(harness(client(new FakeLlmProvider().answering(DescriberTest::answer))))
                .describe(withClaim));

    Document document =
        new Composer("1")
            .compose(
                ViewDefinition.c4System("system:c4", "System", TEMPLATE),
                SNAPSHOT,
                TestTexts.with(withClaim, wordings));

    // The purpose, then the key fact, in one paragraph, each cited.
    Paragraph first = (Paragraph) document.sections().getFirst().blocks().getFirst();
    assertThat(text(first.content()))
        .isEqualTo(
            "Runs an online shop's orders from the cart to the card payment. Card payments go to pay.example.com, the one outside service the shop calls.");
    assertThat(first.content().getLast()).isInstanceOf(Citation.class);
    assertThat(first.content()).filteredOn(Citation.class::isInstance).hasSizeGreaterThan(1);
    DocumentEntity system =
        document.entities().stream()
            .filter(e -> e.id().equals(RepoSections.SYSTEM))
            .findFirst()
            .orElseThrow();
    assertThat(text(system.description()))
        .isEqualTo("Runs an online shop's orders from the cart to the card payment.");
  }

  @Test
  void aPurposeCitingNoReadmeClaimIsMarkedAsInferred() {
    TestTexts.Written wordings =
        TestTexts.written(describe(new FakeLlmProvider().answering(DescriberTest::answer)));

    Document document = system(wordings);

    // Marked as an inferred summary is (ADR 0059), in the overview and the system's description.
    Paragraph first = (Paragraph) document.sections().getFirst().blocks().getFirst();
    assertThat(first.content().getFirst())
        .isEqualTo(new TextRun(Assembler.INFERRED, TextRun.Style.EMPHASIS));
    assertThat(text(first.content()))
        .isEqualTo(
            Assembler.INFERRED
                + " Runs an online shop's orders from the cart to the card payment. Card payments go to pay.example.com, the one outside service the shop calls.");
    assertThat(
            text(
                document.entities().stream()
                    .filter(e -> e.id().equals(RepoSections.SYSTEM))
                    .findFirst()
                    .orElseThrow()
                    .description()))
        .isEqualTo(
            Assembler.INFERRED
                + " Runs an online shop's orders from the cart to the card payment.");
    assertThat(first.facts())
        .contains(
            facts.find(APP.toString(), "application-name").id(),
            facts.find(APP.toString(), "CALLS_HTTP").id());
  }

  /** The purpose and key-fact role's prompt is the epic's, character for character (11.6). */
  @Test
  void thePurposePromptIsTheEpics() throws Exception {
    String epic =
        java.nio.file.Files.readString(
            java.nio.file.Path.of("../docs/epics/001-knowledge-build.md"));

    assertThat(epic).contains(PurposeRole.TASK.strip()).contains(PurposeRole.SCHEMA.strip());
  }

  @Test
  void thePurposeAndKeyFactAreClaimsOfTheSystem() {
    List<Fact> withClaim = TestFacts.runtimeWithContradictedClaim();
    Describer.Result result =
        new Describer(harness(client(new FakeLlmProvider().answering(DescriberTest::answer))))
            .describe(withClaim);

    List<Fact> claims = result.facts(2).get(io.lantern.harness.LlmFacts.origin(Describer.PURPOSE));
    assertThat(claims)
        .extracting(f -> f.subject() + " " + f.predicate() + " " + f.object().value())
        .containsExactly(RepoSections.SYSTEM + " purpose c4", RepoSections.SYSTEM + " key-fact c4");
    // The purpose cites the README's claim, so it is evidenced; the key fact cites code.
    assertThat(claims)
        .extracting(f -> f.attributes().path("basis").asString())
        .containsExactly("evidenced", "inferred");
    assertThat(claims.get(1).attributes().path("text").asString())
        .isEqualTo("Card payments go to pay.example.com, the one outside service the shop calls.");
    assertThat(TestTexts.written(result).keyFact())
        .isEqualTo(
            "Card payments go to pay.example.com, the one outside service the shop calls. [fact:"
                + facts.find(APP.toString(), "CALLS_HTTP").id()
                + "]");
    for (Fact claim : claims) {
      io.lantern.harness.LlmFacts.vocabulary().stream()
          .filter(type -> type.name().equals(claim.predicate()))
          .findFirst()
          .orElseThrow()
          .attributeSchema()
          .validate(claim.attributes());
    }
  }

  @Test
  void aKeyFactWithoutAPurposeFollowsTheUnknownPurpose() {
    FakeLlmProvider keyFactOnly =
        new FakeLlmProvider()
            .answering(
                request ->
                    request.system().contains(PurposeRole.TASK)
                        ? answer(request).replaceFirst("\"purpose\": \\{[^}]*\\},\\s*", "")
                        : answer(request));

    Document document = system(describe(keyFactOnly));

    List<io.lantern.core.document.Block> blocks = document.sections().getFirst().blocks();
    assertThat(text(((Callout) blocks.get(0)).content())).isEqualTo(RepoSections.UNKNOWN_PURPOSE);
    assertThat(text(((Paragraph) blocks.get(1)).content()))
        .isEqualTo("Card payments go to pay.example.com, the one outside service the shop calls.");
  }

  @Test
  void withNoSentenceAndNoClaimThePurposeIsNotAsked() {
    List<String> systems = new java.util.ArrayList<>();
    FakeLlmProvider omitsAll =
        new FakeLlmProvider()
            .answering(
                request -> {
                  systems.add(request.system());
                  List<String> entries =
                      java.util.regex.Pattern.compile("<item id=\"(I[0-9]+)\">")
                          .matcher(request.messages().getFirst().text())
                          .results()
                          .map(m -> "\"" + m.group(1) + "\": {\"omitted\": true}")
                          .toList();
                  return "{\"items\": {" + String.join(", ", entries) + "}}";
                });

    Describer.Result result = describe(omitsAll);

    assertThat(systems).noneMatch(system -> system.contains(PurposeRole.TASK));
    Callout first = (Callout) system(result).sections().getFirst().blocks().getFirst();
    assertThat(text(first.content())).isEqualTo(RepoSections.UNKNOWN_PURPOSE);
  }

  @Test
  void withoutAPurposeTheSystemsOverviewStatesItAsUnknown() {
    FakeLlmProvider leavesItOut =
        new FakeLlmProvider()
            .answering(
                request ->
                    request.system().contains(PurposeRole.TASK)
                        ? "{\"items\": {\"I1\": {\"omitted\": true}}}"
                        : answer(request));
    Describer.Result result = describe(leavesItOut);

    Document document = system(result);

    assertThat(result.asked()).isEqualTo(11);
    assertThat(TestTexts.written(result).responsibilities()).doesNotContainKey(RepoSections.SYSTEM);
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
    TestTexts.Written first = TestTexts.written(describe(fake));
    int calls = fake.sent().size();

    Describer.Result second = describe(fake);

    assertThat(fake.sent()).hasSize(calls);
    assertThat(second.asked()).isZero();
    assertThat(second.reused()).isEqualTo(11);
    assertThat(TestTexts.written(second)).isEqualTo(first);
  }

  @Test
  void anAnswerRejectedTwiceLeavesTheElementAsItWasAndIsStoredAsDropped() {
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
                            : request.system().contains("Task: narrate flows.")
                                ? """
                                {"items": {"I1":
                                  {"walkthrough": {"text": "Does things.", "cites": ["F99"]}}}}"""
                                : """
                                {"items": {"I1": {"flows": {}, "order": ["W99"]}}}""");

    Describer.Result result = describe(fake);

    // Each unit and element, each scope's choice of flows and each flow drawn by rank, twice.
    assertThat(fake.sent()).hasSize(20);
    assertThat(fake.sent())
        .extracting(s -> s.request().purpose().replaceAll("[0-9a-f]{64}", "F"))
        .contains(
            "describe-unit 1 items: " + APP + " (repair)",
            "describe-element 1 items: " + DATABASE + " (repair)",
            "flow-significance 1 items: system (repair)",
            "flow-narrative 1 items: F (repair)");
    assertThat(fake.sent())
        .filteredOn(s -> s.request().purpose().startsWith("flow-narrative"))
        .filteredOn(s -> s.request().purpose().endsWith("(repair)"))
        .hasSize(2)
        .allSatisfy(s -> assertThat(s.request().messages().getLast().text()).contains("F99"));
    assertThat(fake.sent())
        .filteredOn(s -> s.request().purpose().startsWith("describe-"))
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
    // The flows, drawn by rank once no scope's choice held, are rejected too.
    assertThat(result.rejected()).contains(APP, WORKER, DATABASE, TOPIC, PAY).hasSize(7);
    assertThat(result.rejected().subList(5, 7))
        .allSatisfy(flow -> assertThat(flow.toString()).startsWith("flow:" + APP + "/"));
    // Without a unit's sentence or a README claim, the system's purpose is not asked about.
    assertThat(fake.sent())
        .extracting(s -> s.request().purpose())
        .noneMatch(p -> p.contains("purpose"));
    assertThat(TestTexts.written(result)).isEqualTo(TestTexts.Written.NONE);
    // Only the drops are stored, which a rerun repeats without a call (#682).
    assertThat(store.size()).isEqualTo(10);
    // The documents keep summaries and static labels.
    assertThat(edges(diagrams(system(result), Diagram.Kind.C4_CONTEXT).getFirst()))
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

    // The flows, drawn by rank as no model judged them, are skipped too.
    assertThat(result.skipped()).contains(APP, WORKER, DATABASE, TOPIC, PAY).hasSize(7);
    assertThat(result.skipped().subList(5, 7))
        .allSatisfy(flow -> assertThat(flow.toString()).startsWith("flow:" + APP + "/"));
    assertThat(TestTexts.written(result)).isEqualTo(TestTexts.Written.NONE);
  }

  @Test
  void aCitationOfAFactNotInTheDocumentsFactsIsLeftOutAndWithoutAnyTheSummaryStays() {
    Fact deployable = facts.find(APP.toString(), "deployable-unit");
    TestTexts.Written wordings =
        new TestTexts.Written(
            Map.of(APP, "Takes orders. [fact:" + "f".repeat(64) + "]"),
            Map.of(
                facts.find(APP.toString(), "PUBLISHES").id(),
                new Describer.Phrase("Announces orders", List.of("e".repeat(64)))));

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
    // A stored sentence can cite a fact the document's facts do not hold, such as one of another
    // repository: its citation is left out, and the text reads as written.
    Fact gone = facts.find(WORKER.toString(), "deployable-unit");
    String good = "[fact:" + facts.find(APP.toString(), "deployable-unit").id() + "]";
    String bad = "[fact:" + gone.id() + "]";
    TestTexts.Written wordings =
        new TestTexts.Written(
            Map.of(
                APP, "Takes orders. " + bad + " " + good + " " + bad,
                WORKER, "Stores orders and ships them. " + bad + " " + good),
            Map.of());
    List<Fact> stored =
        TestTexts.with(facts.list(), wordings).stream()
            .filter(f -> !f.id().equals(gone.id()))
            .toList();

    Document document =
        new Composer("1")
            .compose(ViewDefinition.c4System("system:c4", "System", TEMPLATE), SNAPSHOT, stored);

    for (EntityId id : List.of(APP, WORKER)) {
      DocumentEntity entity =
          document.entities().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
      String description = text(entity.description());
      assertThat(description).isNotEmpty().isEqualTo(description.strip());
      assertThat(description).doesNotContain("  ").doesNotContain(" .");
    }
    DocumentEntity appEntity =
        document.entities().stream().filter(e -> e.id().equals(APP)).findFirst().orElseThrow();
    assertThat(text(appEntity.description())).contains("Takes orders.");
    DocumentEntity worker =
        document.entities().stream().filter(e -> e.id().equals(WORKER)).findFirst().orElseThrow();
    assertThat(text(worker.description()))
        .endsWith("Stores orders and ships them.")
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
    assertThat(new ArrayList<>(TestTexts.written(result).responsibilities().keySet())).isEmpty();
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
    assertThat(second.reused()).isEqualTo(11);
    // The answers cite the new commit's facts.
    assertThat(TestTexts.written(second).phrases())
        .containsKey(
            moved.stream()
                .filter(f -> f.subject().equals(APP) && f.predicate().equals("PUBLISHES"))
                .findFirst()
                .orElseThrow()
                .id());
  }

  @Test
  void aMergedLineShowsTheMostCommonIntentOfItsLinksOnceAnyHasOne() {
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
    Function<TestTexts.Written, String> label =
        wordings ->
            diagrams(
                    new Composer("1")
                        .compose(
                            ViewDefinition.c4System("system:c4", "System", TEMPLATE),
                            SNAPSHOT,
                            TestTexts.with(more.list(), wordings)),
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
                new TestTexts.Written(
                    Map.of(),
                    Map.of(
                        sends.get(0).id(), new Describer.Phrase("Announces orders", List.of())))))
        // One link of the two has an intent: the line shows it (#739), not "Sends order.paid".
        .isEqualTo("Announces orders");
    assertThat(label.apply(new TestTexts.Written(Map.of(), Map.of())))
        .isEqualTo("Sends order.paid");
    assertThat(
            label.apply(
                new TestTexts.Written(
                    Map.of(),
                    Map.of(
                        sends.get(0).id(),
                        new Describer.Phrase("Announces orders", List.of()),
                        sends.get(1).id(),
                        new Describer.Phrase("Reports payments", List.of())))))
        .isEqualTo("Announces orders");
  }

  @Test
  void componentLinesToStoresAndProfileOnlyLinesShowTheirStoredIntent() {
    TestFacts runtime = TestFacts.runtime();
    Fact store = runtime.find(APP.toString(), "USES_DATABASE");
    Fact pay = runtime.find(APP.toString(), "CALLS_HTTP");
    Document document =
        new Composer("1")
            .compose(
                ViewDefinition.c4System("system:c4", "System", TEMPLATE),
                SNAPSHOT,
                TestTexts.with(
                    runtime.list(),
                    new TestTexts.Written(
                        Map.of(),
                        Map.of(
                            store.id(), new Describer.Phrase("Keeps its orders in", List.of()),
                            pay.id(), new Describer.Phrase("Charges cards through", List.of())))));
    // A component's line to its unit's store shows the unit's intent for the store, not "Reads
    // and writes" (#739).
    assertThat(edges(diagrams(document, Diagram.Kind.C4_COMPONENT).getFirst()))
        .anyMatch(e -> e.endsWith("-> database:db:5432/shop: Keeps its orders in"))
        .noneMatch(e -> e.endsWith("-> database:db:5432/shop: Reads and writes"));
    // A line drawn only under a Spring profile keeps its intent; the profile is its own field.
    Diagram.Edge charge =
        diagrams(document, Diagram.Kind.C4_CONTAINER).getFirst().edges().stream()
            .filter(e -> e.to().equals(PAY))
            .findFirst()
            .orElseThrow();
    assertThat(charge.label()).isEqualTo("Charges cards through");
    assertThat(charge.profiles()).containsExactly("prod");
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
    assertThat(TestTexts.written(result).documented()).containsExactly(controller.id());
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
    assertThat(TestTexts.written(same).documented())
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
    Function<TestTexts.Written, Document> compose =
        wordings ->
            new Composer("1")
                .compose(
                    ViewDefinition.c4System("system:c4", "System", TEMPLATE),
                    SNAPSHOT,
                    TestTexts.with(withCode, wordings));
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
            new TestTexts.Written(
                sentences, Map.of(), Map.of(), java.util.Set.of(controller.id())));
    Document undocumented = compose.apply(new TestTexts.Written(sentences, Map.of()));

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

  /** The flow roles' prompts are the epic's, character for character (section 11.5). */
  @Test
  void theFlowPromptsAreTheEpics() throws Exception {
    String epic =
        java.nio.file.Files.readString(
            java.nio.file.Path.of("../docs/epics/001-knowledge-build.md"));

    for (String prompt :
        List.of(
            FlowJudgements.SIGNIFICANCE_TASK,
            FlowJudgements.SIGNIFICANCE_SCHEMA,
            FlowJudgements.NARRATIVE_TASK,
            FlowJudgements.NARRATIVE_SCHEMA)) {
      assertThat(epic).contains(prompt.strip());
    }
  }

  /** The sequences section of a document. */
  private static Section sequences(Document document) {
    return all(document.sections())
        .filter(s -> s.id().equals("sequences"))
        .findFirst()
        .orElseThrow();
  }

  /** The texts of a section's own callouts. */
  private static List<String> callouts(Section section) {
    return section.blocks().stream()
        .filter(Callout.class::isInstance)
        .map(b -> text(((Callout) b).content()))
        .toList();
  }

  /**
   * A significance answer: each flow whose start line contains one of the words significant, in the
   * words' order, the others not.
   */
  private static String choosing(String prompt, String... words) {
    java.util.regex.Matcher items =
        java.util.regex.Pattern.compile(
                "<item id=\"(I[0-9]+)\">\n(.*?)\n</item>", java.util.regex.Pattern.DOTALL)
            .matcher(prompt);
    items.find();
    Map<String, String> starts = new LinkedHashMap<>();
    Map<String, String> entry = new LinkedHashMap<>();
    String[] lines = items.group(2).split("\n");
    for (int i = 0; i < lines.length; i++) {
      if (lines[i].startsWith("[W")) {
        String w = lines[i].substring(1, lines[i].indexOf(']'));
        starts.put(w, lines[i]);
        String fact = lines[i + 1].strip();
        entry.put(w, fact.substring(1, fact.indexOf(']')));
      }
    }
    List<String> flows = new ArrayList<>();
    List<String> order = new ArrayList<>();
    for (String word : words) {
      starts.forEach(
          (w, line) -> {
            if (line.contains(word) && !order.contains("\"" + w + "\"")) {
              order.add("\"" + w + "\"");
            }
          });
    }
    starts.forEach(
        (w, line) ->
            flows.add(
                "\"%s\": {\"significant\": %s, \"category\": \"scheduled\", \"reason\": {\"text\": \"It runs on its own schedule in the shop.\", \"cites\": [\"%s\"]}}"
                    .formatted(w, order.contains("\"" + w + "\""), entry.get(w))));
    return "{\"items\": {\""
        + "I1"
        + "\": {\"flows\": {"
        + String.join(", ", flows)
        + "}, \"order\": ["
        + String.join(", ", order)
        + "]}}}";
  }

  @Test
  void theModelChoosesWhichFlowsEachScopeDrawsAndInWhatOrder() {
    // Only the scheduled job is worth drawing; the request is not.
    FakeLlmProvider fake =
        new FakeLlmProvider()
            .answering(
                request ->
                    request.system().contains("Task: choose the flows worth drawing.")
                        ? choosing(request.messages().getFirst().text(), "schedule")
                        : answer(request));

    Describer.Result result = describe(fake);

    // Two flows, one for each shape, in the order of what starts them; the units they cross.
    String asked =
        fake.sent().stream()
            .map(FakeLlmProvider.Sent::request)
            .filter(r -> r.purpose().equals("flow-significance 1 items: system"))
            .findFirst()
            .orElseThrow()
            .messages()
            .getFirst()
            .text();
    assertThat(asked)
        .startsWith(
            "<items>\n<item id=\"I1\">\nScope: the system\nFlows, one for each different shape:\n")
        .contains("\n[W1] Starts when shop-app receives GET /orders (Controller.list)\n")
        .contains("\n[W2] Starts when the schedule 0 0 * * * * fires in shop-app (Jobs.run)\n")
        .contains("  Hands work on asynchronously through: orders\n")
        .contains(
            "What the units these flows cross do:\n"
                + "- shop-app: [A1] Takes and lists the shop's orders for its web customers.\n"
                + "- worker: [A2] Processes the shop's orders in the background after they are announced.\n");
    // Only the flow chosen is narrated, once for all scopes, and drawn without the rank's note.
    assertThat(fake.sent())
        .filteredOn(s -> s.request().purpose().startsWith("flow-narrative"))
        .hasSize(1);
    Document document = system(result);
    assertThat(sequences(document).sections())
        .extracting(Section::title)
        .containsExactly("Shop lists its orders");
    assertThat(callouts(sequences(document))).isEmpty();
    // Each judgement is a claim: the flow's scope, whether it is significant, its rank and reason.
    List<Fact> judged =
        result.facts(2).get(io.lantern.harness.LlmFacts.origin(FlowJudgements.SIGNIFICANCE));
    assertThat(judged)
        .filteredOn(f -> f.object().value().equals("system"))
        .hasSize(2)
        .allSatisfy(f -> assertThat(f.predicate()).isEqualTo("flow-significance"))
        .allSatisfy(f -> assertThat(f.attributes().path("model").asString()).isNotEmpty())
        .allSatisfy(f -> assertThat(f.attributes().path("content").asString()).isNotEmpty())
        .extracting(
            f ->
                f.attributes().path("significant").asBoolean()
                    + " "
                    + f.attributes().path("rank").asInt(0)
                    + " "
                    + f.attributes().path("text").asString())
        .containsExactlyInAnyOrder(
            "true 1 It runs on its own schedule in the shop.",
            "false 0 It runs on its own schedule in the shop.");
    assertThat(judged).extracting(f -> f.object().value()).contains("system", "shop", "base");
    List<Fact> narrated =
        result.facts(2).get(io.lantern.harness.LlmFacts.origin(FlowJudgements.NARRATIVE));
    assertThat(narrated).hasSize(1);
    assertThat(narrated.getFirst().predicate()).isEqualTo("flow-narrative");
    assertThat(narrated.getFirst().object().value()).isEqualTo("c4");
    assertThat(narrated.getFirst().attributes().path("title").asString())
        .isEqualTo("Shop lists its orders.");
    assertThat(narrated.getFirst().subject().toString()).startsWith("flow:" + APP + "/");
    assertThat(narrated.getFirst().attributes().path("cites")).isNotEmpty();
    // Their attributes are those their vocabulary types allow.
    for (Fact fact : List.of(judged.getFirst(), narrated.getFirst())) {
      io.lantern.harness.LlmFacts.vocabulary().stream()
          .filter(type -> type.name().equals(fact.predicate()))
          .findFirst()
          .orElseThrow()
          .attributeSchema()
          .validate(fact.attributes());
    }
  }

  @Test
  void aChoiceThatLeavesAFlowOutOrOrdersOneNotSignificantIsRepaired() {
    java.util.concurrent.atomic.AtomicInteger asked =
        new java.util.concurrent.atomic.AtomicInteger();
    FakeLlmProvider fake =
        new FakeLlmProvider()
            .answering(
                request -> {
                  if (!request.purpose().startsWith("flow-significance 1 items: system")) {
                    return request.system().contains("Task: choose the flows worth drawing.")
                        ? "{\"items\": {\"I1\": {\"omitted\": true}}}"
                        : answer(request);
                  }
                  String chosen = choosing(request.messages().getFirst().text(), "schedule");
                  // First the schedule's flow, W2, unanswered though ordered, and the request's,
                  // W1, ordered though not significant; then as chosen.
                  return asked.getAndIncrement() == 0
                      ? chosen
                          .replaceAll(", \"W2\": \\{[^}]*\\}\\}", "")
                          .replace("\"order\": [\"W2\"]", "\"order\": [\"W2\", \"W1\"]")
                      : chosen;
                });

    Describer.Result result = describe(fake);

    LlmRequest repair =
        fake.sent().stream()
            .map(FakeLlmProvider.Sent::request)
            .filter(r -> r.purpose().equals("flow-significance 1 items: system (repair)"))
            .findFirst()
            .orElseThrow();
    assertThat(repair.messages().getLast().text())
        .contains("Answers no flow for W2; answer every flow, significant or not.")
        .contains("The order must hold exactly the flows marked significant; it holds W2, W1.");
    assertThat(sequences(system(result)).sections())
        .extracting(Section::title)
        .containsExactly("Shop lists its orders");
  }

  @Test
  void aReasonRejectedTwiceIsLeftOutAndTheChoiceStands() {
    FakeLlmProvider fake =
        new FakeLlmProvider()
            .answering(
                request ->
                    request.system().contains("Task: choose the flows worth drawing.")
                        // The request's reason cites a fact the item does not show, every time.
                        ? choosing(request.messages().getFirst().text(), "schedule")
                            .replaceFirst("\"cites\": \\[\"F[0-9]+\"\\]", "\"cites\": [\"F99\"]")
                        : answer(request));

    Describer.Result result = describe(fake);

    assertThat(fake.sent())
        .filteredOn(s -> s.request().purpose().equals("flow-significance 1 items: system (repair)"))
        .singleElement()
        .satisfies(
            s ->
                assertThat(s.request().messages().getLast().text())
                    .contains("<rejection item=\"I1\" field=\"flows.W1.reason\">"));
    assertThat(sequences(system(result)).sections())
        .extracting(Section::title)
        .containsExactly("Shop lists its orders");
    // The flow whose reason was left out is still judged, without a reason.
    assertThat(result.facts(2).get(io.lantern.harness.LlmFacts.origin(FlowJudgements.SIGNIFICANCE)))
        .filteredOn(f -> f.object().value().equals("system"))
        .extracting(
            f -> f.attributes().path("significant").asBoolean() + " " + f.attributes().has("text"))
        .containsExactlyInAnyOrder("true true", "false false");
  }

  @Test
  void noFlowSignificantWhileSomeCrossUnitsIsAskedOnceToConfirm() {
    FakeLlmProvider fake =
        new FakeLlmProvider()
            .answering(
                request ->
                    request.system().contains("Task: choose the flows worth drawing.")
                        ? choosing(request.messages().getFirst().text())
                        : answer(request));

    Describer.Result result = describe(fake);

    assertThat(fake.sent())
        .filteredOn(s -> s.request().purpose().startsWith("flow-significance 1 items: system"))
        .hasSize(2)
        .last()
        .satisfies(
            s ->
                assertThat(s.request().messages().getLast().text())
                    .contains(FlowJudgements.NONE_SIGNIFICANT));
    // Confirmed, the scope draws no flow and says why; none is narrated.
    Section sequences = sequences(system(result));
    assertThat(sequences.sections()).isEmpty();
    assertThat(callouts(sequences)).containsExactly(SequenceFlows.NONE_JUDGED_SIGNIFICANT);
    assertThat(fake.sent()).noneMatch(s -> s.request().purpose().startsWith("flow-narrative"));
  }

  @Test
  void withoutAChoiceAScopeDrawsItsHighestRankedAndSaysSo() {
    FakeLlmProvider fake =
        new FakeLlmProvider()
            .answering(
                request ->
                    request.system().contains("Task: choose the flows worth drawing.")
                        ? "{\"items\": {\"I1\": {\"omitted\": true}}}"
                        : answer(request));

    Describer.Result result = describe(fake);

    Section sequences = sequences(system(result));
    assertThat(sequences.sections()).hasSize(2);
    assertThat(callouts(sequences)).containsExactly(SequenceFlows.CHOSEN_BY_RANK);
    assertThat(TestTexts.written(result).chosen()).isEmpty();
    assertThat(result.facts(2).get(io.lantern.harness.LlmFacts.origin(FlowJudgements.SIGNIFICANCE)))
        .isEmpty();
  }

  @Test
  void aLargeScopeIsJudgedUnitByUnitFirstKeepingTheBestOfEach() {
    Fact entry = facts.find(LIST.toString(), "endpoint");
    List<ScopeFlows.Candidate> candidates = new ArrayList<>();
    for (int i = 0; i < 61; i++) {
      EntityId unit = i % 2 == 0 ? APP : WORKER;
      candidates.add(
          new ScopeFlows.Candidate(
              EntityId.parse("flow:" + unit + "/" + "%02d".formatted(i)),
              unit,
              "flow " + i,
              entry,
              List.of("shop-app"),
              List.of(),
              List.of(),
              List.of(unit)));
    }
    Function<EntityId, String> names = EntityId::toString;

    assertThat(FlowJudgements.firstPass("system", candidates.subList(0, 60), Map.of(), names))
        .isEmpty();
    List<FlowJudgements.Asked> byUnit =
        FlowJudgements.firstPass("system", candidates, Map.of(), names);
    assertThat(byUnit)
        .extracting(a -> a.item().subject())
        .containsExactly("system/" + APP, "system/" + WORKER);
    assertThat(byUnit.getFirst().flows()).hasSize(31);
    assertThat(byUnit.getFirst().item().rendering())
        .contains("Scope: the flows that start in " + APP + "\n");

    // Judged, a unit keeps its significant flows in the model's order; unjudged, the first 15.
    FlowJudgements.Asked app = byUnit.getFirst();
    List<String> order =
        List.of(candidates.get(40).flow().toString(), candidates.get(2).flow().toString());
    assertThat(FlowJudgements.kept(app, order))
        .extracting(ScopeFlows.Candidate::flow)
        .containsExactly(candidates.get(40).flow(), candidates.get(2).flow());
    assertThat(FlowJudgements.kept(app, null)).hasSize(FlowJudgements.KEPT_PER_UNIT);
    assertThat(FlowJudgements.kept(app, List.of())).isEmpty();
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
            APP + " responsibility Takes and lists the shop's orders for its web customers.",
            "component:"
                + APP
                + "/type:com.example.web.Controller responsibility Lists the orders the web shop has taken so far.",
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
            DATABASE + " Keeps the shop's orders and their lines.",
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
  void aResponsibilityOrFlowTitleShorterThanItsContractAcceptsIsSentBackNamingTheRange() {
    // Describe's unit responsibility asks 10 to 22 words and accepts 8 to 25 (ADR 0118).
    FakeLlmProvider responsibility =
        new FakeLlmProvider().answering(unitSaying("Takes orders.", "Charges customers' cards"));
    describe(responsibility);
    assertThat(repair(responsibility, APP))
        .contains(
            "<rejection item=\"I1\" field=\"unit\">Has 2 words; the accepted range is 8 to 25"
                + " words, so say more.</rejection>");

    // A flow's title asks 3 to 5 words and accepts 2 to 6.
    FakeLlmProvider title =
        new FakeLlmProvider()
            .answering(
                request ->
                    request.system().contains("Task: narrate flows.")
                            && request.messages().size() == 1
                        ? answer(request).replace("\"Shop lists its orders.\"", "\"Orders.\"")
                        : answer(request));
    new Describer(
            new Harness(
                client(title),
                new InMemoryAnswerStore(),
                RunBudget.NONE,
                BlobLookup.NONE,
                Set.of()))
        .describe(facts.list());
    assertThat(title.sent())
        .filteredOn(
            s ->
                s.request().purpose().startsWith("flow-narrative")
                    && s.request().purpose().endsWith("(repair)"))
        .isNotEmpty()
        .allSatisfy(
            s ->
                assertThat(s.request().messages().getLast().text())
                    .contains(
                        "field=\"title\">Has 1 words; the accepted range is 2 to 6 words, so say"
                            + " more.</rejection>"));
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
            .answering(
                unitSaying(
                    "Takes the shop's orders from its customers on the web.",
                    "Charges cards over HTTPS"));
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

  @Test
  void aWordedFrontendIsDescribedByItsSentence() {
    TestFacts withFrontend =
        TestFacts.runtime()
            .attribute(
                "shop",
                "frontend:shop/web",
                "frontend-app",
                "angular",
                "{\"name\":\"web\",\"directory\":\"web\",\"framework\":\"Angular\","
                    + "\"version\":\"19.2\",\"server\":\"nginx\","
                    + "\"technology\":\"Angular 19.2 · nginx\"}")
            .relate(
                "shop",
                "frontend:shop/web",
                "CALLS_HTTP",
                "service:com.example:app",
                "{\"httpMethod\":\"GET\",\"path\":\"/orders\",\"protocol\":\"JSON/HTTP\","
                    + "\"url\":\"http://shop-app:8080/orders\",\"matchedBy\":\"host\"}");
    FakeLlmProvider fake =
        new FakeLlmProvider()
            .answering(
                request ->
                    request.messages().getFirst().text().contains("The deployable unit web")
                        ? """
                        {"items": {"I1": {"unit": {"text": "Lets shoppers browse the orders they placed with the shop.",
                          "cites": ["F1"]}}}}"""
                        : answer(request));

    Describer.Result result = new Describer(harness(client(fake))).describe(withFrontend.list());
    assertThat(TestTexts.written(result).responsibilities())
        .containsKey(EntityId.parse("frontend:shop/web"));

    Document document =
        new Composer("1")
            .compose(
                ViewDefinition.c4System("system:c4", "System", TEMPLATE),
                SNAPSHOT,
                TestTexts.with(withFrontend.list(), TestTexts.written(result)));
    assertThat(
            document.entities().stream()
                .filter(e -> e.id().toString().equals("frontend:shop/web"))
                .map(e -> text(e.description()))
                .findFirst()
                .orElseThrow())
        .contains("Lets shoppers browse the orders they placed with the shop.");
  }

  @Test
  void anItemMayNotCiteWhatAnotherItemOfItsRequestShows() {
    TestFacts two =
        TestFacts.runtime()
            .relate(
                "shop",
                APP.toString(),
                "CALLS_HTTP",
                "external-system:mail.example.com",
                "{\"protocol\": \"HTTPS\", \"httpMethod\": \"POST\", \"path\": \"/send\"}");
    FakeLlmProvider fake =
        new FakeLlmProvider()
            .answering(
                request -> {
                  if (!request.system().contains(DescribeRole.ELEMENT_TASK)
                      || !request.messages().getFirst().text().contains("pay.example.com")) {
                    return answer(request);
                  }
                  if (request.messages().size() > 1) {
                    return "{\"items\": {\"I1\": {\"omitted\": true}}}";
                  }
                  // I1's own fact is F1; F2 is what I2 shows.
                  return """
                      {"items": {
                        "I1": {"responsibility": {"text": "Takes card payments for the orders the shop sends it.", "cites": ["F2"]}},
                        "I2": {"responsibility": {"text": "Sends the shop's mail to customers about their orders.", "cites": ["F2"]}}}}""";
                });

    new Describer(harness(client(fake))).describe(two.list());

    String request =
        fake.sent().stream()
            .filter(s -> s.request().purpose().startsWith("describe-element 2 items"))
            .map(s -> s.request().messages().getFirst().text())
            .findFirst()
            .orElseThrow();
    assertThat(request)
        .contains("<item id=\"I1\">\nThe external system mail.example.com")
        .contains("<item id=\"I2\">\nThe external system pay.example.com");
    assertThat(
            fake.sent().stream()
                .filter(s -> s.request().purpose().endsWith("(repair)"))
                .map(s -> s.request().messages().getLast().text())
                .filter(t -> t.contains("<rejection"))
                .findFirst()
                .orElseThrow())
        .contains("<rejection item=\"I1\" field=\"responsibility\">Cites F2")
        .doesNotContain("item=\"I2\"");
  }

  @Test
  void aUnitsRequestHasRoomForATextPerComponentAndInteraction() {
    FakeLlmProvider fake = new FakeLlmProvider().answering(DescriberTest::answer);

    describe(fake);

    LlmRequest app =
        fake.sent().stream()
            .map(FakeLlmProvider.Sent::request)
            .filter(r -> r.purpose().equals("describe-unit 1 items: " + APP))
            .findFirst()
            .orElseThrow();
    // The unit, 3 components and 5 interactions.
    assertThat(app.maxOutputTokens()).isGreaterThanOrEqualTo(Math.max(1_000, 150 * 4 + 90 * 5));
  }
}

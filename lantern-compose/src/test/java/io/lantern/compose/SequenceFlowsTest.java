package io.lantern.compose;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.document.Callout;
import io.lantern.core.document.Document;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/** The main flows as sequence diagrams (docs/design.md, Sequence diagrams; ADR 0047). */
class SequenceFlowsTest {

  private static final TemplateRef TEMPLATE = new TemplateRef("c4-model-sequence-diagrams", "1");
  private static final Snapshot SNAPSHOT =
      new Snapshot(
          1,
          Map.of("front", TestFacts.COMMIT, "pay", TestFacts.COMMIT, "ship", TestFacts.COMMIT),
          1,
          "0.1.0",
          Instant.EPOCH);

  private static final String FRONT = "service:x:front";
  private static final String PAY = "service:x:pay";
  private static final String SHIP = "service:x:ship";
  private static final String PLACE = "method:x.front.Api#place()";
  private static final String SERVICE = "method:x.front.OrderService#place()";
  private static final String PAY_CLIENT = "method:x.front.PayClient#charge()";
  private static final String EVENTS = "method:x.front.Events#sent()";
  private static final String AUDIT = "method:x.front.Audit#log()";
  private static final String CHARGE = "method:x.pay.Api#charge()";
  private static final String GATEWAY = "method:x.pay.Gateway#post()";
  private static final String LISTEN = "method:x.ship.Listener#on()";
  private static final String STATUS = "method:x.ship.Status#get()";
  private static final String NIGHTLY = "method:x.ship.Nightly#run()";

  private final List<Fact> facts = new ArrayList<>();

  /**
   * Three units. front's {@code POST /orders} calls pay's {@code POST /pay}, which calls an
   * external gateway (another host under {@code prod}); then sends to the topic {@code orders},
   * which ship listens on and reads its database; under {@code audit} it also sends to a queue no
   * one listens on; a tax call stays unresolved. ship also has an endpoint that reaches nothing and
   * a nightly job that writes its database.
   */
  SequenceFlowsTest() {
    unit("front", FRONT, "front", "x.front", "Api", "OrderService", "PayClient", "Events", "Audit");
    unit("pay", PAY, "pay", "x.pay", "Api", "Gateway");
    unit("ship", SHIP, "ship", "x.ship", "Listener", "Status", "Nightly");
    attribute("front", PLACE, "endpoint", "POST /orders", "{}", 0);
    attribute("pay", CHARGE, "endpoint", "POST /pay", "{}", 0);
    attribute("ship", STATUS, "endpoint", "GET /status", "{}", 0);
    attribute("ship", LISTEN, "message-listener", "orders", "{\"broker\": \"kafka\"}", 0);
    attribute("ship", NIGHTLY, "scheduled", "0 0 2 * * *", "{\"trigger\": \"cron\"}", 0);
    attribute(
        "front", "type:x.front.Audit", "profile-gate", "audit", "{\"profiles\":[\"audit\"]}", 0);
    relate("front", PLACE, "CALLS", SERVICE, "{}", 10);
    relate("front", SERVICE, "CALLS", PAY_CLIENT, "{}", 21);
    relate("front", SERVICE, "CALLS", EVENTS, "{}", 23);
    relate("front", SERVICE, "CALLS", AUDIT, "{}", 24);
    relate("pay", CHARGE, "CALLS", GATEWAY, "{}", 5);
    relate("ship", STATUS, "CALLS", "method:x.ship.Status#format()", "{}", 3);
    relate("ship", LISTEN, "READS_TABLE", "table:x:ship:parcel", "{}", 7);
    relate("ship", NIGHTLY, "WRITES_TABLE", "table:x:ship:parcel", "{}", 4);
    // The linker's links.
    relate(
        "front",
        FRONT,
        "CALLS_HTTP",
        PAY,
        "{\"protocol\": \"JSON/HTTP\", \"endpoint\": \"POST /pay\", \"source\": \""
            + PAY_CLIENT
            + "\", \"target\": \""
            + CHARGE
            + "\"}",
        30);
    relate(
        "pay",
        PAY,
        "CALLS_HTTP",
        "external-system:gw.example.com",
        "{\"protocol\": \"HTTPS\", \"httpMethod\": \"POST\", \"path\": \"/v1/charges\","
            + " \"exceptProfiles\": [\"prod\"], \"alternates\": [\"external-system:live.example.com\"],"
            + " \"source\": \""
            + GATEWAY
            + "\"}",
        12);
    relate(
        "pay",
        PAY,
        "CALLS_HTTP",
        "external-system:live.example.com",
        "{\"protocol\": \"HTTPS\", \"httpMethod\": \"POST\", \"path\": \"/v1/charges\","
            + " \"profile\": \"prod\", \"alternates\": [\"external-system:gw.example.com\"],"
            + " \"source\": \""
            + GATEWAY
            + "\"}",
        12);
    relate(
        "front",
        FRONT,
        "PUBLISHES",
        "topic:kafka:orders",
        "{\"protocol\": \"Kafka\", \"source\": \"" + EVENTS + "\"}",
        40);
    relate(
        "front",
        FRONT,
        "SENDS_MESSAGES_TO",
        SHIP,
        "{\"protocol\": \"Kafka\", \"destination\": \"topic:kafka:orders\", \"source\": \""
            + EVENTS
            + "\", \"target\": \""
            + LISTEN
            + "\"}",
        40);
    relate(
        "front",
        FRONT,
        "CONSUMES",
        "queue:jms:audit",
        "{\"protocol\": \"JMS\", \"source\": \"method:x.front.Nowhere#on()\"}",
        0);
    relate(
        "front",
        FRONT,
        "PUBLISHES",
        "queue:jms:audit",
        "{\"protocol\": \"JMS\", \"profile\": \"audit\", \"source\": \"" + AUDIT + "\"}",
        50);
    attribute(
        "front",
        FRONT,
        "unresolved-link",
        "GET ${TAX_URL}/quotes",
        "{\"kind\": \"http\", \"reason\": \"unresolved-placeholder\", \"unresolved\": [\"TAX_URL\"],"
            + " \"source\": \""
            + SERVICE
            + "\"}",
        22);
    relate(
        "ship",
        SHIP,
        "USES_DATABASE",
        "database:db:5432/ship",
        "{\"protocol\": \"JDBC\", \"key\": \"spring.datasource.url\"}",
        0);
    relate(
        "ship",
        SHIP,
        "USES_DATABASE",
        "database:localhost:5432/ship",
        "{\"protocol\": \"JDBC\", \"key\": \"spring.datasource.url\", \"profile\": \"local\"}",
        0);
    attribute("ship", "database:db:5432/ship", "technology", "PostgreSQL", "{}", 0);
  }

  private void unit(String repo, String unit, String name, String pkg, String... types) {
    String module = "module:x:" + repo;
    String packageId = "package:x:" + repo + ":" + pkg;
    relate(repo, unit, "CONTAINS", module, "{}", 0);
    attribute(repo, module, "packaging", "jar", "{}", 0);
    attribute(repo, unit, "deployable-unit", "spring-boot", "{}", 0);
    attribute(repo, unit, "application-name", name, "{}", 0);
    attribute(repo, unit, "technology", "Spring Boot 3.5.0 / Java 21", "{}", 0);
    relate(repo, module, "CONTAINS", packageId, "{}", 0);
    for (String type : types) {
      String typeId = "type:" + pkg + "." + type;
      relate(repo, packageId, "CONTAINS", typeId, "{}", 0);
      for (String method : List.of(PLACE, SERVICE, PAY_CLIENT, EVENTS, AUDIT, CHARGE, GATEWAY)) {
        addMethod(repo, typeId, method);
      }
      for (String method : List.of(LISTEN, STATUS, NIGHTLY)) {
        addMethod(repo, typeId, method);
      }
    }
  }

  private void addMethod(String repo, String typeId, String method) {
    if (method.startsWith("method:" + typeId.substring("type:".length()) + "#")) {
      relate(repo, typeId, "CONTAINS", method, "{}", 0);
    }
  }

  private void relate(
      String repo, String subject, String predicate, String object, String json, int line) {
    facts.add(
        fact(repo, subject, predicate, FactObject.entity(EntityId.parse(object)), json, line));
  }

  private void attribute(
      String repo, String subject, String predicate, String value, String json, int line) {
    facts.add(fact(repo, subject, predicate, FactObject.string(value), json, line));
  }

  private static Fact fact(
      String repo, String subject, String predicate, FactObject object, String json, int line) {
    return Fact.create(
        EntityId.parse(subject),
        predicate,
        object,
        (ObjectNode) FactJson.parse(json),
        new Origin(Origin.Kind.STATIC, "test", "1"),
        new Evidence(
            repo,
            TestFacts.COMMIT,
            "Code.java",
            line == 0 ? null : new Evidence.LineRange(line, line),
            null),
        1.0,
        Validity.from(1));
  }

  private Document system(List<Fact> facts) {
    return new Composer("1")
        .compose(
            ViewDefinition.c4System("system:c4", "System", TEMPLATE), SNAPSHOT, facts, Map.of());
  }

  private Document repo(String repo) {
    return new Composer("1")
        .compose(ViewDefinition.c4Report(repo + ".c4", repo, TEMPLATE), SNAPSHOT, facts, Map.of());
  }

  private static Section flows(Document document) {
    return document.sections().stream()
        .filter(s -> s.id().equals("sequences"))
        .findFirst()
        .orElseThrow();
  }

  private static SequenceDiagram diagram(Section flow) {
    return flow.blocks().stream()
        .filter(SequenceDiagram.class::isInstance)
        .map(SequenceDiagram.class::cast)
        .findFirst()
        .orElseThrow();
  }

  private static String text(List<Inline> content) {
    return content.stream()
        .map(i -> i instanceof TextRun t ? t.text() : "")
        .collect(Collectors.joining());
  }

  /** One line per step; frames as their operator and guard, then their steps indented. */
  private static List<String> steps(List<SequenceDiagram.Step> steps) {
    List<String> lines = new ArrayList<>();
    for (SequenceDiagram.Step step : steps) {
      switch (step) {
        case SequenceDiagram.Message m ->
            lines.add(
                m.from()
                    + (m.messageKind() == SequenceDiagram.MessageKind.ASYNC ? " >> " : " > ")
                    + m.to()
                    + ": "
                    + m.label()
                    + (m.code() == null ? "" : " [" + m.code() + "]")
                    + (m.profiles().isEmpty() ? "" : " " + m.profiles())
                    + (m.info().isEmpty() ? "" : " (" + text(m.info()) + ")"));
        case SequenceDiagram.Note n -> lines.add("note " + n.over() + ": " + text(n.content()));
        case SequenceDiagram.Frame f -> {
          for (int i = 0; i < f.operands().size(); i++) {
            SequenceDiagram.Operand operand = f.operands().get(i);
            lines.add(
                i == 0
                    ? f.operator().jsonName() + " " + operand.guard()
                    : "  -- " + operand.guard());
            steps(operand.steps()).forEach(s -> lines.add("  " + s));
          }
        }
      }
    }
    return lines;
  }

  @Test
  void tracesFromTheEndpointAndStitchesAcrossContainers() {
    Section flows = flows(system(facts));
    // pay's endpoint and ship's listener are part of the order flow; ship's status endpoint
    // reaches nothing outside ship.
    assertThat(flows.sections())
        .extracting(Section::title)
        .containsExactly("POST /orders on front", "Nightly.run on its schedule in ship");
    Section order = flows.sections().getFirst();
    SequenceDiagram diagram = diagram(order);
    assertThat(diagram.participants())
        .extracting(p -> p.id() + (p.technology() == null ? "" : " [" + p.technology() + "]"))
        .containsExactly(
            "service:x:front [Spring Boot 3.5.0 / Java 21]",
            "service:x:pay [Spring Boot 3.5.0 / Java 21]",
            "external-system:gw.example.com",
            "topic:kafka:orders [Kafka topic]",
            "queue:jms:audit [JMS queue]",
            "service:x:ship [Spring Boot 3.5.0 / Java 21]",
            "database:db:5432/ship [PostgreSQL]");
    assertThat(steps(diagram.steps()))
        .containsExactly(
            "note []: Starts when front receives POST /orders (Api.place).",
            "service:x:front > service:x:pay: Calls Api.charge [POST /pay] (Over JSON/HTTP.)",
            "service:x:pay > external-system:gw.example.com: Calls gw.example.com"
                + " [POST /v1/charges] (Over HTTPS. Under the Spring profile prod it calls"
                + " live.example.com instead.)",
            "note []: front calls GET ${TAX_URL}/quotes, which Lantern could not"
                + " link (unresolved placeholder TAX_URL); the trace does not follow it.",
            "service:x:front >> topic:kafka:orders: Sends a message [Kafka]",
            "note [service:x:front, topic:kafka:orders]: front does not wait: ship takes the"
                + " message later.",
            "opt Spring profile audit",
            "  service:x:front >> queue:jms:audit: Sends a message [JMS] [audit]",
            "  note [queue:jms:audit]: No listener for it was found in the code.",
            "topic:kafka:orders >> service:x:ship: Delivers to Listener.on [Kafka]",
            "service:x:ship > database:db:5432/ship: Reads parcel [JDBC]");
    Paragraph prose = (Paragraph) order.blocks().getFirst();
    assertThat(text(prose.content()))
        .isEqualTo(
            "Starts when front receives POST /orders, handled by Api.place. It reaches pay,"
                + " gw.example.com, orders, audit, ship and ship on db. It hands work on"
                + " asynchronously through audit and orders. One call could not be followed:"
                + " GET ${TAX_URL}/quotes.");
    // It cites the facts of the steps it describes.
    assertThat(prose.facts()).isNotEmpty();
    Table trigger = (Table) order.blocks().get(1);
    assertThat(trigger.rows().getFirst().cells())
        .extracting(SequenceFlowsTest::text)
        .containsExactly(
            "POST /orders (Api.place)", "Not known from the code.", "Not known from the code.");
  }

  @Test
  void everyStepCitesItsFacts() {
    SequenceDiagram diagram = diagram(flows(system(facts)).sections().getFirst());
    SequenceDiagram.Message call = (SequenceDiagram.Message) diagram.steps().get(1);
    assertThat(call.facts())
        .containsExactly(
            facts.stream()
                .filter(
                    f -> f.predicate().equals("CALLS_HTTP") && f.subject().toString().equals(FRONT))
                .findFirst()
                .orElseThrow()
                .id());
    assertThat(diagram.facts()).hasSize(1);
  }

  @Test
  void aRepositoryShowsTheFlowsThatStartInOrPassThroughIt() {
    assertThat(flows(repo("ship")).sections())
        .extracting(Section::title)
        .containsExactly("POST /orders on front", "Nightly.run on its schedule in ship");
    assertThat(flows(repo("pay")).sections())
        .extracting(Section::title)
        .containsExactly("POST /orders on front");
  }

  @Test
  void aCallTheResolverLinkedIsFollowedAndMarkedNotNotedAsUnlinked() {
    Fact open =
        facts.stream().filter(f -> f.predicate().equals("unresolved-link")).findFirst().get();
    facts.add(
        Fact.create(
            EntityId.parse(FRONT),
            "CALLS_HTTP",
            FactObject.entity(EntityId.parse("external-system:tax.example.com")),
            (ObjectNode)
                FactJson.parse(
                    "{\"protocol\": \"HTTPS\", \"httpMethod\": \"GET\", \"path\": \"/quotes\","
                        + " \"matchedBy\": \"llm\", \"resolves\": \""
                        + open.id()
                        + "\", \"source\": \""
                        + SERVICE
                        + "\"}"),
            new Origin(Origin.Kind.LLM, "link-resolver", "1"),
            new Evidence(
                "front", TestFacts.COMMIT, "Code.java", new Evidence.LineRange(22, 22), null),
            0.28,
            Validity.from(1)));
    List<String> lines = steps(diagram(flows(system(facts)).sections().getFirst()).steps());
    assertThat(lines)
        .anyMatch(
            l ->
                l.startsWith("service:x:front > external-system:tax.example.com:")
                    && l.contains("Resolved by the LLM, confidence 0.28."));
    assertThat(lines).noneMatch(l -> l.contains("could not link"));
    assertThat(
            text(
                ((Paragraph) flows(system(facts)).sections().getFirst().blocks().getFirst())
                    .content()))
        .doesNotContain("could not be followed");
  }

  @Test
  void saysSoWhenNoFlowReachesAnotherContainer() {
    List<Fact> inside =
        facts.stream()
            .filter(
                f ->
                    !Character.isUpperCase(f.predicate().charAt(0))
                        || f.predicate().equals("CONTAINS"))
            .filter(f -> !f.predicate().equals("unresolved-link"))
            .toList();
    Section flows = flows(system(inside));
    assertThat(flows.sections()).isEmpty();
    assertThat(text(((Callout) flows.blocks().getFirst()).content()))
        .isEqualTo(
            "No entry point (HTTP endpoint, message listener or scheduled job) was found whose"
                + " code reaches another container.");
  }

  @Test
  void doesNotDependOnInputOrder() {
    List<Fact> shuffled = new ArrayList<>(facts);
    Collections.shuffle(shuffled, new Random(7));
    assertThat(system(shuffled)).isEqualTo(system(facts));
  }

  /** Adds a type to a package of the repository, with its kind and methods. */
  private void type(String repo, String pkg, String type, String kind, String... methods) {
    String typeId = "type:" + type;
    relate(repo, "package:x:" + repo + ":" + pkg, "CONTAINS", typeId, "{}", 0);
    attribute(repo, typeId, "kind", kind, "{}", 0);
    for (String method : methods) {
      relate(repo, typeId, "CONTAINS", "method:" + type + "#" + method, "{}", 0);
    }
  }

  private void httpCall(String repo, String unit, String source, String host, int line) {
    relate(
        repo,
        unit,
        "CALLS_HTTP",
        "external-system:" + host,
        "{\"protocol\": \"HTTPS\", \"httpMethod\": \"POST\", \"path\": \"/n\", \"source\": \""
            + source
            + "\"}",
        line);
  }

  private Section flow(Document document, String title) {
    return flows(document).sections().stream()
        .filter(s -> s.title().equals(title))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void aCallWithSeveralImplementationsIsAnAltFrameOfThem() {
    // Notifier is an interface; MailNotifier implements it, SmsNotifier extends an abstract base
    // that implements it and does not declare the method.
    type("front", "x.front", "x.front.Notifier", "interface", "send()");
    type("front", "x.front", "x.front.MailNotifier", "class", "send()");
    type("front", "x.front", "x.front.BaseNotifier", "class");
    type("front", "x.front", "x.front.SmsNotifier", "class", "send()");
    relate("front", "type:x.front.MailNotifier", "IMPLEMENTS", "type:x.front.Notifier", "{}", 0);
    relate("front", "type:x.front.BaseNotifier", "IMPLEMENTS", "type:x.front.Notifier", "{}", 0);
    relate("front", "type:x.front.SmsNotifier", "EXTENDS", "type:x.front.BaseNotifier", "{}", 0);
    relate("front", SERVICE, "CALLS", "method:x.front.Notifier#send()", "{}", 25);
    httpCall("front", FRONT, "method:x.front.MailNotifier#send()", "mail.example.com", 3);
    httpCall("front", FRONT, "method:x.front.SmsNotifier#send()", "sms.example.com", 3);

    List<String> steps = steps(diagram(flow(system(facts), "POST /orders on front")).steps());

    assertThat(steps)
        .containsSubsequence(
            "alt as MailNotifier",
            "  service:x:front > external-system:mail.example.com: Calls mail.example.com"
                + " [POST /n] (Over HTTPS.)",
            "  -- as SmsNotifier",
            "  service:x:front > external-system:sms.example.com: Calls sms.example.com"
                + " [POST /n] (Over HTTPS.)",
            "topic:kafka:orders >> service:x:ship: Delivers to Listener.on [Kafka]");
  }

  @Test
  void aCallWithMoreImplementationsThanAFrameHoldsIsANote() {
    type("front", "x.front", "x.front.Notifier", "interface", "send()");
    for (int i = 1; i <= SequenceFlows.MAX_ALTERNATIVES + 1; i++) {
      type("front", "x.front", "x.front.N" + i, "class", "send()");
      relate("front", "type:x.front.N" + i, "IMPLEMENTS", "type:x.front.Notifier", "{}", 0);
    }
    relate("front", SERVICE, "CALLS", "method:x.front.Notifier#send()", "{}", 25);

    assertThat(steps(diagram(flow(system(facts), "POST /orders on front")).steps()))
        .contains(
            "note [service:x:front]: One of N1, N2, N3, N4 and N5 runs Notifier.send here; the"
                + " trace does not follow them.");
  }

  @Test
  void followsAMethodTheCalledTypeInherits() {
    // Status.get calls Child.load, which Child inherits from Parent, which reads a table.
    type("ship", "x.ship", "x.ship.Parent", "class", "load()");
    type("ship", "x.ship", "x.ship.Child", "class");
    relate("ship", "type:x.ship.Child", "EXTENDS", "type:x.ship.Parent", "{}", 0);
    relate("ship", STATUS, "CALLS", "method:x.ship.Child#load()", "{}", 4);
    relate("ship", "method:x.ship.Parent#load()", "READS_TABLE", "table:x:ship:stock", "{}", 9);

    assertThat(steps(diagram(flow(system(facts), "GET /status on ship")).steps()))
        .containsExactly(
            "note []: Starts when ship receives GET /status (Status.get).",
            "service:x:ship > database:db:5432/ship: Reads stock [JDBC]");
  }

  @Test
  void aCallToAMethodARepositoryInheritsReachesTheDatabase() {
    // Status.get calls save on ParcelRepository, a Spring Data repository that inherits it from
    // third-party code, so no type in the code declares the method (ADR 0007).
    type("ship", "x.ship", "x.ship.ParcelRepository", "interface");
    relate("ship", "type:x.ship.ParcelRepository", "REPOSITORY_FOR", "type:x.ship.Parcel", "{}", 0);
    relate(
        "ship", STATUS, "CALLS", "method:x.ship.ParcelRepository#save(java.lang.Object)", "{}", 4);

    assertThat(steps(diagram(flow(system(facts), "GET /status on ship")).steps()))
        .containsExactly(
            "note []: Starts when ship receives GET /status (Status.get).",
            "service:x:ship > database:db:5432/ship: Calls ParcelRepository.save [JDBC]");
  }

  @Test
  void recursiveCallsAreFollowedOnce() {
    relate("ship", NIGHTLY, "CALLS", NIGHTLY, "{}", 5);
    relate("ship", NIGHTLY, "CALLS", STATUS, "{}", 6);
    relate("ship", "method:x.ship.Status#format()", "CALLS", NIGHTLY, "{}", 8);

    assertThat(steps(diagram(flow(system(facts), "Nightly.run on its schedule in ship")).steps()))
        .containsExactly(
            "note []: Starts when the schedule cron 0 0 2 * * * fires in ship (Nightly.run).",
            "service:x:ship > database:db:5432/ship: Writes parcel [JDBC]");
  }

  @Test
  void theTraceStopsAfterTheMostMessagesAFlowShows() {
    for (int i = 0; i < SequenceFlows.MAX_MESSAGES + 5; i++) {
      httpCall("ship", SHIP, NIGHTLY, "e" + i + ".example.com", 10 + i);
    }

    List<SequenceDiagram.Step> steps =
        diagram(flow(system(facts), "Nightly.run on its schedule in ship")).steps();

    assertThat(steps.stream().filter(SequenceDiagram.Message.class::isInstance))
        .hasSize(SequenceFlows.MAX_MESSAGES);
    assertThat(steps(List.of(steps.getLast())))
        .containsExactly("note []: The trace stops here, after 40 messages.");
  }

  @Test
  void dataAccessWithoutOneKnownDatabaseIsANote() {
    relate("front", SERVICE, "READS_TABLE", "table:x:front:stock", "{}", 26);

    assertThat(steps(diagram(flow(system(facts), "POST /orders on front")).steps()))
        .contains(
            "note [service:x:front]: front reads stock; no datasource of it was found, so which"
                + " database is not known.");
  }

  @Test
  void showsAtMostSevenFlowsInRankOrder() {
    for (int i = 0; i < SequenceFlows.MAX_FLOWS + 2; i++) {
      String method = "method:x.pay.Job" + i + "#run()";
      type("pay", "x.pay", "x.pay.Job" + i, "class", "run()");
      attribute("pay", method, "scheduled", "0 0 " + i + " * * *", "{}", 0);
      httpCall("pay", PAY, method, "j" + i + ".example.com", 3);
    }

    // The order flow touches most containers; the nightly job and the jobs touch two each, by ID.
    assertThat(flows(system(facts)).sections())
        .extracting(Section::title)
        .containsExactly(
            "POST /orders on front",
            "Job0.run on its schedule in pay",
            "Job1.run on its schedule in pay",
            "Job2.run on its schedule in pay",
            "Job3.run on its schedule in pay",
            "Job4.run on its schedule in pay",
            "Job5.run on its schedule in pay");
  }
}

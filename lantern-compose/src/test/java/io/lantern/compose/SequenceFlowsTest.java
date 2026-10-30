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

  /** The trigger a flow's title names after its scenario: "GET /status on ship". */
  private static String trigger(Section flow) {
    String title = flow.title();
    int open = title.indexOf(" (");
    return open < 0 || !title.endsWith(")") ? title : title.substring(open + 2, title.length() - 1);
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
        .extracting(SequenceFlowsTest::trigger)
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
        .extracting(SequenceFlowsTest::trigger)
        .containsExactly("POST /orders on front", "Nightly.run on its schedule in ship");
    assertThat(flows(repo("pay")).sections())
        .extracting(SequenceFlowsTest::trigger)
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
                        + " \"resolves\": \""
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
  void aMessagePushedOverStompIsANoteNamingTheEndpointClientsConnectTo() {
    // ship's listener pushes what it received to the browsers subscribed to /topic/parcels.
    attribute(
        "ship", LISTEN, "stomp-send", "/topic/parcels", "{\"method\": \"convertAndSend\"}", 9);
    attribute(
        "ship",
        STATUS,
        "websocket-endpoint",
        "/ws-parcels",
        "{\"path\": \"/ws-parcels\", \"protocol\": \"stomp\"}",
        2);

    List<String> lines = steps(diagram(flows(system(facts)).sections().getFirst()).steps());

    assertThat(lines)
        .containsSubsequence(
            "topic:kafka:orders >> service:x:ship: Delivers to Listener.on [Kafka]",
            "service:x:ship > database:db:5432/ship: Reads parcel [JDBC]",
            "note [service:x:ship]: ship pushes to the STOMP subscribers of /topic/parcels,"
                + " connected over /ws-parcels [STOMP over WebSocket]. It does not wait for"
                + " them.");
  }

  @Test
  void anEntryPointThatOnlyPushesOverStompIsAFlow() {
    // ship's status endpoint reaches nothing else, but pushes to browsers subscribed over STOMP.
    attribute("ship", STATUS, "stomp-send", "/topic/status", "{\"method\": \"convertAndSend\"}", 4);

    assertThat(flows(system(facts)).sections())
        .extracting(SequenceFlowsTest::trigger)
        .contains("GET /status on ship");
  }

  @Test
  void aFlowStartedByAStompMessageIsTitledByItsDestination() {
    // A STOMP handler on ship that pushes what it receives to the subscribers.
    attribute("ship", STATUS, "stomp-mapping", "/parcels/track", "{\"kind\": \"message\"}", 0);
    attribute(
        "ship", STATUS, "stomp-send", "/topic/parcels", "{\"method\": \"convertAndSend\"}", 4);

    assertThat(flows(system(facts)).sections())
        .extracting(SequenceFlowsTest::trigger)
        .contains("STOMP message to /parcels/track on ship");
  }

  private static final String UI = "frontend:front/ui";
  private static final String EDGE = "service:x:edge";

  /**
   * A frontend, ui in front's repository, used by a customer, and a gateway, edge, that routes
   * {@code /api/ship/**} and the socket {@code /socket/**} to ship (ADR 0047, amendment for #331).
   */
  private void browser() {
    attribute(
        "front",
        UI,
        "frontend-app",
        "angular",
        "{\"name\": \"ui\", \"directory\": \"ui\", \"technology\": \"Angular 19.2\"}",
        0);
    Fact screen =
        fact(
            "front",
            "repo:front",
            "person-evidence",
            FactObject.string("route"),
            "{\"path\": \"/orders\", \"framework\": \"angular\"}",
            0);
    screen =
        Fact.create(
            screen.subject(),
            screen.predicate(),
            screen.object(),
            screen.attributes(),
            screen.origin(),
            new Evidence("front", TestFacts.COMMIT, "ui/src/app/app.routes.ts", null, null),
            1.0,
            Validity.from(1));
    facts.add(screen);
    attribute(
        "front",
        "person:customer",
        "person-basis",
        "account",
        "{\"basis\": \"account\", \"description\": \"The code lets people sign in.\","
            + " \"cites\": [\""
            + screen.id()
            + "\"], \"repos\": [\"front\"], \"frontends\": [\"front\"]}",
        0);
    attribute("front", "person:customer", "display-name", "Customer", "{}", 0);
    unit("edge", EDGE, "edge", "x.edge", "Routes");
    relate(
        "edge",
        EDGE,
        "CALLS_HTTP",
        SHIP,
        "{\"protocol\": \"HTTP\", \"route\": \"ship\", \"paths\": [\"/api/ship/**\"],"
            + " \"url\": \"lb://ship\", \"source\": \"method:x.edge.Routes#routes()\"}",
        12);
    relate(
        "edge",
        EDGE,
        "CALLS_HTTP",
        SHIP,
        "{\"protocol\": \"WebSocket\", \"route\": \"socket\", \"paths\": [\"/socket/**\"],"
            + " \"url\": \"lb:ws://ship\", \"source\": \"method:x.edge.Routes#routes()\"}",
        14);
  }

  @Test
  void aFlowAFrontendsCallReachesStartsWithThePersonAndTheFrontend() {
    browser();
    // ui calls front's endpoint directly, through the proxy its nginx passes /orders on with.
    relate(
        "front",
        UI,
        "CALLS_HTTP",
        FRONT,
        "{\"httpMethod\": \"POST\", \"path\": \"/orders\", \"protocol\": \"JSON/HTTP\","
            + " \"proxy\": \"/orders\", \"reaches\": \""
            + PLACE
            + "\", \"reachesUnit\": \""
            + FRONT
            + "\", \"reachesEndpoint\": \"POST /orders\"}",
        3);

    Section order = flows(system(facts)).sections().getFirst();
    SequenceDiagram diagram = diagram(order);

    assertThat(order.title()).isEqualTo("Place (POST /orders on front)");
    assertThat(diagram.participants())
        .extracting(p -> p.id().toString())
        .startsWith("person:customer", UI, FRONT);
    assertThat(steps(diagram.steps()))
        .startsWith(
            "note []: Starts when Customer, in ui, sends POST /orders to front (Api.place).",
            "person:customer > frontend:front/ui: Uses the screens (In a web browser.)",
            "frontend:front/ui > service:x:front: Calls Api.place [POST /orders] (Over JSON/HTTP."
                + " Through the proxy /orders of its server.)",
            "service:x:front > service:x:pay: Calls Api.charge [POST /pay] (Over JSON/HTTP.)");
    Table trigger =
        order.blocks().stream()
            .filter(Table.class::isInstance)
            .map(Table.class::cast)
            .findFirst()
            .orElseThrow();
    assertThat(text(trigger.rows().getFirst().cells().getFirst()))
        .isEqualTo("POST /orders from ui (Api.place)");
  }

  @Test
  void aFrontendsCallThroughAGatewayRouteStartsTheFlowItReaches() {
    browser();
    // ship's status endpoint reaches nothing else; called from the browser it is a flow.
    relate(
        "front",
        UI,
        "CALLS_HTTP",
        EDGE,
        "{\"httpMethod\": \"GET\", \"path\": \"/api/ship/status\", \"protocol\": \"JSON/HTTP\","
            + " \"reaches\": \""
            + STATUS
            + "\", \"reachesUnit\": \""
            + SHIP
            + "\", \"reachesEndpoint\": \"GET /status\", \"throughRoute\": \"ship\"}",
        5);

    Section status =
        flows(system(facts)).sections().stream()
            .filter(f -> trigger(f).equals("GET /status on ship"))
            .findFirst()
            .orElseThrow();
    SequenceDiagram diagram = diagram(status);

    assertThat(diagram.participants())
        .extracting(p -> p.id().toString())
        .containsExactly("person:customer", UI, EDGE, SHIP);
    assertThat(steps(diagram.steps()))
        .containsExactly(
            "note []: Starts when Customer, in ui, sends GET /api/ship/status, which edge routes"
                + " to ship (Status.get).",
            "person:customer > frontend:front/ui: Uses the screens (In a web browser.)",
            "frontend:front/ui > service:x:edge: Calls Status.get [GET /api/ship/status] (Over"
                + " JSON/HTTP.)",
            "service:x:edge > service:x:ship: Routes to Status.get [GET /status] (Route ship to"
                + " lb://ship, over HTTP.)");
  }

  @Test
  void aSocketAFrontendOpensThroughAGatewayStartsAFlowThroughTheInboundInterceptors() {
    browser();
    String register = "method:x.ship.WsConfig#register()";
    String inbound = "method:x.ship.WsConfig#inbound()";
    String preSend = "method:x.ship.TokenInterceptor#preSend()";
    relate("ship", "package:x:ship:x.ship", "CONTAINS", "type:x.ship.WsConfig", "{}", 0);
    relate("ship", "package:x:ship:x.ship", "CONTAINS", "type:x.ship.TokenInterceptor", "{}", 0);
    relate("ship", "type:x.ship.WsConfig", "CONTAINS", register, "{}", 0);
    relate("ship", "type:x.ship.WsConfig", "CONTAINS", inbound, "{}", 0);
    relate("ship", "type:x.ship.TokenInterceptor", "CONTAINS", preSend, "{}", 0);
    attribute(
        "ship",
        register,
        "websocket-endpoint",
        "/ws",
        "{\"path\": \"/ws\", \"protocol\": \"stomp\"}",
        2);
    attribute(
        "ship",
        inbound,
        "stomp-interceptor",
        "x.ship.TokenInterceptor",
        "{\"channel\": \"inbound\", \"intercepts\": [\"preSend\"]}",
        8);
    // The interceptor checks the token with pay.
    relate(
        "ship",
        SHIP,
        "CALLS_HTTP",
        PAY,
        "{\"protocol\": \"JSON/HTTP\", \"endpoint\": \"POST /pay\", \"source\": \""
            + preSend
            + "\", \"target\": \""
            + CHARGE
            + "\"}",
        20);
    relate(
        "front",
        UI,
        "CALLS_HTTP",
        EDGE,
        "{\"path\": \"/socket/ws\", \"protocol\": \"WebSocket\", \"reaches\": \""
            + register
            + "\", \"reachesUnit\": \""
            + SHIP
            + "\", \"reachesEndpoint\": \"/ws\", \"throughRoute\": \"socket\"}",
        7);

    Section socket =
        flows(system(facts)).sections().stream()
            .filter(f -> trigger(f).equals("WebSocket connection to /ws on ship"))
            .findFirst()
            .orElseThrow();

    assertThat(steps(diagram(socket).steps()))
        .startsWith(
            "note []: Starts when Customer, in ui, opens a socket to /socket/ws, which edge relays"
                + " to ship (WsConfig.register).",
            "person:customer > frontend:front/ui: Uses the screens (In a web browser.)",
            "frontend:front/ui > service:x:edge: Opens a socket [/socket/ws] (Over WebSocket.)",
            "service:x:edge > service:x:ship: Relays the socket [/ws] (Route socket to"
                + " lb:ws://ship, over WebSocket.)",
            "note [service:x:ship]: Each STOMP frame the client sends passes"
                + " TokenInterceptor.preSend.",
            "service:x:ship > service:x:pay: Calls Api.charge [POST /pay] (Over JSON/HTTP.)");
  }

  @Test
  void theFlowAPersonSignsInThroughComesBeforeTheRankedFlows() {
    browser();
    // ship's status endpoint touches fewer containers than the order flow, but signs people in.
    relate(
        "front",
        UI,
        "CALLS_HTTP",
        EDGE,
        "{\"httpMethod\": \"GET\", \"path\": \"/api/ship/login\", \"protocol\": \"JSON/HTTP\","
            + " \"navigation\": true, \"reaches\": \""
            + STATUS
            + "\", \"reachesUnit\": \""
            + SHIP
            + "\", \"reachesEndpoint\": \"GET /status\", \"throughRoute\": \"ship\"}",
        5);

    Section first = flows(system(facts)).sections().getFirst();

    assertThat(trigger(first)).isEqualTo("GET /status on ship");
    assertThat(steps(diagram(first).steps()))
        .contains(
            "frontend:front/ui > service:x:edge: Navigates the browser to Status.get"
                + " [GET /api/ship/login] (Over JSON/HTTP.)");
  }

  @Test
  void aFlowOfTheShapeOfOneShownIsNotShownAgain() {
    // ship's status endpoint reads its database, as the nightly job writes it: the same lifelines,
    // but one starts with a request and the other on a schedule, so both are shown.
    relate("ship", STATUS, "READS_TABLE", "table:x:ship:parcel", "{}", 4);
    assertThat(flows(system(facts)).sections())
        .extracting(SequenceFlowsTest::trigger)
        .contains("GET /status on ship", "Nightly.run on its schedule in ship");

    // A second endpoint with the same lifelines in the same order is the same picture.
    String track = "method:x.ship.Status#track()";
    relate("ship", "type:x.ship.Status", "CONTAINS", track, "{}", 0);
    attribute("ship", track, "endpoint", "GET /track", "{}", 0);
    relate("ship", track, "READS_TABLE", "table:x:ship:parcel", "{}", 5);
    assertThat(flows(system(facts)).sections())
        .extracting(SequenceFlowsTest::trigger)
        .contains("GET /status on ship")
        .doesNotContain("GET /track on ship");
  }

  @Test
  void aFrontendNoPersonUsesStartsTheFlowItself() {
    attribute(
        "front",
        UI,
        "frontend-app",
        "angular",
        "{\"name\": \"ui\", \"directory\": \"ui\", \"technology\": \"Angular 19.2\"}",
        0);
    relate(
        "front",
        UI,
        "CALLS_HTTP",
        FRONT,
        "{\"httpMethod\": \"POST\", \"path\": \"/orders\", \"protocol\": \"JSON/HTTP\","
            + " \"reaches\": \""
            + PLACE
            + "\", \"reachesUnit\": \""
            + FRONT
            + "\", \"reachesEndpoint\": \"POST /orders\"}",
        3);

    assertThat(steps(diagram(flows(system(facts)).sections().getFirst()).steps()))
        .startsWith(
            "note []: Starts when ui sends POST /orders to front (Api.place).",
            "frontend:front/ui > service:x:front: Calls Api.place [POST /orders] (Over JSON/HTTP.)");
  }

  @Test
  void aWebSocketEndpointNoFrontendOpensStartsNoFlow() {
    attribute(
        "ship",
        STATUS,
        "websocket-endpoint",
        "/ws",
        "{\"path\": \"/ws\", \"protocol\": \"stomp\"}",
        2);

    assertThat(flows(system(facts)).sections())
        .extracting(SequenceFlowsTest::trigger)
        .doesNotContain("WebSocket connection to /ws on ship");
  }

  @Test
  void aSendTheResolverLinkedIsMarkedAsTheLlmsToo() {
    Fact open =
        facts.stream().filter(f -> f.predicate().equals("unresolved-link")).findFirst().get();
    facts.add(
        Fact.create(
            EntityId.parse(FRONT),
            "PUBLISHES",
            FactObject.entity(EntityId.parse("topic:kafka:tax")),
            (ObjectNode)
                FactJson.parse(
                    "{\"protocol\": \"Kafka\", \"resolves\": \""
                        + open.id()
                        + "\", \"source\": \""
                        + SERVICE
                        + "\"}"),
            new Origin(Origin.Kind.LLM, "link-resolver", "1"),
            new Evidence(
                "front", TestFacts.COMMIT, "Code.java", new Evidence.LineRange(22, 22), null),
            0.18,
            Validity.from(1)));
    List<String> lines = steps(diagram(flows(system(facts)).sections().getFirst()).steps());
    assertThat(lines)
        .anyMatch(
            l ->
                l.startsWith("service:x:front >> topic:kafka:tax:")
                    && l.contains("Resolved by the LLM, confidence 0.18."));
    assertThat(lines).noneMatch(l -> l.contains("could not link"));
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
            "No entry point (HTTP endpoint, message listener, STOMP handler, scheduled or"
                + " background job) was found whose code reaches another container.");
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
        .filter(s -> s.title().equals(title) || trigger(s).equals(title))
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
  void aJobRunrJobIsNamedByItsJobNotBySchedule() {
    facts.removeIf(f -> f.predicate().equals("scheduled"));
    attribute(
        "ship",
        NIGHTLY,
        "background-job",
        "Task's reminder job",
        "{\"framework\": \"jobrunr\", \"kind\": \"job\"}",
        0);

    assertThat(steps(diagram(flow(system(facts), "Nightly.run on its schedule in ship")).steps()))
        .first()
        .isEqualTo(
            "note []: Starts when the JobRunr job Task's reminder job runs in ship (Nightly.run).");
  }

  @Test
  void aQuartzJobWithACronAndAJobRunrIntervalAreNamedBySchedule() {
    facts.removeIf(f -> f.predicate().equals("scheduled"));
    attribute(
        "ship",
        NIGHTLY,
        "scheduled",
        "0 15 2 * * ?",
        "{\"framework\": \"quartz\", \"trigger\": \"cron\"}",
        0);
    assertThat(steps(diagram(flow(system(facts), "Nightly.run on its schedule in ship")).steps()))
        .first()
        .isEqualTo(
            "note []: Starts when the Quartz job on the schedule cron 0 15 2 * * ? runs in ship"
                + " (Nightly.run).");

    facts.removeIf(f -> f.predicate().equals("scheduled"));
    attribute(
        "ship",
        NIGHTLY,
        "background-job",
        "PT5M",
        "{\"framework\": \"jobrunr\", \"kind\": \"interval\"}",
        0);
    assertThat(steps(diagram(flow(system(facts), "Nightly.run on its schedule in ship")).steps()))
        .first()
        .isEqualTo(
            "note []: Starts when the JobRunr job on the schedule interval PT5M runs in ship"
                + " (Nightly.run).");
  }

  @Test
  void aFlowGoesOnFromTheMethodSchedulingAJobIntoTheJob() {
    // ship's status endpoint enqueues the JobRunr job that Nightly.run handles.
    facts.removeIf(f -> f.predicate().equals("scheduled"));
    attribute(
        "ship",
        NIGHTLY,
        "background-job",
        "x.ship.ReminderRequest",
        "{\"framework\": \"jobrunr\", \"kind\": \"job\", \"request\": \"x.ship.ReminderRequest\","
            + " \"scheduledBy\": [\""
            + STATUS
            + "\"]}",
        0);
    relate(
        "ship",
        STATUS,
        "CALLS",
        "method:org.jobrunr.scheduling.BackgroundJobRequest#enqueue(org.jobrunr.jobs.lambdas.JobRequest)",
        "{}",
        2);
    Section flows = flows(system(facts));

    // The job's own flow is part of the endpoint's, so it is not shown apart, though it ranks
    // level with it and comes first by ID.
    assertThat(flows.sections())
        .extracting(SequenceFlowsTest::trigger)
        .containsExactly("POST /orders on front", "GET /status on ship");
    SequenceDiagram diagram = diagram(flows.sections().getLast());
    assertThat(steps(diagram.steps()))
        .containsExactly(
            "note []: Starts when ship receives GET /status (Status.get).",
            "service:x:ship >> service:x:ship: Schedules ReminderRequest [JobRunr job]",
            "note [service:x:ship]: ship does not wait: the JobRunr job runs later in ship.",
            "note [service:x:ship]: Later: the JobRunr job ReminderRequest runs (Nightly.run).",
            "service:x:ship > database:db:5432/ship: Writes parcel [JDBC]");
    Paragraph prose = (Paragraph) flows.sections().getLast().blocks().getFirst();
    assertThat(text(prose.content()))
        .contains("It hands work on asynchronously through the JobRunr job ReminderRequest.");
  }

  @Test
  void aJobOnACronIsNotContinuedFromTheCodeThatRegistersIt() {
    facts.removeIf(f -> f.predicate().equals("scheduled"));
    attribute(
        "ship",
        NIGHTLY,
        "scheduled",
        "0 0 1 * * *",
        "{\"framework\": \"jobrunr\", \"trigger\": \"cron\", \"scheduledBy\": [\""
            + STATUS
            + "\"]}",
        0);

    assertThat(flows(system(facts)).sections())
        .extracting(SequenceFlowsTest::trigger)
        .containsExactly("POST /orders on front", "Nightly.run on its schedule in ship");
  }

  @Test
  void aJobScheduledByCodeNoFlowReachesKeepsItsOwnFlow() {
    facts.removeIf(f -> f.predicate().equals("scheduled"));
    attribute(
        "ship",
        NIGHTLY,
        "background-job",
        "x.ship.ReminderRequest",
        "{\"framework\": \"jobrunr\", \"kind\": \"job\","
            + " \"scheduledBy\": [\"method:x.ship.Elsewhere#remind()\"]}",
        0);

    assertThat(flows(system(facts)).sections())
        .extracting(SequenceFlowsTest::trigger)
        .containsExactly("POST /orders on front", "Nightly.run on its schedule in ship");
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
        .extracting(SequenceFlowsTest::trigger)
        .containsExactly(
            "POST /orders on front",
            "Job0.run on its schedule in pay",
            "Job1.run on its schedule in pay",
            "Job2.run on its schedule in pay",
            "Job3.run on its schedule in pay",
            "Job4.run on its schedule in pay",
            "Job5.run on its schedule in pay");
  }

  // Business scenarios (ADR 0047, amendment for #272).

  @Test
  void aConstructorRunsForTheClassTheCodeNamesNotAsAlternatives() {
    type("front", "x.front", "x.front.Problem", "class", "<init>()");
    type("front", "x.front", "x.front.NotFound", "class", "<init>()");
    relate("front", "type:x.front.NotFound", "EXTENDS", "type:x.front.Problem", "{}", 0);
    relate("front", SERVICE, "CALLS", "method:x.front.Problem#<init>()", "{}", 25);
    httpCall("front", FRONT, "method:x.front.Problem#<init>()", "log.example.com", 3);
    httpCall("front", FRONT, "method:x.front.NotFound#<init>()", "other.example.com", 3);

    List<String> steps = steps(diagram(flow(system(facts), "POST /orders on front")).steps());

    assertThat(steps)
        .contains(
            "service:x:front > external-system:log.example.com: Calls log.example.com [POST /n]"
                + " (Over HTTPS.)")
        .noneMatch(step -> step.startsWith("alt") || step.contains("other.example.com"));
  }

  @Test
  void aMethodCalledFromTwoPlacesIsTracedEachTime() {
    // OrderService.place calls Roles.admin and Roles.member, which both call Roles.create, which
    // calls an external system: the code makes the call twice, so the flow shows it twice.
    type("front", "x.front", "x.front.Roles", "class", "admin()", "member()", "create()");
    relate("front", SERVICE, "CALLS", "method:x.front.Roles#admin()", "{}", 25);
    relate("front", SERVICE, "CALLS", "method:x.front.Roles#member()", "{}", 26);
    relate(
        "front", "method:x.front.Roles#admin()", "CALLS", "method:x.front.Roles#create()", "{}", 3);
    relate(
        "front",
        "method:x.front.Roles#member()",
        "CALLS",
        "method:x.front.Roles#create()",
        "{}",
        7);
    httpCall("front", FRONT, "method:x.front.Roles#create()", "roles.example.com", 11);

    assertThat(steps(diagram(flow(system(facts), "POST /orders on front")).steps()))
        .filteredOn(step -> step.contains("roles.example.com"))
        .hasSize(2);
  }

  @Test
  void aUnitsSendToItsOwnListenerIsDeliveredOnceInTheFlow() {
    // ship's listener sends on the topic it listens on: front's message is delivered to it, then
    // its own once, and the flow does not loop.
    relate(
        "ship",
        SHIP,
        "PUBLISHES",
        "topic:kafka:orders",
        "{\"protocol\": \"Kafka\", \"source\": \"" + LISTEN + "\"}",
        8);
    relate(
        "ship",
        SHIP,
        "SENDS_MESSAGES_TO",
        SHIP,
        "{\"protocol\": \"Kafka\", \"destination\": \"topic:kafka:orders\", \"source\": \""
            + LISTEN
            + "\", \"target\": \""
            + LISTEN
            + "\"}",
        8);

    List<String> steps = steps(diagram(flow(system(facts), "POST /orders on front")).steps());

    assertThat(steps)
        .filteredOn(step -> step.startsWith("topic:kafka:orders >> service:x:ship"))
        .containsExactly(
            "topic:kafka:orders >> service:x:ship: Delivers to Listener.on [Kafka]",
            "topic:kafka:orders >> service:x:ship: Delivers to Listener.on [Kafka]");
    assertThat(steps)
        .contains(
            "service:x:ship >> topic:kafka:orders: Sends a message [Kafka]",
            "note [service:x:ship, topic:kafka:orders]: ship does not wait: ship takes the"
                + " message later.");
  }

  @Test
  void aRunOfDataAccessInOneDatabaseIsOneStep() {
    // The nightly job reads two tables and writes one, then calls a repository: one step.
    relate("ship", NIGHTLY, "READS_TABLE", "table:x:ship:orders", "{}", 2);
    relate("ship", NIGHTLY, "READS_TABLE", "table:x:ship:payment", "{}", 3);
    type("ship", "x.ship", "x.ship.ParcelRepository", "interface");
    relate("ship", "type:x.ship.ParcelRepository", "REPOSITORY_FOR", "type:x.ship.Parcel", "{}", 0);
    relate(
        "ship", NIGHTLY, "CALLS", "method:x.ship.ParcelRepository#save(java.lang.Object)", "{}", 6);

    assertThat(steps(diagram(flow(system(facts), "Nightly.run on its schedule in ship")).steps()))
        .containsExactly(
            "note []: Starts when the schedule cron 0 0 2 * * * fires in ship (Nightly.run).",
            "service:x:ship > database:db:5432/ship: Reads orders, payment; writes parcel;"
                + " calls ParcelRepository.save [JDBC]");
  }

  @Test
  void codeWhoseAccessJoinedARunIsTracedAgainWhenCalledAgain() {
    // Nightly.run writes parcel, then a() reads stock through find(), joining the run; after a
    // call out, b() runs find() again, which is a step of its own.
    type("ship", "x.ship", "x.ship.Nightly", "class", "a()", "b()", "find()");
    relate("ship", NIGHTLY, "CALLS", "method:x.ship.Nightly#a()", "{}", 5);
    relate("ship", NIGHTLY, "CALLS", "method:x.ship.Nightly#b()", "{}", 7);
    relate("ship", "method:x.ship.Nightly#a()", "CALLS", "method:x.ship.Nightly#find()", "{}", 11);
    relate("ship", "method:x.ship.Nightly#b()", "CALLS", "method:x.ship.Nightly#find()", "{}", 12);
    relate("ship", "method:x.ship.Nightly#find()", "READS_TABLE", "table:x:ship:stock", "{}", 15);
    httpCall("ship", SHIP, NIGHTLY, "rates.example.com", 6);

    assertThat(steps(diagram(flow(system(facts), "Nightly.run on its schedule in ship")).steps()))
        .containsExactly(
            "note []: Starts when the schedule cron 0 0 2 * * * fires in ship (Nightly.run).",
            "service:x:ship > database:db:5432/ship: Writes parcel; reads stock [JDBC]",
            "service:x:ship > external-system:rates.example.com: Calls rates.example.com [POST /n]"
                + " (Over HTTPS.)",
            "service:x:ship > database:db:5432/ship: Reads stock [JDBC]");
  }

  @Test
  void aRunOfDataAccessNamesFourAndSaysHowManyMore() {
    for (int i = 0; i < 6; i++) {
      relate("ship", NIGHTLY, "WRITES_TABLE", "table:x:ship:t" + i, "{}", 4);
    }

    assertThat(steps(diagram(flow(system(facts), "Nightly.run on its schedule in ship")).steps()))
        .contains(
            "service:x:ship > database:db:5432/ship: Writes parcel, t0, t1, t2 and 3 more [JDBC]");
  }

  @Test
  void requestsComeBeforeSchedulesAndFlowsThatOnlyReachTheirOwnDatabaseComeLast() {
    // ship's status endpoint reads its own database only; a job reaches more containers than a
    // request, but what someone asks for comes first.
    relate("ship", STATUS, "READS_TABLE", "table:x:ship:parcel", "{}", 4);
    for (int i = 0; i < 3; i++) {
      httpCall("ship", SHIP, NIGHTLY, "n" + i + ".example.com", 10 + i);
    }
    String quote = "method:x.pay.Api#quote()";
    relate("pay", "type:x.pay.Api", "CONTAINS", quote, "{}", 0);
    attribute("pay", quote, "endpoint", "GET /quote", "{}", 0);
    httpCall("pay", PAY, quote, "rates.example.com", 4);

    assertThat(flows(system(facts)).sections())
        .extracting(SequenceFlowsTest::trigger)
        .containsExactly(
            "POST /orders on front", "GET /quote on pay", "Nightly.run on its schedule in ship");
  }

  @Test
  void anEndpointThatSignsAPersonInComesFirstWithoutABrowser() {
    String login = "method:x.pay.Api#login()";
    relate("pay", "type:x.pay.Api", "CONTAINS", login, "{}", 0);
    attribute("pay", login, "endpoint", "POST /api/auth/login", "{}", 0);
    httpCall("pay", PAY, login, "idp.example.com", 4);

    assertThat(flows(system(facts)).sections())
        .extracting(SequenceFlowsTest::trigger)
        .first()
        .isEqualTo("POST /api/auth/login on pay");
  }

  @Test
  void aFlowIsTitledByItsScenarioThenItsTrigger() {
    Section order = flows(system(facts)).sections().getFirst();
    assertThat(order.title()).isEqualTo("Place (POST /orders on front)");
    assertThat(diagram(order).title()).isEqualTo(order.title());
    assertThat(
            SequenceFlows.scenario(
                new SequenceFlows.Entry(
                    EntityId.parse(PAY),
                    EntityId.parse("method:x.pay.OrganizationController#createOrganization()"),
                    null)))
        .isEqualTo("Create organization");
    assertThat(
            SequenceFlows.scenario(
                new SequenceFlows.Entry(EntityId.parse(SHIP), EntityId.parse(STATUS), null)))
        .isEqualTo("Get status");
  }

  /**
   * edge routes {@code GET /api/ship/status} to ship's {@code GET /status} (StripPrefix and
   * PrefixPath, as the linker reverses them), or, with {@code locator}, its discovery locator
   * forwards {@code GET /SHIP/status}; the status endpoint reads ship's database, so it is a flow.
   */
  private void routedStatus(boolean locator) {
    browser();
    relate("ship", STATUS, "READS_TABLE", "table:x:ship:parcel", "{}", 3);
    String endpoint =
        fact("ship", STATUS, "endpoint", FactObject.string("GET /status"), "{}", 0).id();
    attribute(
        "edge",
        EDGE,
        "routed-endpoint",
        locator ? "GET /SHIP/status" : "GET /api/ship/status",
        "{\"target\": \""
            + SHIP
            + "\", \"endpoint\": \""
            + STATUS
            + "\", \"served\": \"GET /status\", "
            + (locator
                ? "\"discoveryLocator\": true, \"url\": \"lb://SHIP\","
                : "\"route\": \"ship\", \"url\": \"lb://ship\",")
            + " \"source\": \"module:x:edge\", \"facts\": [\""
            + endpoint
            + "\"]}",
        8);
  }

  @Test
  void aFlowWhoseEndpointAGatewayRouteForwardsToStartsAtTheGateway() {
    routedStatus(false);

    Section status =
        flows(system(facts)).sections().stream()
            .filter(f -> trigger(f).equals("GET /api/ship/status on edge"))
            .findFirst()
            .orElseThrow();
    SequenceDiagram diagram = diagram(status);

    assertThat(diagram.participants())
        .extracting(p -> p.id().toString())
        .containsExactly(EDGE, SHIP, "database:db:5432/ship");
    assertThat(steps(diagram.steps()))
        .startsWith(
            "note []: Starts when edge receives GET /api/ship/status and routes it to ship"
                + " (Status.get).",
            "service:x:edge > service:x:ship: Routes to Status.get [GET /status] (Route ship to"
                + " lb://ship.)");
    Table trigger =
        status.blocks().stream()
            .filter(Table.class::isInstance)
            .map(Table.class::cast)
            .findFirst()
            .orElseThrow();
    assertThat(text(trigger.rows().getFirst().cells().getFirst()))
        .isEqualTo("GET /api/ship/status to edge (Status.get)");
    // A flow whose endpoint no gateway routes to is unchanged: it starts at its endpoint.
    Section order = flows(system(facts)).sections().getFirst();
    assertThat(order.title()).isEqualTo("Place (POST /orders on front)");
    assertThat(steps(diagram(order).steps()).getFirst())
        .isEqualTo("note []: Starts when front receives POST /orders (Api.place).");
  }

  @Test
  void aFlowTheDiscoveryLocatorForwardsToStartsAtTheGateway() {
    routedStatus(true);

    Section status =
        flows(system(facts)).sections().stream()
            .filter(f -> trigger(f).equals("GET /SHIP/status on edge"))
            .findFirst()
            .orElseThrow();

    assertThat(steps(diagram(status).steps()))
        .startsWith(
            "note []: Starts when edge receives GET /SHIP/status and routes it to ship"
                + " (Status.get).",
            "service:x:edge > service:x:ship: Routes to Status.get [GET /status] (Through the"
                + " discovery locator, to lb://SHIP.)");
  }

  @Test
  void anEndpointAGatewayRoutesToThatReachesNothingIsNoFlow() {
    routedStatus(false);
    facts.removeIf(
        f -> f.subject().toString().equals(STATUS) && f.predicate().equals("READS_TABLE"));

    assertThat(flows(system(facts)).sections())
        .extracting(SequenceFlowsTest::trigger)
        .noneMatch(t -> t.contains("/status"));
  }
}

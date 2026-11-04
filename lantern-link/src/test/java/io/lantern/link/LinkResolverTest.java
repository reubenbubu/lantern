package io.lantern.link;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.types.Types;
import io.lantern.extract.gateway.GatewayExtractor;
import io.lantern.extract.http.HttpClientExtractor;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.extract.maven.MavenExtractor;
import io.lantern.extract.messaging.MessagingExtractor;
import io.lantern.extract.spring.SpringExtractor;
import io.lantern.harness.Harness;
import io.lantern.harness.InMemoryAnswerStore;
import io.lantern.harness.RunBudget;
import io.lantern.harness.checks.CommonPrompt;
import io.lantern.llm.BudgetGuard;
import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.InMemoryLlmCallLog;
import io.lantern.llm.LlmCall;
import io.lantern.llm.LlmClient;
import io.lantern.llm.LlmMessage;
import io.lantern.llm.LlmProvider;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.LlmToolCall;
import io.lantern.llm.ModelSettings;
import io.lantern.llm.RecordReplayLlmProvider;
import io.lantern.llm.Role;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Resolve role (knowledge-build epic, section 11.1), with a fake model that submits through the
 * {@code submit} tool, and recorded answers; never a real one.
 */
class LinkResolverTest {

  private String commit = "a".repeat(40);
  private String model = "m";
  private int callLine = 18;
  private final List<Fact> facts = new ArrayList<>();
  private final InMemoryAnswerStore store = new InMemoryAnswerStore();

  /** A harness with the test's answer cache, so later runs reuse earlier answers. */
  private Harness harness(LlmClient client) {
    return new Harness(
        client,
        store,
        RunBudget.NONE,
        (repo, path) -> FILES.read(repo, path).map(io.lantern.harness.Fingerprints::gitBlob),
        Set.of());
  }

  private final InMemoryLlmCallLog log = new InMemoryLlmCallLog();

  @TempDir Path recordings;

  // ---------------------------------------------------------------------------------------------
  // Facts, written as the extractors and the deployable units write them

  private void add(
      String extractor,
      String subject,
      String predicate,
      FactObject object,
      String attributes,
      String where) {
    ObjectNode json =
        attributes == null ? FactJson.newObject() : (ObjectNode) FactJson.parse(attributes);
    String repo = where.substring(0, where.indexOf(':'));
    String rest = where.substring(where.indexOf(':') + 1);
    Evidence.LineRange lines = null;
    if (rest.contains("#")) {
      int line = Integer.parseInt(rest.substring(rest.indexOf('#') + 1));
      lines = new Evidence.LineRange(line, line);
      rest = rest.substring(0, rest.indexOf('#'));
    }
    facts.add(
        Fact.create(
            EntityId.parse(subject),
            predicate,
            object,
            json,
            new Origin(Origin.Kind.STATIC, extractor, "1"),
            Evidence.of(repo, rest, lines, null),
            1.0,
            Validity.from(1)));
  }

  private void unit(String repo, String artifact) {
    add(
        MavenExtractor.NAME,
        "service:g:" + artifact,
        Types.CONTAINS,
        FactObject.entity(EntityId.parse("module:g:" + artifact)),
        null,
        repo + ":pom.xml");
    add(
        DeployableUnits.NAME,
        "service:g:" + artifact,
        Types.DEPLOYABLE_UNIT,
        FactObject.string("spring-boot"),
        null,
        repo + ":pom.xml");
    add(
        DeployableUnits.NAME,
        "service:g:" + artifact,
        Types.APPLICATION_NAME,
        FactObject.string(artifact),
        "{\"module\":\"module:g:" + artifact + "\"}",
        repo + ":src/main/resources/application.yml#2");
  }

  private void type(String repo, String module, String type) {
    String pkg = type.substring(0, type.lastIndexOf('.'));
    add(
        JavaExtractor.NAME,
        "module:g:" + module,
        Types.CONTAINS,
        FactObject.entity(EntityId.parse("package:g:" + module + ":" + pkg)),
        null,
        repo + ":src/main/java");
    add(
        JavaExtractor.NAME,
        "package:g:" + module + ":" + pkg,
        Types.CONTAINS,
        FactObject.entity(EntityId.parse("type:" + type)),
        null,
        repo + ":src/main/java/" + type.replace('.', '/') + ".java#1");
  }

  private void endpoint(String repo, String module, String controller, String path) {
    type(repo, module, controller);
    add(
        SpringExtractor.NAME,
        "method:" + controller + "#quotes()",
        Types.ENDPOINT,
        FactObject.string("GET " + path),
        "{\"controller\":\"" + controller + "\",\"httpMethod\":\"GET\",\"path\":\"" + path + "\"}",
        repo + ":src/main/java/" + controller.replace('.', '/') + ".java#20");
    add(
        SpringExtractor.NAME,
        "type:" + controller,
        Types.STEREOTYPE,
        FactObject.string("controller"),
        "{\"rest\":true}",
        repo + ":src/main/java/" + controller.replace('.', '/') + ".java#5");
  }

  /** Orders calls tax through a URL from an environment variable; tax and billing serve it. */
  private void taxCall() {
    unit("a", "orders");
    type("a", "orders", "o.TaxClient");
    add(
        HttpClientExtractor.NAME,
        "method:o.TaxClient#quote()",
        Types.HTTP_CALL,
        FactObject.string("GET ${TAX_SERVICE_URL}/v2/quotes"),
        "{\"client\":\"rest-client\",\"httpMethod\":\"GET\",\"path\":\"/v2/quotes\","
            + "\"unresolved\":[\"TAX_SERVICE_URL\"],\"written\":\"${clients.tax.url}/v2/quotes\"}",
        "a:src/main/java/o/TaxClient.java#" + callLine);
    unit("b", "billing");
    endpoint("b", "billing", "b.BillingController", "/v2/quotes");
    unit("t", "tax");
    endpoint("t", "tax", "t.TaxController", "/v2/quotes");
  }

  /** The facts with the deterministic linker's, as the resolver sees them after it. */
  private List<Fact> linked() {
    // With the relations value resolution gives with the message facts written here (#597).
    List<Fact> all = MessageRelationsFixture.withRelations(facts);
    all.addAll(new CrossRepoLinks().derive(all, 1));
    return all;
  }

  private LlmClient client(LlmProvider provider, BudgetGuard budget) {
    return new LlmClient(
        ModelSettings.single(new FakeLlmProvider(provider.name()).model(model)),
        List.of(provider),
        budget,
        log,
        Clock.systemUTC());
  }

  private LinkResolver.Result resolve(FakeLlmProvider fake) {
    return resolve(fake, BudgetGuard.unlimited("test"));
  }

  private LinkResolver.Result resolve(LlmProvider provider, BudgetGuard budget) {
    LinkResolver.Result result =
        new LinkResolver(harness(client(provider, budget))).resolve(linked(), 1);
    result.facts().forEach(DeployableUnitsTest.vocabulary()::validate);
    return result;
  }

  private static String user(LlmRequest request) {
    return request.messages().getFirst().text();
  }

  // ---------------------------------------------------------------------------------------------
  // A model that answers through submit

  private static final Pattern FACT_REF = Pattern.compile("^\\[(F[0-9]+)\\] ");

  /** The turn a request asks for, from 1. */
  private static int turn(LlmRequest request) {
    return (request.messages().size() + 1) / 2;
  }

  /** The reference of the first fact line of the request that holds the text. */
  private static String ref(LlmRequest request, String text) {
    for (String line : user(request).lines().toList()) {
      Matcher ref = FACT_REF.matcher(line);
      if (ref.find() && line.contains(text)) {
        return ref.group(1);
      }
    }
    throw new AssertionError("No fact line holds " + text + " in\n" + user(request));
  }

  /** The reference of a candidate unit in the request. */
  private static String candidate(LlmRequest request, String unit) {
    Matcher ref =
        Pattern.compile("(?m)^\\[(K[0-9]+)\\] " + Pattern.quote(unit) + "$").matcher(user(request));
    if (!ref.find()) {
      throw new AssertionError("No candidate " + unit + " in\n" + user(request));
    }
    return ref.group(1);
  }

  /** One item's answer as the submit schema has it. */
  private static String entry(String pick, String confidence, String reason, String... cites) {
    StringBuilder json = new StringBuilder("{\"pick\": \"").append(pick).append("\", ");
    json.append("\"confidence\": \"").append(confidence).append("\", ");
    json.append("\"reason\": \"").append(reason).append("\", \"cites\": [");
    for (int i = 0; i < cites.length; i++) {
      json.append(i == 0 ? "" : ", ").append('"').append(cites[i]).append('"');
    }
    return json.append("]}").toString();
  }

  private static LlmToolCall submit(LlmRequest request, String items) {
    return new LlmToolCall(
        "s" + turn(request), "submit", FactJson.parse("{\"items\": " + items + "}"));
  }

  private static LlmToolCall toolCall(String id, String name, String input) {
    return new LlmToolCall(id, name, FactJson.parse(input));
  }

  /** A model that calls the tools the function gives each turn, and ends when it gives none. */
  private static FakeLlmProvider model(Function<LlmRequest, List<LlmToolCall>> calls) {
    return new FakeLlmProvider().callingTools(calls).answering("Done.");
  }

  /** A model that submits, turn by turn, the items the function gives, and ends on null. */
  private static FakeLlmProvider submitting(Function<LlmRequest, String> items) {
    return model(
        r -> {
          String answer = items.apply(r);
          return answer == null ? List.of() : List.of(submit(r, answer));
        });
  }

  /** A model that submits once, on its first turn. */
  private static FakeLlmProvider submittingOnce(Function<LlmRequest, String> items) {
    return submitting(r -> turn(r) == 1 ? items.apply(r) : null);
  }

  /** The first request of the conversation the request is a turn of. */
  private static LlmRequest first(FakeLlmProvider fake) {
    return fake.sent().getFirst().request();
  }

  /** Orders' call picked as tax's, citing the call and tax's endpoint. */
  private static String pickTax(LlmRequest r) {
    return "{\"I1\": "
        + entry(
            candidate(r, "service:g:tax"),
            "high",
            "Only tax serves GET /v2/quotes among the candidates shown.",
            ref(r, "http-call"),
            ref(r, "t.TaxController#quotes() endpoint"))
        + "}";
  }

  private static final Function<LlmRequest, String> PICK_TAX = LinkResolverTest::pickTax;

  /** The submit verdicts the model got back in the request's last turn. */
  private static String verdicts(LlmRequest request) {
    return request.messages().getLast().toolResults().getFirst().content();
  }

  // ---------------------------------------------------------------------------------------------
  // Cases

  @Test
  void theRequestHoldsTheEpicsBlocksTheCallersAddressFactsAndEachCandidate() {
    taxCall();
    FakeLlmProvider fake = submittingOnce(PICK_TAX);

    resolve(fake);

    LlmRequest request = first(fake);
    assertThat(request.role()).isEqualTo(Role.RESOLVE);
    assertThat(request.system()).isEqualTo(CommonPrompt.SYSTEM + "\n\n" + ResolvePrompt.ROLE);
    assertThat(request.tools())
        .extracting(io.lantern.llm.LlmTool::name)
        .containsExactly("facts_about", "search_facts", "submit");
    // submit takes the epic's schema.
    assertThat(request.tools().getLast().inputSchema()).isEqualTo(ResolvePrompt.SUBMIT_SCHEMA);
    String user = user(request);
    // Block 3, the system map, orients and cites nothing.
    assertThat(user)
        .startsWith(
            "System map: the deployable units, where they run, and the links static analysis"
                + " found between them.\n<facts>\n"
                + "unit service:g:billing, application name billing\n"
                + "unit service:g:orders, application name orders\n"
                + "unit service:g:tax, application name tax\n"
                + "</facts>\n\n");
    // Block 4: the calling unit's facts that bear on addresses.
    assertThat(user)
        .contains(
            "The calling unit: service:g:orders\n"
                + "Its facts that bear on addresses (its application name, the deployment"
                + " containers that run it, and the configuration values of the keys its links"
                + " use, per view):\n<facts>\n"
                + "[F1] service:g:orders application-name \"orders\" {\"module\":\"module:g:orders\"}"
                + " (a:src/main/resources/application.yml)\n"
                + "[F2] service:g:orders deployable-unit \"spring-boot\" (a:pom.xml)\n"
                + "</facts>\n\n<items>\n<item id=\"I1\">\n");
    // Block 5: the item, with the call and the candidates by unit ID.
    assertThat(user)
        .contains(
            "Open link: an HTTP call, GET ${TAX_SERVICE_URL}/v2/quotes\n"
                + "Of the unit service:g:orders, made in method:o.TaxClient#quote()\n"
                + "Static matching stopped because its URL or destination has placeholders;"
                + " placeholders no configuration resolves: TAX_SERVICE_URL.\n"
                + "Its facts:\n<facts>\n")
        .contains(
            "[K1] service:g:billing\n<facts>\n"
                + "[F4] method:b.BillingController#quotes() endpoint \"GET /v2/quotes\"")
        .contains("[K2] service:g:tax\n<facts>\n")
        .endsWith("</facts>\n</item>\n</items>");
    assertThat(request.purpose()).startsWith("resolve conversation: ");
    assertThat(log.conversations())
        .singleElement()
        .satisfies(c -> assertThat(c.role()).isEqualTo(Role.RESOLVE));
  }

  @Test
  void aPickBecomesAnObservationOfTheLinkClaimOfOriginLlmResolveWithItsReason() {
    taxCall();
    LinkResolver.Result result = resolve(submittingOnce(PICK_TAX));

    Fact unresolved =
        linked().stream()
            .filter(f -> f.predicate().equals(Types.UNRESOLVED_LINK))
            .findFirst()
            .orElseThrow();
    assertThat(result.asked()).isEqualTo(1);
    assertThat(result.facts()).hasSize(1);
    Fact link = result.facts().getFirst();
    assertThat(link.subject()).isEqualTo(EntityId.parse("service:g:orders"));
    assertThat(link.predicate()).isEqualTo(Types.CALLS_HTTP);
    assertThat(link.object().value()).isEqualTo("service:g:tax");
    assertThat(link.origin())
        .isEqualTo(new Origin(Origin.Kind.LLM, "resolve", ResolvePrompt.VERSION));
    assertThat(link.confidence()).isEqualTo(0.5);
    assertThat(link.evidence()).isEqualTo(unresolved.evidence());
    ObjectNode attributes = link.attributes().deepCopy();
    assertThat(attributes.remove("facts")).isNotNull();
    // The link left open is named by its claim ID once stored; here, before, by its fact ID.
    assertThat(attributes.remove("resolves").asString()).isEqualTo(unresolved.id());
    assertThat(attributes.toString())
        .isEqualTo(
            "{\"client\":\"rest-client\",\"endpoint\":\"GET /v2/quotes\",\"httpMethod\":\"GET\","
                + "\"matchedBy\":\"llm\",\"model\":\"fake/m\",\"path\":\"/v2/quotes\","
                + "\"protocol\":\"JSON/HTTP\",\"reason\":\"Only tax serves GET /v2/quotes among the candidates shown.\","
                + "\"source\":\"method:o.TaxClient#quote()\","
                + "\"target\":\"method:t.TaxController#quotes()\"}");
    // The facts of both ends: the link left open, the call and the endpoint cited.
    assertThat(link.attributes().get("facts").toString())
        .contains(unresolved.id())
        .contains(endpointId("t.TaxController"));
    // A static link to tax's endpoint would be the same claim: same subject, predicate, object and
    // key attributes (endpoint, protocol, view).
    assertThat(link.attributes().get("endpoint").asString()).isEqualTo("GET /v2/quotes");
  }

  private String endpointId(String controller) {
    return facts.stream()
        .filter(
            f ->
                f.predicate().equals(Types.ENDPOINT) && f.subject().toString().contains(controller))
        .findFirst()
        .orElseThrow()
        .id();
  }

  @Test
  void aGatewayRouteLeftOpenIsAskedWithTheUnitsItsPathsReachAndBecomesARouteLink() {
    unit("g", "edge");
    type("g", "edge", "gw.Routes");
    add(
        GatewayExtractor.NAME,
        "type:gw.Routes",
        Types.GATEWAY_ROUTE,
        FactObject.string("quotes"),
        "{\"definedIn\":\"config\",\"uri\":\"http://quotes-svc:8080\","
            + "\"paths\":[\"/v2/quotes\"]}",
        "g:src/main/resources/application.yml#12");
    unit("b", "billing");
    endpoint("b", "billing", "b.BillingController", "/v2/quotes");
    unit("t", "tax");
    endpoint("t", "tax", "t.TaxController", "/v2/quotes");
    FakeLlmProvider fake =
        submittingOnce(
            r ->
                "{\"I1\": "
                    + entry(
                        candidate(r, "service:g:tax"),
                        "high",
                        "The route forwards /v2/quotes, which only tax serves.",
                        ref(r, "gateway-route"),
                        ref(r, "t.TaxController#quotes() endpoint"))
                    + "}");

    LinkResolver.Result result = resolve(fake);

    assertThat(user(first(fake)))
        .contains("Open link: the HTTP call a gateway route forwards, http://quotes-svc:8080\n")
        .contains("[K1] service:g:billing")
        .contains("[K2] service:g:tax");
    Fact link = result.facts().getFirst();
    assertThat(link.subject()).isEqualTo(EntityId.parse("service:g:edge"));
    assertThat(link.predicate()).isEqualTo(Types.CALLS_HTTP);
    assertThat(link.object().value()).isEqualTo("service:g:tax");
    ObjectNode attributes = link.attributes().deepCopy();
    attributes.remove("facts");
    attributes.remove("link");
    attributes.remove("resolves");
    assertThat(attributes.toString())
        .isEqualTo(
            "{\"endpoint\":\"GET /v2/quotes\",\"matchedBy\":\"llm\",\"model\":\"fake/m\","
                + "\"paths\":[\"/v2/quotes\"],\"protocol\":\"JSON/HTTP\","
                + "\"reason\":\"The route forwards /v2/quotes, which only tax serves.\","
                + "\"route\":\"quotes\",\"source\":\"type:gw.Routes\","
                + "\"target\":\"method:t.TaxController#quotes()\","
                + "\"url\":\"http://quotes-svc:8080\"}");
  }

  @Test
  void aPickOutsideTheCandidatesIsSentBackAndASecondOneDropsTheItem() {
    taxCall();
    FakeLlmProvider fake =
        submitting(
            r ->
                turn(r) <= 2
                    ? "{\"I1\": "
                        + entry(
                            "K7",
                            "high",
                            "Payments computes tax, so the caller asks it for quotes.",
                            ref(first(r), "http-call"))
                        + "}"
                    : null);

    LinkResolver.Result result = resolve(fake);

    assertThat(result.facts()).isEmpty();
    assertThat(result.rejected()).hasSize(1);
    // The drop is stored, so a rerun repeats it without a call (#682).
    assertThat(store.size()).isEqualTo(1);
    assertThat(verdicts(fake.sent().get(1).request()))
        .contains("Picks K7, which is not one of this item's candidates (K1, K2)");
    assertThat(verdicts(fake.sent().get(2).request())).contains("rejected again and dropped");

    // A rerun repeats the drop without a call, and the link stays open (#682).
    int sent = fake.sent().size();
    LinkResolver.Result again = resolve(fake);
    assertThat(fake.sent()).hasSize(sent);
    assertThat(again.facts()).isEmpty();
    assertThat(again.rejected()).hasSize(1);
  }

  /** The first request of a conversation, from any of its turns. */
  private static LlmRequest first(LlmRequest request) {
    return new LlmRequest(
        request.role(),
        request.purpose(),
        request.system(),
        List.of(request.messages().getFirst()),
        request.maxOutputTokens(),
        false);
  }

  @Test
  void aRejectedPickIsFixedInTheSameConversation() {
    taxCall();
    FakeLlmProvider fake =
        submitting(
            r ->
                switch (turn(r)) {
                  case 1 ->
                      "{\"I1\": "
                          + entry(
                              "K7",
                              "high",
                              "Tax serves the path the caller requests here.",
                              ref(r, "http-call"))
                          + "}";
                  case 2 -> pickTax(first(r));
                  default -> null;
                });

    LinkResolver.Result result = resolve(fake);

    assertThat(result.facts())
        .singleElement()
        .satisfies(f -> assertThat(f.object().value()).isEqualTo("service:g:tax"));
    assertThat(verdicts(fake.sent().get(2).request())).contains("\"I1\":\"accepted\"");
    // The history keeps the cached prefix: the whole conversation is one.
    assertThat(log.conversations()).hasSize(1);
  }

  @Test
  void aPickCitedOnOneSideOnlyIsRejected() {
    taxCall();
    FakeLlmProvider calleeOnly =
        submittingOnce(
            r ->
                "{\"I1\": "
                    + entry(
                        candidate(r, "service:g:tax"),
                        "high",
                        "Tax serves GET /v2/quotes, the path the caller requests.",
                        ref(r, "t.TaxController#quotes() endpoint"))
                    + "}");
    assertThat(resolve(calleeOnly).facts()).isEmpty();
    assertThat(verdicts(calleeOnly.sent().get(1).request()))
        .contains("Cites nothing on the caller's side");

    FakeLlmProvider callerOnly =
        submittingOnce(
            r ->
                "{\"I1\": "
                    + entry(
                        candidate(r, "service:g:tax"),
                        "high",
                        "It calls GET /v2/quotes, which the candidate serves.",
                        ref(r, "http-call"),
                        ref(r, "b.BillingController#quotes() endpoint"))
                    + "}");
    model = "m2";
    assertThat(resolve(callerOnly).facts()).isEmpty();
    assertThat(verdicts(callerOnly.sent().get(1).request()))
        .contains("Cites nothing on the side of K2, the candidate it picks");
  }

  @Test
  void inOneRepositoryOneCitationDoesNotCountForBothSides() {
    // Caller and candidates in one repository: a range there is on both sides.
    unit("m", "orders");
    type("m", "orders", "o.TaxClient");
    add(
        HttpClientExtractor.NAME,
        "method:o.TaxClient#quote()",
        Types.HTTP_CALL,
        FactObject.string("GET ${TAX_SERVICE_URL}/v2/quotes"),
        "{\"client\":\"rest-client\",\"httpMethod\":\"GET\",\"path\":\"/v2/quotes\","
            + "\"unresolved\":[\"TAX_SERVICE_URL\"]}",
        "m:src/main/java/o/TaxClient.java#18");
    unit("m", "billing");
    endpoint("m", "billing", "b.BillingController", "/v2/quotes");
    unit("m", "tax");
    endpoint("m", "tax", "t.TaxController", "/v2/quotes");
    FakeLlmProvider one =
        submittingOnce(
            r ->
                "{\"I1\": "
                    + entry(
                        candidate(r, "service:g:tax"),
                        "high",
                        "Tax serves the path the caller requests here.",
                        "m:config/clients.yml:1-3")
                    + "}");

    assertThat(resolveWithFiles(one).facts()).isEmpty();
    assertThat(verdicts(one.sent().get(1).request()))
        .contains(
            "Cites nothing on the side of K2, the candidate it picks, apart from what it cites");

    model = "m2";
    FakeLlmProvider two =
        submittingOnce(
            r ->
                "{\"I1\": "
                    + entry(
                        candidate(r, "service:g:tax"),
                        "high",
                        "Tax serves the path the caller requests here.",
                        "m:config/clients.yml:1-3",
                        ref(r, "t.TaxController#quotes() endpoint"))
                    + "}");
    assertThat(resolveWithFiles(two).facts()).hasSize(1);
  }

  @Test
  void aRangeInARepositoryOfNeitherSideCountsForNeither() {
    // A shared library's file can be evidence, but it is on neither the caller's side nor the
    // candidate's (#703).
    taxCall();
    unit("c", "shared");
    FILE_TEXTS.put("c:src/main/java/c/Hosts.java", "class Hosts {\n  String TAX = \"tax\";\n}\n");
    FakeLlmProvider third =
        submittingOnce(
            r ->
                "{\"I1\": "
                    + entry(
                        candidate(r, "service:g:tax"),
                        "high",
                        "The shared hosts name tax as the service called here.",
                        ref(r, "http-call"),
                        "c:src/main/java/c/Hosts.java:1-2")
                    + "}");
    assertThat(resolveWithFiles(third).facts()).isEmpty();
    assertThat(verdicts(third.sent().get(1).request())).contains("Cites nothing on the side of K");

    model = "m2";
    FakeLlmProvider read =
        submittingOnce(
            r ->
                "{\"I1\": "
                    + entry(
                        candidate(r, "service:g:tax"),
                        "high",
                        "Tax serves GET /v2/quotes, the path the caller requests.",
                        ref(r, "http-call"),
                        "c:src/main/java/c/Hosts.java:1-2",
                        ref(r, "t.TaxController#quotes() endpoint"))
                    + "}");
    assertThat(resolveWithFiles(read).facts()).hasSize(1);
    FILE_TEXTS.remove("c:src/main/java/c/Hosts.java");
  }

  @Test
  void aPickCitingNoEndpointReachesTheOneTheLinkerRanksFirst() {
    taxCall();
    FakeLlmProvider fake =
        submittingOnce(
            r ->
                "{\"I1\": "
                    + entry(
                        candidate(r, "service:g:tax"),
                        "high",
                        "Only tax is named for it in the caller's configuration.",
                        ref(r, "http-call"),
                        ref(r, "service:g:tax application-name"))
                    + "}");

    Fact link = resolve(fake).facts().getFirst();

    assertThat(link.attributes().get("endpoint").asString()).isEqualTo("GET /v2/quotes");
    assertThat(link.attributes().get("target").asString())
        .isEqualTo("method:t.TaxController#quotes()");
  }

  @Test
  void aCachedAnswerIsAskedAgainOnceAFileItCitesChanges() {
    taxCall();
    Function<LlmRequest, String> citingLines =
        r ->
            "{\"I1\": "
                + entry(
                    candidate(r, "service:g:tax"),
                    "high",
                    "Only tax serves GET /v2/quotes among the candidates shown.",
                    ref(r, "http-call"),
                    ref(r, "t.TaxController#quotes() endpoint"),
                    "t:src/main/java/t/TaxController.java:3-6")
                + "}";
    resolveWithFiles(submittingOnce(citingLines));
    FakeLlmProvider unchanged = model(r -> List.of());
    resolveWithFiles(unchanged);
    assertThat(unchanged.sent()).isEmpty();

    String key = "t:src/main/java/t/TaxController.java";
    String before = FILE_TEXTS.get(key);
    try {
      FILE_TEXTS.put(key, before + "// edited\n");
      FakeLlmProvider again = model(r -> List.of());
      resolveWithFiles(again);
      assertThat(again.sent()).isNotEmpty();
    } finally {
      FILE_TEXTS.put(key, before);
    }
  }

  @Test
  void aCachedAnswerCitingOneLineIsNotAskedAgain() {
    // A single cited line is a range of that line, in the check and in the cache (#703).
    taxCall();
    Function<LlmRequest, String> citingOneLine =
        r ->
            "{\"I1\": "
                + entry(
                    candidate(r, "service:g:tax"),
                    "high",
                    "Only tax serves GET /v2/quotes among the candidates shown.",
                    ref(r, "http-call"),
                    ref(r, "t.TaxController#quotes() endpoint"),
                    "t:src/main/java/t/TaxController.java:4")
                + "}";
    assertThat(resolveWithFiles(submittingOnce(citingOneLine)).facts()).isNotEmpty();
    FakeLlmProvider unchanged = model(r -> List.of());
    resolveWithFiles(unchanged);
    assertThat(unchanged.sent()).isEmpty();
  }

  @Test
  void aReasonNamingWhatItDoesNotCiteIsRejected() {
    taxCall();
    FakeLlmProvider fake =
        submittingOnce(
            r ->
                "{\"I1\": "
                    + entry(
                        candidate(r, "service:g:tax"),
                        "high",
                        "The TaxGateway routes GET /v2/quotes to tax.",
                        ref(r, "http-call"),
                        ref(r, "t.TaxController#quotes() endpoint"))
                    + "}");

    assertThat(resolve(fake).facts()).isEmpty();
    assertThat(verdicts(fake.sent().get(1).request()))
        .contains("Names TaxGateway, which none of its citations show.");
  }

  @Test
  void aCitationNotInTheRequestIsRejected() {
    taxCall();
    FakeLlmProvider fake =
        submittingOnce(
            r ->
                "{\"I1\": "
                    + entry(
                        candidate(r, "service:g:tax"),
                        "high",
                        "Tax serves the path the caller requests here.",
                        ref(r, "http-call"),
                        "F42")
                    + "}");

    assertThat(resolve(fake).facts()).isEmpty();
    assertThat(verdicts(fake.sent().get(1).request()))
        .contains("Cites F42, which is not in your input.");
  }

  @Test
  void unknownLeavesTheLinkOpenAndIsCached() {
    taxCall();
    FakeLlmProvider fake =
        submittingOnce(
            r ->
                "{\"I1\": "
                    + entry(
                        "unknown",
                        "low",
                        "Both serve the path and nothing in the code names either.",
                        ref(r, "http-call"),
                        ref(r, "t.TaxController#quotes() endpoint"))
                    + "}");

    LinkResolver.Result first = resolve(fake);
    LinkResolver.Result second = resolve(fake);

    assertThat(first.facts()).isEmpty();
    assertThat(first.unknown()).isEqualTo(1);
    assertThat(first.asked()).isEqualTo(1);
    assertThat(second.unknown()).isEqualTo(1);
    assertThat(second.reused()).isEqualTo(1);
    assertThat(second.asked()).isZero();
    assertThat(fake.sent()).hasSize(2);
  }

  @Test
  void aConversationThatNeverSubmitsLeavesTheLinkOpenAndIsNotAskedAgain() {
    taxCall();
    FakeLlmProvider fake = model(r -> List.of());

    LinkResolver.Result first = resolve(fake);
    LinkResolver.Result second = resolve(fake);

    assertThat(first.facts()).isEmpty();
    assertThat(first.unknown()).isEqualTo(1);
    assertThat(second.reused()).isEqualTo(1);
    assertThat(fake.sent()).hasSize(1);
  }

  @Test
  void aSecondRunMakesNoCallAndRecordsTheSameLink() {
    taxCall();
    FakeLlmProvider fake = submittingOnce(PICK_TAX);

    LinkResolver.Result first = resolve(fake);
    LinkResolver.Result second = resolve(fake);

    assertThat(fake.sent()).hasSize(2);
    assertThat(second.reused()).isEqualTo(1);
    assertThat(second.asked()).isZero();
    assertThat(second.facts()).isEqualTo(first.facts());
  }

  @Test
  void theResolverTotalsTheLinksItAskedAboutAcrossItsResolves() {
    taxCall();
    FakeLlmProvider fake = submittingOnce(PICK_TAX);
    LinkResolver resolver = new LinkResolver(harness(client(fake, BudgetGuard.unlimited("test"))));

    resolver.derive(linked(), 1);
    resolver.derive(linked(), 1);

    // A run counts its RESOLVE calls with its summaries and wordings from these (#245).
    assertThat(resolver.asked()).isEqualTo(1);
    assertThat(resolver.reused()).isEqualTo(1);
  }

  @Test
  void theCacheOutlivesANewCommitThatChangesNothingThePromptShows() {
    taxCall();
    FakeLlmProvider fake = submittingOnce(PICK_TAX);
    LinkResolver.Result first = resolve(fake);

    facts.clear();
    commit = "b".repeat(40);
    taxCall();
    LinkResolver.Result second = resolve(fake);

    assertThat(fake.sent()).hasSize(2);
    assertThat(second.facts())
        .singleElement()
        .satisfies(
            f -> {
              assertThat(f.object()).isEqualTo(first.facts().getFirst().object());
              // Evidence names no commit, so it is the same observation (ADR 0091).
              assertThat(f.id()).isEqualTo(first.facts().getFirst().id());
              assertThat(f.attributes().get("resolves"))
                  .isEqualTo(first.facts().getFirst().attributes().get("resolves"));
            });
  }

  @Test
  void anEditThatOnlyMovesTheCallMakesNoCall() {
    taxCall();
    FakeLlmProvider fake = submittingOnce(PICK_TAX);
    resolve(fake);

    facts.clear();
    callLine = 42;
    taxCall();
    LinkResolver.Result second = resolve(fake);

    assertThat(fake.sent()).hasSize(2);
    assertThat(second.facts())
        .singleElement()
        .satisfies(f -> assertThat(f.evidence().lines().start()).isEqualTo(42));
  }

  @Test
  void aChangedConfigValueOfTheCallsKeyAsksAgainAndIsShownInBlockFour() {
    taxCall();
    config("clients.tax.url", "${TAX_SERVICE_URL}");
    FakeLlmProvider fake = submittingOnce(PICK_TAX);
    resolve(fake);

    assertThat(user(first(fake)))
        .contains(
            "The calling unit: service:g:orders\n"
                + "Its facts that bear on addresses (its application name, the deployment"
                + " containers that run it, and the configuration values of the keys its links"
                + " use, per view):\n<facts>\n"
                + "[F1] module:g:orders config-value \"${TAX_SERVICE_URL}\""
                + " {\"key\":\"clients.tax.url\"} (a:src/main/resources/application.yml)\n")
        // Shown once, in block 4, not again in the item.
        .containsOnlyOnce("config-value");

    facts.removeIf(f -> f.predicate().equals(Types.CONFIG_VALUE));
    config("clients.tax.url", "${TAX_URL}");
    resolve(fake);

    assertThat(log.conversations()).hasSize(2);
  }

  private void config(String key, String value) {
    add(
        io.lantern.extract.config.ConfigExtractor.NAME,
        "module:g:orders",
        Types.CONFIG_VALUE,
        FactObject.string(value),
        "{\"key\":\"" + key + "\"}",
        "a:src/main/resources/application.yml#3");
  }

  @Test
  void aUnitsOpenLinksAreAskedFourToAConversation() {
    fiveOpenLinks();
    FakeLlmProvider fake = model(r -> List.of());

    LinkResolver.Result result = resolve(fake);

    assertThat(result.unknown()).isEqualTo(5);
    assertThat(log.conversations()).hasSize(2);
    List<String> users = fake.sent().stream().map(s -> user(s.request())).toList();
    assertThat(users).anyMatch(u -> u.contains("<item id=\"I4\">") && !u.contains("\"I5\""));
    assertThat(users).anyMatch(u -> u.contains("<item id=\"I1\">") && !u.contains("\"I2\""));
  }

  @Test
  void aCalibrationAsksTheLinksAtItsSize() {
    fiveOpenLinks();
    FakeLlmProvider fake = model(r -> List.of());
    io.lantern.harness.Harness harness =
        harness(client(fake, BudgetGuard.unlimited("test")))
            .calibrating("resolve", new io.lantern.harness.Harness.Calibration(1, "1"));

    LinkResolver.Result result = new LinkResolver(harness).resolve(linked(), 1);

    assertThat(result.unknown()).isEqualTo(5);
    // One link a conversation (ADR 0112), the run record saying so.
    assertThat(log.conversations()).hasSize(5);
    assertThat(harness.records().getFirst().toJson().path("packetSize").asInt()).isEqualTo(1);
  }

  private void fiveOpenLinks() {
    unit("a", "orders");
    unit("b", "billing");
    unit("t", "tax");
    for (int i = 1; i <= 5; i++) {
      type("a", "orders", "o.Client" + i);
      add(
          HttpClientExtractor.NAME,
          "method:o.Client" + i + "#call()",
          Types.HTTP_CALL,
          FactObject.string("GET ${URL" + i + "}/v2/q" + i),
          "{\"client\":\"rest-client\",\"httpMethod\":\"GET\",\"path\":\"/v2/q"
              + i
              + "\","
              + "\"unresolved\":[\"URL"
              + i
              + "\"]}",
          "a:src/main/java/o/Client" + i + ".java#5");
      endpoint("b", "billing", "b.Billing" + i, "/v2/q" + i);
      endpoint("t", "tax", "t.Tax" + i, "/v2/q" + i);
    }
  }

  @Test
  void aSkippedCallKeepsTheLinkTheModelPickedBefore() {
    taxCall();
    LinkResolver.Result first = resolve(submittingOnce(PICK_TAX));

    // Another model, so the cache has no answer for its key, and no budget to ask it.
    model = "m2";
    FakeLlmProvider fake = submittingOnce(PICK_TAX);
    LinkResolver.Result second = resolve(fake, new BudgetGuard("run", 100L, null));

    assertThat(fake.sent()).isEmpty();
    assertThat(second.skipped()).hasSize(1);
    assertThat(second.facts()).isEqualTo(first.facts());
  }

  @Test
  void aRejectedAnswerKeepsTheLinkTheModelPickedBeforeIfStillACandidate() {
    taxCall();
    LinkResolver.Result first = resolve(submittingOnce(PICK_TAX));
    Function<LlmRequest, String> wrong =
        r ->
            turn(r) <= 2
                ? "{\"I1\": "
                    + entry("K9", "high", "x x x x x x x x", ref(first(r), "http-call"))
                    + "}"
                : null;

    model = "m2";
    LinkResolver.Result second = resolve(submitting(wrong));

    assertThat(second.rejected()).hasSize(1);
    assertThat(second.facts()).isEqualTo(first.facts());

    // Once tax is no longer a candidate, the earlier pick is not kept.
    facts.removeIf(f -> f.evidence().repo().equals("t"));
    model = "m3";
    assertThat(resolve(submitting(wrong)).facts()).isEmpty();
  }

  @Test
  void anExhaustedBudgetLeavesTheLinksOpenWithoutFailing() {
    taxCall();
    FakeLlmProvider fake = submittingOnce(PICK_TAX);

    LinkResolver.Result result = resolve(fake, new BudgetGuard("run", 100L, null));

    assertThat(result.facts()).isEmpty();
    assertThat(result.skipped()).hasSize(1);
    assertThat(fake.sent()).isEmpty();
    assertThat(store.size()).isZero();
    assertThat(log.calls())
        .singleElement()
        .satisfies(c -> assertThat(c.outcome()).isEqualTo(LlmCall.Outcome.REFUSED));

    // With budget left, a later run asks.
    assertThat(resolve(fake).facts()).hasSize(1);
  }

  @Test
  void aLinkWithoutCandidatesIsNotAskedAbout() {
    // HTTP-5 of project-0: no unit serves the path, so there is nothing to pick from.
    unit("a", "orders");
    type("a", "orders", "o.TaxClient");
    add(
        HttpClientExtractor.NAME,
        "method:o.TaxClient#quote()",
        Types.HTTP_CALL,
        FactObject.string("GET ${TAX_SERVICE_URL}/v2/quotes"),
        "{\"client\":\"rest-client\",\"httpMethod\":\"GET\",\"path\":\"/v2/quotes\","
            + "\"unresolved\":[\"TAX_SERVICE_URL\"]}",
        "a:src/main/java/o/TaxClient.java#18");
    FakeLlmProvider fake = submittingOnce(PICK_TAX);

    LinkResolver.Result result = resolve(fake);

    assertThat(linked()).anyMatch(f -> f.predicate().equals(Types.UNRESOLVED_LINK));
    assertThat(result.facts()).isEmpty();
    assertThat(fake.sent()).isEmpty();
  }

  @Test
  void aSendWithAnUnknownRoutingKeyIsResolvedToTheListeningUnit() {
    unit("s", "shop");
    type("s", "shop", "s.Publisher");
    add(
        MessagingExtractor.NAME,
        "method:s.Publisher#unknown()",
        Types.MESSAGE_SEND,
        FactObject.string("direct.x"),
        "{\"broker\":\"rabbitmq\",\"destinationKind\":\"exchange\",\"routingKey\":\"k\","
            + "\"unknown\":[\"routingKey\"]}",
        "s:src/main/java/s/Publisher.java#7");
    unit("w", "worker");
    type("w", "worker", "w.Config");
    type("w", "worker", "w.Listener");
    add(
        MessagingExtractor.NAME,
        "method:w.Config#direct()",
        Types.MESSAGE_BINDING,
        FactObject.string("q.direct"),
        "{\"broker\":\"rabbitmq\",\"exchange\":\"direct.x\",\"exchangeType\":\"direct\","
            + "\"routingKey\":\"order.created\"}",
        "w:src/main/java/w/Config.java#5");
    add(
        MessagingExtractor.NAME,
        "method:w.Listener#direct()",
        Types.MESSAGE_LISTENER,
        FactObject.string("q.direct"),
        "{\"broker\":\"rabbitmq\",\"destinationKind\":\"queue\"}",
        "w:src/main/java/w/Listener.java#8");
    FakeLlmProvider fake =
        submittingOnce(
            r -> {
              assertThat(user(r)).contains("[K1] service:g:worker");
              return "{\"I1\": "
                  + entry(
                      "K1",
                      "medium",
                      "Only worker listens on a queue bound to direct.x here.",
                      ref(r, "s.Publisher#unknown()"),
                      ref(r, "w.Config#direct() message-binding"),
                      ref(r, "w.Listener#direct() message-listener"))
                  + "}";
            });

    LinkResolver.Result result = resolve(fake);

    assertThat(result.facts())
        .singleElement()
        .satisfies(
            f -> {
              assertThat(f.predicate()).isEqualTo(Types.SENDS_MESSAGES_TO);
              assertThat(f.object().value()).isEqualTo("service:g:worker");
              assertThat(f.confidence()).isEqualTo(0.35);
              assertThat(f.attributes().get("protocol").asString()).isEqualTo("AMQP");
              assertThat(f.attributes().get("target").asString())
                  .isEqualTo("method:w.Listener#direct()");
              // The key attributes static matching writes, so the pick is the same claim.
              assertThat(f.attributes().get("destination").asString())
                  .isEqualTo("exchange:rabbitmq:direct.x");
              assertThat(f.attributes().get("bindingKey").asString()).isEqualTo("order.created");
              assertThat(f.attributes().get("queue").asString())
                  .isEqualTo("queue:rabbitmq:q.direct");
            });
  }

  @Test
  void anOpenSendCitesValueResolutionsRelationWhoseBrokerNamesTheProtocol() {
    unit("s", "shop");
    type("s", "shop", "s.Publisher");
    add(
        MessagingExtractor.NAME,
        "method:s.Publisher#send()",
        Types.MESSAGE_SEND,
        FactObject.string("orders"),
        "{\"broker\":\"kafka\",\"destinationKind\":\"topic\"}",
        "s:src/main/java/s/Publisher.java#7");
    Fact relation =
        linked().stream()
            .filter(f -> f.predicate().equals(Types.SENDS_TO))
            .findFirst()
            .orElseThrow();

    // The messaging matcher cites the relation, not the message fact (#597).
    assertThat(LinkResolver.isSend(relation)).isTrue();
    assertThat(LinkResolver.brokerOf(relation)).isEqualTo("kafka");
    assertThat(LinkResolver.protocol(LinkResolver.brokerOf(relation))).isEqualTo("Kafka");
  }

  @Test
  void aRecordedConversationIsReplayedWithoutTheModel() {
    taxCall();
    FakeLlmProvider fake = submittingOnce(PICK_TAX);
    LinkResolver.Result recorded =
        resolve(RecordReplayLlmProvider.recording(fake, recordings), BudgetGuard.unlimited("t"));
    assertThat(fake.sent()).hasSize(2);

    LinkResolver.Result replayed =
        new LinkResolver(
                Harness.inMemory(
                    client(
                        RecordReplayLlmProvider.replaying(FakeLlmProvider.NAME, recordings),
                        BudgetGuard.unlimited("t"))))
            .resolve(linked(), 1);

    assertThat(fake.sent()).hasSize(2);
    assertThat(replayed.asked()).isEqualTo(1);
    assertThat(replayed.facts()).isEqualTo(recorded.facts());
  }

  @Test
  void aModelThatCannotUseToolsIsNotAsked() {
    taxCall();
    FakeLlmProvider fake = submittingOnce(PICK_TAX);
    LlmClient client =
        new LlmClient(
            ModelSettings.single(
                new io.lantern.llm.ModelConfig(
                    FakeLlmProvider.NAME,
                    model,
                    java.util.Set.of(),
                    1_000_000,
                    io.lantern.llm.Pricing.FREE)),
            List.of(fake),
            BudgetGuard.unlimited("t"),
            log,
            Clock.systemUTC());

    LinkResolver.Result result = new LinkResolver(harness(client), FILES).resolve(linked(), 1);

    assertThat(result.facts()).isEmpty();
    assertThat(result.skipped()).hasSize(1);
    assertThat(fake.sent()).isEmpty();
  }

  // ---------------------------------------------------------------------------------------------
  // Tools (ADR 0081)

  private static final String TAX_CONTROLLER =
      """
      package t;

      @RestController
      class TaxController {
        private String apiKey = "sk-live-123";
        @GetMapping("/v2/quotes") List<Quote> quotes() { return rates.quotes(); }
      }
      """;

  private static final String ORDERS_CONFIG =
      """
      clients:
        tax:
          url: ${TAX_SERVICE_URL}
      """;

  private static final Map<String, String> FILE_TEXTS =
      new java.util.concurrent.ConcurrentHashMap<>(
          Map.of(
              "t:src/main/java/t/TaxController.java", TAX_CONTROLLER,
              "a:src/main/resources/application.yml", ORDERS_CONFIG,
              "m:config/clients.yml", ORDERS_CONFIG));

  private static final GraphTools.RepoFiles FILES =
      new GraphTools.RepoFiles() {
        @Override
        public java.util.Optional<byte[]> read(String repo, String path) {
          return java.util.Optional.ofNullable(FILE_TEXTS.get(repo + ":" + path))
              .map(text -> text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override
        public List<GraphTools.Hit> find(String repo, String text, int max) {
          List<GraphTools.Hit> hits = new ArrayList<>();
          new java.util.TreeMap<>(FILE_TEXTS)
              .forEach(
                  (file, content) -> {
                    if (file.startsWith(repo + ":")) {
                      List<String> lines = content.lines().toList();
                      for (int i = 0; i < lines.size() && hits.size() < max; i++) {
                        if (lines.get(i).contains(text)) {
                          hits.add(new GraphTools.Hit(file.substring(repo.length() + 1), i + 1));
                        }
                      }
                    }
                  });
          return hits;
        }
      };

  /** The reference a tool result of the request gave the first fact line holding the text. */
  private static String toolRef(LlmRequest request, String text) {
    for (LlmMessage message : request.messages()) {
      for (var result : message.toolResults()) {
        for (String line : result.content().lines().toList()) {
          Matcher ref = FACT_REF.matcher(line);
          if (ref.find() && line.contains(text)) {
            return ref.group(1);
          }
        }
      }
    }
    throw new AssertionError("No tool showed " + text);
  }

  /**
   * A model that looks up the tax controller's facts, reads its file, then picks tax citing the
   * call, a fact the lookup showed and the lines it read.
   */
  private static FakeLlmProvider lookingFurther() {
    return model(
        r ->
            switch (turn(r)) {
              case 1 ->
                  List.of(
                      toolCall("toolu_01", "facts_about", "{\"entity\":\"type:t.TaxController\"}"));
              case 2 ->
                  List.of(
                      toolCall(
                          "toolu_02",
                          "read_file",
                          "{\"repo\":\"t\",\"path\":\"src/main/java/t/TaxController.java\","
                              + "\"from\":3,\"to\":6}"));
              case 3 ->
                  List.of(
                      submit(
                          r,
                          "{\"I1\": "
                              + entry(
                                  candidate(first(r), "service:g:tax"),
                                  "medium",
                                  "TaxController is a REST controller that serves GET /v2/quotes.",
                                  ref(first(r), "http-call"),
                                  toolRef(r, "type:t.TaxController stereotype"),
                                  "t:src/main/java/t/TaxController.java:3-6")
                              + "}"));
              default -> List.of();
            });
  }

  private LinkResolver.Result resolveWithFiles(LlmProvider provider) {
    LinkResolver.Result result =
        new LinkResolver(harness(client(provider, BudgetGuard.unlimited("t"))), FILES)
            .resolve(linked(), 1);
    result.facts().forEach(DeployableUnitsTest.vocabulary()::validate);
    return result;
  }

  @Test
  void aModelLooksFurtherWithTheToolsAndCitesWhatTheyShowedAndTheLinesItRead() {
    taxCall();
    FakeLlmProvider fake = lookingFurther();

    LinkResolver.Result result = resolveWithFiles(fake);

    assertThat(fake.sent()).hasSize(4);
    LlmRequest last = fake.sent().getLast().request();
    assertThat(last.tools())
        .extracting(io.lantern.llm.LlmTool::name)
        .containsExactly("facts_about", "search_facts", "read_file", "find_in_code", "submit");
    String about = last.messages().get(2).toolResults().getFirst().content();
    // The tools' facts are numbered after the request's.
    assertThat(about)
        .startsWith("Facts about type:t.TaxController:\n[F")
        .contains(
            "type:t.TaxController stereotype \"controller\" {\"rest\":true}"
                + " (t:src/main/java/t/TaxController.java)");
    assertThat(about).doesNotContain("[F1] ");
    String read = last.messages().get(4).toolResults().getFirst().content();
    assertThat(read)
        .isEqualTo(
            """
            t:src/main/java/t/TaxController.java, lines 3 to 6 of 7:
            3: @RestController
            4: class TaxController {
            5:   private String apiKey = "******";
            6:   @GetMapping("/v2/quotes") List<Quote> quotes() { return rates.quotes(); }
            """);
    assertThat(verdicts(last)).contains("\"I1\":\"accepted\"");
    Fact link = result.facts().getFirst();
    assertThat(link.object().value()).isEqualTo("service:g:tax");
    assertThat(link.confidence()).isEqualTo(0.35);
    Fact stereotype =
        facts.stream()
            .filter(
                f ->
                    f.predicate().equals(Types.STEREOTYPE)
                        && f.subject().toString().equals("type:t.TaxController"))
            .findFirst()
            .orElseThrow();
    assertThat(link.attributes().get("facts").toString()).contains(stereotype.id());
    // Stored by claim ID and as the range was written, whatever the request numbered them.
    assertThat(
            ResolveRole.storedCites(
                store
                    .latest(LinkResolver.ROLE.name(), link.attributes().get("resolves").asString())
                    .orElseThrow()
                    .answer()))
        .contains(ResolvePrompt.key(stereotype), "t:src/main/java/t/TaxController.java:3-6");
    assertThat(log.conversations())
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.role()).isEqualTo(Role.RESOLVE);
              assertThat(c.turns()).isEqualTo(4);
              assertThat(c.toolCalls())
                  .isEqualTo(Map.of("facts_about", 1, "read_file", 1, "submit", 1));
            });
  }

  @Test
  void findInCodeGivesTheLinesThatHoldATextWithTheirFileAndNumber() {
    taxCall();
    FakeLlmProvider fake =
        model(
            r ->
                turn(r) == 1
                    ? List.of(
                        toolCall("toolu_01", "find_in_code", "{\"text\":\"TAX_SERVICE_URL\"}"))
                    : List.of());

    resolveWithFiles(fake);

    assertThat(fake.sent().get(1).request().messages().getLast().toolResults().getFirst().content())
        .isEqualTo(
            "Lines that hold \"TAX_SERVICE_URL\":\n"
                + "a:src/main/resources/application.yml:3: url: ${TAX_SERVICE_URL}\n");
  }

  @Test
  void aCachedAnswerThatCitedAToolsFactGivesTheSameLink() {
    taxCall();
    LinkResolver.Result first = resolveWithFiles(lookingFurther());
    FakeLlmProvider unused = lookingFurther();

    LinkResolver.Result second = resolveWithFiles(unused);

    assertThat(unused.sent()).isEmpty();
    assertThat(second.reused()).isEqualTo(1);
    assertThat(second.facts()).isEqualTo(first.facts());
  }

  @Test
  void aCachedAnswerWhoseToolsFactNoLongerHoldsIsAskedAgain() {
    taxCall();
    resolveWithFiles(lookingFurther());
    facts.removeIf(
        f ->
            f.predicate().equals(Types.STEREOTYPE)
                && f.subject().toString().equals("type:t.TaxController"));
    FakeLlmProvider again = model(r -> List.of());

    LinkResolver.Result second = resolveWithFiles(again);

    assertThat(again.sent()).isNotEmpty();
    assertThat(second.reused()).isZero();
    assertThat(second.asked()).isEqualTo(1);
  }

  @Test
  void aToolReferenceNoToolShowedIsRejected() {
    taxCall();
    FakeLlmProvider fake =
        submittingOnce(
            r ->
                "{\"I1\": "
                    + entry(
                        candidate(r, "service:g:tax"),
                        "high",
                        "Seen in a tool result that the caller's code shows.",
                        ref(r, "http-call"),
                        ref(r, "t.TaxController#quotes() endpoint"),
                        "F40")
                    + "}");

    LinkResolver.Result result = resolveWithFiles(fake);

    // Rejected once and never submitted again: dropped, not cached.
    assertThat(result.rejected()).hasSize(1);
    assertThat(result.facts()).isEmpty();
    assertThat(verdicts(fake.sent().get(1).request()))
        .contains("Cites F40, which is not in your input.");
  }

  /**
   * The recordings of {@link #lookingFurther()}'s conversation, kept in the repository in the
   * record and replay format (ADR 0008): four turns, three of them asking for tools.
   */
  private static final Path TOOL_RECORDINGS =
      Path.of("src/test/resources/recordings/resolve-tools");

  @Test
  void aRecordedToolLoopReplaysTurnByTurn() {
    taxCall();
    if (Boolean.getBoolean("lantern.test.rerecord")) {
      // Rewrites the recordings from the scripted model above; no real model is called.
      FakeLlmProvider scripted = lookingFurther();
      new LinkResolver(
              Harness.inMemory(
                  new LlmClient(
                      ModelSettings.single(scripted.model(model)),
                      List.of(RecordReplayLlmProvider.recording(scripted, TOOL_RECORDINGS)),
                      BudgetGuard.unlimited("t"),
                      new InMemoryLlmCallLog(),
                      Clock.systemUTC())),
              FILES)
          .resolve(linked(), 1);
    }

    LinkResolver.Result replayed =
        resolveWithFiles(RecordReplayLlmProvider.replaying(FakeLlmProvider.NAME, TOOL_RECORDINGS));

    assertThat(replayed.asked()).isEqualTo(1);
    assertThat(replayed.facts())
        .singleElement()
        .satisfies(f -> assertThat(f.object().value()).isEqualTo("service:g:tax"));
    assertThat(log.conversations())
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.turns()).isEqualTo(4);
              assertThat(c.toolCallCount()).isEqualTo(3);
            });
  }
}

package io.lantern.link;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.extract.gateway.GatewayExtractor;
import io.lantern.extract.http.HttpClientExtractor;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.extract.maven.MavenExtractor;
import io.lantern.extract.messaging.MessagingExtractor;
import io.lantern.extract.spring.SpringExtractor;
import io.lantern.llm.BudgetGuard;
import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.InMemoryLlmCallLog;
import io.lantern.llm.LlmCall;
import io.lantern.llm.LlmClient;
import io.lantern.llm.LlmMessage;
import io.lantern.llm.LlmProvider;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.ModelSettings;
import io.lantern.llm.RecordReplayLlmProvider;
import io.lantern.llm.Role;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;

/** The LLM resolver, with a fake model and recorded answers; never a real one. */
class LinkResolverTest {

  private static final String PICK_TAX =
      "{\"target\": \"C2\", \"confidence\": \"high\", \"cites\": [\"F3\", \"C2\", \"F9\"],"
          + " \"rationale\": \"The client is named TaxClient and only tax serves GET /v2/quotes.\"}";

  private String commit = "a".repeat(40);
  private String model = "m";
  private int callLine = 18;
  private final List<Fact> facts = new ArrayList<>();
  private final InMemoryResolutionStore store = new InMemoryResolutionStore();
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
        BuiltInTypes.CONTAINS,
        FactObject.entity(EntityId.parse("module:g:" + artifact)),
        null,
        repo + ":pom.xml");
    add(
        DeployableUnits.NAME,
        "service:g:" + artifact,
        DeployableUnits.DEPLOYABLE_UNIT,
        FactObject.string("spring-boot"),
        null,
        repo + ":pom.xml");
    add(
        DeployableUnits.NAME,
        "service:g:" + artifact,
        DeployableUnits.APPLICATION_NAME,
        FactObject.string(artifact),
        "{\"module\":\"module:g:" + artifact + "\"}",
        repo + ":src/main/resources/application.yml#2");
  }

  private void type(String repo, String module, String type) {
    String pkg = type.substring(0, type.lastIndexOf('.'));
    add(
        JavaExtractor.NAME,
        "module:g:" + module,
        BuiltInTypes.CONTAINS,
        FactObject.entity(EntityId.parse("package:g:" + module + ":" + pkg)),
        null,
        repo + ":src/main/java");
    add(
        JavaExtractor.NAME,
        "package:g:" + module + ":" + pkg,
        BuiltInTypes.CONTAINS,
        FactObject.entity(EntityId.parse("type:" + type)),
        null,
        repo + ":src/main/java/" + type.replace('.', '/') + ".java#1");
  }

  private void endpoint(String repo, String module, String controller, String path) {
    type(repo, module, controller);
    add(
        SpringExtractor.NAME,
        "method:" + controller + "#quotes()",
        SpringExtractor.ENDPOINT,
        FactObject.string("GET " + path),
        "{\"controller\":\"" + controller + "\",\"httpMethod\":\"GET\",\"path\":\"" + path + "\"}",
        repo + ":src/main/java/" + controller.replace('.', '/') + ".java#20");
    add(
        SpringExtractor.NAME,
        "type:" + controller,
        SpringExtractor.STEREOTYPE,
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
        HttpClientExtractor.HTTP_CALL,
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
        new LinkResolver(client(provider, budget), store).resolve(linked(), 1);
    result.facts().forEach(DeployableUnitsTest.vocabulary()::validate);
    return result;
  }

  private static FakeLlmProvider answering(Function<LlmRequest, String> answer) {
    return new FakeLlmProvider().answering(answer);
  }

  private static String user(LlmRequest request) {
    return request.messages().getFirst().text();
  }

  // ---------------------------------------------------------------------------------------------
  // Cases

  @Test
  void thePromptShowsTheCallItsFactsAndEachCandidateWithItsEndpoints() {
    taxCall();
    FakeLlmProvider fake = answering(r -> PICK_TAX);

    resolve(fake);

    LlmRequest request = fake.sent().getFirst().request();
    assertThat(request.role()).isEqualTo(Role.RESOLVE);
    assertThat(request.system()).isEqualTo(ResolvePrompt.system(true));
    assertThat(request.tools())
        .extracting(io.lantern.llm.LlmTool::name)
        .containsExactly("facts_about", "search_facts");
    assertThat(request.purpose())
        .isEqualTo("resolve http link GET ${TAX_SERVICE_URL}/v2/quotes of service:g:orders");
    assertThat(user(request))
        .isEqualTo(
            """
            The HTTP call that static matching left open: GET ${TAX_SERVICE_URL}/v2/quotes
            Made by the unit service:g:orders, in method:o.TaxClient#quote().
            Left open because its URL or destination has placeholders; placeholders no config \
            resolves: TAX_SERVICE_URL.

            Facts about the call:
            [F1] service:g:orders deployable-unit "spring-boot" (a:pom.xml)
            [F2] service:g:orders application-name "orders" {"module":"module:g:orders"} \
            (a:src/main/resources/application.yml)
            [F3] method:o.TaxClient#quote() http-call "GET ${TAX_SERVICE_URL}/v2/quotes" \
            {"client":"rest-client","httpMethod":"GET","path":"/v2/quotes",\
            "unresolved":["TAX_SERVICE_URL"],"written":"${clients.tax.url}/v2/quotes"} \
            (a:src/main/java/o/TaxClient.java)

            Candidates:
            [C1] service:g:billing
              [F4] service:g:billing deployable-unit "spring-boot" (b:pom.xml)
              [F5] service:g:billing application-name "billing" {"module":"module:g:billing"} \
            (b:src/main/resources/application.yml)
              [F6] method:b.BillingController#quotes() endpoint "GET /v2/quotes" \
            {"controller":"b.BillingController","httpMethod":"GET","path":"/v2/quotes"} \
            (b:src/main/java/b/BillingController.java)
            [C2] service:g:tax
              [F7] service:g:tax deployable-unit "spring-boot" (t:pom.xml)
              [F8] service:g:tax application-name "tax" {"module":"module:g:tax"} \
            (t:src/main/resources/application.yml)
              [F9] method:t.TaxController#quotes() endpoint "GET /v2/quotes" \
            {"controller":"t.TaxController","httpMethod":"GET","path":"/v2/quotes"} \
            (t:src/main/java/t/TaxController.java)
            """);
    assertThat(log.calls())
        .singleElement()
        .satisfies(
            call -> {
              assertThat(call.role()).isEqualTo(Role.RESOLVE);
              assertThat(call.purpose()).isEqualTo(request.purpose());
              assertThat(call.outcome()).isEqualTo(LlmCall.Outcome.SUCCEEDED);
            });
  }

  @Test
  void aPickBecomesALinkOfOriginLlmWithLowerConfidenceAndTheRationale() {
    taxCall();
    LinkResolver.Result result = resolve(answering(r -> PICK_TAX));

    Fact unresolved =
        linked().stream()
            .filter(f -> f.predicate().equals(CrossRepoLinks.UNRESOLVED_LINK))
            .findFirst()
            .orElseThrow();
    assertThat(result.asked()).isEqualTo(1);
    assertThat(result.facts()).hasSize(1);
    Fact link = result.facts().getFirst();
    assertThat(link.subject()).isEqualTo(EntityId.parse("service:g:orders"));
    assertThat(link.predicate()).isEqualTo(CrossRepoLinks.CALLS_HTTP);
    assertThat(link.object().value()).isEqualTo("service:g:tax");
    assertThat(link.origin()).isEqualTo(new Origin(Origin.Kind.LLM, "link-resolver", "4"));
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
                + "\"protocol\":\"JSON/HTTP\",\"rationale\":\"The client is named TaxClient and"
                + " only tax serves GET /v2/quotes.\",\"source\":\"method:o.TaxClient#quote()\","
                + "\"target\":\"method:t.TaxController#quotes()\"}");
    // The facts of both ends: the link left open, the call and the endpoint cited.
    assertThat(link.attributes().get("facts").toString())
        .contains(unresolved.id())
        .contains(endpointId("t.TaxController"));
  }

  private String endpointId(String controller) {
    return facts.stream()
        .filter(
            f ->
                f.predicate().equals(SpringExtractor.ENDPOINT)
                    && f.subject().toString().contains(controller))
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
        GatewayExtractor.ROUTE,
        FactObject.string("quotes"),
        "{\"definedIn\":\"config\",\"uri\":\"http://quotes-svc:8080\","
            + "\"paths\":[\"/v2/quotes\"]}",
        "g:src/main/resources/application.yml#12");
    unit("b", "billing");
    endpoint("b", "billing", "b.BillingController", "/v2/quotes");
    unit("t", "tax");
    endpoint("t", "tax", "t.TaxController", "/v2/quotes");
    FakeLlmProvider fake =
        answering(
            r -> {
              String user = user(r);
              String line =
                  user.lines()
                      .filter(l -> l.contains("t.TaxController#quotes() endpoint"))
                      .findFirst()
                      .orElseThrow();
              String ref = line.substring(line.indexOf('[') + 1, line.indexOf(']'));
              return "{\"target\": \"C2\", \"confidence\": \"high\", \"cites\": [\"C2\", \""
                  + ref
                  + "\"], \"rationale\": \"The route's host names quotes.\"}";
            });

    LinkResolver.Result result = resolve(fake);

    assertThat(user(fake.sent().getFirst().request()))
        .startsWith(
            "The HTTP call a gateway route forwards that static matching left open:"
                + " http://quotes-svc:8080\n")
        .contains("Facts about the route:")
        .contains("[C1] service:g:billing")
        .contains("[C2] service:g:tax");
    Fact link = result.facts().getFirst();
    assertThat(link.subject()).isEqualTo(EntityId.parse("service:g:edge"));
    assertThat(link.predicate()).isEqualTo(CrossRepoLinks.CALLS_HTTP);
    assertThat(link.object().value()).isEqualTo("service:g:tax");
    ObjectNode attributes = link.attributes().deepCopy();
    attributes.remove("facts");
    attributes.remove("link");
    attributes.remove("resolves");
    assertThat(attributes.toString())
        .isEqualTo(
            "{\"endpoint\":\"GET /v2/quotes\",\"matchedBy\":\"llm\",\"model\":\"fake/m\","
                + "\"paths\":[\"/v2/quotes\"],\"protocol\":\"JSON/HTTP\","
                + "\"rationale\":\"The route's host names quotes.\",\"route\":\"quotes\","
                + "\"source\":\"type:gw.Routes\",\"target\":\"method:t.TaxController#quotes()\","
                + "\"url\":\"http://quotes-svc:8080\"}");
  }

  @Test
  void aTargetOutsideTheCandidatesIsRejectedAndTheLinkStaysOpen() {
    taxCall();
    FakeLlmProvider fake =
        answering(
            r ->
                "{\"target\": \"service:g:payments\", \"confidence\": \"high\","
                    + " \"cites\": [\"F3\"], \"rationale\": \"Payments computes tax.\"}");

    LinkResolver.Result result = resolve(fake);

    assertThat(result.facts()).isEmpty();
    assertThat(result.rejected()).hasSize(1);
    assertThat(store.size()).isZero();
    // Sent back once with the reason, then left open.
    assertThat(fake.sent()).hasSize(2);
    List<LlmMessage> retry = fake.sent().get(1).request().messages();
    assertThat(retry).hasSize(3);
    assertThat(retry.getLast().text())
        .contains("the target service:g:payments is not one of the candidates (C1, C2)");
    assertThat(fake.sent().get(1).request().purpose()).endsWith(" (retry)");
  }

  @Test
  void aRejectedAnswerIsSentBackOnceAndAGoodSecondAnswerCounts() {
    taxCall();
    FakeLlmProvider fake =
        answering(r -> r.messages().size() == 1 ? "{\"target\": \"C7\"}" : PICK_TAX);

    LinkResolver.Result result = resolve(fake);

    assertThat(fake.sent()).hasSize(2);
    assertThat(result.facts())
        .singleElement()
        .satisfies(f -> assertThat(f.object().value()).isEqualTo("service:g:tax"));
  }

  @Test
  void anAnswerThatCitesWhatThePromptDoesNotShowOrNotItsPickIsRejected() {
    taxCall();
    FakeLlmProvider unknownRef =
        answering(
            r ->
                "{\"target\": \"C2\", \"confidence\": \"high\", \"cites\": [\"C2\", \"F42\"],"
                    + " \"rationale\": \"x\"}");
    assertThat(resolve(unknownRef).rejected()).hasSize(1);
    assertThat(unknownRef.sent().get(1).request().messages().getLast().text())
        .contains("it cites F42, which is not in the input");

    FakeLlmProvider notThePick =
        answering(
            r ->
                "{\"target\": \"C2\", \"confidence\": \"high\", \"cites\": [\"F6\"],"
                    + " \"rationale\": \"x\"}");
    assertThat(resolve(notThePick).rejected()).hasSize(1);
    assertThat(notThePick.sent().get(1).request().messages().getLast().text())
        .contains("it does not cite the candidate it picks, C2, or any of its facts");

    FakeLlmProvider prose = answering(r -> "It is probably tax.");
    assertThat(resolve(prose).rejected()).hasSize(1);
    assertThat(prose.sent().get(1).request().messages().getLast().text())
        .contains("it is not one JSON object");
  }

  @Test
  void unknownLeavesTheLinkOpenAndIsCached() {
    taxCall();
    FakeLlmProvider fake =
        answering(
            r ->
                "```json\n{\"target\": \"unknown\", \"cites\": [\"F3\"], \"rationale\": \"Both"
                    + " serve the path and nothing names either.\"}\n```");

    LinkResolver.Result first = resolve(fake);
    LinkResolver.Result second = resolve(fake);

    assertThat(first.facts()).isEmpty();
    assertThat(first.unknown()).isEqualTo(1);
    assertThat(first.asked()).isEqualTo(1);
    assertThat(second.unknown()).isEqualTo(1);
    assertThat(second.reused()).isEqualTo(1);
    assertThat(second.asked()).isZero();
    assertThat(fake.sent()).hasSize(1);
  }

  @Test
  void aSecondRunMakesNoCallAndRecordsTheSameLink() {
    taxCall();
    FakeLlmProvider fake = answering(r -> PICK_TAX);

    LinkResolver.Result first = resolve(fake);
    LinkResolver.Result second = resolve(fake);

    assertThat(fake.sent()).hasSize(1);
    assertThat(second.reused()).isEqualTo(1);
    assertThat(second.facts()).isEqualTo(first.facts());
  }

  @Test
  void theResolverTotalsTheLinksItAskedAboutAcrossItsResolves() {
    taxCall();
    FakeLlmProvider fake = answering(r -> PICK_TAX);
    LinkResolver resolver = new LinkResolver(client(fake, BudgetGuard.unlimited("test")), store);

    resolver.derive(linked(), 1);
    resolver.derive(linked(), 1);

    // A run counts its RESOLVE calls with its summaries and wordings from these (#245).
    assertThat(resolver.asked()).isEqualTo(1).isEqualTo(fake.sent().size());
    assertThat(resolver.reused()).isEqualTo(1);
  }

  @Test
  void theCacheOutlivesANewCommitThatChangesNothingThePromptShows() {
    taxCall();
    FakeLlmProvider fake = answering(r -> PICK_TAX);
    LinkResolver.Result first = resolve(fake);

    facts.clear();
    commit = "b".repeat(40);
    taxCall();
    LinkResolver.Result second = resolve(fake);

    assertThat(fake.sent()).hasSize(1);
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
    FakeLlmProvider fake = answering(r -> PICK_TAX);
    resolve(fake);

    facts.clear();
    callLine = 42;
    taxCall();
    LinkResolver.Result second = resolve(fake);

    assertThat(fake.sent()).hasSize(1);
    assertThat(second.facts())
        .singleElement()
        .satisfies(f -> assertThat(f.evidence().lines().start()).isEqualTo(42));
  }

  @Test
  void aSkippedCallKeepsTheLinkTheModelPickedBefore() {
    taxCall();
    LinkResolver.Result first = resolve(answering(r -> PICK_TAX));

    // Another model, so the cache has no answer for its key, and no budget to ask it.
    model = "m2";
    FakeLlmProvider fake = answering(r -> PICK_TAX);
    LinkResolver.Result second = resolve(fake, new BudgetGuard("run", 100L, null));

    assertThat(fake.sent()).isEmpty();
    assertThat(second.skipped()).hasSize(1);
    assertThat(second.facts()).isEqualTo(first.facts());
  }

  @Test
  void aRejectedAnswerKeepsTheLinkTheModelPickedBeforeIfStillACandidate() {
    taxCall();
    LinkResolver.Result first = resolve(answering(r -> PICK_TAX));

    model = "m2";
    FakeLlmProvider fake = answering(r -> "It is probably tax.");
    LinkResolver.Result second = resolve(fake);

    assertThat(fake.sent()).hasSize(2);
    assertThat(second.rejected()).hasSize(1);
    assertThat(second.facts()).isEqualTo(first.facts());

    // Once tax is no longer a candidate, the earlier pick is not kept.
    facts.removeIf(f -> f.evidence().repo().equals("t"));
    model = "m3";
    assertThat(resolve(answering(r -> "It is probably tax.")).facts()).isEmpty();
  }

  @Test
  void anExhaustedBudgetLeavesTheLinksOpenWithoutFailing() {
    taxCall();
    FakeLlmProvider fake = answering(r -> PICK_TAX);

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
        HttpClientExtractor.HTTP_CALL,
        FactObject.string("GET ${TAX_SERVICE_URL}/v2/quotes"),
        "{\"client\":\"rest-client\",\"httpMethod\":\"GET\",\"path\":\"/v2/quotes\","
            + "\"unresolved\":[\"TAX_SERVICE_URL\"]}",
        "a:src/main/java/o/TaxClient.java#18");
    FakeLlmProvider fake = answering(r -> PICK_TAX);

    LinkResolver.Result result = resolve(fake);

    assertThat(linked()).anyMatch(f -> f.predicate().equals(CrossRepoLinks.UNRESOLVED_LINK));
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
        MessagingExtractor.SEND,
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
        MessagingExtractor.BINDING,
        FactObject.string("q.direct"),
        "{\"broker\":\"rabbitmq\",\"exchange\":\"direct.x\",\"exchangeType\":\"direct\","
            + "\"routingKey\":\"order.created\"}",
        "w:src/main/java/w/Config.java#5");
    add(
        MessagingExtractor.NAME,
        "method:w.Listener#direct()",
        MessagingExtractor.LISTENER,
        FactObject.string("q.direct"),
        "{\"broker\":\"rabbitmq\",\"destinationKind\":\"queue\"}",
        "w:src/main/java/w/Listener.java#8");
    FakeLlmProvider fake =
        answering(
            r -> {
              assertThat(user(r))
                  .contains("[C1] service:g:worker")
                  .contains("  [F6] method:w.Config#direct() message-binding \"q.direct\"")
                  .contains("  [F7] method:w.Listener#direct() message-listener \"q.direct\"");
              return "{\"target\": \"C1\", \"confidence\": \"medium\", \"cites\": [\"F6\", \"F7\"],"
                  + " \"rationale\": \"Only worker listens on a queue bound to direct.x.\"}";
            });

    LinkResolver.Result result = resolve(fake);

    assertThat(result.facts())
        .singleElement()
        .satisfies(
            f -> {
              assertThat(f.predicate()).isEqualTo(CrossRepoLinks.SENDS_MESSAGES_TO);
              assertThat(f.object().value()).isEqualTo("service:g:worker");
              assertThat(f.confidence()).isEqualTo(0.35);
              assertThat(f.attributes().get("protocol").asString()).isEqualTo("AMQP");
              assertThat(f.attributes().get("target").asString())
                  .isEqualTo("method:w.Listener#direct()");
            });
  }

  @Test
  void aRecordedAnswerIsReplayedWithoutTheModel() {
    taxCall();
    FakeLlmProvider fake = answering(r -> PICK_TAX);
    LinkResolver.Result recorded =
        resolve(RecordReplayLlmProvider.recording(fake, recordings), BudgetGuard.unlimited("t"));
    assertThat(fake.sent()).hasSize(1);

    InMemoryResolutionStore fresh = new InMemoryResolutionStore();
    LinkResolver.Result replayed =
        new LinkResolver(
                client(
                    RecordReplayLlmProvider.replaying(FakeLlmProvider.NAME, recordings),
                    BudgetGuard.unlimited("t")),
                fresh)
            .resolve(linked(), 1);

    assertThat(fake.sent()).hasSize(1);
    assertThat(replayed.asked()).isEqualTo(1);
    assertThat(replayed.facts()).isEqualTo(recorded.facts());
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

  private static final GraphTools.RepoFiles FILES =
      (repo, path) ->
          repo.equals("t") && path.equals("src/main/java/t/TaxController.java")
              ? java.util.Optional.of(
                  TAX_CONTROLLER.getBytes(java.nio.charset.StandardCharsets.UTF_8))
              : java.util.Optional.empty();

  private static io.lantern.llm.LlmToolCall toolCall(String id, String name, String input) {
    return new io.lantern.llm.LlmToolCall(id, name, FactJson.parse(input));
  }

  /**
   * A model that looks up the tax controller's facts, then reads its file, then picks tax citing a
   * fact the lookup showed.
   */
  private static FakeLlmProvider lookingFurther() {
    return new FakeLlmProvider()
        .callingTools(
            r ->
                switch (r.messages().size()) {
                  case 1 ->
                      List.of(
                          toolCall(
                              "toolu_01", "facts_about", "{\"entity\":\"type:t.TaxController\"}"));
                  case 3 ->
                      List.of(
                          toolCall(
                              "toolu_02",
                              "read_file",
                              "{\"repo\":\"t\",\"path\":\"src/main/java/t/TaxController.java\","
                                  + "\"from\":3,\"to\":6}"));
                  default -> List.of();
                })
        .answering(
            "{\"target\": \"C2\", \"confidence\": \"medium\", \"cites\": [\"C2\", \"F9\", \"T1\"],"
                + " \"rationale\": \"TaxController is a REST controller that serves GET /v2/quotes.\"}");
  }

  private LinkResolver.Result resolveWithFiles(LlmProvider provider) {
    LinkResolver.Result result =
        new LinkResolver(client(provider, BudgetGuard.unlimited("t")), store, FILES)
            .resolve(linked(), 1);
    result.facts().forEach(DeployableUnitsTest.vocabulary()::validate);
    return result;
  }

  @Test
  void aModelThatCanUseToolsLooksFurtherAndCitesWhatTheyShowed() {
    taxCall();
    FakeLlmProvider fake = lookingFurther();

    LinkResolver.Result result = resolveWithFiles(fake);

    assertThat(fake.sent()).hasSize(3);
    LlmRequest last = fake.sent().getLast().request();
    assertThat(last.system()).isEqualTo(ResolvePrompt.system(true));
    assertThat(last.tools())
        .extracting(io.lantern.llm.LlmTool::name)
        .containsExactly("facts_about", "search_facts", "read_file");
    String about = last.messages().get(2).toolResults().getFirst().content();
    assertThat(about)
        .isEqualTo(
            """
            Facts about type:t.TaxController:
            [T1] type:t.TaxController stereotype "controller" {"rest":true} \
            (t:src/main/java/t/TaxController.java)
            [T2] package:g:tax:t CONTAINS type:t.TaxController (t:src/main/java/t/TaxController.java)
            """);
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
    Fact link = result.facts().getFirst();
    assertThat(link.object().value()).isEqualTo("service:g:tax");
    assertThat(link.confidence()).isEqualTo(0.35);
    String stereotype =
        facts.stream()
            .filter(
                f ->
                    f.predicate().equals(SpringExtractor.STEREOTYPE)
                        && f.subject().toString().equals("type:t.TaxController"))
            .findFirst()
            .orElseThrow()
            .id();
    assertThat(link.attributes().get("facts").toString()).contains(stereotype);
    assertThat(store.latest(link.attributes().get("resolves").asString()).orElseThrow().cited())
        .containsExactly("C2", "F9", stereotype);
    // One conversation of three turns, its calls numbered.
    assertThat(log.calls()).extracting(LlmCall::turn).containsExactly(1, 2, 3);
    assertThat(log.conversations())
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.role()).isEqualTo(Role.RESOLVE);
              assertThat(c.turns()).isEqualTo(3);
              assertThat(c.toolCalls())
                  .isEqualTo(java.util.Map.of("facts_about", 1, "read_file", 1));
              assertThat(c.ending()).isEqualTo(io.lantern.llm.LlmConversation.Ending.ANSWERED);
            });
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
            f.predicate().equals(SpringExtractor.STEREOTYPE)
                && f.subject().toString().equals("type:t.TaxController"));
    FakeLlmProvider again = lookingFurther();

    LinkResolver.Result second = resolveWithFiles(again);

    assertThat(again.sent()).isNotEmpty();
    assertThat(second.reused()).isZero();
    assertThat(second.asked()).isEqualTo(1);
  }

  @Test
  void aModelWithoutToolUseGetsThePromptAlone() {
    taxCall();
    FakeLlmProvider fake = answering(r -> PICK_TAX);
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

    LinkResolver.Result result = new LinkResolver(client, store, FILES).resolve(linked(), 1);

    assertThat(result.facts()).hasSize(1);
    LlmRequest request = fake.sent().getFirst().request();
    assertThat(request.system()).isEqualTo(ResolvePrompt.SYSTEM);
    assertThat(request.tools()).isEmpty();
  }

  @Test
  void anAnswerCitingAToolReferenceNoToolShowedIsRejected() {
    taxCall();
    FakeLlmProvider fake =
        answering(
            r ->
                "{\"target\": \"C2\", \"confidence\": \"high\", \"cites\": [\"C2\", \"T4\"],"
                    + " \"rationale\": \"Seen in a tool.\"}");

    LinkResolver.Result result = resolveWithFiles(fake);

    assertThat(result.rejected()).hasSize(1);
    assertThat(fake.sent().getLast().request().messages().getLast().text())
        .contains("it cites T4, which is not in the input.");
    assertThat(log.conversations().getFirst().ending())
        .isEqualTo(io.lantern.llm.LlmConversation.Ending.REJECTED);
  }

  /**
   * The recordings of {@link #lookingFurther()}'s conversation, kept in the repository in the
   * record and replay format (ADR 0008): three turns, two of them asking for tools.
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
              new LlmClient(
                  ModelSettings.single(scripted.model(model)),
                  List.of(RecordReplayLlmProvider.recording(scripted, TOOL_RECORDINGS)),
                  BudgetGuard.unlimited("t"),
                  new InMemoryLlmCallLog(),
                  Clock.systemUTC()),
              new InMemoryResolutionStore(),
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
              assertThat(c.turns()).isEqualTo(3);
              assertThat(c.toolCallCount()).isEqualTo(2);
            });
  }
}

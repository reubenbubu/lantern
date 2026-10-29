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
            new Evidence(repo, commit, rest, lines, null),
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
    List<Fact> all = new ArrayList<>(facts);
    all.addAll(new CrossRepoLinks().derive(facts, 1));
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
    assertThat(request.system()).isEqualTo(ResolvePrompt.SYSTEM);
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
    assertThat(link.origin()).isEqualTo(new Origin(Origin.Kind.LLM, "link-resolver", "3"));
    assertThat(link.confidence()).isEqualTo(0.5);
    assertThat(link.evidence()).isEqualTo(unresolved.evidence());
    ObjectNode attributes = link.attributes().deepCopy();
    assertThat(attributes.remove("facts")).isNotNull();
    assertThat(attributes.remove("link").asString()).hasSize(16);
    assertThat(attributes.remove("resolves").asString())
        .isEqualTo(unresolved.attributes().get("link").asString());
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
              assertThat(f.id()).isNotEqualTo(first.facts().getFirst().id());
              assertThat(f.attributes().get("link"))
                  .isEqualTo(first.facts().getFirst().attributes().get("link"));
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
}

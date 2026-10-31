package io.lantern.link;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.repo.Repo;
import io.lantern.core.vocabulary.Vocabulary;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.deployment.DeploymentExtractor;
import io.lantern.extract.docs.DocsExtractor;
import io.lantern.extract.gateway.GatewayExtractor;
import io.lantern.extract.http.HttpClientExtractor;
import io.lantern.extract.java.JavaAnalysis;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.extract.maven.MavenExtractor;
import io.lantern.extract.messaging.MessagingExtractor;
import io.lantern.extract.persistence.PersistenceExtractor;
import io.lantern.extract.spring.SpringExtractor;
import io.lantern.extract.sql.SqlExtractor;
import io.lantern.llm.BudgetGuard;
import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.InMemoryLlmCallLog;
import io.lantern.llm.LlmClient;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.ModelSettings;
import io.lantern.sandbox.BuildSandbox;
import io.lantern.sandbox.MavenMirror;
import io.lantern.sandbox.SandboxSettings;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The links between the deployable units of the synthetic test set project-0 (docs/adr/0029),
 * checked against the cases its README lists, named by id: with the repositories built in the
 * sandbox, and read without builds (degraded mode). Runs only when {@code lantern.test.project0}
 * names the folder {@code testsets/project-0/setup.sh} set it up in; see Commands in {@code
 * CLAUDE.md}. Needs Docker and Maven Central, or the Maven mirror of the test properties; the
 * builds use the default shared Maven cache directory.
 */
@EnabledIfSystemProperty(named = "lantern.test.project0", matches = ".+")
class Project0LinksIT {

  private static final String J = "src/main/java/com/acme/shop/";

  @TempDir static Path temp;

  private static List<String> built;
  private static List<String> unbuilt;

  /** The README claims the code contradicts, with builds and without (DOC-2). */
  private static List<String> builtClaims;

  private static List<String> unbuiltClaims;

  /**
   * The candidate the LLM resolver should pick for each link project-0 leaves open with candidates,
   * by the code that makes the call or send (HTTP-6, HTTP-7, MSG-5).
   */
  private static final Map<String, String> ANSWERS =
      Map.of(
          "method:com.acme.shop.fulfilment.OrderClient#order(long)",
          "service:com.acme.shop:storefront-app",
          "method:com.acme.shop.fulfilment.PaymentStatusClient#paymentOf(long)",
          "service:com.acme.shop:payments",
          "method:com.acme.shop.fulfilment.ReturnsPublisher#refundDue(long,java.lang.String)",
          "service:com.acme.shop:payments");

  /** What the LLM resolver, with a fake model, was asked with builds and without. */
  private static final List<String> builtPrompts = new ArrayList<>();

  private static final List<String> unbuiltPrompts = new ArrayList<>();

  /** The resolver's conversations and the results of its tool calls, both runs together. */
  private static final List<io.lantern.llm.LlmConversation> conversations = new ArrayList<>();

  private static final List<String> toolResults = new ArrayList<>();

  /** The links the LLM resolver picked with builds and without. */
  private static List<String> builtResolved;

  private static List<String> unbuiltResolved;

  @BeforeAll
  static void derive() throws IOException {
    BuildSandbox sandbox = new BuildSandbox(sandboxSettings(temp.resolve("builds")));
    Map<String, Repo> project = Project0.repos();
    List<RepoBuild> withoutBuilds = Project0.unbuilt(project);
    List<RepoBuild> withBuilds = Project0.built(project, sandbox);
    List<Fact> resolved = new ArrayList<>();
    List<String> withBuildsFacts = render(extractAndLink(withBuilds, builtPrompts, resolved));
    builtResolved = render(resolved);
    resolved.clear();
    List<String> withoutBuildsFacts =
        render(extractAndLink(withoutBuilds, unbuiltPrompts, resolved));
    unbuiltResolved = render(resolved);
    built = withBuildsFacts.stream().filter(l -> !l.startsWith("readme:")).toList();
    unbuilt = withoutBuildsFacts.stream().filter(l -> !l.startsWith("readme:")).toList();
    builtClaims = withBuildsFacts.stream().filter(l -> l.startsWith("readme:")).toList();
    unbuiltClaims = withoutBuildsFacts.stream().filter(l -> l.startsWith("readme:")).toList();
  }

  /**
   * The facts the app links, made as the app makes them, and the links; the LLM resolver's prompts
   * and the links it picks, with a fake model that gives the known answers, go to the lists given.
   */
  private static List<Fact> extractAndLink(
      List<RepoBuild> repos, List<String> prompts, List<Fact> resolved) {
    List<Fact> facts = new ArrayList<>(new MavenExtractor().extract(repos, 1));
    JavaAnalysis code = JavaExtractor.analyze(repos);
    facts.addAll(new JavaExtractor().extract(code, 1));
    facts.addAll(new ConfigExtractor().extract(repos, 1));
    facts.addAll(new SqlExtractor().extract(repos, 1));
    facts.addAll(new SpringExtractor().extract(code, 1));
    facts.addAll(new PersistenceExtractor().extract(code, 1));
    facts.addAll(new HttpClientExtractor().extract(code, 1));
    facts.addAll(new MessagingExtractor().extract(code, 1));
    facts.addAll(new GatewayExtractor().extract(code, 1));
    facts.addAll(new DocsExtractor().extract(repos, 1));
    facts.addAll(new DeploymentExtractor().extract(repos, 1));
    facts.addAll(new ValueViews().derive(facts, 1));
    facts.addAll(new DeployableUnits().derive(facts, 1));
    List<Fact> links = new ArrayList<>(new CrossRepoLinks().derive(facts, 1));
    facts.addAll(links);
    List<Fact> claims = new ClaimChecks().derive(facts, 1);
    links.addAll(claims);
    // The LLM resolver with a fake model that gives the known answer, to see what a real run
    // would ask and that the answer is recorded as the link.
    // It first looks up the facts of the unit it will pick with a tool (ADR 0081), then answers.
    FakeLlmProvider fake =
        new FakeLlmProvider()
            .callingTools(Project0LinksIT::lookUpTheAnswer)
            .answering(Project0LinksIT::knownAnswer);
    InMemoryLlmCallLog log = new InMemoryLlmCallLog();
    List<Fact> all = new ArrayList<>(facts);
    all.addAll(claims);
    LinkResolver.Result result =
        new LinkResolver(
                new LlmClient(
                    ModelSettings.single(fake.model("fake")),
                    List.of(fake),
                    BudgetGuard.unlimited("project-0"),
                    log,
                    Clock.systemUTC()),
                new InMemoryResolutionStore())
            .resolve(all, 1);
    assertThat(result.rejected()).as("answers rejected").isEmpty();
    assertThat(result.skipped()).as("calls skipped").isEmpty();
    assertThat(result.unknown()).as("answered unknown").isZero();
    resolved.addAll(result.facts());
    fake.sent().stream()
        .filter(sent -> sent.request().messages().size() == 1)
        .forEach(sent -> prompts.add(sent.request().messages().getFirst().text()));
    conversations.addAll(log.conversations());
    fake.sent().stream()
        .flatMap(sent -> sent.request().messages().stream())
        .flatMap(message -> message.toolResults().stream())
        .forEach(toolResult -> toolResults.add(toolResult.content()));
    Vocabulary vocabulary = DeployableUnitsTest.vocabulary();
    links.forEach(vocabulary::validate);
    resolved.forEach(vocabulary::validate);
    return links;
  }

  /**
   * The answer a model that knows project-0 gives: the reference of the candidate {@link #ANSWERS}
   * names for the code the prompt asks about, citing it; unknown for any other prompt.
   */
  private static List<io.lantern.llm.LlmToolCall> lookUpTheAnswer(LlmRequest request) {
    if (request.messages().size() > 1) {
      return List.of();
    }
    String prompt = request.messages().getFirst().text();
    for (Map.Entry<String, String> answer : ANSWERS.entrySet()) {
      if (prompt.contains(", in " + answer.getKey() + ".")) {
        return List.of(
            new io.lantern.llm.LlmToolCall(
                "toolu_1", "facts_about", FactJson.newObject().put("entity", answer.getValue())));
      }
    }
    return List.of();
  }

  private static String knownAnswer(LlmRequest request) {
    String prompt = request.messages().getFirst().text();
    for (Map.Entry<String, String> answer : ANSWERS.entrySet()) {
      if (!prompt.contains(", in " + answer.getKey() + ".")) {
        continue;
      }
      for (String line : prompt.lines().toList()) {
        if (line.matches("\\[C\\d+] .*") && line.endsWith("] " + answer.getValue())) {
          String ref = line.substring(1, line.indexOf(']'));
          return "{\"target\": \""
              + ref
              + "\", \"confidence\": \"high\", \"cites\": [\""
              + ref
              + "\"], \"rationale\": \"The answer project-0 documents.\"}";
        }
      }
    }
    return "{\"target\": \"unknown\", \"rationale\": \"Not a project-0 case.\"}";
  }

  /**
   * One line per fact, without the confidence, the IDs of the facts it comes from and its link ID,
   * the package com.acme.shop as "~".
   */
  private static List<String> render(List<Fact> facts) {
    return facts.stream()
        .map(
            f -> {
              ObjectNode attributes = f.attributes();
              attributes.remove("facts");
              attributes.remove("link");
              attributes.remove("claim");
              if (attributes.has("contradictedBy")) {
                ArrayNode by = attributes.putArray("contradictedBy");
                for (JsonNode e : f.attributes().get("contradictedBy")) {
                  by.add(
                      e.get("subject").asString()
                          + " "
                          + e.get("predicate").asString()
                          + " "
                          + e.get("object").asString());
                }
              }
              String where = f.evidence().repo() + ":" + f.evidence().path();
              if (f.evidence().lines() != null) {
                where += "#" + f.evidence().lines().start();
              }
              return (f.subject()
                      + " "
                      + f.predicate()
                      + " "
                      + f.object().value()
                      + (attributes.isEmpty() ? "" : " " + attributes)
                      + " @"
                      + where)
                  .replace("com.acme.shop", "~");
            })
        .toList();
  }

  /** The sandbox settings of the extractors' tests, with the Maven mirror if one is set. */
  static SandboxSettings sandboxSettings(Path buildsDirectory) {
    String file = System.getProperty("lantern.test.properties");
    if (file == null || file.isBlank()) {
      file = System.getenv("LANTERN_TEST_PROPERTIES");
    }
    SandboxSettings settings = SandboxSettings.defaults(buildsDirectory);
    if (file == null || file.isBlank()) {
      return settings;
    }
    try {
      return settings.withMavenMirror(MavenMirror.from(Path.of(file)));
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read the test properties " + file, e);
    }
  }

  /** Checks facts both with builds and without. */
  private static void assertBoth(String... lines) {
    assertThat(built).as("with builds").contains(lines);
    assertThat(unbuilt).as("without builds").contains(lines);
  }

  private static List<String> of(List<String> facts, String predicate) {
    return facts.stream().filter(f -> f.contains(" " + predicate + " ")).toList();
  }

  @Test
  void http1FeignByClientNameCalledByTheContainerThatEnablesIt() {
    assertBoth(
        "service:~:storefront-app CALLS_HTTP service:~:catalog-service {\"client\":\"feign\","
            + "\"endpoint\":\"GET /api/products/{id}\",\"httpMethod\":\"GET\","
            + "\"matchedBy\":\"service-name\",\"path\":\"/api/products/{id}\","
            + "\"protocol\":\"JSON/HTTP\","
            + "\"source\":\"method:~.storefront.integration.CatalogClient#product(long)\","
            + "\"target\":\"method:~.catalog.ProductController#product(long)\","
            + "\"url\":\"http://catalog-service/api/products/{id}\"}"
            + " @storefront:storefront-integration/"
            + J
            + "storefront/integration/CatalogClient.java#11");
  }

  @Test
  void http2RestTemplateByHostAndItsLocalProfileByPathAreOneLink() {
    String line =
        "service:~:storefront-app CALLS_HTTP service:~:payments {\"client\":\"rest-template\","
            + "\"endpoint\":\"POST /api/payments\",\"httpMethod\":\"POST\",\"matchedBy\":\"host\","
            + "\"path\":\"/api/payments\",\"protocol\":\"JSON/HTTP\","
            + "\"source\":\"method:~.storefront.integration.PaymentsClient#charge("
            + "~.storefront.integration.PaymentRequest)\","
            + "\"target\":\"method:~.payments.PaymentController#charge("
            + "~.payments.PaymentController$Charge)\","
            + "\"url\":\"http://payments:8080/api/payments\"}"
            + " @storefront:storefront-integration/"
            + J
            + "storefront/integration/PaymentsClient.java#21";
    assertBoth(line);
    // Under profile local the call goes to localhost:8082, which only payments serves by path: the
    // same link, so no conditional one.
    assertThat(of(built, "CALLS_HTTP")).filteredOn(l -> l.contains("PaymentsClient")).hasSize(1);
  }

  @Test
  void http3WebClientByHost() {
    assertBoth(
        "service:~:fulfilment CALLS_HTTP service:~:catalog-service {\"client\":\"web-client\","
            + "\"endpoint\":\"GET /api/products/{id}/stock\",\"httpMethod\":\"GET\","
            + "\"matchedBy\":\"host\",\"path\":\"/api/products/{id}/stock\","
            + "\"protocol\":\"JSON/HTTP\",\"source\":\"method:~.fulfilment.StockClient#available(long)\","
            + "\"target\":\"method:~.catalog.ProductController#stock(long)\","
            + "\"url\":\"http://catalog-service:8080/api/products/{id}/stock\"}"
            + " @fulfilment:"
            + J
            + "fulfilment/StockClient.java#19");
  }

  @Test
  void http4AndExt1PayGateIsAnExternalSystemWhoseProdHostIsItsAlternate() {
    assertBoth(
        "service:~:payments CALLS_HTTP external-system:sandbox.paygate.example.com"
            + " {\"alternates\":[\"external-system:api.paygate.example.com\"],"
            + "\"client\":\"rest-client\",\"exceptProfiles\":[\"prod\"],\"httpMethod\":\"POST\","
            + "\"matchedBy\":\"external\",\"path\":\"/v1/charges\",\"protocol\":\"HTTPS\","
            + "\"source\":\"method:~.payments.PaymentGatewayClient#charge(long)\","
            + "\"url\":\"https://sandbox.paygate.example.com/v1/charges\"}"
            + " @payments:"
            + J
            + "payments/PaymentGatewayClient.java#22",
        "service:~:payments CALLS_HTTP external-system:api.paygate.example.com"
            + " {\"alternates\":[\"external-system:sandbox.paygate.example.com\"],"
            + "\"client\":\"rest-client\",\"httpMethod\":\"POST\",\"matchedBy\":\"external\","
            + "\"path\":\"/v1/charges\",\"profile\":\"prod\",\"protocol\":\"HTTPS\","
            + "\"source\":\"method:~.payments.PaymentGatewayClient#charge(long)\","
            + "\"url\":\"https://api.paygate.example.com/v1/charges\"}"
            + " @payments:"
            + J
            + "payments/PaymentGatewayClient.java#22");
    assertThat(of(built, "CALLS_HTTP")).filteredOn(l -> l.contains("external-system")).hasSize(2);
  }

  @Test
  void http5HasNoCandidatesSoTheResolverAsksTheModelNothingAboutIt() {
    // HTTP-5's tax service is outside the set, so no unit serves its path: nothing to pick from.
    assertThat(builtPrompts).noneMatch(p -> p.contains("TaxClient"));
    assertThat(unbuiltPrompts).noneMatch(p -> p.contains("TaxClient"));
  }

  @Test
  void theResolverIsAskedOnceAboutEachLinkLeftOpenWithCandidates() {
    for (List<String> prompts : List.of(builtPrompts, unbuiltPrompts)) {
      assertThat(prompts)
          .extracting(p -> p.substring(p.indexOf(", in ") + 5, p.indexOf(".\n")))
          .containsExactlyInAnyOrderElementsOf(ANSWERS.keySet());
      // The candidates by unit ID, not ranked (ADR 0045): payments first in every case.
      for (String prompt : prompts) {
        assertThat(prompt)
            .contains("\n[C1] service:com.acme.shop:payments\n")
            .contains("\n[C2] service:com.acme.shop:storefront-app\n")
            .doesNotContain("[C3]");
      }
    }
    assertThat(builtResolved).hasSize(ANSWERS.size());
    assertThat(unbuiltResolved).hasSize(ANSWERS.size());
  }

  @Test
  void theResolverLooksUpEachAnswersEndpointsWithAToolFirst() {
    assertThat(conversations).hasSize(2 * ANSWERS.size());
    assertThat(conversations)
        .allSatisfy(
            c -> {
              assertThat(c.turns()).isEqualTo(2);
              assertThat(c.toolCalls()).isEqualTo(Map.of("facts_about", 1));
              assertThat(c.ending()).isEqualTo(io.lantern.llm.LlmConversation.Ending.ANSWERED);
            });
    // HTTP-7 and MSG-5 pick payments, HTTP-6 the storefront app.
    assertThat(toolResults)
        .hasSize(2 * ANSWERS.size())
        .anyMatch(r -> r.startsWith("Facts about service:com.acme.shop:payments:\n[T1] "))
        .anyMatch(r -> r.startsWith("Facts about service:com.acme.shop:storefront-app:\n[T1] "));
  }

  @Test
  void http6AHostThatNamesNoUnitOnAPathTwoUnitsServe() {
    assertBoth(
        "service:~:fulfilment unresolved-link GET http://storefront-web:8080/api/orders/{id}"
            + " {\"candidates\":[\"service:~:payments\",\"service:~:storefront-app\"],"
            + "\"kind\":\"http\",\"reason\":\"unknown-host\","
            + "\"source\":\"method:~.fulfilment.OrderClient#order(long)\"}"
            + " @fulfilment:"
            + J
            + "fulfilment/OrderClient.java#18");
    // What decides it is in the prompt: the host, and the candidate's application name.
    for (List<String> prompts : List.of(builtPrompts, unbuiltPrompts)) {
      assertThat(prompt(prompts, "OrderClient"))
          .contains("storefront-web")
          .contains("service:com.acme.shop:storefront-app application-name \"storefront\"");
    }
    assertBothResolved(
        "service:~:fulfilment CALLS_HTTP service:~:storefront-app {\"client\":\"web-client\","
            + "\"httpMethod\":\"GET\",\"matchedBy\":\"llm\",\"model\":\"fake/fake\","
            + "\"path\":\"/api/orders/{id}\",\"protocol\":\"HTTP\","
            + "\"rationale\":\"The answer project-0 documents.\",\"resolves\":\"50bc052e7262ce21\","
            + "\"source\":\"method:~.fulfilment.OrderClient#order(long)\"}"
            + " @fulfilment:"
            + J
            + "fulfilment/OrderClient.java#18");
  }

  @Test
  void http7AnUnresolvableUrlOnAPathTwoUnitsServe() {
    assertBoth(
        "service:~:fulfilment unresolved-link GET ${PAYMENTS_SERVICE_URL}/api/orders/{id}"
            + " {\"candidates\":[\"service:~:payments\",\"service:~:storefront-app\"],"
            + "\"kind\":\"http\",\"reason\":\"unresolved-placeholder\","
            + "\"source\":\"method:~.fulfilment.PaymentStatusClient#paymentOf(long)\","
            + "\"unresolved\":[\"PAYMENTS_SERVICE_URL\"]}"
            + " @fulfilment:"
            + J
            + "fulfilment/PaymentStatusClient.java#19");
    for (List<String> prompts : List.of(builtPrompts, unbuiltPrompts)) {
      assertThat(prompt(prompts, "PaymentStatusClient"))
          .contains("placeholders no config resolves: PAYMENTS_SERVICE_URL")
          .contains("service:com.acme.shop:payments application-name \"payments\"");
    }
    assertBothResolved(
        "service:~:fulfilment CALLS_HTTP service:~:payments {\"client\":\"web-client\","
            + "\"httpMethod\":\"GET\",\"matchedBy\":\"llm\",\"model\":\"fake/fake\","
            + "\"path\":\"/api/orders/{id}\",\"protocol\":\"HTTP\","
            + "\"rationale\":\"The answer project-0 documents.\",\"resolves\":\"d50bdb199adaf990\","
            + "\"source\":\"method:~.fulfilment.PaymentStatusClient#paymentOf(long)\"}"
            + " @fulfilment:"
            + J
            + "fulfilment/PaymentStatusClient.java#19");
  }

  @Test
  void msg5ARoutingKeyKnownOnlyAtRunTimeToAnExchangeTwoUnitsBindTo() {
    assertBoth(
        "service:~:fulfilment unresolved-link shop.returns"
            + " {\"candidates\":[\"service:~:payments\",\"service:~:storefront-app\"],"
            + "\"kind\":\"message\",\"reason\":\"unknown-routing-key\","
            + "\"source\":\"method:~.fulfilment.ReturnsPublisher#refundDue(long,java.lang.String)\"}"
            + " @fulfilment:"
            + J
            + "fulfilment/ReturnsPublisher.java#19",
        "service:~:fulfilment PUBLISHES exchange:rabbitmq:shop.returns {\"protocol\":\"AMQP\","
            + "\"source\":\"method:~.fulfilment.ReturnsPublisher#refundDue(long,java.lang.String)\"}"
            + " @fulfilment:"
            + J
            + "fulfilment/ReturnsPublisher.java#19");
    // Each candidate's binding is in the prompt, under it: only payments' takes refund keys.
    for (List<String> prompts : List.of(builtPrompts, unbuiltPrompts)) {
      String prompt = prompt(prompts, "ReturnsPublisher");
      int payments = prompt.indexOf("\n[C1] service:com.acme.shop:payments\n");
      int refund = prompt.indexOf("\"routingKey\":\"refund.*\"");
      int storefront = prompt.indexOf("\n[C2] service:com.acme.shop:storefront-app\n");
      int returned = prompt.indexOf("\"routingKey\":\"return.*\"");
      assertThat(List.of(payments, refund, storefront, returned)).as(prompt).isSorted();
      assertThat(payments).as(prompt).isNotNegative();
    }
    assertBothResolved(
        "service:~:fulfilment SENDS_MESSAGES_TO service:~:payments {\"model\":\"fake/fake\","
            + "\"protocol\":\"AMQP\",\"rationale\":\"The answer project-0 documents.\","
            + "\"resolves\":\"f9995358ef8d1925\","
            + "\"source\":\"method:~.fulfilment.ReturnsPublisher#refundDue(long,java.lang.String)\"}"
            + " @fulfilment:"
            + J
            + "fulfilment/ReturnsPublisher.java#19");
  }

  /** The one prompt about the code of a class. */
  private static String prompt(List<String> prompts, String type) {
    List<String> about = prompts.stream().filter(p -> p.contains("." + type + "#")).toList();
    assertThat(about).as("prompts about " + type).hasSize(1);
    return about.getFirst();
  }

  /** Checks links the resolver picked, both with builds and without, each once. */
  private static void assertBothResolved(String... lines) {
    assertThat(builtResolved).as("picked with builds").containsOnlyOnce(lines);
    assertThat(unbuiltResolved).as("picked without builds").containsOnlyOnce(lines);
  }

  @Test
  void http5AnUnresolvableUrlIsLeftForTheResolver() {
    assertBoth(
        "service:~:storefront-app unresolved-link GET ${TAX_SERVICE_URL}/v2/quotes"
            + " {\"kind\":\"http\",\"reason\":\"unresolved-placeholder\","
            + "\"source\":\"method:~.storefront.integration.TaxClient#quote(long)\","
            + "\"unresolved\":[\"TAX_SERVICE_URL\"]}"
            + " @storefront:storefront-integration/"
            + J
            + "storefront/integration/TaxClient.java#18");
    assertThat(of(built, "CALLS_HTTP")).noneMatch(l -> l.contains("TaxClient"));
  }

  @Test
  void msg1JmsFromFulfilmentToStorefront() {
    assertBoth(
        "service:~:fulfilment SENDS_MESSAGES_TO service:~:storefront-app"
            + " {\"destination\":\"queue:jms:shipments.dispatched\",\"protocol\":\"JMS\","
            + "\"source\":\"method:~.fulfilment.ShipmentService#ship(long)\","
            + "\"target\":\"method:~.storefront.order.ShipmentListener#dispatched(java.lang.String)\"}"
            + " @fulfilment:"
            + J
            + "fulfilment/ShipmentService.java#19",
        "service:~:storefront-app CONSUMES queue:jms:shipments.dispatched {\"protocol\":\"JMS\","
            + "\"source\":\"method:~.storefront.order.ShipmentListener#dispatched(java.lang.String)\"}"
            + " @storefront:storefront-app/"
            + J
            + "storefront/order/ShipmentListener.java#18");
  }

  @Test
  void msg2RabbitThroughTheWildcardBinding() {
    assertBoth(
        "service:~:storefront-app SENDS_MESSAGES_TO service:~:payments {\"bindingKey\":\"order.*\","
            + "\"destination\":\"exchange:rabbitmq:shop.orders\",\"protocol\":\"AMQP\","
            + "\"queue\":\"queue:rabbitmq:payments.order-events\",\"routingKey\":\"order.created\","
            + "\"source\":\"method:~.storefront.integration.OrderEventPublisher#orderCreated("
            + "~.storefront.integration.OrderCreatedEvent)\","
            + "\"target\":\"method:~.payments.OrderEventsListener#orderEvent(java.lang.String)\"}"
            + " @storefront:storefront-integration/"
            + J
            + "storefront/integration/OrderEventPublisher.java#16",
        "exchange:rabbitmq:shop.orders ROUTES_TO queue:rabbitmq:payments.order-events"
            + " {\"bindingKey\":\"order.*\",\"exchangeType\":\"topic\",\"protocol\":\"AMQP\","
            + "\"source\":\"method:~.payments.RabbitConfig#orderEventsBinding("
            + "org.springframework.amqp.core.Queue,org.springframework.amqp.core.TopicExchange)\"}"
            + " @payments:"
            + J
            + "payments/RabbitConfig.java#33");
  }

  @Test
  void msg3AWildcardBindingNothingMatchesLinksNoUnit() {
    for (List<String> facts : List.of(built, unbuilt)) {
      assertThat(facts)
          .anyMatch(
              l -> l.startsWith("service:~:payments CONSUMES queue:rabbitmq:payments.refunds "));
      assertThat(of(facts, "SENDS_MESSAGES_TO")).noneMatch(l -> l.contains("refund"));
    }
  }

  @Test
  void msg4KafkaFromPaymentsToFulfilment() {
    assertBoth(
        "service:~:payments SENDS_MESSAGES_TO service:~:fulfilment"
            + " {\"destination\":\"topic:kafka:payments.completed\",\"protocol\":\"Kafka\","
            + "\"source\":\"method:~.payments.PaymentCompletedPublisher#publish(java.lang.String)\","
            + "\"target\":\"method:~.fulfilment.PaymentCompletedListener#paymentCompleted("
            + "java.lang.String)\"}"
            + " @payments:"
            + J
            + "payments/PaymentCompletedPublisher.java#20");
  }

  @Test
  void spr4TheAuditLinkHoldsOnlyUnderItsProfile() {
    assertBoth(
        "service:~:storefront-app SENDS_MESSAGES_TO service:~:fulfilment"
            + " {\"destination\":\"queue:jms:audit.orders\",\"profile\":\"audit\",\"protocol\":\"JMS\","
            + "\"source\":\"method:~.storefront.integration.AuditTrail#orderPlaced(long)\","
            + "\"target\":\"method:~.fulfilment.AuditListener#orderPlaced(java.lang.String)\"}"
            + " @storefront:storefront-integration/"
            + J
            + "storefront/integration/AuditTrail.java#19");
  }

  @Test
  void db5StorefrontAndPaymentsShareTheShopDatabaseAndPaymentsReadsOrders() {
    assertBoth(
        "service:~:payments USES_DATABASE database:shop-db:5432/shop {\"key\":\"spring.datasource.url\","
            + "\"protocol\":\"JDBC\",\"schemas\":[\"schema:~:payments\"],\"technology\":\"PostgreSQL\","
            + "\"url\":\"jdbc:postgresql://shop-db:5432/shop\"}"
            + " @payments:src/main/resources/application.properties#5",
        "service:~:storefront-app USES_DATABASE database:shop-db:5432/shop"
            + " {\"exceptProfiles\":[\"local\"],\"key\":\"spring.datasource.url\",\"protocol\":\"JDBC\","
            + "\"schemas\":[\"schema:~:storefront-app\"],\"technology\":\"PostgreSQL\","
            + "\"url\":\"jdbc:postgresql://shop-db:5432/shop\"}"
            + " @storefront:storefront-app/src/main/resources/application.yml#5",
        "service:~:catalog-service USES_DATABASE database:catalog-db:5432/catalog"
            + " {\"key\":\"spring.datasource.url\",\"protocol\":\"JDBC\","
            + "\"schemas\":[\"schema:~:catalog-lib\"],\"technology\":\"PostgreSQL\","
            + "\"url\":\"jdbc:postgresql://catalog-db:5432/catalog\"}"
            + " @catalog:src/main/resources/application.yml#6",
        "database:shop-db:5432/shop technology PostgreSQL"
            + " @payments:src/main/resources/application.properties#5");
    // SQL in code is read only with the build's classpath (ADR 0036).
    assertThat(of(built, "ACCESSES_DATA_OF"))
        .singleElement()
        .asString()
        .startsWith(
            "service:~:payments ACCESSES_DATA_OF service:~:storefront-app"
                + " {\"database\":\"database:shop-db:5432/shop\",\"exceptProfiles\":[\"local\"],"
                + "\"protocol\":\"JDBC\","
                + "\"reads\":[\"table:~:storefront-app:orders\"]}");
  }

  /** The one link of the code, with builds and without, that starts as given and comes from it. */
  private static void assertDeployed(String start, String source, String... parts) {
    for (List<String> facts : List.of(built, unbuilt)) {
      assertThat(facts)
          .filteredOn(l -> l.startsWith(start) && l.contains(source))
          .singleElement()
          .asString()
          .contains(parts);
    }
  }

  @Test
  void dep1AComposeServiceNamedNeitherAsTheApplicationNorAsTheArtifact() {
    assertDeployed(
        "service:~:fulfilment CALLS_HTTP service:~:catalog-service",
        "CatalogAdminClient#product(long)",
        "\"endpoint\":\"GET /api/products/{id}\"",
        "\"matchedBy\":\"container-host\"",
        "\"url\":\"http://catalog-api:8080/api/products/{id}\"");
  }

  @Test
  void dep2APrivateAddressIsTheUnitComposePublishesThePortFor() {
    assertDeployed(
        "service:~:fulfilment CALLS_HTTP service:~:storefront-app",
        "OrderLookupClient#order(long)",
        "\"endpoint\":\"GET /api/orders/{id}\"",
        "\"matchedBy\":\"published-port\"",
        "\"url\":\"http://192.168.1.10:8081/api/orders/{id}\"");
    for (List<String> facts : List.of(built, unbuilt)) {
      assertThat(facts).noneMatch(l -> l.contains("external-system:192.168."));
    }
  }

  @Test
  void dep3AUrlOnlyTheDeploymentSetsIsFilledFromItsEnvironment() {
    assertDeployed(
        "service:~:fulfilment CALLS_HTTP service:~:storefront-app",
        "ReturnsOrderClient#orderOf(long)",
        "\"endpoint\":\"GET /api/orders/{id}\"",
        "\"matchedBy\":\"container-host\"",
        "\"url\":\"http://shop-web:8080/api/orders/{id}\"");
  }

  @Test
  void dep4AMailHostOnlyTheDeploymentSetsIsTheExternalSystemItNames() {
    for (List<String> facts : List.of(built, unbuilt)) {
      assertThat(of(facts, "SENDS_MAIL"))
          .singleElement()
          .asString()
          .startsWith("service:~:payments SENDS_MAIL external-system:smtp.mail.example.com")
          .contains("\"protocol\":\"SMTP\"");
    }
  }

  @Test
  void everyContainerLinkOfFlow1() {
    for (List<String> facts : List.of(built, unbuilt)) {
      List<String> pairs = new ArrayList<>();
      for (String line : facts) {
        for (String predicate : List.of("CALLS_HTTP", "SENDS_MESSAGES_TO")) {
          if (line.contains(" " + predicate + " ")) {
            String rest =
                line.substring(line.indexOf(" " + predicate + " ") + predicate.length() + 2);
            pairs.add(
                line.substring(0, line.indexOf(' '))
                    + " "
                    + predicate
                    + " "
                    + rest.substring(0, rest.indexOf(' ')));
          }
        }
      }
      assertThat(pairs.stream().distinct())
          .containsExactlyInAnyOrder(
              "service:~:fulfilment CALLS_HTTP service:~:catalog-service",
              // DEP-2 and DEP-3, besides the flow.
              "service:~:fulfilment CALLS_HTTP service:~:storefront-app",
              "service:~:fulfilment SENDS_MESSAGES_TO service:~:storefront-app",
              "service:~:payments CALLS_HTTP external-system:sandbox.paygate.example.com",
              "service:~:payments CALLS_HTTP external-system:api.paygate.example.com",
              "service:~:payments SENDS_MESSAGES_TO service:~:fulfilment",
              "service:~:storefront-app CALLS_HTTP service:~:catalog-service",
              "service:~:storefront-app CALLS_HTTP service:~:payments",
              "service:~:storefront-app SENDS_MESSAGES_TO service:~:fulfilment",
              "service:~:storefront-app SENDS_MESSAGES_TO service:~:payments");
    }
  }

  @Test
  void doc2ReadmeClaimsTheCodeContradicts() {
    String storefront =
        "readme:storefront:README.md claim-contradiction synchronous-calls {\"contradictedBy\":["
            + "\"service:~:storefront-app unresolved-link GET ${TAX_SERVICE_URL}/v2/quotes\","
            + "\"service:~:storefront-app CALLS_HTTP service:~:catalog-service\","
            + "\"service:~:storefront-app CALLS_HTTP service:~:payments\"],"
            + "\"units\":[\"service:~:storefront-app\"]} @storefront:README.md#5";
    String ownDatabase =
        "readme:payments:README.md claim-contradiction own-database {\"contradictedBy\":["
            + "\"service:~:payments USES_DATABASE database:shop-db:5432/shop\","
            + "\"service:~:storefront-app USES_DATABASE database:shop-db:5432/shop\"],"
            + "\"found\":[\"service:~:storefront-app\"],\"units\":[\"service:~:payments\"]}"
            + " @payments:README.md#5";
    String othersTables =
        "readme:payments:README.md claim-contradiction other-services-data {\"contradictedBy\":["
            + "\"service:~:payments ACCESSES_DATA_OF service:~:storefront-app\"],"
            + "\"units\":[\"service:~:payments\"]} @payments:README.md#5";
    // Storefront does publish order.created to RabbitMQ (MSG-2), so that sentence holds. Without
    // builds the table access is not known (ADR 0036), so only the shared database shows.
    assertThat(builtClaims).containsExactlyInAnyOrder(storefront, ownDatabase, othersTables);
    assertThat(unbuiltClaims).containsExactlyInAnyOrder(storefront, ownDatabase);
  }
}

package io.lantern.link;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.repo.Repo;
import io.lantern.core.vocabulary.Vocabulary;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.http.HttpClientExtractor;
import io.lantern.extract.java.JavaAnalysis;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.extract.maven.MavenExtractor;
import io.lantern.extract.messaging.MessagingExtractor;
import io.lantern.extract.persistence.PersistenceExtractor;
import io.lantern.extract.spring.SpringExtractor;
import io.lantern.extract.sql.SqlExtractor;
import io.lantern.sandbox.BuildSandbox;
import io.lantern.sandbox.MavenMirror;
import io.lantern.sandbox.SandboxSettings;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
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

  @BeforeAll
  static void derive() throws IOException {
    BuildSandbox sandbox = new BuildSandbox(sandboxSettings(temp.resolve("builds")));
    Map<String, Repo> project = Project0.repos();
    List<RepoBuild> withoutBuilds = Project0.unbuilt(project);
    List<RepoBuild> withBuilds = Project0.built(project, sandbox);
    built = render(extractAndLink(withBuilds));
    unbuilt = render(extractAndLink(withoutBuilds));
  }

  /** The facts the app links, made as the app makes them, and the links. */
  private static List<Fact> extractAndLink(List<RepoBuild> repos) {
    List<Fact> facts = new ArrayList<>(new MavenExtractor().extract(repos, 1));
    JavaAnalysis code = JavaExtractor.analyze(repos);
    facts.addAll(new JavaExtractor().extract(code, 1));
    facts.addAll(new ConfigExtractor().extract(repos, 1));
    facts.addAll(new SqlExtractor().extract(repos, 1));
    facts.addAll(new SpringExtractor().extract(code, 1));
    facts.addAll(new PersistenceExtractor().extract(code, 1));
    facts.addAll(new HttpClientExtractor().extract(code, 1));
    facts.addAll(new MessagingExtractor().extract(code, 1));
    facts.addAll(new DeployableUnits().derive(facts, 1));
    List<Fact> links = new CrossRepoLinks().derive(facts, 1);
    Vocabulary vocabulary = DeployableUnitsTest.vocabulary();
    links.forEach(vocabulary::validate);
    return links;
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
  private static SandboxSettings sandboxSettings(Path buildsDirectory) {
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
      assertThat(pairs)
          .containsExactlyInAnyOrder(
              "service:~:fulfilment CALLS_HTTP service:~:catalog-service",
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
}

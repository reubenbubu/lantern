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
import io.lantern.core.vocabulary.Vocabulary;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.docs.DocsExtractor;
import io.lantern.extract.maven.MavenExtractor;
import io.lantern.extract.messaging.MessagingExtractor;
import io.lantern.extract.spring.SpringExtractor;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** The checks of README claims against the code, on facts written for each case. */
class ClaimChecksTest {

  private static final String COMMIT = "a".repeat(40);

  private final List<Fact> facts = new ArrayList<>();

  private Fact add(
      Origin.Kind kind,
      String origin,
      String subject,
      String predicate,
      FactObject object,
      String attributes,
      String repo,
      String path,
      double confidence) {
    ObjectNode json =
        attributes == null ? FactJson.newObject() : (ObjectNode) FactJson.parse(attributes);
    Fact fact =
        Fact.create(
            EntityId.parse(subject),
            predicate,
            object,
            json,
            new Origin(kind, origin, "1"),
            new Evidence(repo, COMMIT, path, new Evidence.LineRange(3, 3), null),
            confidence,
            Validity.from(1));
    facts.add(fact);
    return fact;
  }

  private Fact claim(String repo, String path, String text) {
    return add(
        Origin.Kind.README,
        DocsExtractor.NAME,
        "readme:" + repo + ":" + path,
        DocsExtractor.CLAIM,
        FactObject.string(text),
        null,
        repo,
        path,
        0.5);
  }

  private void unit(String repo, String artifact, String path) {
    add(
        Origin.Kind.STATIC,
        DeployableUnits.NAME,
        "service:g:" + artifact,
        DeployableUnits.DEPLOYABLE_UNIT,
        FactObject.string("spring-boot"),
        null,
        repo,
        path,
        1.0);
  }

  private Fact link(
      String repo, String from, String predicate, String to, String attributes, double confidence) {
    return add(
        Origin.Kind.STATIC,
        CrossRepoLinks.NAME,
        "service:g:" + from,
        predicate,
        FactObject.entity(EntityId.parse(to)),
        attributes,
        repo,
        "src/main/java/g/Code.java",
        confidence);
  }

  private Fact value(String repo, String subject, String predicate, String value, String json) {
    return add(
        Origin.Kind.STATIC,
        CrossRepoLinks.NAME,
        subject,
        predicate,
        FactObject.string(value),
        json,
        repo,
        "pom.xml",
        1.0);
  }

  private List<Fact> derive() {
    List<Fact> derived = new ClaimChecks().derive(facts, 1);
    Vocabulary vocabulary = DeployableUnitsTest.vocabulary();
    derived.forEach(vocabulary::validate);
    return derived;
  }

  private static List<String> contradictedBy(Fact f) {
    List<String> ids = new ArrayList<>();
    for (JsonNode entry : f.attributes().get("contradictedBy")) {
      ids.add(entry.get("fact").asString());
    }
    return ids;
  }

  // ---------------------------------------------------------------------------------------------
  // project-0's DOC-2, as facts

  @Test
  void noSynchronousCallsIsContradictedByEveryHttpCallOfTheUnitsResolvedOrNot() {
    unit("storefront", "storefront-app", "storefront-app/src/main/java/g/App.java");
    Fact claim =
        claim(
            "storefront",
            "README.md",
            "Storefront never calls another service synchronously when an order is placed. It"
                + " publishes an `order.created` event to RabbitMQ and the payments service"
                + " charges the order from that event.");
    Fact catalog =
        link(
            "storefront",
            "storefront-app",
            CrossRepoLinks.CALLS_HTTP,
            "service:g:catalog-service",
            "{\"protocol\":\"JSON/HTTP\"}",
            0.9);
    Fact payments =
        link(
            "storefront",
            "storefront-app",
            CrossRepoLinks.CALLS_HTTP,
            "service:g:payments",
            "{\"protocol\":\"JSON/HTTP\"}",
            1.0);
    Fact tax =
        value(
            "storefront",
            "service:g:storefront-app",
            CrossRepoLinks.UNRESOLVED_LINK,
            "GET ${TAX_SERVICE_URL}/v2/quotes",
            "{\"kind\":\"http\",\"reason\":\"unresolved-placeholder\"}");
    value(
        "storefront",
        "service:g:storefront-app",
        CrossRepoLinks.UNRESOLVED_LINK,
        "orders.${x}",
        "{\"kind\":\"message\",\"reason\":\"unresolved-placeholder\"}");
    link(
        "storefront",
        "storefront-app",
        CrossRepoLinks.PUBLISHES,
        "exchange:rabbitmq:shop.orders",
        "{\"protocol\":\"AMQP\"}",
        1.0);
    // Another repository's calls are not the storefront's.
    unit("fulfilment", "fulfilment", "src/main/java/g/App.java");
    link(
        "fulfilment",
        "fulfilment",
        CrossRepoLinks.CALLS_HTTP,
        "service:g:catalog-service",
        "{\"protocol\":\"JSON/HTTP\"}",
        1.0);

    List<Fact> derived = derive();

    assertThat(derived).hasSize(1);
    Fact f = derived.getFirst();
    assertThat(f.subject().toString()).isEqualTo("readme:storefront:README.md");
    assertThat(f.predicate()).isEqualTo(ClaimChecks.CLAIM_CONTRADICTION);
    assertThat(f.object().value()).isEqualTo(ClaimChecks.SYNCHRONOUS_CALLS);
    assertThat(f.evidence()).isEqualTo(claim.evidence());
    assertThat(f.origin().kind()).isEqualTo(Origin.Kind.STATIC);
    assertThat(f.attributes().get("claim").asString()).isEqualTo(claim.id());
    assertThat(contradictedBy(f)).containsExactlyInAnyOrder(catalog.id(), payments.id(), tax.id());
    assertThat(f.attributes().get("facts").get(0).asString()).isEqualTo(claim.id());
    assertThat(f.attributes().get("units").toString()).isEqualTo("[\"service:g:storefront-app\"]");
    assertThat(f.confidence()).isEqualTo(0.72);
    JsonNode first = null;
    for (JsonNode entry : f.attributes().get("contradictedBy")) {
      if (entry.get("fact").asString().equals(catalog.id())) {
        first = entry;
      }
    }
    assertThat(first).isNotNull();
    assertThat(first.get("object").asString()).isEqualTo("service:g:catalog-service");
    assertThat(first.get("repo").asString()).isEqualTo("storefront");
    assertThat(first.get("path").asString()).isEqualTo("src/main/java/g/Code.java");
    assertThat(first.get("line").asInt()).isEqualTo(3);
  }

  @Test
  void neverReadingOthersTablesAndADatabaseOfItsOwnAreContradictedBySharedData() {
    unit("payments", "payments", "src/main/java/g/App.java");
    unit("storefront", "storefront-app", "storefront-app/src/main/java/g/App.java");
    claim(
        "payments",
        "README.md",
        "Payments has a database of its own and never reads another service's tables.");
    Fact access =
        link(
            "payments",
            "payments",
            CrossRepoLinks.ACCESSES_DATA_OF,
            "service:g:storefront-app",
            "{\"reads\":[\"orders\"]}",
            1.0);
    Fact own =
        link(
            "payments",
            "payments",
            CrossRepoLinks.USES_DATABASE,
            "database:shop-db:5432/shop",
            "{\"technology\":\"PostgreSQL\"}",
            1.0);
    Fact other =
        link(
            "storefront",
            "storefront-app",
            CrossRepoLinks.USES_DATABASE,
            "database:shop-db:5432/shop",
            "{\"technology\":\"PostgreSQL\"}",
            1.0);

    List<Fact> derived = derive();

    assertThat(derived)
        .extracting(f -> f.object().value())
        .containsExactlyInAnyOrder(ClaimChecks.OTHER_SERVICES_DATA, ClaimChecks.OWN_DATABASE);
    for (Fact f : derived) {
      if (f.object().value().equals(ClaimChecks.OTHER_SERVICES_DATA)) {
        assertThat(contradictedBy(f)).containsExactly(access.id());
      } else {
        assertThat(contradictedBy(f)).containsExactlyInAnyOrder(own.id(), other.id());
        assertThat(f.attributes().get("found").toString())
            .isEqualTo("[\"service:g:storefront-app\"]");
      }
    }
  }

  @Test
  void writingOnlyDoesNotContradictNeverReading() {
    unit("payments", "payments", "src/main/java/g/App.java");
    claim("payments", "README.md", "Payments never reads another service's tables.");
    link(
        "payments",
        "payments",
        CrossRepoLinks.ACCESSES_DATA_OF,
        "service:g:storefront-app",
        "{\"writes\":[\"orders\"]}",
        1.0);

    assertThat(derive()).isEmpty();
  }

  // ---------------------------------------------------------------------------------------------
  // Claims the code agrees with, or that say nothing checkable

  @Test
  void claimsTheCodeAgreesWithOrThatAreNotCheckableAreLeftAlone() {
    unit("orders", "orders", "src/main/java/g/App.java");
    link(
        "orders",
        "orders",
        CrossRepoLinks.CALLS_HTTP,
        "service:g:billing",
        "{\"protocol\":\"HTTP\"}",
        1.0);
    link(
        "orders",
        "orders",
        CrossRepoLinks.USES_DATABASE,
        "database:db:5432/orders",
        "{\"technology\":\"PostgreSQL\"}",
        1.0);
    claim("orders", "README.md", "Calls to billing are not retried; they are synchronous.");
    claim("orders", "README.md", "Orders are stored in PostgreSQL.");
    claim("orders", "README.md", "Orders does not use MySQL.");
    claim("orders", "README.md", "Tests use an in-memory H2 database.");
    claim("orders", "README.md", "We migrated from MySQL to Postgres in 2021.");
    claim("orders", "README.md", "Orders has its own database.");
    claim("orders", "README.md", "Orders takes orders from customers.");

    assertThat(derive()).isEmpty();
  }

  @Test
  void onlyTheUnitsOwnOutgoingCallsSaidGenerallyAreReadAsNoSynchronousCalls() {
    unit("orders", "orders", "src/main/java/g/App.java");
    link("orders", "orders", CrossRepoLinks.CALLS_HTTP, "service:g:billing", null, 1.0);
    claim("orders", "README.md", "It does not retry HTTP calls.");
    claim("orders", "README.md", "Nothing calls this service over HTTP; it only consumes events.");
    claim("orders", "README.md", "No other service calls orders synchronously.");
    claim("orders", "README.md", "It never logs HTTP requests.");
    claim("orders", "README.md", "It doesn't call billing, the rest is event-driven.");
    claim("orders", "README.md", "It does not call billing over HTTP.");
    claim("orders", "README.md", "Orders is not called synchronously by anyone.");

    assertThat(derive()).isEmpty();
  }

  @Test
  void restIsReadOnlyAsTheAcronym() {
    unit("orders", "orders", "src/main/java/g/App.java");
    link("orders", "orders", CrossRepoLinks.CALLS_HTTP, "service:g:billing", null, 1.0);
    claim("orders", "README.md", "Orders never calls other services through rest.");
    claim("orders", "README.md", "Orders makes no REST calls.");
    claim("orders", "README.md", "Orders does not call other services over HTTP.");

    assertThat(derive())
        .extracting(f -> f.object().value())
        .containsExactly(ClaimChecks.SYNCHRONOUS_CALLS, ClaimChecks.SYNCHRONOUS_CALLS);
  }

  @Test
  void hedgedOrFutureTechnologyClaimsAreNotReadAsWhatRuns() {
    unit("orders", "orders", "src/main/java/g/App.java");
    link(
        "orders",
        "orders",
        CrossRepoLinks.USES_DATABASE,
        "database:db:5432/orders",
        "{\"technology\":\"PostgreSQL\"}",
        1.0);
    claim("orders", "README.md", "We plan to move orders to MySQL.");
    claim("orders", "README.md", "Orders will be stored in MySQL.");
    claim("orders", "README.md", "In the future orders go to MySQL.");
    claim("orders", "README.md", "Orders used to live in MySQL.");
    claim("orders", "README.md", "The legacy importer reads MySQL.");
    claim("orders", "README.md", "The deprecated MySQL store is still documented.");
    claim("orders", "README.md", "MySQL support is on the roadmap.");
    claim("orders", "README.md", "We are migrating to MySQL.");

    assertThat(derive()).isEmpty();
  }

  @Test
  void javaVersionsNamingAFeatureAreNotTheRuntime() {
    unit("orders", "orders", "src/main/java/g/App.java");
    value("orders", "service:g:orders", DeployableUnits.TECHNOLOGY, "Java 21", "{\"java\":\"21\"}");
    claim("orders", "README.md", "Calls go through the JDK 11 HttpClient.");
    claim("orders", "README.md", "The code uses Java 8 streams throughout.");
    claim("orders", "README.md", "Written in Java 17 style.");
    claim("orders", "README.md", "Requires Java 17 streams.");

    assertThat(derive()).isEmpty();
  }

  @Test
  void aDatabaseSharedOnlyUnderAProfileIsStillItsOwn() {
    unit("orders", "orders", "src/main/java/g/App.java");
    unit("billing", "billing", "src/main/java/g/App.java");
    claim("orders", "README.md", "Orders has its own database.");
    link(
        "orders",
        "orders",
        CrossRepoLinks.USES_DATABASE,
        "database:db:5432/orders",
        "{\"technology\":\"PostgreSQL\"}",
        1.0);
    link(
        "orders",
        "orders",
        CrossRepoLinks.USES_DATABASE,
        "database:localhost:5432/shop",
        "{\"profile\":\"local\"}",
        1.0);
    link(
        "billing",
        "billing",
        CrossRepoLinks.USES_DATABASE,
        "database:localhost:5432/shop",
        "{\"profile\":\"local\"}",
        1.0);

    assertThat(derive()).isEmpty();
  }

  @Test
  void aReadmeInAFolderWithoutUnitsChecksNothing() {
    unit("orders", "orders", "orders-app/src/main/java/g/App.java");
    link("orders", "orders", CrossRepoLinks.CALLS_HTTP, "service:g:billing", null, 1.0);
    claim("orders", "docs/README.md", "Orders makes no synchronous calls.");
    claim("orders", "orders-lib/README.md", "Orders makes no synchronous calls.");

    assertThat(derive()).isEmpty();
  }

  @Test
  void aLibraryRepositoryHasNoUnitsToCheck() {
    claim("lib", "README.md", "This library never calls another service synchronously.");
    link("lib", "x", CrossRepoLinks.CALLS_HTTP, "service:g:y", null, 1.0);

    assertThat(derive()).isEmpty();
  }

  @Test
  void aReadmeInAUnitsFolderSpeaksForThatUnitOnly() {
    unit("shop", "orders", "orders/src/main/java/g/App.java");
    unit("shop", "billing", "billing/src/main/java/g/App.java");
    claim("shop", "orders/README.md", "Orders makes no synchronous calls.");
    claim("shop", "billing/README.md", "Billing makes no synchronous calls.");
    link("shop", "billing", CrossRepoLinks.CALLS_HTTP, "service:g:orders", null, 1.0);

    List<Fact> derived = derive();

    assertThat(derived).hasSize(1);
    assertThat(derived.getFirst().subject().toString()).isEqualTo("readme:shop:billing/README.md");
  }

  // ---------------------------------------------------------------------------------------------
  // Named technologies and versions

  @Test
  void aNamedDatabaseTheUnitsDoNotUseIsContradicted() {
    unit("orders", "orders", "src/main/java/g/App.java");
    claim("orders", "README.md", "Orders are stored in **MySQL**.");
    Fact uses =
        link(
            "orders",
            "orders",
            CrossRepoLinks.USES_DATABASE,
            "database:db:5432/orders",
            "{\"technology\":\"PostgreSQL\"}",
            1.0);

    List<Fact> derived = derive();

    assertThat(derived).hasSize(1);
    Fact f = derived.getFirst();
    assertThat(f.object().value()).isEqualTo(ClaimChecks.DATABASE_TECHNOLOGY);
    assertThat(f.attributes().get("stated").asString()).isEqualTo("MySQL");
    assertThat(f.attributes().get("found").toString()).isEqualTo("[\"PostgreSQL\"]");
    assertThat(contradictedBy(f)).containsExactly(uses.id());
  }

  @Test
  void aNamedBrokerTheUnitsDoNotUseIsContradicted() {
    unit("orders", "orders", "src/main/java/g/App.java");
    claim("orders", "README.md", "Order events are published to Kafka.");
    claim("orders", "README.md", "Order events go through RabbitMQ.");
    link(
        "orders",
        "orders",
        CrossRepoLinks.PUBLISHES,
        "exchange:rabbitmq:orders",
        "{\"protocol\":\"AMQP\"}",
        1.0);

    List<Fact> derived = derive();

    assertThat(derived).hasSize(1);
    assertThat(derived.getFirst().object().value()).isEqualTo(ClaimChecks.BROKER_TECHNOLOGY);
    assertThat(derived.getFirst().attributes().get("stated").asString()).isEqualTo("Kafka");
    assertThat(derived.getFirst().attributes().get("found").toString()).isEqualTo("[\"AMQP\"]");
  }

  @Test
  void namedJavaAndSpringBootVersionsAreComparedWithTheUnitsTechnology() {
    unit("orders", "orders", "src/main/java/g/App.java");
    value(
        "orders",
        "service:g:orders",
        DeployableUnits.TECHNOLOGY,
        "Spring Boot 3.5.14 / Java 21",
        "{\"framework\":\"Spring Boot\",\"frameworkVersion\":\"3.5.14\",\"java\":\"21\"}");
    claim("orders", "README.md", "Orders runs on Java 17.");
    claim("orders", "README.md", "Built with Spring Boot 2.7.");
    claim("orders", "README.md", "Requires Java 17 or later.");
    claim("orders", "README.md", "Needs at least JDK 21.");
    claim("orders", "README.md", "A Spring Boot 3 application.");
    claim("orders", "README.md", "Uses Spring Boot 3.5.x.");

    List<Fact> derived = derive();

    assertThat(derived)
        .extracting(f -> f.object().value() + " " + f.attributes().get("stated").asString())
        .containsExactlyInAnyOrder("java-version Java 17", "spring-boot-version Spring Boot 2.7");
    assertThat(derived)
        .filteredOn(f -> f.object().value().equals(ClaimChecks.JAVA_VERSION))
        .first()
        .satisfies(f -> assertThat(f.attributes().get("found").toString()).contains("Java 21"));
  }

  @Test
  void versionsAreReadAsWritten() {
    assertThat(new ClaimChecks.Version(List.of(3), false).holdsFor("3.5.14")).isTrue();
    assertThat(new ClaimChecks.Version(List.of(3, 2), false).holdsFor("3.5.14")).isFalse();
    assertThat(new ClaimChecks.Version(List.of(3, 2), true).holdsFor("3.5.14")).isTrue();
    assertThat(new ClaimChecks.Version(List.of(17), true).holdsFor("11")).isFalse();
    assertThat(new ClaimChecks.Version(List.of(8), false).holdsFor("8")).isTrue();
  }

  // ---------------------------------------------------------------------------------------------
  // Ports, endpoints, destinations and named services (#160)

  /** A unit {@code service:g:<artifact>} with its module and application name. */
  private void service(String repo, String artifact) {
    add(
        Origin.Kind.STATIC,
        MavenExtractor.NAME,
        "service:g:" + artifact,
        BuiltInTypes.CONTAINS,
        FactObject.entity(EntityId.parse("module:g:" + artifact)),
        null,
        repo,
        "pom.xml",
        1.0);
    unit(repo, artifact, "pom.xml");
    add(
        Origin.Kind.STATIC,
        DeployableUnits.NAME,
        "service:g:" + artifact,
        DeployableUnits.APPLICATION_NAME,
        FactObject.string(artifact),
        null,
        repo,
        "src/main/resources/application.yml",
        1.0);
  }

  private Fact config(String repo, String module, String file, String key, String value) {
    return add(
        Origin.Kind.STATIC,
        ConfigExtractor.NAME,
        "module:g:" + module,
        ConfigExtractor.CONFIG_VALUE,
        FactObject.string(value),
        "{\"key\":\""
            + key
            + "\""
            + (file.contains("-")
                ? ",\"profile\":\"" + file.replaceAll(".*-|\\..*", "") + "\""
                : "")
            + "}",
        repo,
        "src/main/resources/" + file,
        1.0);
  }

  /** A REST endpoint of a controller in a module. */
  private Fact endpoint(String repo, String module, String httpMethod, String path) {
    String controller = "g." + module.replace("-", "") + ".Controller";
    add(
        Origin.Kind.STATIC,
        "java",
        "module:g:" + module,
        BuiltInTypes.CONTAINS,
        FactObject.entity(EntityId.parse("package:g:" + module + ":g")),
        null,
        repo,
        "src/main/java",
        1.0);
    add(
        Origin.Kind.STATIC,
        "java",
        "package:g:" + module + ":g",
        BuiltInTypes.CONTAINS,
        FactObject.entity(EntityId.parse("type:" + controller)),
        null,
        repo,
        "src/main/java/g/Controller.java",
        1.0);
    return add(
        Origin.Kind.STATIC,
        SpringExtractor.NAME,
        "method:" + controller + "#m" + facts.size() + "()",
        SpringExtractor.ENDPOINT,
        FactObject.string(httpMethod + " " + path),
        "{\"controller\":\""
            + controller
            + "\",\"httpMethod\":\""
            + httpMethod
            + "\",\"path\":\""
            + path
            + "\"}",
        repo,
        "src/main/java/g/Controller.java",
        1.0);
  }

  private List<String> checks() {
    return derive().stream()
        .map(
            f ->
                f.object().value()
                    + (f.attributes().has("stated")
                        ? " " + f.attributes().get("stated").asString()
                        : ""))
        .toList();
  }

  @Test
  void aStatedPortIsComparedWithServerPortInEveryView() {
    service("orders", "orders");
    Fact port = config("orders", "orders", "application.yml", "server.port", "8080");
    claim("orders", "README.md", "Orders runs on port 8081.");
    claim("orders", "README.md", "Kafka listens on port 9092.");
    claim("orders", "README.md", "The management port is 8082.");
    claim("orders", "README.md", "Run it locally on port 8083.");
    claim("orders", "README.md", "Billing listens on port 8084.");

    List<Fact> derived = derive();

    assertThat(derived).hasSize(1);
    Fact f = derived.getFirst();
    assertThat(f.object().value()).isEqualTo(ClaimChecks.PORT);
    assertThat(f.attributes().get("stated").asString()).isEqualTo("port 8081");
    assertThat(f.attributes().get("found").toString()).isEqualTo("[\"port 8080\"]");
    assertThat(contradictedBy(f)).containsExactly(port.id());
  }

  @Test
  void aPortSetUnderAProfileOrNotKnownIsNotFlagged() {
    service("orders", "orders");
    config("orders", "orders", "application.yml", "server.port", "8080");
    config("orders", "orders", "application-prod.yml", "server.port", "8081");
    claim("orders", "README.md", "It listens on port 8081.");
    service("billing", "billing");
    config("billing", "billing", "application.yml", "server.port", "${PORT:9000}");
    claim("billing", "README.md", "Billing runs on port 9001.");
    service("catalog", "catalog");
    claim("catalog", "README.md", "The HTTP port is 9002.");

    assertThat(derive()).isEmpty();
  }

  @Test
  void endpointsAreComparedWithTheUnitsEndpointsBehindTheirContextPath() {
    service("orders", "orders");
    config("orders", "orders", "application.yml", "server.servlet.context-path", "/api");
    Fact get = endpoint("orders", "orders", "GET", "/orders/{id}");
    Fact post = endpoint("orders", "orders", "POST", "/orders");
    endpoint("orders", "orders", "GET", "/health-checks");
    claim("orders", "README.md", "It exposes `GET /orders/{id}` and `POST /orders`.");
    claim("orders", "README.md", "It serves `GET /shop/api/orders/42` behind the gateway.");
    claim("orders", "README.md", "It exposes its REST API under /api.");
    claim("orders", "README.md", "It exposes a REST API and writes reports to /reports.");
    claim("orders", "README.md", "Our mobile app exposes `GET /app-only`.");
    claim("orders", "README.md", "It exposes `DELETE /orders/{id}` and the endpoint `/refunds`.");
    claim("orders", "README.md", "It serves files from /data.");
    claim("orders", "README.md", "It exposes /actuator/health.");
    claim("orders", "README.md", "Billing exposes `POST /charges`.");
    claim("orders", "README.md", "Tests serve `GET /stub`.");

    List<Fact> derived = derive();

    assertThat(derived).hasSize(1);
    Fact f = derived.getFirst();
    assertThat(f.object().value()).isEqualTo(ClaimChecks.ENDPOINT);
    assertThat(f.attributes().get("stated").asString()).isEqualTo("DELETE /orders/{id}, /refunds");
    // The endpoints nearest the path stated, or all of them when none shares its first segment.
    assertThat(contradictedBy(f)).contains(get.id(), post.id()).hasSize(3);
  }

  @Test
  void aUnitWithoutKnownEndpointsHasNoEndpointClaimChecked() {
    service("orders", "orders");
    claim("orders", "README.md", "It exposes `GET /orders`.");

    assertThat(derive()).isEmpty();
  }

  @Test
  void namedDestinationsAreComparedWithWhatTheUnitsPublishAndConsume() {
    service("orders", "orders");
    Fact kafka =
        link(
            "orders",
            "orders",
            CrossRepoLinks.PUBLISHES,
            "topic:kafka:orders.created",
            "{\"protocol\":\"Kafka\"}",
            1.0);
    link(
        "orders",
        "orders",
        CrossRepoLinks.PUBLISHES,
        "exchange:rabbitmq:shop.orders",
        "{\"protocol\":\"AMQP\",\"routingKey\":\"order.paid\"}",
        1.0);
    add(
        Origin.Kind.STATIC,
        CrossRepoLinks.NAME,
        "exchange:rabbitmq:shop.orders",
        CrossRepoLinks.ROUTES_TO,
        FactObject.entity(EntityId.parse("queue:rabbitmq:payments.orders")),
        "{\"protocol\":\"AMQP\"}",
        "payments",
        "src/main/java/g/Config.java",
        1.0);
    link(
        "orders",
        "orders",
        CrossRepoLinks.PUBLISHES,
        "topic:kafka:orders.send",
        "{\"protocol\":\"Kafka\"}",
        1.0);
    claim("orders", "README.md", "It publishes to the orders.created topic.");
    // A verb inside a name does not end it.
    claim("orders", "README.md", "It publishes to the topic orders.send.");
    claim("orders", "README.md", "Orders publishes to the RabbitMQ topic exchange `shop.orders`.");
    claim("orders", "README.md", "Paid orders reach the payments.orders queue: it sends them.");
    claim("orders", "README.md", "It sends paid orders to the payments.orders queue.");
    claim("orders", "README.md", "It publishes an `order.created` event to RabbitMQ.");
    claim("orders", "README.md", "It sends failures to the dead-letter queue.");
    claim("orders", "README.md", "It publishes to a fan-out exchange.");
    claim("orders", "README.md", "It publishes to a topic e.g. orders.created.");
    claim(
        "orders",
        "README.md",
        "It consumes payment events and publishes to the shop.orders exchange.");
    claim("orders", "README.md", "It consumes from the shipments.ready queue.");
    claim("orders", "README.md", "It publishes order events to the Kafka topic orders.placed.");

    List<Fact> derived = derive();

    assertThat(derived).hasSize(1);
    Fact f = derived.getFirst();
    assertThat(f.object().value()).isEqualTo(ClaimChecks.DESTINATION);
    assertThat(f.attributes().get("stated").asString()).isEqualTo("orders.placed");
    assertThat(f.attributes().get("found").toString())
        .isEqualTo("[\"orders.created\",\"orders.send\",\"shop.orders\"]");
    assertThat(contradictedBy(f)).contains(kafka.id()).hasSize(3);
  }

  @Test
  void aDestinationIsNotFlaggedWhenAnySendOfTheUnitsIsNotKnown() {
    service("orders", "orders");
    link(
        "orders",
        "orders",
        CrossRepoLinks.PUBLISHES,
        "topic:kafka:orders.created",
        "{\"protocol\":\"Kafka\"}",
        1.0);
    value(
        "orders",
        "service:g:orders",
        CrossRepoLinks.UNRESOLVED_LINK,
        "orders.${x}",
        "{\"kind\":\"message\",\"reason\":\"unresolved-placeholder\"}");
    claim("orders", "README.md", "It publishes to the orders.placed topic.");

    assertThat(derive()).isEmpty();
  }

  @Test
  void namedServicesAreComparedWithTheUnitsHttpCalls() {
    service("orders", "orders");
    service("billing", "billing-service");
    service("catalog", "catalog");
    Fact billing =
        link("orders", "orders", CrossRepoLinks.CALLS_HTTP, "service:g:billing-service", null, 1.0);
    claim("orders", "README.md", "Orders never calls billing.");
    claim("orders", "README.md", "It does not call catalog over HTTP.");
    claim("orders", "README.md", "Orders calls the billing service to charge orders.");
    claim("orders", "README.md", "Billing never calls catalog.");
    claim("orders", "README.md", "Billing charges orders. It calls catalog.");
    claim("orders", "README.md", "Tests call catalog.");

    List<Fact> derived = derive();

    assertThat(derived).hasSize(1);
    Fact f = derived.getFirst();
    assertThat(f.object().value()).isEqualTo(ClaimChecks.NAMED_SERVICES);
    assertThat(f.attributes().get("stated").asString()).isEqualTo("no calls to billing-service");
    assertThat(contradictedBy(f)).containsExactly(billing.id());
  }

  @Test
  void aServiceSaidToBeCalledThatTheUnitsDoNotCallIsFlaggedOnlyWhenEveryCallIsLinked() {
    service("orders", "orders");
    service("billing", "billing");
    service("catalog", "catalog");
    Fact billing =
        link("orders", "orders", CrossRepoLinks.CALLS_HTTP, "service:g:billing", null, 1.0);
    claim("orders", "README.md", "Orders calls catalog for prices.");
    claim("orders", "README.md", "It only calls billing.");

    assertThat(checks()).containsExactly("named-services calls catalog");
    assertThat(derive().getFirst().attributes().get("found").toString())
        .isEqualTo("[\"calls billing\"]");
    assertThat(contradictedBy(derive().getFirst())).containsExactly(billing.id());

    value(
        "orders",
        "service:g:orders",
        CrossRepoLinks.UNRESOLVED_LINK,
        "GET ${CATALOG_URL}/prices",
        "{\"kind\":\"http\",\"reason\":\"unresolved-placeholder\"}");

    assertThat(derive()).isEmpty();
  }

  @Test
  void onlyCallingANamedServiceIsContradictedByCallsToOthers() {
    service("orders", "orders");
    service("billing", "billing");
    service("catalog", "catalog");
    link("orders", "orders", CrossRepoLinks.CALLS_HTTP, "service:g:billing", null, 1.0);
    Fact catalog =
        link("orders", "orders", CrossRepoLinks.CALLS_HTTP, "service:g:catalog", null, 1.0);
    claim("orders", "README.md", "It only calls billing.");

    List<Fact> derived = derive();

    assertThat(checks()).containsExactly("named-services calls only billing");
    assertThat(contradictedBy(derived.getFirst())).containsExactly(catalog.id());
  }

  @Test
  void onlyCommunicatingAsynchronouslyIsContradictedByHttpCalls() {
    service("orders", "orders");
    service("catalog", "catalog");
    link("orders", "orders", CrossRepoLinks.CALLS_HTTP, "service:g:catalog", null, 1.0);
    claim("orders", "README.md", "Orders only communicates asynchronously.");
    claim("orders", "README.md", "All communication with other services is asynchronous.");
    claim("orders", "README.md", "It talks to other services only through Kafka events.");
    claim("orders", "README.md", "It only communicates with catalog through events.");
    claim(
        "orders", "README.md", "It only talks to Stripe over REST and publishes events to Kafka.");
    claim("orders", "README.md", "Catalog calls billing. It only communicates asynchronously.");
    claim("orders", "README.md", "Consumers only communicate with it through Kafka events.");
    claim("orders", "README.md", "It does not only communicate asynchronously.");
    claim("orders", "README.md", "Orders is event-driven.");

    assertThat(checks())
        .containsExactly(
            ClaimChecks.SYNCHRONOUS_CALLS,
            ClaimChecks.SYNCHRONOUS_CALLS,
            ClaimChecks.SYNCHRONOUS_CALLS);
  }

  @Test
  void portsAreReadOnlyWithTheUnitsAsSubjectAndNotForHostMappings() {
    service("orders", "orders");
    service("billing", "billing");
    config("orders", "orders", "application.yml", "server.port", "8080");
    claim("orders", "README.md", "The HTTP port is 8081.");
    claim("orders", "README.md", "server.port=8082");
    claim("orders", "README.md", "The service is available on port 8083.");
    claim("orders", "README.md", "Billing charges orders. It listens on port 8084.");
    claim("orders", "README.md", "Billing handles payments. Its HTTP port is 8086.");
    claim("orders", "README.md", "When it fails, the watchdog runs on port 8085.");

    assertThat(checks()).containsExactlyInAnyOrder("port port 8081", "port port 8082");
  }

  @Test
  void anEndpointClaimNeedsEveryUnitOfTheReadmeToHaveEndpoints() {
    service("shop", "orders");
    service("shop", "billing");
    endpoint("shop", "orders", "GET", "/orders");
    claim("shop", "README.md", "It exposes `GET /invoices`.");

    assertThat(derive()).isEmpty();
  }

  @Test
  void consumedDestinationsAreComparedWithListenersRoutesAndPatterns() {
    service("orders", "orders");
    link(
        "orders",
        "orders",
        CrossRepoLinks.CONSUMES,
        "queue:rabbitmq:orders.inbox",
        "{\"protocol\":\"AMQP\"}",
        1.0);
    add(
        Origin.Kind.STATIC,
        CrossRepoLinks.NAME,
        "exchange:rabbitmq:shop.payments",
        CrossRepoLinks.ROUTES_TO,
        FactObject.entity(EntityId.parse("queue:rabbitmq:orders.inbox")),
        "{\"protocol\":\"AMQP\"}",
        "payments",
        "src/main/java/g/Config.java",
        1.0);
    endpoint("orders", "orders", "GET", "/orders");
    add(
        Origin.Kind.STATIC,
        MessagingExtractor.NAME,
        "method:g.orders.Controller#listen()",
        MessagingExtractor.LISTENER,
        FactObject.string("payments\\..*"),
        "{\"broker\":\"kafka\",\"destinationKind\":\"topic-pattern\"}",
        "orders",
        "src/main/java/g/Controller.java",
        1.0);
    claim("orders", "README.md", "It consumes from the shop.payments exchange.");
    claim("orders", "README.md", "It listens on the payments.settled topic.");
    claim("orders", "README.md", "It reads from queue shipments.ready.");

    assertThat(checks()).containsExactly("destination shipments.ready");
  }

  @Test
  void aMaskedSendLeavesDestinationsAlone() {
    service("orders", "orders");
    link(
        "orders",
        "orders",
        CrossRepoLinks.PUBLISHES,
        "topic:kafka:orders.created",
        "{\"protocol\":\"Kafka\"}",
        1.0);
    endpoint("orders", "orders", "GET", "/orders");
    add(
        Origin.Kind.STATIC,
        MessagingExtractor.NAME,
        "method:g.orders.Controller#send()",
        MessagingExtractor.SEND,
        FactObject.string("${secret.topic}"),
        "{\"broker\":\"kafka\",\"destinationKind\":\"topic\",\"masked\":true}",
        "orders",
        "src/main/java/g/Controller.java",
        1.0);
    claim("orders", "README.md", "It publishes to the orders.placed topic.");

    assertThat(derive()).isEmpty();
  }

  @Test
  void namedServiceListsAndQualifiedCallsAreReadPlainlyOrNotAtAll() {
    service("orders", "orders");
    service("billing", "billing");
    service("catalog", "catalog");
    service("search-a", "search");
    service("search-b", "search-service");
    link("orders", "orders", CrossRepoLinks.CALLS_HTTP, "service:g:billing", null, 1.0);
    link("orders", "orders", CrossRepoLinks.CALLS_HTTP, "service:g:catalog", null, 1.0);
    link("orders", "orders", CrossRepoLinks.CALLS_HTTP, "service:g:search", null, 1.0);
    claim("orders", "README.md", "It never calls billing or catalog.");
    claim("orders", "README.md", "It never calls billing or PayGate.");
    claim("orders", "README.md", "It never calls search.");
    claim("orders", "README.md", "It calls search-service via RabbitMQ.");
    claim("orders", "README.md", "It calls search-service asynchronously.");
    claim("orders", "README.md", "It only calls billing when an order is paid.");
    claim("orders", "README.md", "It only calls billing for invoices and catalog for prices.");

    assertThat(checks()).containsExactly("named-services no calls to billing; no calls to catalog");
  }
}

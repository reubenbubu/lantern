package io.lantern.link;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.vocabulary.Vocabulary;
import io.lantern.extract.docs.DocsExtractor;
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
}

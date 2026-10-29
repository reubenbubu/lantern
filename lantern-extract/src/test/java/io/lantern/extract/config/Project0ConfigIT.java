package io.lantern.extract.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.repo.Git;
import io.lantern.core.repo.Repo;
import io.lantern.core.repo.RepoSettings;
import io.lantern.extract.Fixtures;
import io.lantern.extract.RepoBuild;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The config facts of the synthetic test set project-0 (docs/adr/0029), checked against the cases
 * its README lists, named by id. Runs only when {@code lantern.test.project0} names the folder
 * {@code testsets/project-0/setup.sh} set it up in; see Commands in {@code CLAUDE.md}. Reads the
 * repositories from git without building them.
 */
@EnabledIfSystemProperty(named = "lantern.test.project0", matches = ".+")
class Project0ConfigIT {

  /** The commits setup.sh makes from the pinned source, as Project0BuildIT checks them. */
  private static final Map<String, String> COMMITS =
      Map.of(
          "catalog", "8637326f638af796f242548b68216c8729e1a5f7",
          "fulfilment", "d7a159078e44f5de32d3b3f4aa5fa0ef9cb24921",
          "payments", "871bf725e98d45d11ea093749943bb555b65bae8",
          "storefront", "fa592e452e659d26597defe3519ba0ec3bfc5014");

  private static List<Repo> repos;
  private static List<String> facts;

  @BeforeAll
  static void extract() throws IOException {
    String setUp = System.getProperty("lantern.test.project0");
    assertThat(Path.of(setUp).isAbsolute())
        .as("lantern.test.project0 must be an absolute path: " + setUp)
        .isTrue();
    repos = new ArrayList<>();
    List<RepoBuild> builds = new ArrayList<>();
    for (Map.Entry<String, String> e : new java.util.TreeMap<>(COMMITS).entrySet()) {
      Path path = Path.of(setUp).resolve(e.getKey()).toRealPath();
      Git.Result head = Git.run(path, "rev-parse", "HEAD");
      assertThat(head.out())
          .as("%s is not at the pinned commit; run testsets/project-0/setup.sh again", e.getKey())
          .isEqualTo(e.getValue());
      Repo repo = new Repo(e.getKey(), path, RepoSettings.DEFAULT);
      repos.add(repo);
      builds.add(new RepoBuild(repo, e.getValue(), null));
    }
    List<Fact> extracted = new ConfigExtractor().extract(builds, 1);
    Fixtures.checkEnvelope(extracted, repos, 1, ConfigExtractor.NAME, ConfigExtractor.VERSION);
    facts = Fixtures.render(extracted).lines().toList();
  }

  private static final String STOREFRONT = "module:com.acme.shop:storefront-app config-value ";
  private static final String CATALOG = "module:com.acme.shop:catalog-service config-value ";
  private static final String PAYMENTS = "module:com.acme.shop:payments config-value ";
  private static final String FULFILMENT = "module:com.acme.shop:fulfilment config-value ";

  @Test
  void cfg1ApplicationNamesFromYamlAndProperties() {
    assertThat(facts)
        .contains(
            STOREFRONT
                + "storefront {\"key\":\"spring.application.name\"}"
                + " @storefront:storefront-app/src/main/resources/application.yml#3-3 1.0",
            PAYMENTS
                + "payments {\"key\":\"spring.application.name\"}"
                + " @payments:src/main/resources/application.properties#1-1 1.0",
            FULFILMENT
                + "fulfilment {\"key\":\"spring.application.name\"}"
                + " @fulfilment:src/main/resources/application.yml#3-3 1.0");
  }

  @Test
  void cfg2ApplicationNameSetOnlyInBootstrap() {
    assertThat(facts)
        .contains(
            CATALOG
                + "catalog-service {\"key\":\"spring.application.name\"}"
                + " @catalog:catalog-service/src/main/resources/bootstrap.yml#3-3 1.0");
    assertThat(facts.stream().filter(f -> f.contains("\"spring.application.name\""))).hasSize(4);
  }

  @Test
  void cfg3ProfileFilesOverrideTheDefaults() {
    assertThat(facts)
        .contains(
            STOREFRONT
                + "http://payments:8080 {\"key\":\"clients.payments.url\"}"
                + " @storefront:storefront-app/src/main/resources/application.yml#18-18 1.0",
            STOREFRONT
                + "http://localhost:8082 {\"key\":\"clients.payments.url\",\"profile\":\"local\"}"
                + " @storefront:storefront-app/src/main/resources/application-local.yml#11-11 1.0",
            STOREFRONT
                + "jdbc:postgresql://shop-db:5432/shop {\"key\":\"spring.datasource.url\"}"
                + " @storefront:storefront-app/src/main/resources/application.yml#5-5 1.0",
            STOREFRONT
                + "jdbc:postgresql://localhost:5432/shop"
                + " {\"key\":\"spring.datasource.url\",\"profile\":\"local\"}"
                + " @storefront:storefront-app/src/main/resources/application-local.yml#3-3 1.0",
            // HTTP-4: the default PayGate URL is the sandbox, prod overrides it.
            PAYMENTS
                + "https://sandbox.paygate.example.com {\"key\":\"paygate.url\"}"
                + " @payments:src/main/resources/application.properties#16-16 1.0",
            PAYMENTS
                + "https://api.paygate.example.com {\"key\":\"paygate.url\",\"profile\":\"prod\"}"
                + " @payments:src/main/resources/application-prod.properties#1-1 1.0");
    // Only what differs is recorded per profile.
    assertThat(facts.stream().filter(f -> f.contains("\"profile\":\"local\"")))
        .allMatch(f -> f.contains("application-local.yml"));
    assertThat(facts.stream().filter(f -> f.contains("\"profile\":\"prod\""))).hasSize(1);
  }

  @Test
  void val5UnresolvableEnvVarsKeepTheirPlaceholderKeys() {
    assertThat(facts)
        .contains(
            // HTTP-5: the tax service's URL comes from an env var nothing sets.
            STOREFRONT
                + "${TAX_SERVICE_URL} {\"key\":\"clients.tax.url\","
                + "\"unresolved\":[\"TAX_SERVICE_URL\"]}"
                + " @storefront:storefront-app/src/main/resources/application.yml#20-20 1.0",
            STOREFRONT
                + "${SHOP_DB_PASSWORD} {\"key\":\"spring.datasource.password\","
                + "\"unresolved\":[\"SHOP_DB_PASSWORD\"]}"
                + " @storefront:storefront-app/src/main/resources/application.yml#7-7 1.0",
            CATALOG
                + "${CATALOG_DB_PASSWORD} {\"key\":\"spring.datasource.password\","
                + "\"unresolved\":[\"CATALOG_DB_PASSWORD\"]}"
                + " @catalog:catalog-service/src/main/resources/application.yml#8-8 1.0",
            PAYMENTS
                + "${PAYGATE_API_KEY} {\"key\":\"paygate.api-key\","
                + "\"unresolved\":[\"PAYGATE_API_KEY\"]}"
                + " @payments:src/main/resources/application.properties#17-17 1.0");
  }

  @Test
  void valuesOtherExtractorsResolveAgainst() {
    // MSG-1, MSG-4, ENTRY-1, HTTP-3 and DB-5 resolve through these.
    assertThat(facts)
        .contains(
            STOREFRONT
                + "shipments.dispatched {\"key\":\"shop.jms.shipments-queue\"}"
                + " @storefront:storefront-app/src/main/resources/application.yml#24-24 1.0",
            PAYMENTS
                + "payments.completed {\"key\":\"payments.kafka.completed-topic\"}"
                + " @payments:src/main/resources/application.properties#13-13 1.0",
            PAYMENTS
                + "0 0 2 * * * {\"key\":\"payments.settlement.cron\"}"
                + " @payments:src/main/resources/application.properties#14-14 1.0",
            FULFILMENT
                + "http://catalog-service:8080 {\"key\":\"catalog.base-url\"}"
                + " @fulfilment:src/main/resources/application.yml#10-10 1.0",
            PAYMENTS
                + "jdbc:postgresql://shop-db:5432/shop {\"key\":\"spring.datasource.url\"}"
                + " @payments:src/main/resources/application.properties#5-5 1.0",
            CATALOG
                + "jdbc:postgresql://catalog-db:5432/catalog {\"key\":\"spring.datasource.url\"}"
                + " @catalog:catalog-service/src/main/resources/application.yml#6-6 1.0");
  }
}

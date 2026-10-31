package io.lantern.extract.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.repo.Repo;
import io.lantern.extract.Fixtures;
import io.lantern.extract.Project0;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.java.JavaAnalysis;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.sandbox.BuildSandbox;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * The persistence facts of the synthetic test set project-0 (docs/adr/0029), checked against the
 * cases its README lists, named by id: built in the sandbox and read through javac, and parsed
 * without builds (degraded mode), where calls into Spring JDBC do not resolve, so SQL in code is
 * found only through javac. Runs only when {@code lantern.test.project0} names the folder {@code
 * testsets/project-0/setup.sh} set it up in; see Commands in {@code CLAUDE.md}. Needs Docker and
 * Maven Central, or the Maven mirror of the test properties; the builds use the default shared
 * Maven cache directory.
 */
@EnabledIfSystemProperty(named = "lantern.test.project0", matches = ".+")
class Project0PersistenceIT {

  @TempDir static Path temp;

  private static List<String> compiled;
  private static List<String> parsed;

  @BeforeAll
  static void extract() throws IOException {
    BuildSandbox sandbox = new BuildSandbox(Fixtures.sandboxSettings(temp.resolve("builds")));
    Map<String, Repo> project = Project0.repos();
    List<Repo> repos = new ArrayList<>(project.values());
    List<RepoBuild> unbuilt = Project0.unbuilt(project);
    List<RepoBuild> built = Project0.built(project, sandbox);
    JavaAnalysis code = JavaExtractor.analyze(built);
    assertThat(code.modules())
        .as("every module with code is read through javac")
        .allMatch(m -> m.compiled() || m.structure().types().isEmpty());
    compiled = render(Resolved.facts(code, 1), repos);
    parsed = render(Resolved.facts(unbuilt, 1), repos);
  }

  private static List<String> render(List<Fact> facts, List<Repo> repos) throws IOException {
    Fixtures.checkEnvelope(
        facts, repos, 1, PersistenceExtractor.NAME, PersistenceExtractor.VERSION);
    return Fixtures.render(facts)
        .lines()
        .map(line -> line.replaceAll(" [01]\\.[07]$", ""))
        .toList();
  }

  /** Checks a case both ways: through javac and parsed. */
  private static void assertBoth(String... lines) {
    assertThat(compiled).as("through javac").contains(lines);
    assertThat(parsed).as("parsed").contains(lines);
  }

  private static final String MODEL =
      "@storefront:storefront-model/src/main/java/com/acme/shop/storefront/model/";

  private static final String CATALOG =
      "@catalog-lib:catalog-lib/src/main/java/com/acme/shop/catalog/";

  @Test
  void db1JpaEntitiesAndRepositories() {
    assertBoth(
        "type:com.acme.shop.storefront.model.CustomerOrder MAPS_TO"
            + " table:com.acme.shop:storefront-model:orders {\"framework\":\"jpa\"} "
            + MODEL
            + "CustomerOrder.java#12-12",
        "type:com.acme.shop.storefront.model.OrderRepository REPOSITORY_FOR"
            + " type:com.acme.shop.storefront.model.CustomerOrder"
            + " {\"base\":\"org.springframework.data.jpa.repository.JpaRepository\"} "
            + MODEL
            + "OrderRepository.java#5-5",
        "type:com.acme.shop.catalog.Product MAPS_TO table:com.acme.shop:catalog-lib:product"
            + " {\"framework\":\"jpa\"} "
            + CATALOG
            + "Product.java#9-9",
        "type:com.acme.shop.catalog.ProductRepository REPOSITORY_FOR"
            + " type:com.acme.shop.catalog.Product"
            + " {\"base\":\"org.springframework.data.jpa.repository.JpaRepository\"} "
            + CATALOG
            + "ProductRepository.java#5-5");
    for (List<String> facts : List.of(compiled, parsed)) {
      assertThat(facts.stream().filter(l -> l.contains(" MAPS_TO ")))
          .extracting(l -> l.substring(0, l.indexOf(" {")))
          .containsExactly(
              "type:com.acme.shop.catalog.Product MAPS_TO table:com.acme.shop:catalog-lib:product",
              "type:com.acme.shop.storefront.model.CustomerOrder MAPS_TO"
                  + " table:com.acme.shop:storefront-model:orders");
    }
  }

  @Test
  void db2JdbcTemplateReadsOrdersAndPaymentAndWritesPayment() {
    String job = " @payments:src/main/java/com/acme/shop/payments/SettlementJob.java#";
    String api = "\"api\":\"org.springframework.jdbc.core.JdbcTemplate\"";
    assertThat(compiled.stream().filter(l -> l.contains("_TABLE ")))
        .containsExactly(
            "method:com.acme.shop.payments.SettlementJob#settle() READS_TABLE"
                + " table:com.acme.shop:payments:orders {"
                + api
                + ",\"statement\":\"select\"}"
                + job
                + "21-24",
            "method:com.acme.shop.payments.SettlementJob#settle() READS_TABLE"
                + " table:com.acme.shop:payments:payment {"
                + api
                + ",\"statement\":\"select\"}"
                + job
                + "21-24",
            "method:com.acme.shop.payments.SettlementJob#settle() WRITES_TABLE"
                + " table:com.acme.shop:payments:payment {"
                + api
                + ",\"statement\":\"insert\"}"
                + job
                + "26-26");
    // Parsed without the build's classpath, calls into Spring JDBC do not resolve.
    assertThat(parsed).noneMatch(l -> l.contains("_TABLE "));
  }

  @Test
  void db5TwoServicesShareOneDatabaseAndCatalogHasItsOwn() {
    assertBoth(
        "module:com.acme.shop:storefront-app datasource jdbc:postgresql://shop-db:5432/shop"
            + " {\"database\":\"shop\",\"key\":\"spring.datasource.url\","
            + "\"server\":\"shop-db:5432\",\"technology\":\"PostgreSQL\"}"
            + " @storefront:storefront-app/src/main/resources/application.yml#5-5",
        "module:com.acme.shop:storefront-app datasource jdbc:postgresql://localhost:5432/shop"
            + " {\"database\":\"shop\",\"key\":\"spring.datasource.url\",\"profile\":\"local\","
            + "\"server\":\"localhost:5432\",\"technology\":\"PostgreSQL\"}"
            + " @storefront:storefront-app/src/main/resources/application-local.yml#3-3",
        "module:com.acme.shop:payments datasource jdbc:postgresql://shop-db:5432/shop"
            + " {\"database\":\"shop\",\"key\":\"spring.datasource.url\","
            + "\"server\":\"shop-db:5432\",\"technology\":\"PostgreSQL\"}"
            + " @payments:src/main/resources/application.properties#5-5",
        "module:com.acme.shop:catalog-service datasource jdbc:postgresql://catalog-db:5432/catalog"
            + " {\"database\":\"catalog\",\"key\":\"spring.datasource.url\","
            + "\"server\":\"catalog-db:5432\",\"technology\":\"PostgreSQL\"}"
            + " @catalog:src/main/resources/application.yml#6-6");
    // Payments reads storefront's orders table: the same name in the same database (DB-2).
    assertThat(compiled)
        .anyMatch(
            l ->
                l.startsWith(
                    "method:com.acme.shop.payments.SettlementJob#settle() READS_TABLE"
                        + " table:com.acme.shop:payments:orders "));
    // No credentials reach a fact.
    assertThat(String.join("\n", compiled)).doesNotContain("password", "username");
  }

  @Test
  void bothWaysGiveTheSameFactsExceptSqlInCode() {
    assertThat(parsed).isEqualTo(compiled.stream().filter(l -> !l.startsWith("method:")).toList());
  }
}

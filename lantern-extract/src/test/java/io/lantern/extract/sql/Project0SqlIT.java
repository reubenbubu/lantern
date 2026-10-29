package io.lantern.extract.sql;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.repo.Repo;
import io.lantern.extract.Fixtures;
import io.lantern.extract.Project0;
import io.lantern.extract.RepoBuild;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The schema facts of the synthetic test set project-0 (docs/adr/0029), checked against the cases
 * its README lists, named by id. Runs only when {@code lantern.test.project0} names the folder
 * {@code testsets/project-0/setup.sh} set it up in; see Commands in {@code CLAUDE.md}. Reads the
 * repositories from git without building them.
 */
@EnabledIfSystemProperty(named = "lantern.test.project0", matches = ".+")
class Project0SqlIT {

  private static List<String> facts;

  @BeforeAll
  static void extract() throws IOException {
    Map<String, Repo> project = Project0.repos();
    List<Repo> repos = new ArrayList<>(project.values());
    List<RepoBuild> builds = Project0.unbuilt(project);
    List<Fact> extracted = new SqlExtractor().extract(builds, 1);
    Fixtures.checkEnvelope(extracted, repos, 1, SqlExtractor.NAME, SqlExtractor.VERSION);
    facts = Fixtures.render(extracted).lines().toList();
  }

  private static final String STOREFRONT = "com.acme.shop:storefront-app";
  private static final String STOREFRONT_V1 =
      " @storefront:storefront-app/src/main/resources/db/migration/V1__create_orders.sql";
  private static final String PAYMENTS = "com.acme.shop:payments";
  private static final String PAYMENTS_V1 =
      " @payments:src/main/resources/db/migration/V1__create_payment.sql";
  private static final String CATALOG = "com.acme.shop:catalog-lib";
  private static final String CATALOG_CHANGELOG =
      " @catalog-lib:catalog-lib/src/main/resources/db/changelog/db.changelog-master.yaml";

  @Test
  void db3FlywayMigrationsGiveTablesOrdersAndPayment() {
    assertThat(facts)
        .as("DB-3")
        .contains(
            "module:" + STOREFRONT + " CONTAINS schema:" + STOREFRONT + STOREFRONT_V1 + " 1.0",
            "schema:" + STOREFRONT + " kind flyway" + STOREFRONT_V1 + " 1.0",
            "schema:"
                + STOREFRONT
                + " CONTAINS table:"
                + STOREFRONT
                + ":orders"
                + STOREFRONT_V1
                + "#1-6 1.0",
            "module:" + PAYMENTS + " CONTAINS schema:" + PAYMENTS + PAYMENTS_V1 + " 1.0",
            "schema:" + PAYMENTS + " kind flyway" + PAYMENTS_V1 + " 1.0",
            "schema:"
                + PAYMENTS
                + " CONTAINS table:"
                + PAYMENTS
                + ":payment"
                + PAYMENTS_V1
                + "#1-5 1.0");
    assertThat(columns(STOREFRONT, "orders"))
        .as("DB-3")
        .containsExactly("id", "product_id", "total_cents", "status");
    assertThat(columns(PAYMENTS, "payment"))
        .as("DB-3")
        .containsExactly("id", "order_id", "settled");
    assertThat(facts)
        .as("DB-3")
        .contains(
            "table:"
                + STOREFRONT
                + ":orders PRIMARY_KEY column:"
                + STOREFRONT
                + ":orders#id {\"columns\":[\"id\"]}"
                + STOREFRONT_V1
                + "#1-6 1.0",
            "column:"
                + STOREFRONT
                + ":orders#status data-type varchar(20)"
                + STOREFRONT_V1
                + "#1-6 1.0",
            "column:" + PAYMENTS + ":payment#settled nullable false" + PAYMENTS_V1 + "#1-5 1.0");
  }

  @Test
  void db4LiquibaseChangelogGivesTableProduct() {
    assertThat(facts)
        .as("DB-4")
        .contains(
            "module:" + CATALOG + " CONTAINS schema:" + CATALOG + CATALOG_CHANGELOG + " 1.0",
            "schema:" + CATALOG + " kind liquibase" + CATALOG_CHANGELOG + " 1.0",
            "schema:"
                + CATALOG
                + " CONTAINS table:"
                + CATALOG
                + ":product"
                + CATALOG_CHANGELOG
                + "#6-28 1.0",
            "table:"
                + CATALOG
                + ":product PRIMARY_KEY column:"
                + CATALOG
                + ":product#id {\"columns\":[\"id\"]}"
                + CATALOG_CHANGELOG
                + "#9-13 1.0");
    assertThat(columns(CATALOG, "product"))
        .as("DB-4")
        .containsExactly("id", "name", "price_cents", "stock");
  }

  @Test
  void onlyTheMigratedModulesHaveSchemas() {
    assertThat(facts)
        .filteredOn(f -> f.contains(" CONTAINS schema:"))
        .extracting(f -> f.substring(0, f.indexOf(' ')))
        .containsExactlyInAnyOrder(
            "module:" + STOREFRONT, "module:" + PAYMENTS, "module:" + CATALOG);
  }

  /** A table's column names in position order. */
  private static List<String> columns(String schema, String table) {
    String prefix = "column:" + schema + ":" + table + "#";
    return facts.stream()
        .filter(f -> f.startsWith(prefix) && f.contains(" position "))
        .sorted(java.util.Comparator.comparingInt(f -> Integer.parseInt(f.split(" ")[2])))
        .map(f -> f.substring(prefix.length(), f.indexOf(' ')))
        .toList();
  }
}

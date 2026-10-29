package io.lantern.extract.spring;

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
 * The Spring facts of the synthetic test set project-0 (docs/adr/0029), checked against the cases
 * its README lists, named by id, both ways the code is read: built in the sandbox and read through
 * javac, and parsed without builds (degraded mode). Runs only when {@code lantern.test.project0}
 * names the folder {@code testsets/project-0/setup.sh} set it up in; see Commands in {@code
 * CLAUDE.md}. Needs Docker and Maven Central, or the Maven mirror of the test properties; the
 * builds use the default shared Maven cache directory.
 */
@EnabledIfSystemProperty(named = "lantern.test.project0", matches = ".+")
class Project0SpringIT {

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
    compiled = render(new SpringExtractor().extract(code, 1), repos);
    parsed = render(new SpringExtractor().extract(unbuilt, 1), repos);
  }

  private static List<String> render(List<Fact> facts, List<Repo> repos) throws IOException {
    Fixtures.checkEnvelope(facts, repos, 1, SpringExtractor.NAME, SpringExtractor.VERSION);
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

  private static final String STOREFRONT_WEB =
      "@storefront:storefront-app/src/main/java/com/acme/shop/storefront/web/";

  @Test
  void spr1CustomAnnotationCarryingRestController() {
    String endpoint =
        " {\"annotation\":\"org.springframework.web.bind.annotation.%s\","
            + "\"controller\":\"com.acme.shop.storefront.web.OrderController\","
            + "\"framework\":\"spring-mvc\",\"httpMethod\":\"%s\",\"path\":\"%s\"} "
            + STOREFRONT_WEB
            + "OrderController.java#%s";
    assertBoth(
        "type:com.acme.shop.storefront.web.OrderController stereotype controller"
            + " {\"annotation\":\"com.acme.shop.storefront.web.WebApi\",\"rest\":true,"
            + "\"via\":[\"org.springframework.web.bind.annotation.RestController\","
            + "\"org.springframework.stereotype.Controller\"]} "
            + STOREFRONT_WEB
            + "OrderController.java#11-11",
        "method:com.acme.shop.storefront.web.OrderController#place("
            + "com.acme.shop.storefront.web.OrderController$NewOrder) endpoint POST /api/orders"
            + endpoint.formatted("PostMapping", "POST", "/api/orders", "21-21"),
        "method:com.acme.shop.storefront.web.OrderController#order(long) endpoint"
            + " GET /api/orders/{id}"
            + endpoint.formatted("GetMapping", "GET", "/api/orders/{id}", "26-26"));
    // WebApi itself is an annotation, not a controller.
    assertThat(compiled).noneMatch(l -> l.startsWith("type:com.acme.shop.storefront.web.WebApi "));
  }

  @Test
  void spr2CustomAnnotationCarryingService() {
    assertBoth(
        "type:com.acme.shop.storefront.order.PlaceOrderService stereotype service"
            + " {\"annotation\":\"com.acme.shop.storefront.order.UseCase\","
            + "\"via\":[\"org.springframework.stereotype.Service\"]}"
            + " @storefront:storefront-app/src/main/java/com/acme/shop/storefront/order/"
            + "PlaceOrderService.java#16-16");
  }

  @Test
  void spr3ApplicationMainClasses() {
    for (List<String> facts : List.of(compiled, parsed)) {
      assertThat(facts.stream().filter(l -> l.contains(" main-class ")))
          .extracting(l -> l.substring(0, l.indexOf(" {")))
          .containsExactly(
              "type:com.acme.shop.catalog.CatalogApplication main-class spring-boot",
              "type:com.acme.shop.fulfilment.FulfilmentApplication main-class spring-boot",
              "type:com.acme.shop.payments.PaymentsApplication main-class spring-boot",
              "type:com.acme.shop.storefront.StorefrontApplication main-class spring-boot");
    }
    assertBoth(
        "type:com.acme.shop.catalog.CatalogApplication main-class spring-boot"
            + " {\"main\":\"method:com.acme.shop.catalog.CatalogApplication#main(java.lang.String[])\"}"
            + " @catalog:src/main/java/com/acme/shop/catalog/"
            + "CatalogApplication.java#6-6");
  }

  @Test
  void spr4ProfileGatedBean() {
    assertBoth(
        "type:com.acme.shop.storefront.integration.AuditTrail profile-gate audit"
            + " {\"profiles\":[\"audit\"]}"
            + " @storefront:storefront-integration/src/main/java/com/acme/shop/storefront/"
            + "integration/AuditTrail.java#9-9",
        "type:com.acme.shop.storefront.integration.AuditTrail stereotype component"
            + " {\"annotation\":\"org.springframework.stereotype.Component\"}"
            + " @storefront:storefront-integration/src/main/java/com/acme/shop/storefront/"
            + "integration/AuditTrail.java#8-8");
  }

  @Test
  void entry1AndVal3ScheduledJobWithItsCronResolved() {
    assertBoth(
        "method:com.acme.shop.payments.SettlementJob#settle() scheduled 0 0 2 * * *"
            + " {\"trigger\":\"cron\",\"written\":\"${payments.settlement.cron}\"}"
            + " @payments:src/main/java/com/acme/shop/payments/SettlementJob.java#18-18");
  }

  @Test
  void bothWaysGiveTheSameFacts() {
    assertThat(parsed).isEqualTo(compiled);
  }
}

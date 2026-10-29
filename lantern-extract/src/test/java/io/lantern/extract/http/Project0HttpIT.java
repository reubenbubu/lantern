package io.lantern.extract.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.repo.Git;
import io.lantern.core.repo.Repo;
import io.lantern.core.repo.RepoSettings;
import io.lantern.extract.Fixtures;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.java.JavaAnalysis;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.sandbox.BuildSandbox;
import io.lantern.sandbox.BuildStatus;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * The HTTP client facts of the synthetic test set project-0 (docs/adr/0029), checked against the
 * cases its README lists, named by id, both ways the code is read: built in the sandbox and read
 * through javac, and parsed without builds (degraded mode). Runs only when {@code
 * lantern.test.project0} names the folder {@code testsets/project-0/setup.sh} set it up in; see
 * Commands in {@code CLAUDE.md}. Needs Docker and Maven Central, or the Maven mirror of the test
 * properties; the builds use the default shared Maven cache directory.
 */
@EnabledIfSystemProperty(named = "lantern.test.project0", matches = ".+")
class Project0HttpIT {

  /** The commits setup.sh makes from the pinned source, as Project0BuildIT checks them. */
  private static final Map<String, String> COMMITS =
      new TreeMap<>(
          Map.of(
              "catalog", "8637326f638af796f242548b68216c8729e1a5f7",
              "fulfilment", "d7a159078e44f5de32d3b3f4aa5fa0ef9cb24921",
              "payments", "871bf725e98d45d11ea093749943bb555b65bae8",
              "storefront", "fa592e452e659d26597defe3519ba0ec3bfc5014"));

  @TempDir static Path temp;

  private static List<String> compiled;
  private static List<String> parsed;

  @BeforeAll
  static void extract() throws IOException {
    String setUp = System.getProperty("lantern.test.project0");
    assertThat(Path.of(setUp).isAbsolute())
        .as("lantern.test.project0 must be an absolute path: " + setUp)
        .isTrue();
    BuildSandbox sandbox = new BuildSandbox(Fixtures.sandboxSettings(temp.resolve("builds")));
    List<Repo> repos = new ArrayList<>();
    List<RepoBuild> unbuilt = new ArrayList<>();
    List<RepoBuild> built = new ArrayList<>();
    for (Map.Entry<String, String> e : COMMITS.entrySet()) {
      Path path = Path.of(setUp).resolve(e.getKey()).toRealPath();
      Git.Result head = Git.run(path, "rev-parse", "HEAD");
      assertThat(head.out())
          .as("%s is not at the pinned commit; run testsets/project-0/setup.sh again", e.getKey())
          .isEqualTo(e.getValue());
      Repo repo = new Repo(e.getKey(), path, RepoSettings.DEFAULT);
      repos.add(repo);
      unbuilt.add(new RepoBuild(repo, e.getValue(), null));
      RepoBuild build = new RepoBuild(repo, e.getValue(), sandbox.build(repo, e.getValue()));
      assertThat(build.build().status()).as(e.getKey()).isEqualTo(BuildStatus.SUCCEEDED);
      built.add(build);
    }
    JavaAnalysis code = JavaExtractor.analyze(built);
    assertThat(code.modules())
        .as("every module with code is read through javac")
        .allMatch(m -> m.compiled() || m.structure().types().isEmpty());
    compiled = render(new HttpClientExtractor().extract(code, 1), repos);
    parsed = render(new HttpClientExtractor().extract(unbuilt, 1), repos);
  }

  private static List<String> render(List<Fact> facts, List<Repo> repos) throws IOException {
    Fixtures.checkEnvelope(facts, repos, 1, HttpClientExtractor.NAME, HttpClientExtractor.VERSION);
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

  private static final String INTEGRATION =
      "storefront-integration/src/main/java/com/acme/shop/storefront/integration/";

  @Test
  void http1FeignClientByNameCalledByTheCodeThatEnablesIt() {
    assertBoth(
        "method:com.acme.shop.storefront.integration.CatalogClient#product(long) http-call"
            + " GET http://catalog-service/api/products/{id}"
            + " {\"annotation\":\"org.springframework.web.bind.annotation.GetMapping\","
            + "\"baseUrl\":\"http://catalog-service\",\"client\":\"feign\","
            + "\"clientType\":\"com.acme.shop.storefront.integration.CatalogClient\","
            + "\"httpMethod\":\"GET\",\"path\":\"/api/products/{id}\","
            + "\"service\":\"catalog-service\",\"url\":\"http://catalog-service/api/products/{id}\"}"
            + " @storefront:"
            + INTEGRATION
            + "CatalogClient.java#11-11",
        "type:com.acme.shop.storefront.integration.CatalogClient http-client feign"
            + " {\"annotation\":\"org.springframework.cloud.openfeign.FeignClient\"}"
            + " @storefront:"
            + INTEGRATION
            + "CatalogClient.java#8-8",
        // The storefront-app container makes the call, not the storefront-integration jar.
        "type:com.acme.shop.storefront.StorefrontApplication ENABLES"
            + " type:com.acme.shop.storefront.integration.CatalogClient"
            + " {\"annotation\":\"org.springframework.cloud.openfeign.EnableFeignClients\"}"
            + " @storefront:storefront-app/src/main/java/com/acme/shop/storefront/"
            + "StorefrontApplication.java#8-8",
        "type:com.acme.shop.storefront.order.PlaceOrderService INJECTS"
            + " type:com.acme.shop.storefront.integration.CatalogClient"
            + " {\"member\":\"field:com.acme.shop.storefront.order.PlaceOrderService#catalog\"}"
            + " @storefront:storefront-app/src/main/java/com/acme/shop/storefront/order/"
            + "PlaceOrderService.java#19-19");
  }

  @Test
  void http2AndVal1RestTemplateWithItsUrlFromAValueField() {
    String call =
        "method:com.acme.shop.storefront.integration.PaymentsClient#charge("
            + "com.acme.shop.storefront.integration.PaymentRequest) http-call POST %s"
            + " {\"baseUrl\":\"%s\",\"client\":\"rest-template\",\"httpMethod\":\"POST\","
            + "\"path\":\"/api/payments\",%s\"url\":\"%s\","
            + "\"written\":\"${clients.payments.url}/api/payments\"}"
            + " @storefront:"
            + INTEGRATION
            + "PaymentsClient.java#21-21";
    assertBoth(
        call.formatted(
            "http://payments:8080/api/payments",
            "http://payments:8080",
            "",
            "http://payments:8080/api/payments"),
        call.formatted(
            "http://localhost:8082/api/payments",
            "http://localhost:8082",
            "\"profile\":\"local\",",
            "http://localhost:8082/api/payments"));
  }

  @Test
  void http3AndVal2WebClientBuiltFromAConstructorArgument() {
    assertBoth(
        "method:com.acme.shop.fulfilment.StockClient#available(long) http-call"
            + " GET http://catalog-service:8080/api/products/{id}/stock"
            + " {\"baseUrl\":\"http://catalog-service:8080\",\"client\":\"web-client\","
            + "\"httpMethod\":\"GET\",\"path\":\"/api/products/{id}/stock\","
            + "\"url\":\"http://catalog-service:8080/api/products/{id}/stock\","
            + "\"written\":\"${catalog.base-url}/api/products/{id}/stock\"}"
            + " @fulfilment:src/main/java/com/acme/shop/fulfilment/StockClient.java#19-24");
  }

  @Test
  void http4AndExt1RestClientToAnExternalSystemPerProfile() {
    String call =
        "method:com.acme.shop.payments.PaymentGatewayClient#charge(long) http-call POST %s"
            + " {\"baseUrl\":\"%s\",\"client\":\"rest-client\",\"httpMethod\":\"POST\","
            + "\"path\":\"/v1/charges\",%s\"url\":\"%s\","
            + "\"written\":\"${paygate.url}/v1/charges\"}"
            + " @payments:src/main/java/com/acme/shop/payments/PaymentGatewayClient.java#22-27";
    assertBoth(
        call.formatted(
            "https://sandbox.paygate.example.com/v1/charges",
            "https://sandbox.paygate.example.com",
            "",
            "https://sandbox.paygate.example.com/v1/charges"),
        call.formatted(
            "https://api.paygate.example.com/v1/charges",
            "https://api.paygate.example.com",
            "\"profile\":\"prod\",",
            "https://api.paygate.example.com/v1/charges"));
  }

  @Test
  void http5AndVal5RestClientWithAnUnresolvableEnvVar() {
    assertBoth(
        "method:com.acme.shop.storefront.integration.TaxClient#quote(long) http-call"
            + " GET ${TAX_SERVICE_URL}/v2/quotes"
            + " {\"client\":\"rest-client\",\"httpMethod\":\"GET\",\"path\":\"/v2/quotes\","
            + "\"unresolved\":[\"TAX_SERVICE_URL\"],\"written\":\"${clients.tax.url}/v2/quotes\"}"
            + " @storefront:"
            + INTEGRATION
            + "TaxClient.java#18-18");
  }

  @Test
  void noSecretReachesAFact() {
    // PAYGATE_API_KEY is a header of the PayGate client, never part of a URL.
    assertThat(compiled).noneMatch(l -> l.contains("api-key") || l.contains("PAYGATE_API_KEY"));
  }

  @Test
  void bothWaysGiveTheSameFacts() {
    assertThat(parsed).isEqualTo(compiled);
  }
}

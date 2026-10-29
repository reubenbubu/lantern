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
import io.lantern.extract.gateway.GatewayExtractor;
import io.lantern.extract.http.HttpClientExtractor;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.extract.mail.MailExtractor;
import io.lantern.extract.maven.MavenExtractor;
import io.lantern.extract.messaging.MessagingExtractor;
import io.lantern.extract.persistence.PersistenceExtractor;
import io.lantern.extract.redis.RedisExtractor;
import io.lantern.extract.spring.SpringExtractor;
import io.lantern.extract.sql.SqlExtractor;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/** The rules for links between deployable units, on facts written for each case. */
class CrossRepoLinksTest {

  private String commit = "a".repeat(40);

  private final List<Fact> facts = new ArrayList<>();

  // ---------------------------------------------------------------------------------------------
  // Writing the extractors' and the deployable units' facts

  private void add(
      String extractor,
      String subject,
      String predicate,
      FactObject object,
      String attributes,
      String where,
      double confidence) {
    ObjectNode json =
        attributes == null ? FactJson.newObject() : (ObjectNode) FactJson.parse(attributes);
    facts.add(
        Fact.create(
            EntityId.parse(subject),
            predicate,
            object,
            json,
            new Origin(Origin.Kind.STATIC, extractor, "1"),
            evidence(where),
            confidence,
            Validity.from(1)));
  }

  /** Evidence from {@code repo:path#line}. */
  private Evidence evidence(String where) {
    String repo = where.substring(0, where.indexOf(':'));
    String rest = where.substring(where.indexOf(':') + 1);
    Evidence.LineRange lines = null;
    if (rest.contains("#")) {
      int line = Integer.parseInt(rest.substring(rest.indexOf('#') + 1));
      lines = new Evidence.LineRange(line, line);
      rest = rest.substring(0, rest.indexOf('#'));
    }
    return new Evidence(repo, commit, rest, lines, null);
  }

  /** A deployable unit {@code service:g:<artifact>} in a repository, named {@code name}. */
  private void unit(String repo, String artifact, String name) {
    add(
        MavenExtractor.NAME,
        "service:g:" + artifact,
        BuiltInTypes.CONTAINS,
        FactObject.entity(EntityId.parse("module:g:" + artifact)),
        null,
        repo + ":pom.xml",
        1.0);
    add(
        DeployableUnits.NAME,
        "service:g:" + artifact,
        DeployableUnits.DEPLOYABLE_UNIT,
        FactObject.string("spring-boot"),
        null,
        repo + ":pom.xml",
        1.0);
    if (name != null) {
      add(
          DeployableUnits.NAME,
          "service:g:" + artifact,
          DeployableUnits.APPLICATION_NAME,
          FactObject.string(name),
          "{\"module\":\"module:g:" + artifact + "\"}",
          repo + ":src/main/resources/application.yml#2",
          1.0);
    }
  }

  /** A library {@code library:g:<artifact>} that the unit bundles. */
  private void bundled(String repo, String unit, String artifact) {
    add(
        MavenExtractor.NAME,
        "library:g:" + artifact,
        BuiltInTypes.CONTAINS,
        FactObject.entity(EntityId.parse("module:g:" + artifact)),
        null,
        repo + ":" + artifact + "/pom.xml",
        1.0);
    add(
        DeployableUnits.NAME,
        "service:g:" + unit,
        DeployableUnits.BUNDLES,
        FactObject.entity(EntityId.parse("library:g:" + artifact)),
        null,
        repo + ":pom.xml#9",
        1.0);
  }

  /** A class {@code type} in a module, through its package. */
  private void type(String repo, String module, String type) {
    String pkg = type.substring(0, type.lastIndexOf('.'));
    add(
        JavaExtractor.NAME,
        "module:g:" + module,
        BuiltInTypes.CONTAINS,
        FactObject.entity(EntityId.parse("package:g:" + module + ":" + pkg)),
        null,
        repo + ":src/main/java",
        1.0);
    add(
        JavaExtractor.NAME,
        "package:g:" + module + ":" + pkg,
        BuiltInTypes.CONTAINS,
        FactObject.entity(EntityId.parse("type:" + type)),
        null,
        repo + ":src/main/java/" + type.replace('.', '/') + ".java#1",
        1.0);
  }

  private void config(
      String repo, String module, String file, String key, String value, String more) {
    add(
        ConfigExtractor.NAME,
        "module:g:" + module,
        ConfigExtractor.CONFIG_VALUE,
        FactObject.string(value),
        "{\"key\":\"" + key + "\"" + (more == null ? "" : "," + more) + "}",
        repo + ":src/main/resources/" + file + "#3",
        1.0);
  }

  private void endpoint(
      String repo, String controller, String method, String httpMethod, String path) {
    add(
        SpringExtractor.NAME,
        "method:" + controller + "#" + method,
        SpringExtractor.ENDPOINT,
        FactObject.string(httpMethod + " " + path),
        "{\"controller\":\""
            + controller
            + "\",\"httpMethod\":\""
            + httpMethod
            + "\",\"path\":\""
            + path
            + "\"}",
        repo + ":src/main/java/" + controller.replace('.', '/') + ".java#20",
        1.0);
    add(
        SpringExtractor.NAME,
        "type:" + controller,
        SpringExtractor.STEREOTYPE,
        FactObject.string("controller"),
        "{\"rest\":true}",
        repo + ":src/main/java/" + controller.replace('.', '/') + ".java#5",
        1.0);
  }

  private void call(String repo, String method, String object, String attributes, int line) {
    add(
        HttpClientExtractor.NAME,
        "method:" + method,
        HttpClientExtractor.HTTP_CALL,
        FactObject.string(object),
        attributes,
        repo
            + ":src/main/java/"
            + method.substring(0, method.indexOf('#')).replace('.', '/')
            + ".java#"
            + line,
        1.0);
  }

  private void messaging(
      String repo, String method, String predicate, String object, String attributes, int line) {
    add(
        MessagingExtractor.NAME,
        "method:" + method,
        predicate,
        FactObject.string(object),
        attributes,
        repo
            + ":src/main/java/"
            + method.substring(0, method.indexOf('#')).replace('.', '/')
            + ".java#"
            + line,
        1.0);
  }

  private void gate(String repo, String type, String expression) {
    add(
        SpringExtractor.NAME,
        "type:" + type,
        SpringExtractor.PROFILE_GATE,
        FactObject.string(expression),
        "{\"profiles\":[\"" + expression + "\"]}",
        repo + ":src/main/java/" + type.replace('.', '/') + ".java#4",
        1.0);
  }

  private void datasource(String repo, String module, String file, String url, String more) {
    add(
        PersistenceExtractor.NAME,
        "module:g:" + module,
        PersistenceExtractor.DATASOURCE,
        FactObject.string(url),
        "{\"key\":\"spring.datasource.url\",\"technology\":\"PostgreSQL\""
            + (more == null ? "" : "," + more)
            + "}",
        repo + ":src/main/resources/" + file + "#4",
        1.0);
  }

  /** A Redis use ({@code kind}) by a type of a module, with the attributes besides its source. */
  private void redisUse(String repo, String module, String type, String kind, String attributes) {
    add(
        RedisExtractor.NAME,
        "type:" + type,
        RedisExtractor.REDIS_USE,
        FactObject.string(kind),
        attributes,
        repo + ":src/main/java/" + type.replace('.', '/') + ".java#5",
        1.0);
  }

  /** A Redis image a repository's compose file runs. */
  private void redisImage(String repo, String module, String image, String technology) {
    add(
        RedisExtractor.NAME,
        "repository:" + repo,
        RedisExtractor.REDIS_IMAGE,
        FactObject.string(image),
        "{\"technology\":\"" + technology + "\",\"service\":\"redis\"}",
        repo + ":compose.yaml#5",
        1.0);
  }

  private List<Fact> linked() {
    List<Fact> derived = new CrossRepoLinks().derive(facts, 1);
    Vocabulary vocabulary = DeployableUnitsTest.vocabulary();
    derived.forEach(vocabulary::validate);
    return derived;
  }

  /** One line per fact, without its {@code facts} and {@code link} attributes and its evidence. */
  private List<String> derive() {
    return linked().stream()
        .map(
            f -> {
              ObjectNode attributes = f.attributes();
              attributes.remove("facts");
              attributes.remove("link");
              return f.subject()
                  + " "
                  + f.predicate()
                  + " "
                  + f.object().value()
                  + (attributes.isEmpty() ? "" : " " + attributes)
                  + " "
                  + f.confidence();
            })
        .toList();
  }

  private List<String> derive(String predicate) {
    return derive().stream().filter(l -> l.contains(" " + predicate + " ")).toList();
  }

  // ---------------------------------------------------------------------------------------------
  // HTTP

  @Test
  void aFeignClientLinksByItsNameToTheEndpointOfTheUnitWithThatApplicationName() {
    unit("shop", "shop-app", "shop");
    type("shop", "shop-app", "s.App");
    type("shop", "shop-app", "s.CatalogClient");
    add(
        HttpClientExtractor.NAME,
        "type:s.App",
        HttpClientExtractor.ENABLES,
        FactObject.entity(EntityId.parse("type:s.CatalogClient")),
        null,
        "shop:src/main/java/s/App.java#8",
        1.0);
    call(
        "shop",
        "s.CatalogClient#product(long)",
        "GET http://catalog/api/products/{id}",
        "{\"client\":\"feign\",\"httpMethod\":\"GET\",\"path\":\"/api/products/{id}\","
            + "\"service\":\"catalog\",\"url\":\"http://catalog/api/products/{id}\"}",
        11);
    unit("cat", "catalog-service", "catalog");
    type("cat", "catalog-service", "c.ProductController");
    endpoint("cat", "c.ProductController", "product(long)", "GET", "/api/products/{id}");
    endpoint("cat", "c.ProductController", "stock(long)", "GET", "/api/products/{id}/stock");

    assertThat(derive(CrossRepoLinks.CALLS_HTTP))
        .containsExactly(
            "service:g:shop-app CALLS_HTTP service:g:catalog-service {\"client\":\"feign\","
                + "\"endpoint\":\"GET /api/products/{id}\",\"httpMethod\":\"GET\","
                + "\"matchedBy\":\"service-name\",\"path\":\"/api/products/{id}\","
                + "\"protocol\":\"JSON/HTTP\",\"source\":\"method:s.CatalogClient#product(long)\","
                + "\"target\":\"method:c.ProductController#product(long)\","
                + "\"url\":\"http://catalog/api/products/{id}\"} 1.0");
  }

  @Test
  void aFeignClientNothingEnablesCallsNothing() {
    unit("shop", "shop-app", "shop");
    type("shop", "shop-app", "s.CatalogClient");
    call(
        "shop",
        "s.CatalogClient#product(long)",
        "GET http://catalog/api/products/{id}",
        "{\"client\":\"feign\",\"httpMethod\":\"GET\",\"path\":\"/api/products/{id}\","
            + "\"service\":\"catalog\",\"url\":\"http://catalog/api/products/{id}\"}",
        11);
    unit("cat", "catalog-service", "catalog");

    assertThat(derive()).isEmpty();
  }

  @Test
  void aUrlLinksByItsHostAndThePathBehindTheTargetsContextPath() {
    unit("a", "orders", "orders");
    type("a", "orders", "o.PaymentsClient");
    call(
        "a",
        "o.PaymentsClient#charge()",
        "POST http://payments:8080/pay/api/payments",
        "{\"client\":\"rest-template\",\"httpMethod\":\"POST\",\"path\":\"/pay/api/payments\","
            + "\"url\":\"http://payments:8080/pay/api/payments\"}",
        21);
    unit("b", "payments-service", "payments");
    type("b", "payments-service", "p.PaymentController");
    config("b", "payments-service", "application.yml", "server.servlet.context-path", "/pay", null);
    endpoint("b", "p.PaymentController", "charge()", "POST", "/api/payments");
    endpoint("b", "p.PaymentController", "refund()", "POST", "/api/payments/{id}/refund");

    assertThat(derive(CrossRepoLinks.CALLS_HTTP))
        .singleElement()
        .asString()
        .contains("CALLS_HTTP service:g:payments-service")
        .contains("\"endpoint\":\"POST /api/payments\"")
        .contains("\"matchedBy\":\"host\"")
        .endsWith(" 0.9");
  }

  @Test
  void aHostThatIsAUnitsArtifactIdLinksWithLessConfidenceAndWithoutEndpointLess() {
    unit("a", "orders", "orders");
    type("a", "orders", "o.Client");
    call(
        "a",
        "o.Client#go()",
        "GET http://stock-service/api/unknown",
        "{\"client\":\"rest-client\",\"httpMethod\":\"GET\",\"path\":\"/api/unknown\","
            + "\"url\":\"http://stock-service/api/unknown\"}",
        5);
    unit("b", "stock-service", "stock");

    assertThat(derive(CrossRepoLinks.CALLS_HTTP))
        .containsExactly(
            "service:g:orders CALLS_HTTP service:g:stock-service {\"client\":\"rest-client\","
                + "\"httpMethod\":\"GET\",\"matchedBy\":\"artifact-id\",\"path\":\"/api/unknown\","
                + "\"protocol\":\"HTTP\",\"source\":\"method:o.Client#go()\","
                + "\"url\":\"http://stock-service/api/unknown\"} 0.64");
  }

  @Test
  void aHostNoUnitHasIsAnExternalSystemAndOtherProfilesHostsAreItsAlternates() {
    unit("p", "payments", "payments");
    type("p", "payments", "p.Gateway");
    call(
        "p",
        "p.Gateway#charge()",
        "POST https://sandbox.paygate.example.com/v1/charges",
        "{\"client\":\"rest-client\",\"httpMethod\":\"POST\",\"path\":\"/v1/charges\","
            + "\"url\":\"https://sandbox.paygate.example.com/v1/charges\","
            + "\"written\":\"${paygate.url}/v1/charges\"}",
        22);
    call(
        "p",
        "p.Gateway#charge()",
        "POST https://api.paygate.example.com/v1/charges",
        "{\"client\":\"rest-client\",\"httpMethod\":\"POST\",\"path\":\"/v1/charges\","
            + "\"profile\":\"prod\",\"url\":\"https://api.paygate.example.com/v1/charges\","
            + "\"written\":\"${paygate.url}/v1/charges\"}",
        22);

    assertThat(derive())
        .containsExactly(
            "service:g:payments CALLS_HTTP external-system:api.paygate.example.com"
                + " {\"alternates\":[\"external-system:sandbox.paygate.example.com\"],"
                + "\"client\":\"rest-client\",\"httpMethod\":\"POST\",\"matchedBy\":\"external\","
                + "\"path\":\"/v1/charges\",\"profile\":\"prod\",\"protocol\":\"HTTPS\","
                + "\"source\":\"method:p.Gateway#charge()\","
                + "\"url\":\"https://api.paygate.example.com/v1/charges\"} 1.0",
            "service:g:payments CALLS_HTTP external-system:sandbox.paygate.example.com"
                + " {\"alternates\":[\"external-system:api.paygate.example.com\"],"
                + "\"client\":\"rest-client\",\"exceptProfiles\":[\"prod\"],\"httpMethod\":\"POST\","
                + "\"matchedBy\":\"external\",\"path\":\"/v1/charges\",\"protocol\":\"HTTPS\","
                + "\"source\":\"method:p.Gateway#charge()\","
                + "\"url\":\"https://sandbox.paygate.example.com/v1/charges\"} 1.0");
  }

  @Test
  void aHostWithoutADomainThatNamesNoUnitIsLeftOpenNotAnExternalSystem() {
    unit("a", "orders", "orders");
    type("a", "orders", "o.Client");
    call(
        "a",
        "o.Client#items()",
        "GET http://inventory-svc/items",
        "{\"client\":\"rest-client\",\"httpMethod\":\"GET\",\"path\":\"/items\","
            + "\"url\":\"http://inventory-svc/items\"}",
        5);
    unit("i", "inventory", "inventory");
    type("i", "inventory", "i.ItemController");
    endpoint("i", "i.ItemController", "items()", "GET", "/items");

    assertThat(derive())
        .containsExactly(
            "service:g:orders unresolved-link GET http://inventory-svc/items"
                + " {\"candidates\":[\"service:g:inventory\"],\"kind\":\"http\","
                + "\"reason\":\"unknown-host\",\"source\":\"method:o.Client#items()\"} 1.0");
  }

  @Test
  void aLinkSwitchedOffUnderAProfileNamesThatProfile() {
    unit("s", "shop", "shop");
    type("s", "shop", "s.Publisher");
    gate("s", "s.Publisher", "!local");
    messaging(
        "s",
        "s.Publisher#send()",
        MessagingExtractor.SEND,
        "orders",
        "{\"broker\":\"kafka\",\"destinationKind\":\"topic\"}",
        4);
    unit("f", "fulfilment", "fulfilment");
    type("f", "fulfilment", "f.Listener");
    messaging(
        "f",
        "f.Listener#on()",
        MessagingExtractor.LISTENER,
        "orders",
        "{\"broker\":\"kafka\",\"destinationKind\":\"topic\"}",
        3);

    assertThat(derive(CrossRepoLinks.SENDS_MESSAGES_TO))
        .singleElement()
        .asString()
        .contains("\"exceptProfiles\":[\"local\"]");
  }

  @Test
  void aCallToThisMachineLinksByItsPathWhenOneUnitServesIt() {
    unit("a", "orders", "orders");
    type("a", "orders", "o.Client");
    call(
        "a",
        "o.Client#go()",
        "POST http://localhost:8082/api/payments",
        "{\"client\":\"rest-template\",\"httpMethod\":\"POST\",\"path\":\"/api/payments\","
            + "\"profile\":\"local\",\"url\":\"http://localhost:8082/api/payments\","
            + "\"written\":\"${payments.url}/api/payments\"}",
        5);
    unit("b", "payments", "payments");
    type("b", "payments", "p.PaymentController");
    endpoint("b", "p.PaymentController", "charge()", "POST", "/api/payments");

    assertThat(derive(CrossRepoLinks.CALLS_HTTP))
        .singleElement()
        .asString()
        .contains("CALLS_HTTP service:g:payments")
        .contains("\"matchedBy\":\"path\"")
        .contains("\"profile\":\"local\"")
        .endsWith(" 0.6");
  }

  @Test
  void aUrlFromAnUnresolvedPlaceholderIsLeftForTheResolverWithTheUnitsServingItsPath() {
    unit("a", "orders", "orders");
    type("a", "orders", "o.TaxClient");
    call(
        "a",
        "o.TaxClient#quote()",
        "GET ${TAX_SERVICE_URL}/v2/quotes",
        "{\"client\":\"rest-client\",\"httpMethod\":\"GET\",\"path\":\"/v2/quotes\","
            + "\"unresolved\":[\"TAX_SERVICE_URL\"],\"written\":\"${clients.tax.url}/v2/quotes\"}",
        18);
    unit("t", "tax", "tax");
    type("t", "tax", "t.TaxController");
    endpoint("t", "t.TaxController", "quotes()", "GET", "/v2/quotes");

    assertThat(derive())
        .containsExactly(
            "service:g:orders unresolved-link GET ${TAX_SERVICE_URL}/v2/quotes"
                + " {\"candidates\":[\"service:g:tax\"],\"kind\":\"http\","
                + "\"reason\":\"unresolved-placeholder\",\"source\":\"method:o.TaxClient#quote()\","
                + "\"unresolved\":[\"TAX_SERVICE_URL\"]} 1.0");
  }

  @Test
  void twoUnitsWithTheCalledNameAreAmbiguous() {
    unit("a", "orders", "orders");
    type("a", "orders", "o.Client");
    call(
        "a",
        "o.Client#go()",
        "GET http://stock/x",
        "{\"client\":\"rest-client\",\"httpMethod\":\"GET\",\"path\":\"/x\",\"url\":\"http://stock/x\"}",
        5);
    unit("b", "stock-a", "stock");
    unit("c", "stock-b", "stock");

    assertThat(derive())
        .singleElement()
        .asString()
        .contains("unresolved-link")
        .contains("\"candidates\":[\"service:g:stock-a\",\"service:g:stock-b\"]")
        .contains("\"reason\":\"ambiguous\"");
  }

  @Test
  void aLibrarysCallResolvesWithTheConfigOfEachUnitBundlingIt() {
    type("lib", "client-lib", "l.StockClient");
    call(
        "lib",
        "l.StockClient#stock()",
        "GET ${stock.url}/api/stock",
        "{\"client\":\"web-client\",\"httpMethod\":\"GET\",\"path\":\"/api/stock\","
            + "\"unresolved\":[\"stock.url\"],\"written\":\"${stock.url}/api/stock\"}",
        7);
    unit("a", "shop", "shop");
    bundled("a", "shop", "client-lib");
    config("a", "shop", "application.yml", "stock.url", "http://stock-eu:8080", null);
    unit("b", "admin", "admin");
    bundled("b", "admin", "client-lib");
    config("b", "admin", "application.yml", "stock.url", "http://stock-us:8080", null);
    unit("s", "stock-eu", "stock-eu");
    unit("t", "stock-us", "stock-us");

    assertThat(derive(CrossRepoLinks.CALLS_HTTP))
        .extracting(l -> l.substring(0, l.indexOf(" {")))
        .containsExactly(
            "service:g:admin CALLS_HTTP service:g:stock-us",
            "service:g:shop CALLS_HTTP service:g:stock-eu");
  }

  // ---------------------------------------------------------------------------------------------
  // Messaging

  // ---------------------------------------------------------------------------------------------
  // Gateway routes

  private void route(String repo, String subject, String id, String attributes) {
    add(
        GatewayExtractor.NAME,
        subject,
        GatewayExtractor.ROUTE,
        FactObject.string(id),
        attributes,
        repo + ":src/main/java/gw/Routes.java#30",
        1.0);
  }

  @Test
  void codeRoutesLinkByTheirLoadBalancedNameWithTheirProtocol() {
    unit("gw", "edge", "edge");
    type("gw", "edge", "gw.Routes");
    route(
        "gw",
        "method:gw.Routes#routes()",
        "users",
        "{\"definedIn\":\"java\",\"uri\":\"lb://users\",\"paths\":[\"/api/users/**\"],"
            + "\"methods\":[\"GET\"]}");
    route(
        "gw",
        "method:gw.Routes#routes()",
        "notify",
        "{\"definedIn\":\"java\",\"uri\":\"lb:ws://notifications\",\"paths\":[\"/ws/**\"]}");
    // A route the gateway answers itself is no line.
    route(
        "gw",
        "method:gw.Routes#routes()",
        "fallback",
        "{\"definedIn\":\"java\",\"uri\":\"forward:/fallback\"}");
    unit("u", "users-service", "users");
    unit("n", "notifications-service", "notifications");

    assertThat(derive(CrossRepoLinks.CALLS_HTTP))
        .containsExactly(
            "service:g:edge CALLS_HTTP service:g:notifications-service"
                + " {\"matchedBy\":\"service-name\",\"paths\":[\"/ws/**\"],"
                + "\"protocol\":\"WebSocket\",\"route\":\"notify\","
                + "\"source\":\"method:gw.Routes#routes()\",\"url\":\"lb:ws://notifications\"} 1.0",
            "service:g:edge CALLS_HTTP service:g:users-service"
                + " {\"matchedBy\":\"service-name\",\"methods\":[\"GET\"],"
                + "\"paths\":[\"/api/users/**\"],\"protocol\":\"HTTP\",\"route\":\"users\","
                + "\"source\":\"method:gw.Routes#routes()\",\"url\":\"lb://users\"} 1.0");
  }

  @Test
  void configRoutesToThisMachineLinkByPortAndUnroutedEndpointsAreRecorded() {
    unit("gw", "edge", "edge");
    config("gw", "edge", "application.yml", "server.port", "8080", null);
    route(
        "gw",
        "module:g:edge",
        "orders",
        "{\"definedIn\":\"config\",\"uri\":\"http://localhost:8082\","
            + "\"paths\":[\"/orders/**\"],\"filters\":[\"StripPrefix=1\",\"PrefixPath=/api\"]}");
    route(
        "gw",
        "module:g:edge",
        "partner",
        "{\"definedIn\":\"config\",\"uri\":\"https://partner.example.com\"}");
    route(
        "gw",
        "module:g:edge",
        "compose",
        "{\"definedIn\":\"config\",\"uri\":\"http://billing:8080\"}");
    unit("o", "orders-service", "orders");
    config("o", "orders-service", "application.yml", "server.port", "8082", null);
    type("o", "orders-service", "o.OrderController");
    endpoint("o", "o.OrderController", "list()", "GET", "/api/orders");
    endpoint("o", "o.OrderController", "one(long)", "GET", "/api/orders/{id}");
    endpoint("o", "o.OrderController", "internal(long)", "GET", "/internal/orders/{id}");
    unit("r", "reports", "reports");
    type("r", "reports", "r.ReportJob");
    call(
        "r",
        "r.ReportJob#run()",
        "GET http://orders/internal/orders/1",
        "{\"client\":\"rest-client\",\"httpMethod\":\"GET\",\"path\":\"/internal/orders/1\","
            + "\"url\":\"http://orders/internal/orders/1\"}",
        12);

    assertThat(derive(CrossRepoLinks.CALLS_HTTP))
        .contains(
            "service:g:edge CALLS_HTTP service:g:orders-service {\"matchedBy\":\"port\","
                + "\"paths\":[\"/orders/**\"],\"protocol\":\"HTTP\",\"route\":\"orders\","
                + "\"source\":\"module:g:edge\",\"url\":\"http://localhost:8082\"} 0.8",
            "service:g:edge CALLS_HTTP external-system:partner.example.com"
                + " {\"matchedBy\":\"external\",\"protocol\":\"HTTPS\",\"route\":\"partner\","
                + "\"source\":\"module:g:edge\",\"url\":\"https://partner.example.com\"} 1.0");
    assertThat(derive(CrossRepoLinks.UNRESOLVED_LINK))
        .containsExactly(
            "service:g:edge unresolved-link http://billing:8080 {\"kind\":\"route\","
                + "\"reason\":\"unknown-host\",\"source\":\"module:g:edge\"} 1.0");
    // /orders/** reaches /api/** behind StripPrefix and PrefixPath; the internal endpoint is
    // reached only by another unit's call.
    assertThat(derive(CrossRepoLinks.UNROUTED_ENDPOINT))
        .containsExactly(
            "service:g:edge unrouted-endpoint GET /internal/orders/{id}"
                + " {\"calledBy\":[\"service:g:reports\"],"
                + "\"endpoint\":\"method:o.OrderController#internal(long)\","
                + "\"target\":\"service:g:orders-service\"} 1.0");
  }

  @Test
  void routePatternsTakeOnlyTheEndpointsTheyMatch() {
    assertThat(RouteLinker.matches("/api/organizations/{id}", "/api/organizations/*")).isTrue();
    assertThat(RouteLinker.matches("/api/organizations/{id}/preview", "/api/organizations/*"))
        .isFalse();
    // A literal of the pattern does not take a variable of the endpoint.
    assertThat(
            RouteLinker.matches(
                "/api/organizations/{id}/preview", "/api/organizations/invitations/**"))
        .isFalse();
    assertThat(RouteLinker.matches("/api/files", "/api/files/**")).isTrue();
    assertThat(RouteLinker.matches("/v2/a/b", "/v*/**")).isTrue();
    assertThat(RouteLinker.matches("/api/files/{id}/x", "/api/{*rest}")).isTrue();
  }

  @Test
  void aRabbitSendReachesTheListenersOfQueuesBoundWithMatchingWildcards() {
    unit("s", "shop", "shop");
    type("s", "shop", "s.Publisher");
    messaging(
        "s",
        "s.Publisher#created()",
        MessagingExtractor.SEND,
        "shop.orders",
        "{\"broker\":\"rabbitmq\",\"destinationKind\":\"exchange\",\"routingKey\":\"order.created\"}",
        16);
    unit("p", "payments", "payments");
    type("p", "payments", "p.RabbitConfig");
    type("p", "payments", "p.Listener");
    messaging(
        "p",
        "p.RabbitConfig#binding()",
        MessagingExtractor.BINDING,
        "payments.order-events",
        "{\"broker\":\"rabbitmq\",\"exchange\":\"shop.orders\",\"exchangeType\":\"topic\","
            + "\"routingKey\":\"order.*\"}",
        33);
    messaging(
        "p",
        "p.RabbitConfig#refunds()",
        MessagingExtractor.BINDING,
        "payments.refunds",
        "{\"broker\":\"rabbitmq\",\"exchange\":\"shop.orders\",\"exchangeType\":\"topic\","
            + "\"routingKey\":\"refund.#\"}",
        38);
    messaging(
        "p",
        "p.Listener#orderEvent()",
        MessagingExtractor.LISTENER,
        "payments.order-events",
        "{\"broker\":\"rabbitmq\",\"destinationKind\":\"queue\"}",
        15);
    messaging(
        "p",
        "p.Listener#refund()",
        MessagingExtractor.LISTENER,
        "payments.refunds",
        "{\"broker\":\"rabbitmq\",\"destinationKind\":\"queue\"}",
        20);

    assertThat(derive())
        .containsExactly(
            "exchange:rabbitmq:shop.orders ROUTES_TO queue:rabbitmq:payments.order-events"
                + " {\"bindingKey\":\"order.*\",\"exchangeType\":\"topic\",\"protocol\":\"AMQP\","
                + "\"source\":\"method:p.RabbitConfig#binding()\"} 1.0",
            "exchange:rabbitmq:shop.orders ROUTES_TO queue:rabbitmq:payments.refunds"
                + " {\"bindingKey\":\"refund.#\",\"exchangeType\":\"topic\",\"protocol\":\"AMQP\","
                + "\"source\":\"method:p.RabbitConfig#refunds()\"} 1.0",
            "service:g:payments CONSUMES queue:rabbitmq:payments.order-events"
                + " {\"protocol\":\"AMQP\",\"source\":\"method:p.Listener#orderEvent()\"} 1.0",
            "service:g:payments CONSUMES queue:rabbitmq:payments.refunds"
                + " {\"protocol\":\"AMQP\",\"source\":\"method:p.Listener#refund()\"} 1.0",
            "service:g:shop PUBLISHES exchange:rabbitmq:shop.orders {\"protocol\":\"AMQP\","
                + "\"routingKey\":\"order.created\",\"source\":\"method:s.Publisher#created()\"} 1.0",
            "service:g:shop SENDS_MESSAGES_TO service:g:payments {\"bindingKey\":\"order.*\","
                + "\"destination\":\"exchange:rabbitmq:shop.orders\",\"protocol\":\"AMQP\","
                + "\"queue\":\"queue:rabbitmq:payments.order-events\","
                + "\"routingKey\":\"order.created\",\"source\":\"method:s.Publisher#created()\","
                + "\"target\":\"method:p.Listener#orderEvent()\"} 1.0");
  }

  @Test
  void directAndFanoutExchangesRouteAsTheirTypeSaysAndAnUnknownKeyIsLeftOpen() {
    unit("s", "shop", "shop");
    type("s", "shop", "s.Publisher");
    messaging(
        "s",
        "s.Publisher#direct()",
        MessagingExtractor.SEND,
        "direct.x",
        "{\"broker\":\"rabbitmq\",\"destinationKind\":\"exchange\",\"routingKey\":\"order.*\"}",
        10);
    messaging(
        "s",
        "s.Publisher#fanout()",
        MessagingExtractor.SEND,
        "fanout.x",
        "{\"broker\":\"rabbitmq\",\"destinationKind\":\"exchange\",\"routingKey\":\"k\","
            + "\"unknown\":[\"routingKey\"]}",
        11);
    messaging(
        "s",
        "s.Publisher#unknown()",
        MessagingExtractor.SEND,
        "direct.x",
        "{\"broker\":\"rabbitmq\",\"destinationKind\":\"exchange\",\"routingKey\":\"k\","
            + "\"unknown\":[\"routingKey\"]}",
        12);
    unit("w", "worker", "worker");
    type("w", "worker", "w.Config");
    type("w", "worker", "w.Listener");
    messaging(
        "w",
        "w.Config#direct()",
        MessagingExtractor.BINDING,
        "q.direct",
        "{\"broker\":\"rabbitmq\",\"exchange\":\"direct.x\",\"exchangeType\":\"direct\","
            + "\"routingKey\":\"order.created\"}",
        5);
    messaging(
        "w",
        "w.Config#fanout()",
        MessagingExtractor.BINDING,
        "q.fanout",
        "{\"broker\":\"rabbitmq\",\"exchange\":\"fanout.x\",\"exchangeType\":\"fanout\"}",
        6);
    messaging(
        "w",
        "w.Listener#direct()",
        MessagingExtractor.LISTENER,
        "q.direct",
        "{\"broker\":\"rabbitmq\",\"destinationKind\":\"queue\"}",
        8);
    messaging(
        "w",
        "w.Listener#fanout()",
        MessagingExtractor.LISTENER,
        "q.fanout",
        "{\"broker\":\"rabbitmq\",\"destinationKind\":\"queue\"}",
        9);

    // A direct exchange takes the key literally: order.* does not match order.created.
    assertThat(derive(CrossRepoLinks.SENDS_MESSAGES_TO))
        .singleElement()
        .asString()
        .contains("\"destination\":\"exchange:rabbitmq:fanout.x\"");
    assertThat(derive(CrossRepoLinks.UNRESOLVED_LINK))
        .containsExactly(
            "service:g:shop unresolved-link direct.x {\"candidates\":[\"service:g:worker\"],"
                + "\"kind\":\"message\",\"reason\":\"unknown-routing-key\","
                + "\"source\":\"method:s.Publisher#unknown()\"} 1.0");
  }

  @Test
  void aSendInALibraryBundledBySeveralUnitsIsTheirsWhoCallIt() {
    type("lib", "messaging-lib", "l.Messages");
    messaging(
        "lib",
        "l.Messages#send()",
        MessagingExtractor.SEND,
        "notify.send",
        "{\"broker\":\"rabbitmq\",\"destinationKind\":\"queue\"}",
        30);
    // Nothing calls the heartbeat, so it runs in every unit bundling the library.
    messaging(
        "lib",
        "l.Messages#heartbeat()",
        MessagingExtractor.SEND,
        "heartbeat",
        "{\"broker\":\"rabbitmq\",\"destinationKind\":\"queue\"}",
        40);
    calls("lib", "l.Messages#toUser()", "l.Messages#send()");
    unit("a", "shop", "shop");
    bundled("a", "shop", "messaging-lib");
    type("a", "shop", "s.Orders");
    calls("a", "s.Orders#place()", "l.Messages#toUser()");
    unit("b", "admin", "admin");
    bundled("b", "admin", "messaging-lib");

    assertThat(derive(CrossRepoLinks.PUBLISHES))
        .extracting(l -> l.substring(0, l.indexOf(" {")))
        .containsExactly(
            "service:g:admin PUBLISHES queue:rabbitmq:heartbeat",
            "service:g:shop PUBLISHES queue:rabbitmq:heartbeat",
            "service:g:shop PUBLISHES queue:rabbitmq:notify.send");
  }

  private void calls(String repo, String from, String to) {
    add(
        JavaExtractor.NAME,
        "method:" + from,
        BuiltInTypes.CALLS,
        FactObject.entity(EntityId.parse("method:" + to)),
        null,
        repo
            + ":src/main/java/"
            + from.substring(0, from.indexOf('#')).replace('.', '/')
            + ".java#9",
        1.0);
  }

  @Test
  void kafkaTopicsMatchByNameOrByAListenersPatternAndJmsByDestination() {
    unit("p", "payments", "payments");
    type("p", "payments", "p.Publisher");
    messaging(
        "p",
        "p.Publisher#publish()",
        MessagingExtractor.SEND,
        "payments.completed",
        "{\"broker\":\"kafka\",\"destinationKind\":\"topic\"}",
        20);
    messaging(
        "p",
        "p.Publisher#ship()",
        MessagingExtractor.SEND,
        "shipments",
        "{\"broker\":\"jms\",\"destinationKind\":\"destination\"}",
        21);
    unit("f", "fulfilment", "fulfilment");
    type("f", "fulfilment", "f.Listener");
    messaging(
        "f",
        "f.Listener#all()",
        MessagingExtractor.LISTENER,
        "payments\\..*",
        "{\"broker\":\"kafka\",\"destinationKind\":\"topic-pattern\",\"group\":\"f\"}",
        15);
    messaging(
        "f",
        "f.Listener#shipments()",
        MessagingExtractor.LISTENER,
        "shipments",
        "{\"broker\":\"jms\",\"destinationKind\":\"destination\"}",
        16);

    assertThat(derive(CrossRepoLinks.SENDS_MESSAGES_TO))
        .containsExactlyInAnyOrder(
            "service:g:payments SENDS_MESSAGES_TO service:g:fulfilment"
                + " {\"destination\":\"queue:jms:shipments\",\"protocol\":\"JMS\","
                + "\"source\":\"method:p.Publisher#ship()\","
                + "\"target\":\"method:f.Listener#shipments()\"} 1.0",
            "service:g:payments SENDS_MESSAGES_TO service:g:fulfilment"
                + " {\"destination\":\"topic:kafka:payments.completed\",\"pattern\":\"payments\\\\..*\","
                + "\"protocol\":\"Kafka\",\"source\":\"method:p.Publisher#publish()\","
                + "\"target\":\"method:f.Listener#all()\"} 0.9");
    assertThat(derive(CrossRepoLinks.CONSUMES))
        .contains(
            "service:g:fulfilment CONSUMES topic:kafka:payments.completed {\"group\":\"f\","
                + "\"pattern\":\"payments\\\\..*\",\"protocol\":\"Kafka\","
                + "\"source\":\"method:f.Listener#all()\"} 0.9");
  }

  @Test
  void codeGatedByAProfileLinksOnlyUnderThatProfile() {
    unit("s", "shop", "shop");
    type("s", "shop", "s.AuditTrail");
    gate("s", "s.AuditTrail", "audit");
    messaging(
        "s",
        "s.AuditTrail#placed()",
        MessagingExtractor.SEND,
        "audit.orders",
        "{\"broker\":\"jms\",\"destinationKind\":\"destination\"}",
        19);
    unit("f", "fulfilment", "fulfilment");
    type("f", "fulfilment", "f.AuditListener");
    messaging(
        "f",
        "f.AuditListener#placed()",
        MessagingExtractor.LISTENER,
        "audit.orders",
        "{\"broker\":\"jms\",\"destinationKind\":\"destination\"}",
        14);

    assertThat(derive(CrossRepoLinks.SENDS_MESSAGES_TO))
        .singleElement()
        .asString()
        .contains("\"profile\":\"audit\"");
    assertThat(derive(CrossRepoLinks.CONSUMES))
        .singleElement()
        .asString()
        .doesNotContain("profile");
  }

  // ---------------------------------------------------------------------------------------------
  // Data, brokers and infrastructure

  @Test
  void unitsOnOneDatabaseShareItAndReadingAnothersTableAccessesItsData() {
    unit("s", "shop", "shop");
    datasource(
        "s",
        "shop",
        "application.yml",
        "jdbc:postgresql://shop-db:5432/shop",
        "\"server\":\"shop-db:5432\",\"database\":\"shop\"");
    datasource(
        "s",
        "shop",
        "application-local.yml",
        "jdbc:postgresql://localhost:5432/shop",
        "\"server\":\"localhost:5432\",\"database\":\"shop\",\"profile\":\"local\"");
    config("s", "shop", "application.yml", "spring.application.name", "shop", null);
    add(
        SqlExtractor.NAME,
        "module:g:shop",
        BuiltInTypes.CONTAINS,
        FactObject.entity(EntityId.parse("schema:g:shop")),
        null,
        "s:src/main/resources/db/V1.sql",
        1.0);
    add(
        SqlExtractor.NAME,
        "schema:g:shop",
        BuiltInTypes.CONTAINS,
        FactObject.entity(EntityId.parse("table:g:shop:orders")),
        null,
        "s:src/main/resources/db/V1.sql#1",
        1.0);
    unit("p", "payments", "payments");
    config("p", "payments", "application.properties", "spring.application.name", "payments", null);
    datasource(
        "p",
        "payments",
        "application.properties",
        "jdbc:postgresql://shop-db:5432/shop",
        "\"server\":\"shop-db:5432\",\"database\":\"shop\"");
    type("p", "payments", "p.SettlementJob");
    add(
        PersistenceExtractor.NAME,
        "method:p.SettlementJob#settle()",
        PersistenceExtractor.READS_TABLE,
        FactObject.entity(EntityId.parse("table:g:payments:orders")),
        "{\"statement\":\"select\"}",
        "p:src/main/java/p/SettlementJob.java#22",
        1.0);

    assertThat(derive())
        .containsExactly(
            "database:localhost:5432/shop technology PostgreSQL 1.0",
            "database:shop-db:5432/shop technology PostgreSQL 1.0",
            "service:g:payments ACCESSES_DATA_OF service:g:shop {\"database\":\"database:shop-db:5432/shop\","
                + "\"exceptProfiles\":[\"local\"],\"protocol\":\"JDBC\",\"reads\":[\"table:g:shop:orders\"]} 1.0",
            "service:g:payments USES_DATABASE database:shop-db:5432/shop {\"key\":\"spring.datasource.url\","
                + "\"protocol\":\"JDBC\",\"technology\":\"PostgreSQL\",\"url\":\"jdbc:postgresql://shop-db:5432/shop\"} 1.0",
            "service:g:shop USES_DATABASE database:localhost:5432/shop {\"key\":\"spring.datasource.url\","
                + "\"profile\":\"local\",\"protocol\":\"JDBC\",\"schemas\":[\"schema:g:shop\"],"
                + "\"technology\":\"PostgreSQL\",\"url\":\"jdbc:postgresql://localhost:5432/shop\"} 1.0",
            "service:g:shop USES_DATABASE database:shop-db:5432/shop {\"exceptProfiles\":[\"local\"],"
                + "\"key\":\"spring.datasource.url\",\"protocol\":\"JDBC\",\"schemas\":[\"schema:g:shop\"],\"technology\":\"PostgreSQL\","
                + "\"url\":\"jdbc:postgresql://shop-db:5432/shop\"} 1.0");
  }

  @Test
  void aDatasourceWithAPlaceholderIsTheUnitsOwnDatabase() {
    for (String name : List.of("auth", "files")) {
      unit(name, name, name);
      config(name, name, "application.properties", "spring.application.name", name, null);
      datasource(
          name,
          name,
          "application.properties",
          "jdbc:postgresql://${db.host}:${db.port}/${db.name}",
          "\"server\":\"${db.host}:${db.port}\",\"database\":\"${db.name}\","
              + "\"unresolved\":[\"db.host\",\"db.port\",\"db.name\"]");
    }
    unit("m", "main", "main");
    config("m", "main", "application.properties", "spring.application.name", "main", null);
    datasource(
        "m",
        "main",
        "application.properties",
        "jdbc:postgresql://${db.host}:5432/orders",
        "\"server\":\"${db.host}:5432\",\"database\":\"orders\",\"unresolved\":[\"db.host\"]");

    assertThat(derive(CrossRepoLinks.USES_DATABASE))
        .extracting(l -> l.substring(0, l.indexOf(" {")))
        .containsExactlyInAnyOrder(
            "service:g:auth USES_DATABASE database:g:auth/postgresql:g:auth",
            "service:g:files USES_DATABASE database:g:files/postgresql:g:files",
            "service:g:main USES_DATABASE database:g:main/postgresql:orders");
    assertThat(derive(CrossRepoLinks.UNRESOLVED_LINK)).isEmpty();
  }

  private void ownDatasource(
      String repo, String module, String url, String technology, String key) {
    add(
        PersistenceExtractor.NAME,
        "module:g:" + module,
        PersistenceExtractor.DATASOURCE,
        FactObject.string(url),
        "{\"key\":\""
            + key
            + "\""
            + (technology == null ? "" : ",\"technology\":\"" + technology + "\"")
            + ",\"unresolved\":[\"db.host\"]}",
        repo + ":src/main/resources/application.properties#4",
        1.0);
  }

  @Test
  void aUnitsOwnDatabaseGetsADisplayNameWhateverItsTechnologyOrKey() {
    unit("a", "auth", "auth-service");
    config("a", "auth", "application.properties", "x.y", "z", null);
    ownDatasource(
        "a", "auth", "jdbc:postgresql://${db.host}/x", "PostgreSQL", "spring.datasource.url");
    unit("f", "files", "files");
    config("f", "files", "application.properties", "x.y", "z", null);
    ownDatasource(
        "f", "files", "jdbc:sqlserver://${db.host}", "SQL Server", "spring.datasource.url");
    unit("n", "notes", "notes");
    config("n", "notes", "application.properties", "x.y", "z", null);
    ownDatasource("n", "notes", "jdbc:x://${db.host}", null, "spring.datasource.url");
    unit("r", "reports", "reports");
    config("r", "reports", "application.properties", "x.y", "z", null);
    ownDatasource(
        "r", "reports", "jdbc:postgresql://${db.host}/x", "PostgreSQL", "app.reporting.url");
    unit("m", "main", "main");
    config("m", "main", "application.properties", "x.y", "z", null);
    datasource(
        "m",
        "main",
        "application.properties",
        "jdbc:postgresql://${db.host}:5432/orders",
        "\"server\":\"${db.host}:5432\",\"database\":\"orders\",\"unresolved\":[\"db.host\"]");
    // Embedded: the unit's own, named by its database.
    unit("e", "embedded", "embedded");
    config("e", "embedded", "application.properties", "x.y", "z", null);
    datasource(
        "e", "embedded", "application.properties", "jdbc:h2:mem:cache", "\"database\":\"cache\"");

    assertThat(derive("display-name"))
        .containsExactlyInAnyOrder(
            "database:g:auth/postgresql:g:auth display-name auth-service database 1.0",
            "database:g:files/sql-server:g:files display-name files database 1.0",
            "database:g:notes/g:notes display-name notes database 1.0",
            "database:g:reports/postgresql:g:reports@app.reporting.url display-name"
                + " reports database (app.reporting.url) 1.0",
            "database:g:main/postgresql:orders display-name orders 1.0",
            "database:g:embedded/postgresql:cache display-name cache 1.0");
  }

  @Test
  void aDatabaseOnAServerHasNoDisplayName() {
    unit("s", "shop", "shop");
    datasource(
        "s",
        "shop",
        "application.yml",
        "jdbc:postgresql://shop-db:5432/shop",
        "\"server\":\"shop-db:5432\",\"database\":\"shop\"");

    assertThat(derive("display-name")).isEmpty();
  }

  @Test
  void twoPlaceholderDatasourcesOfOneUnitAreSeparateDatabases() {
    unit("a", "app", "app");
    config("a", "app", "application.properties", "spring.application.name", "app", null);
    datasource(
        "a",
        "app",
        "application.properties",
        "jdbc:postgresql://${db.host}/${db.name}",
        "\"unresolved\":[\"db.host\",\"db.name\"]");
    add(
        PersistenceExtractor.NAME,
        "module:g:app",
        PersistenceExtractor.DATASOURCE,
        FactObject.string("jdbc:postgresql://${rep.host}/${rep.name}"),
        "{\"key\":\"app.reporting.url\",\"technology\":\"PostgreSQL\","
            + "\"unresolved\":[\"rep.host\",\"rep.name\"]}",
        "a:src/main/resources/application.properties#9",
        1.0);

    assertThat(derive(CrossRepoLinks.USES_DATABASE))
        .extracting(l -> l.substring(0, l.indexOf(" {")))
        .containsExactlyInAnyOrder(
            "service:g:app USES_DATABASE database:g:app/postgresql:g:app",
            "service:g:app USES_DATABASE database:g:app/postgresql:g:app@app.reporting.url");
  }

  @Test
  void aTableIsAnothersDataOnlyWhereItsMigrationsRun() {
    unit("s", "shop", "shop");
    // shop's tables live in shop-db, where its migrations run; it also reaches a reporting copy.
    datasource(
        "s",
        "shop",
        "application.yml",
        "jdbc:postgresql://shop-db:5432/shop",
        "\"server\":\"shop-db:5432\",\"database\":\"shop\"");
    add(
        PersistenceExtractor.NAME,
        "module:g:shop",
        PersistenceExtractor.DATASOURCE,
        FactObject.string("jdbc:postgresql://report-db:5432/report"),
        "{\"key\":\"app.reporting.url\",\"server\":\"report-db:5432\",\"database\":\"report\"}",
        "s:src/main/resources/application.yml#9",
        1.0);
    config("s", "shop", "application.yml", "spring.application.name", "shop", null);
    add(
        SqlExtractor.NAME,
        "module:g:shop",
        BuiltInTypes.CONTAINS,
        FactObject.entity(EntityId.parse("schema:g:shop")),
        null,
        "s:src/main/resources/db/V1.sql",
        1.0);
    add(
        SqlExtractor.NAME,
        "schema:g:shop",
        BuiltInTypes.CONTAINS,
        FactObject.entity(EntityId.parse("table:g:shop:orders")),
        null,
        "s:src/main/resources/db/V1.sql#1",
        1.0);
    unit("r", "reports", "reports");
    config("r", "reports", "application.yml", "spring.application.name", "reports", null);
    datasource(
        "r",
        "reports",
        "application.yml",
        "jdbc:postgresql://report-db:5432/report",
        "\"server\":\"report-db:5432\",\"database\":\"report\"");
    type("r", "reports", "r.Job");
    add(
        PersistenceExtractor.NAME,
        "method:r.Job#run()",
        PersistenceExtractor.READS_TABLE,
        FactObject.entity(EntityId.parse("table:g:reports:orders")),
        "{\"statement\":\"select\"}",
        "r:src/main/java/r/Job.java#5",
        1.0);

    // Both use report-db, but shop's orders table is in shop-db.
    assertThat(derive(CrossRepoLinks.ACCESSES_DATA_OF)).isEmpty();
    assertThat(derive(CrossRepoLinks.USES_DATABASE))
        .anyMatch(l -> l.startsWith("service:g:shop USES_DATABASE database:report-db:5432/report "))
        .anyMatch(
            l -> l.startsWith("service:g:reports USES_DATABASE database:report-db:5432/report "));
  }

  @Test
  void unitsOnOneRedisServerShareItWithTheProtocolTechnologyAndWhatTheyStore() {
    unit("bff", "bff", "bff");
    unit("gw", "gateway", "gateway");
    unit("other", "other", "other");
    for (String module : List.of("bff", "gateway")) {
      String repo = module.equals("bff") ? "bff" : "gw";
      config(repo, module, "application.properties", "spring.data.redis.host", "Redis", null);
      config(repo, module, "application.properties", "spring.data.redis.port", "6379", null);
      config(
          repo,
          module,
          "application.properties",
          "spring.data.redis.password",
          "******",
          "\"masked\":true");
    }
    // The other unit's Redis is another server, on a port of its own.
    config("other", "other", "application.properties", "spring.redis.host", "cache", null);
    config("other", "other", "application.properties", "spring.redis.port", "6400", null);
    type("bff", "bff", "app.SessionStore");
    redisUse(
        "bff",
        "bff",
        "app.SessionStore",
        "template",
        "{\"api\":\"org.springframework.data.redis.core.RedisTemplate\",\"key\":\"bff:session:*\","
            + "\"operation\":\"write\",\"structure\":\"value\"}");
    redisUse(
        "bff",
        "bff",
        "app.SessionStore",
        "template",
        "{\"api\":\"org.springframework.data.redis.core.RedisTemplate\",\"key\":\"bff:session:*\","
            + "\"operation\":\"delete\",\"structure\":\"value\"}");
    redisImage("bff", "bff", "redis:8-alpine", "Redis 8");
    redisImage("gw", "gateway", "redis:8-alpine", "Redis 8");
    redisImage("other", "other", "redis/redis-stack:7", "Redis Stack 7");

    assertThat(derive(CrossRepoLinks.USES_DATABASE))
        .containsExactly(
            "service:g:bff USES_DATABASE database:redis:6379/redis"
                + " {\"key\":\"spring.data.redis.host\",\"protocol\":\"RESP\","
                + "\"server\":\"redis:6379\",\"stores\":[{\"key\":\"bff:session:*\","
                + "\"kind\":\"template\",\"operations\":[\"delete\",\"write\"],"
                + "\"structure\":\"value\"}],\"technology\":\"Redis 8\","
                + "\"uses\":[\"template\"]} 1.0",
            "service:g:gateway USES_DATABASE database:redis:6379/redis"
                + " {\"key\":\"spring.data.redis.host\",\"protocol\":\"RESP\","
                + "\"server\":\"redis:6379\",\"technology\":\"Redis 8\"} 1.0",
            "service:g:other USES_DATABASE database:cache:6400/redis"
                + " {\"key\":\"spring.redis.host\",\"protocol\":\"RESP\","
                + "\"server\":\"cache:6400\",\"technology\":\"Redis Stack 7\"} 1.0");
    assertThat(derive())
        .filteredOn(l -> l.startsWith("database:") || l.contains(" technology "))
        .anySatisfy(l -> assertThat(l).contains("database:redis:6379/redis technology Redis 8"))
        .anySatisfy(
            l ->
                assertThat(l)
                    .contains("database:cache:6400/redis display-name Redis Stack (cache:6400)"));
  }

  @Test
  void aRedisHostOrPortNoConfigResolvesIsSharedByTheUnitsThatWriteItTheSameWay() {
    for (String name : List.of("auth", "gw")) {
      unit(name, name, name);
      config(
          name,
          name,
          "application.properties",
          "spring.data.redis.host",
          "${redis.host}",
          "\"unresolved\":[\"redis.host\"]");
      config(
          name,
          name,
          "application.properties",
          "spring.data.redis.port",
          "${redis.port}",
          "\"unresolved\":[\"redis.port\"]");
    }
    unit("main", "main", "main");
    config(
        "main",
        "main",
        "application.properties",
        "spring.data.redis.host",
        "${cache.host}",
        "\"unresolved\":[\"cache.host\"]");

    assertThat(derive(CrossRepoLinks.USES_DATABASE))
        .extracting(l -> l.substring(0, l.indexOf(" {")))
        .containsExactly(
            "service:g:auth USES_DATABASE database:${redis.host}:${redis.port}/redis",
            "service:g:gw USES_DATABASE database:${redis.host}:${redis.port}/redis",
            "service:g:main USES_DATABASE database:${cache.host}:6379/redis");
    assertThat(derive(CrossRepoLinks.USES_DATABASE).getFirst())
        .contains("\"inferred\"")
        .endsWith(" 0.8");
  }

  @Test
  void aRedisUrlAndAClusterAreLinked() {
    unit("a", "a", "a");
    config(
        "a",
        "a",
        "application.properties",
        "spring.data.redis.url",
        "rediss://u:p@Cache-A/2",
        null);
    unit("b", "b", "b");
    config(
        "b", "b", "application.properties", "spring.data.redis.cluster.nodes[0]", "n2:7001", null);
    config(
        "b", "b", "application.properties", "spring.data.redis.cluster.nodes[1]", "n1:7000", null);

    assertThat(derive(CrossRepoLinks.USES_DATABASE))
        .extracting(l -> l.substring(0, l.indexOf(" {")))
        .containsExactly(
            "service:g:a USES_DATABASE database:cache-a:6379/redis",
            "service:g:b USES_DATABASE database:n1:7000,n2:7001/redis");
    assertThat(derive(CrossRepoLinks.USES_DATABASE).getFirst()).contains("\"ssl\":true");
    assertThat(derive()).noneMatch(l -> l.contains("u:p"));
  }

  @Test
  void aUnitUsingRedisWithoutConfigTakesTheOnlyConfiguredServerElseTheDefaultElseNone() {
    unit("c", "c", "c");
    type("c", "c", "app.Limiter");
    redisUse("c", "c", "app.Limiter", "rate-limiter", "{\"operation\":\"other\"}");

    // No server is configured anywhere: Spring Boot's default.
    assertThat(derive(CrossRepoLinks.USES_DATABASE))
        .singleElement()
        .satisfies(
            l ->
                assertThat(l)
                    .startsWith("service:g:c USES_DATABASE database:localhost:6379/redis")
                    .contains("\"defaulted\":true")
                    .endsWith(" 0.6"));

    // One server is configured: the unit is taken to use it, inferred.
    unit("a", "a", "a");
    config("a", "a", "application.properties", "spring.data.redis.host", "cache", null);
    assertThat(derive(CrossRepoLinks.USES_DATABASE))
        .anySatisfy(
            l ->
                assertThat(l)
                    .startsWith("service:g:c USES_DATABASE database:cache:6379/redis")
                    .contains("\"inferred\"")
                    .doesNotContain("defaulted")
                    .endsWith(" 0.6"));

    // Several are: left open, no link and no guess.
    unit("b", "b", "b");
    config("b", "b", "application.properties", "spring.data.redis.host", "other", null);
    assertThat(derive(CrossRepoLinks.USES_DATABASE))
        .extracting(l -> l.substring(0, l.indexOf(" {")))
        .containsExactly(
            "service:g:a USES_DATABASE database:cache:6379/redis",
            "service:g:b USES_DATABASE database:other:6379/redis");
  }

  @Test
  void brokersAndInfrastructureComeFromConfigAndInfrastructureIsKeptApart() {
    unit("s", "shop", "shop");
    config(
        "s",
        "shop",
        "application.yml",
        "spring.kafka.bootstrap-servers",
        "kafka-2:9092,kafka-1:9092",
        null);
    config("s", "shop", "application.yml", "spring.rabbitmq.host", "rabbit", null);
    config(
        "s",
        "shop",
        "application.yml",
        "spring.config.import[0]",
        "optional:configserver:http://config:8888",
        null);
    config(
        "s",
        "shop",
        "application.yml",
        "eureka.client.serviceUrl.defaultZone",
        "http://registry:8761/eureka/",
        null);
    config(
        "s",
        "shop",
        "application.yml",
        "management.zipkin.tracing.endpoint",
        "${ZIPKIN_URL}",
        "\"unresolved\":[\"ZIPKIN_URL\"]");
    unit("r", "registry", "registry");

    assertThat(derive())
        .extracting(l -> l.substring(0, l.contains(" {") ? l.indexOf(" {") : l.length()))
        .containsExactly(
            "service:g:shop USES_BROKER message-broker:kafka:kafka-1:9092,kafka-2:9092",
            "service:g:shop USES_BROKER message-broker:rabbitmq:rabbit:5672",
            "service:g:shop USES_INFRASTRUCTURE infrastructure:config:8888",
            "service:g:shop USES_INFRASTRUCTURE service:g:registry");
    assertThat(derive(CrossRepoLinks.USES_INFRASTRUCTURE))
        .anySatisfy(l -> assertThat(l).contains("\"role\":\"service-discovery\""))
        .anySatisfy(l -> assertThat(l).contains("\"role\":\"config-server\""));
  }

  // ---------------------------------------------------------------------------------------------
  // Identity

  @Test
  void linkIdsStayTheSameWhenOnlyTheCommitChanges() {
    kafkaSendAndListener();
    List<String> before =
        linked().stream().map(f -> f.attributes().get("link").asString()).toList();
    List<String> ids = linked().stream().map(Fact::id).toList();

    facts.clear();
    commit = "b".repeat(40);
    kafkaSendAndListener();
    List<Fact> after = linked();

    assertThat(after.stream().map(f -> f.attributes().get("link").asString()))
        .containsExactlyElementsOf(before)
        .doesNotHaveDuplicates();
    assertThat(after.stream().map(Fact::id)).doesNotContainAnyElementsOf(ids);
    assertThat(after).allSatisfy(f -> assertThat(f.attributes().get("facts").size()).isPositive());
  }

  private void kafkaSendAndListener() {
    unit("f", "fulfilment", "fulfilment");
    type("f", "fulfilment", "f.Listener");
    messaging(
        "f",
        "f.Listener#on()",
        MessagingExtractor.LISTENER,
        "t",
        "{\"broker\":\"kafka\",\"destinationKind\":\"topic\"}",
        3);
    unit("p", "payments", "payments");
    type("p", "payments", "p.Publisher");
    messaging(
        "p",
        "p.Publisher#send()",
        MessagingExtractor.SEND,
        "t",
        "{\"broker\":\"kafka\",\"destinationKind\":\"topic\"}",
        4);
  }

  @Test
  void noFactsNoLinks() {
    assertThat(new CrossRepoLinks().derive(List.of(), 1)).isEmpty();
  }

  // ---------------------------------------------------------------------------------------------
  // Mail

  private void mailSend(String repo, String method, int line) {
    add(
        MailExtractor.NAME,
        "method:" + method,
        MailExtractor.MAIL_SEND,
        FactObject.string("send"),
        "{\"method\":\"send\",\"template\":\"org.springframework.mail.javamail.JavaMailSender\"}",
        repo
            + ":src/main/java/"
            + method.substring(0, method.indexOf('#')).replace('.', '/')
            + ".java#"
            + line,
        1.0);
  }

  @Test
  void aUnitThatSendsMailThroughTheHostInItsConfigSendsMailToAnExternalSystem() {
    unit("n", "notification", "notification");
    type("n", "notification", "n.EmailService");
    config(
        "n", "notification", "application.properties", "spring.mail.host", "smtp.gmail.com", null);
    config("n", "notification", "application.properties", "spring.mail.port", "587", null);
    mailSend("n", "n.EmailService#send(java.lang.String)", 35);

    assertThat(derive(CrossRepoLinks.SENDS_MAIL))
        .containsExactly(
            "service:g:notification SENDS_MAIL external-system:smtp.gmail.com"
                + " {\"host\":\"smtp.gmail.com\",\"key\":\"spring.mail.host\","
                + "\"port\":\"587\",\"protocol\":\"SMTP\","
                + "\"source\":\"method:n.EmailService#send(java.lang.String)\"} 1.0");
  }

  @Test
  void port465OrTheSmtpsProtocolMakesItSmtps() {
    unit("n", "notification", "notification");
    type("n", "notification", "n.EmailService");
    config(
        "n",
        "notification",
        "application.properties",
        "spring.mail.host",
        "smtp.example.org",
        null);
    config("n", "notification", "application.properties", "spring.mail.port", "465", null);
    mailSend("n", "n.EmailService#send()", 12);

    assertThat(derive(CrossRepoLinks.SENDS_MAIL))
        .singleElement()
        .asString()
        .contains("\"protocol\":\"SMTPS\"");
  }

  @Test
  void aMailHostInConfigWithoutRecognisedCodeIsOneLinkWithoutASource() {
    unit("n", "notification", "notification");
    config(
        "n", "notification", "application.properties", "spring.mail.host", "smtp.gmail.com", null);

    assertThat(derive(CrossRepoLinks.SENDS_MAIL))
        .containsExactly(
            "service:g:notification SENDS_MAIL external-system:smtp.gmail.com"
                + " {\"host\":\"smtp.gmail.com\",\"key\":\"spring.mail.host\","
                + "\"protocol\":\"SMTP\"} 1.0");
  }

  @Test
  void aMailHostWithoutADomainOrWithAnUnresolvedPlaceholderIsNotAnExternalSystem() {
    unit("n", "notification", "notification");
    type("n", "notification", "n.EmailService");
    config("n", "notification", "application.properties", "spring.mail.host", "mailhog", null);
    mailSend("n", "n.EmailService#send()", 12);
    for (String local :
        List.of("localhost", "127.0.0.1", "10.0.0.5", "mail.internal", "smtp.corp.local")) {
      config("n", "notification", "application.properties", "spring.mail.host", local, null);
      assertThat(derive(CrossRepoLinks.SENDS_MAIL)).as(local).isEmpty();
      facts.removeIf(f -> f.object().value().equals(local));
    }
    unit("m", "mailer", "mailer");
    config(
        "m",
        "mailer",
        "application.properties",
        "spring.mail.host",
        "${SMTP_HOST}",
        "\"unresolved\":[\"SMTP_HOST\"]");

    assertThat(derive(CrossRepoLinks.SENDS_MAIL)).isEmpty();
  }
}

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
import io.lantern.extract.deployment.DeploymentExtractor;
import io.lantern.extract.frontend.FrontendExtractor;
import io.lantern.extract.gateway.GatewayExtractor;
import io.lantern.extract.http.HttpClientExtractor;
import io.lantern.extract.identity.IdentityExtractor;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.extract.mail.MailExtractor;
import io.lantern.extract.maven.MavenExtractor;
import io.lantern.extract.messaging.MessagingExtractor;
import io.lantern.extract.people.PeopleExtractor;
import io.lantern.extract.persistence.PersistenceExtractor;
import io.lantern.extract.redis.RedisExtractor;
import io.lantern.extract.spring.SpringExtractor;
import io.lantern.extract.sql.SqlExtractor;
import io.lantern.extract.websocket.WebSocketExtractor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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

  /** A container a repository's compose file runs, with the product and version of its image. */
  private void redisImage(String repo, String module, String image, String technology) {
    String product = technology.replaceFirst(" [0-9.]+$", "");
    String version = technology.substring(product.length()).strip();
    container(
        repo,
        "redis",
        "{\"platform\":\"compose\",\"environment\":\"default\",\"kind\":\"service\","
            + "\"image\":\""
            + image
            + "\",\"product\":\""
            + product
            + "\""
            + (version.isEmpty() ? "" : ",\"version\":\"" + version + "\"")
            + ",\"hosts\":[\"redis\"]}");
  }

  /** A container a deployment descriptor of a repository runs (ADR 0082). */
  private void container(String repo, String name, String attributes) {
    add(
        DeploymentExtractor.NAME,
        "repo:" + repo,
        DeploymentExtractor.DEPLOYMENT_CONTAINER,
        FactObject.string(name),
        attributes,
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
              attributes.remove("fact");
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
  void aCallToThisMachineLinksByItsPortWhenOneOtherUnitListensThere() {
    unit("a", "bff", "bff");
    type("a", "bff", "b.Proxy");
    call(
        "a",
        "b.Proxy#send()",
        "http://127.0.0.1:8888{path}",
        "{\"client\":\"rest-client\",\"expressions\":[\"path\"],\"path\":\"{path}\","
            + "\"url\":\"http://127.0.0.1:8888{path}\",\"baseUrl\":\"http://127.0.0.1:8888\"}",
        5);
    unit("g", "gateway", "gateway");
    config("g", "gateway", "application.properties", "server.port", "8888", null);
    // Serves a one-segment path, so the path alone would have taken it.
    unit("p", "profile", "profile");
    type("p", "profile", "p.ProfileController");
    endpoint("p", "p.ProfileController", "all()", "GET", "/profiles");

    assertThat(derive(CrossRepoLinks.CALLS_HTTP))
        .singleElement()
        .asString()
        .startsWith("service:g:bff CALLS_HTTP service:g:gateway")
        .contains("\"matchedBy\":\"port\"")
        .endsWith(" 0.64");
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

  private void webSocketEndpoint(String repo, String method, String path) {
    add(
        WebSocketExtractor.NAME,
        "method:" + method,
        WebSocketExtractor.ENDPOINT,
        FactObject.string(path),
        "{\"path\":\"" + path + "\",\"protocol\":\"stomp\",\"sockjs\":true}",
        repo + ":src/main/java/n/WsConfig.java#12",
        1.0);
  }

  @Test
  void aWebSocketRouteNamesTheWebSocketEndpointItReaches() {
    unit("gw", "edge", "edge");
    type("gw", "edge", "gw.Routes");
    route(
        "gw",
        "method:gw.Routes#routes()",
        "notify",
        "{\"definedIn\":\"java\",\"uri\":\"lb:ws://notifications\","
            + "\"paths\":[\"/ws-notifications/**\"]}");
    // Without a Path predicate a route reaches every endpoint, so it names none of several.
    route(
        "gw",
        "method:gw.Routes#routes()",
        "all",
        "{\"definedIn\":\"java\",\"uri\":\"lb:ws://notifications\"}");
    unit("n", "notifications-service", "notifications");
    type("n", "notifications-service", "n.WsConfig");
    webSocketEndpoint("n", "n.WsConfig#stomp()", "/ws-notifications");
    webSocketEndpoint("n", "n.WsConfig#raw()", "/ws-raw");

    assertThat(derive(CrossRepoLinks.CALLS_HTTP))
        .containsExactlyInAnyOrder(
            "service:g:edge CALLS_HTTP service:g:notifications-service"
                + " {\"endpoints\":[\"/ws-notifications\",\"/ws-raw\"],"
                + "\"matchedBy\":\"service-name\",\"protocol\":\"WebSocket\",\"route\":\"all\","
                + "\"source\":\"method:gw.Routes#routes()\",\"url\":\"lb:ws://notifications\"} 1.0",
            "service:g:edge CALLS_HTTP service:g:notifications-service"
                + " {\"endpoint\":\"/ws-notifications\",\"matchedBy\":\"service-name\","
                + "\"paths\":[\"/ws-notifications/**\"],\"protocol\":\"WebSocket\","
                + "\"route\":\"notify\",\"source\":\"method:gw.Routes#routes()\","
                + "\"target\":\"method:n.WsConfig#stomp()\",\"url\":\"lb:ws://notifications\"}"
                + " 1.0");
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

  @Test
  void aCallThroughAnInterfaceOrAnInheritedMethodReachesALibrarysSend() {
    type("lib", "messaging-lib", "l.Messages");
    type("lib", "messaging-lib", "l.Api");
    type("lib", "messaging-lib", "l.Base");
    messaging(
        "lib",
        "l.Messages#send()",
        MessagingExtractor.SEND,
        "notify.send",
        "{\"broker\":\"rabbitmq\",\"destinationKind\":\"queue\"}",
        30);
    messaging(
        "lib",
        "l.Base#audit()",
        MessagingExtractor.SEND,
        "audit",
        "{\"broker\":\"rabbitmq\",\"destinationKind\":\"queue\"}",
        31);
    relation("lib", "l.Messages", BuiltInTypes.IMPLEMENTS, "l.Api");
    relation("lib", "l.Messages", BuiltInTypes.EXTENDS, "l.Base");
    unit("a", "shop", "shop");
    bundled("a", "shop", "messaging-lib");
    type("a", "shop", "s.Orders");
    // shop calls the interface's method, which l.Messages implements.
    calls("a", "s.Orders#place()", "l.Api#send()");
    unit("b", "admin", "admin");
    bundled("b", "admin", "messaging-lib");
    type("b", "admin", "b.Audit");
    // admin calls the method l.Messages inherits from l.Base.
    calls("b", "b.Audit#run()", "l.Messages#audit()");

    assertThat(derive(CrossRepoLinks.PUBLISHES))
        .extracting(l -> l.substring(0, l.indexOf(" {")))
        .containsExactly(
            "service:g:admin PUBLISHES queue:rabbitmq:audit",
            "service:g:shop PUBLISHES queue:rabbitmq:notify.send");
  }

  @Test
  void aLibrarysSendStaysWithASingleUnitAndWithTheUnitWhoseOwnModuleDeclaresIt() {
    // One unit runs the library: it sends, though nothing calls the method. (With one unit the
    // result is that unit either way; unitsCalling only skips the call search for it.)
    type("lib", "messaging-lib", "l.Messages");
    messaging(
        "lib",
        "l.Messages#send()",
        MessagingExtractor.SEND,
        "notify.send",
        "{\"broker\":\"rabbitmq\",\"destinationKind\":\"queue\"}",
        30);
    unit("a", "shop", "shop");
    bundled("a", "shop", "messaging-lib");
    // worker's own module declares a send that shop, which bundles worker as a library, calls.
    unit("w", "worker", "worker");
    type("w", "worker", "w.Jobs");
    messaging(
        "w",
        "w.Jobs#done()",
        MessagingExtractor.SEND,
        "jobs.done",
        "{\"broker\":\"rabbitmq\",\"destinationKind\":\"queue\"}",
        12);
    bundled("a", "shop", "worker");
    type("a", "shop", "s.Orders");
    calls("a", "s.Orders#place()", "w.Jobs#done()");

    assertThat(derive(CrossRepoLinks.PUBLISHES))
        .extracting(l -> l.substring(0, l.indexOf(" {")))
        .containsExactly(
            "service:g:shop PUBLISHES queue:rabbitmq:jobs.done",
            "service:g:shop PUBLISHES queue:rabbitmq:notify.send",
            "service:g:worker PUBLISHES queue:rabbitmq:jobs.done");
  }

  private void relation(String repo, String from, String predicate, String to) {
    add(
        JavaExtractor.NAME,
        "type:" + from,
        predicate,
        FactObject.entity(EntityId.parse("type:" + to)),
        null,
        repo + ":src/main/java/" + from.replace('.', '/') + ".java#3",
        1.0);
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

  /** Two units whose datasources leave host, port and database to the environment. */
  private void unitsWithPlaceholderDatasources() {
    for (String name : List.of("auth", "main")) {
      unit(name, name + "-service", name + "-service");
      config(name, name + "-service", "application.properties", "x.y", "z", null);
      datasource(
          name,
          name + "-service",
          "application.properties",
          "jdbc:postgresql://${"
              + name
              + ".db.host}:${"
              + name
              + ".db.port}/${"
              + name
              + ".db.name}",
          "\"server\":\"${"
              + name
              + ".db.host}:${"
              + name
              + ".db.port}\","
              + "\"database\":\"${"
              + name
              + ".db.name}\","
              + "\"unresolved\":[\""
              + name
              + ".db.host\",\""
              + name
              + ".db.port\",\""
              + name
              + ".db.name\"]");
    }
  }

  @Test
  void aDatasourceTheEnvironmentFillsIsNamedAfterTheDatabaseItsServerCreates() {
    unitsWithPlaceholderDatasources();
    // Kubernetes: one PostgreSQL whose init script creates both units' databases; the units' host
    // and port come from a ConfigMap, their database names from a Secret Lantern does not read.
    container(
        "deploy",
        "postgres",
        "{\"platform\":\"kubernetes\",\"environment\":\"dev\",\"kind\":\"StatefulSet\","
            + "\"image\":\"postgres:17.5\",\"product\":\"PostgreSQL\",\"version\":\"17.5\","
            + "\"hosts\":[\"postgres\",\"postgres-service\"],"
            + "\"databases\":[\"auth_db\",\"main_db\"]}");
    for (String name : List.of("auth", "main")) {
      String upper = name.toUpperCase(java.util.Locale.ROOT);
      container(
          "deploy",
          name + "service",
          "{\"platform\":\"kubernetes\",\"environment\":\"dev\",\"kind\":\"Deployment\","
              + "\"image\":\"acme/"
              + name
              + "-service:1\",\"hosts\":[\""
              + name
              + "service\"],\"env\":{\""
              + upper
              + "_DB_HOST\":\"postgres-service\",\""
              + upper
              + "_DB_PORT\":\"5432\"},\"masked\":[\""
              + upper
              + "_DB_NAME\"]}");
    }

    assertThat(derive("display-name"))
        .containsExactlyInAnyOrder(
            "database:g:auth-service/postgresql:g:auth-service display-name auth_db 1.0",
            "database:g:main-service/postgresql:g:main-service display-name main_db 1.0");
    assertThat(derive(DeployableUnits.TECHNOLOGY))
        .filteredOn(l -> l.startsWith("database:"))
        .containsExactlyInAnyOrder(
            "database:g:auth-service/postgresql:g:auth-service technology PostgreSQL 17.5 1.0",
            "database:g:main-service/postgresql:g:main-service technology PostgreSQL 17.5 1.0");
    assertThat(derive(CrossRepoLinks.USES_DATABASE))
        .anySatisfy(
            l ->
                assertThat(l)
                    .startsWith("service:g:auth-service USES_DATABASE")
                    .contains(
                        "\"deployments\":[{\"address\":\"postgres-service:5432\","
                            + "\"container\":\"authservice\",\"database\":\"auth_db\","
                            + "\"environment\":\"Kubernetes (dev)\",\"server\":\"postgres\"}]")
                    .contains("\"inferred\":")
                    .endsWith(" 0.8"));
    assertThat(derive(Deployments.DEPLOYED_IN))
        .containsExactlyInAnyOrder(
            "service:g:auth-service deployed-in Kubernetes (dev) {\"container\":\"authservice\","
                + "\"environment\":\"dev\",\"platform\":\"kubernetes\"} 1.0",
            "service:g:main-service deployed-in Kubernetes (dev) {\"container\":\"mainservice\","
                + "\"environment\":\"dev\",\"platform\":\"kubernetes\"} 1.0",
            "database:g:auth-service/postgresql:g:auth-service deployed-in Kubernetes (dev)"
                + " {\"container\":\"postgres\",\"environment\":\"dev\","
                + "\"platform\":\"kubernetes\"} 1.0",
            "database:g:main-service/postgresql:g:main-service deployed-in Kubernetes (dev)"
                + " {\"container\":\"postgres\",\"environment\":\"dev\","
                + "\"platform\":\"kubernetes\"} 1.0");
  }

  @Test
  void aComposeVariableTiesAUnitsDatabaseToTheContainerThatCreatesIt() {
    unitsWithPlaceholderDatasources();
    // Compose: each unit's database is the container whose POSTGRES_DB is the same variable; the
    // variables are set in an .env file that is not committed, so only the image is known.
    for (String name : List.of("auth", "main")) {
      String upper = name.toUpperCase(java.util.Locale.ROOT);
      container(
          "deploy",
          name + "-db",
          "{\"platform\":\"compose\",\"environment\":\"local\",\"kind\":\"service\","
              + "\"image\":\"postgres:16\",\"product\":\"PostgreSQL\",\"version\":\"16\","
              + "\"hosts\":[\""
              + name
              + "-db\"],\"env\":{\"POSTGRES_DB\":\"${"
              + upper
              + "_DB_NAME}\"}}");
      container(
          "deploy",
          name + "-service",
          "{\"platform\":\"compose\",\"environment\":\"local\",\"kind\":\"service\","
              + "\"hosts\":[\""
              + name
              + "-service\"],\"env\":{\""
              + upper
              + "_DB_HOST\":\"${"
              + upper
              + "_DB_HOST}\",\""
              + upper
              + "_DB_NAME\":\"${"
              + upper
              + "_DB_NAME}\"}}");
    }

    // Each stays its own database, named by the unit, with the version of its container.
    assertThat(derive("display-name"))
        .containsExactlyInAnyOrder(
            "database:g:auth-service/postgresql:g:auth-service display-name auth-service database"
                + " 1.0",
            "database:g:main-service/postgresql:g:main-service display-name main-service database"
                + " 1.0");
    assertThat(derive(DeployableUnits.TECHNOLOGY))
        .filteredOn(l -> l.startsWith("database:"))
        .containsExactlyInAnyOrder(
            "database:g:auth-service/postgresql:g:auth-service technology PostgreSQL 16 1.0",
            "database:g:main-service/postgresql:g:main-service technology PostgreSQL 16 1.0");
    assertThat(derive(Deployments.DEPLOYED_IN))
        .filteredOn(l -> l.startsWith("database:g:auth-service"))
        .singleElement()
        .asString()
        .contains("{\"container\":\"auth-db\"");
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

  // ---------------------------------------------------------------------------------------------
  // Frontends

  private void frontend(String repo, String directory) {
    add(
        FrontendExtractor.NAME,
        "frontend:" + repo + (directory.isEmpty() ? "" : "/" + directory),
        FrontendExtractor.APP,
        FactObject.string("angular"),
        "{\"name\":\"web\",\"directory\":\""
            + directory
            + "\",\"framework\":\"Angular\",\"technology\":\"Angular 19.2\"}",
        repo + ":" + (directory.isEmpty() ? "" : directory + "/") + "package.json#4",
        1.0);
  }

  private void frontendCall(String frontend, String method, String url, String more, int line) {
    add(
        FrontendExtractor.NAME,
        frontend,
        FrontendExtractor.CALL,
        FactObject.string((method == null ? "WebSocket" : method) + " " + url),
        "{"
            + (method == null ? "" : "\"httpMethod\":\"" + method + "\",")
            + "\"url\":\""
            + url
            + "\",\"protocol\":\""
            + (method == null ? "WebSocket" : "HTTP")
            + "\""
            + (more == null ? "" : "," + more)
            + "}",
        frontend.substring(frontend.indexOf(':') + 1).split("/")[0] + ":src/app/api.ts#" + line,
        0.9);
  }

  private void frontendProxy(String frontend, String prefix, String target, String source) {
    add(
        FrontendExtractor.NAME,
        frontend,
        FrontendExtractor.PROXY,
        FactObject.string(prefix),
        "{\"target\":\"" + target + "\",\"source\":\"" + source + "\"}",
        frontend.substring(frontend.indexOf(':') + 1).split("/")[0] + ":nginx.conf#9",
        0.9);
  }

  @Test
  void aFrontendsCallsToThisMachineReachTheUnitWhoseContainerPublishesThePort() {
    unit("gw", "api-gateway", "api-gateway");
    unit("auth", "auth-service", "auth-service");
    // Both listen on 8080 inside their containers; only the gateway is published on it.
    container(
        "deploy",
        "api-gateway",
        "{\"platform\":\"compose\",\"environment\":\"local\",\"kind\":\"service\","
            + "\"image\":\"acme/api-gateway:1\",\"hosts\":[\"api-gateway\"],"
            + "\"ports\":[\"8080:8080\"]}");
    container(
        "deploy",
        "auth-service",
        "{\"platform\":\"compose\",\"environment\":\"local\",\"kind\":\"service\","
            + "\"image\":\"acme/auth-service:1\",\"hosts\":[\"auth-service\"]}");
    frontend("web", "");
    frontendCall(
        "frontend:web",
        "POST",
        "http://192.168.1.20:8080/api/auth/sign-in",
        "\"alternates\":[\"http://localhost:8080/api/auth/sign-in\"]",
        3);
    frontendCall("frontend:web", null, "http://localhost:8080/ws-notifications", null, 5);
    frontendCall("frontend:web", "GET", "https://maps.example.com/tiles/{z}", null, 7);
    frontendCall("frontend:web", "GET", "http://localhost:9999/nothing", null, 9);

    assertThat(derive(CrossRepoLinks.CALLS_HTTP))
        .containsExactlyInAnyOrder(
            "frontend:web CALLS_HTTP service:g:api-gateway {\"httpMethod\":\"POST\","
                + "\"matchedBy\":\"published-port\",\"path\":\"/api/auth/sign-in\","
                + "\"protocol\":\"JSON/HTTP\",\"url\":\"http://192.168.1.20:8080/api/auth/sign-in\"}"
                + " 0.72",
            "frontend:web CALLS_HTTP service:g:api-gateway {\"matchedBy\":\"published-port\","
                + "\"path\":\"/ws-notifications\",\"protocol\":\"WebSocket\","
                + "\"url\":\"http://localhost:8080/ws-notifications\"} 0.72",
            "frontend:web CALLS_HTTP external-system:maps.example.com {\"httpMethod\":\"GET\","
                + "\"matchedBy\":\"external\",\"path\":\"/tiles/{z}\",\"protocol\":\"HTTPS\","
                + "\"url\":\"https://maps.example.com/tiles/{z}\"} 0.9");
  }

  @Test
  void aFrontendsCallNamesTheEndpointItReachesDirectlyOrThroughAGatewayRoute() {
    unit("gw", "api-gateway", "api-gateway");
    type("gw", "api-gateway", "gw.Routes");
    type("gw", "api-gateway", "gw.CompositeController");
    route(
        "gw",
        "method:gw.Routes#routes()",
        "auth",
        "{\"definedIn\":\"java\",\"uri\":\"lb://auth-service\"," + "\"paths\":[\"/api/auth/**\"]}");
    // A more specific route wins over a broader one of the same order.
    route(
        "gw",
        "method:gw.Routes#routes()",
        "all",
        "{\"definedIn\":\"java\",\"uri\":\"lb://main-service\",\"paths\":[\"/api/**\"]}");
    route(
        "gw",
        "method:gw.Routes#routes()",
        "notify",
        "{\"definedIn\":\"java\",\"uri\":\"lb:ws://notification-service\","
            + "\"paths\":[\"/socket/**\"],\"filters\":[\"StripPrefix=1\"]}");
    // The gateway's own endpoint answers before any route.
    endpoint("gw", "gw.CompositeController", "tasks()", "POST", "/api/composite/tasks");
    unit("a", "auth-service", "auth-service");
    type("a", "auth-service", "a.AuthController");
    endpoint("a", "a.AuthController", "signIn()", "POST", "/api/auth/sign-in");
    unit("m", "main-service", "main-service");
    unit("n", "notification-service", "notification-service");
    type("n", "notification-service", "n.WsConfig");
    webSocketEndpoint("n", "n.WsConfig#stomp()", "/ws-notifications");
    config("gw", "api-gateway", "application.yml", "server.port", "8080", null);
    config("a", "auth-service", "application.yml", "server.port", "8081", null);
    config("m", "main-service", "application.yml", "server.port", "8082", null);
    config("n", "notification-service", "application.yml", "server.port", "8083", null);
    frontend("web", "");
    frontendCall("frontend:web", "POST", "http://localhost:8080/api/auth/sign-in", null, 3);
    frontendCall("frontend:web", "POST", "http://localhost:8080/api/composite/tasks", null, 4);
    frontendCall("frontend:web", null, "http://localhost:8080/socket/ws-notifications", null, 5);
    // A path no route and no endpoint takes names nothing.
    frontendCall("frontend:web", "GET", "http://localhost:8080/elsewhere", null, 6);
    unit("shop", "bff", "bff");
    type("shop", "bff", "b.BffController");
    endpoint("shop", "b.BffController", "login()", "GET", "/bff/login");
    config("shop", "bff", "application.yml", "server.port", "8090", null);
    frontend("shop", "web-ui");
    frontendProxy("frontend:shop/web-ui", "/bff/", "http://bff:8090/bff/", "nginx");
    frontendCall("frontend:shop/web-ui", "GET", "/bff/login", "\"navigation\":true", 7);

    List<String> links =
        derive(CrossRepoLinks.CALLS_HTTP).stream().filter(l -> l.startsWith("frontend:")).toList();
    assertThat(links)
        .containsExactlyInAnyOrder(
            "frontend:web CALLS_HTTP service:g:api-gateway {\"httpMethod\":\"POST\","
                + "\"matchedBy\":\"port\",\"path\":\"/api/auth/sign-in\","
                + "\"protocol\":\"JSON/HTTP\",\"reaches\":\"method:a.AuthController#signIn()\","
                + "\"reachesEndpoint\":\"POST /api/auth/sign-in\","
                + "\"reachesUnit\":\"service:g:auth-service\",\"throughRoute\":\"auth\","
                + "\"url\":\"http://localhost:8080/api/auth/sign-in\"} 0.63",
            "frontend:web CALLS_HTTP service:g:api-gateway {\"httpMethod\":\"POST\","
                + "\"matchedBy\":\"port\",\"path\":\"/api/composite/tasks\","
                + "\"protocol\":\"JSON/HTTP\","
                + "\"reaches\":\"method:gw.CompositeController#tasks()\","
                + "\"reachesEndpoint\":\"POST /api/composite/tasks\","
                + "\"reachesUnit\":\"service:g:api-gateway\","
                + "\"url\":\"http://localhost:8080/api/composite/tasks\"} 0.63",
            "frontend:web CALLS_HTTP service:g:api-gateway {\"matchedBy\":\"port\","
                + "\"path\":\"/socket/ws-notifications\",\"protocol\":\"WebSocket\","
                + "\"reaches\":\"method:n.WsConfig#stomp()\","
                + "\"reachesEndpoint\":\"/ws-notifications\","
                + "\"reachesUnit\":\"service:g:notification-service\","
                + "\"throughRoute\":\"notify\","
                + "\"url\":\"http://localhost:8080/socket/ws-notifications\"} 0.63",
            "frontend:web CALLS_HTTP service:g:api-gateway {\"httpMethod\":\"GET\","
                + "\"matchedBy\":\"port\",\"path\":\"/elsewhere\",\"protocol\":\"JSON/HTTP\","
                + "\"url\":\"http://localhost:8080/elsewhere\"} 0.63",
            "frontend:shop/web-ui CALLS_HTTP service:g:bff {\"httpMethod\":\"GET\","
                + "\"matchedBy\":\"host\",\"navigation\":true,\"path\":\"/bff/login\","
                + "\"protocol\":\"JSON/HTTP\","
                + "\"proxy\":\"/bff/\",\"reaches\":\"method:b.BffController#login()\","
                + "\"reachesEndpoint\":\"GET /bff/login\",\"reachesUnit\":\"service:g:bff\","
                + "\"url\":\"http://bff:8090/bff/login\"} 0.9");
  }

  @Test
  void aFrontendsOwnPathsGoWhereItsNginxPassesThemAndItsComposeServiceNamesIt() {
    unit("shop", "bff", "bff");
    config("shop", "bff", "application.yml", "server.port", "8081", null);
    unit("shop", "files", "files");
    frontend("shop", "web-ui");
    String web = "frontend:shop/web-ui";
    frontendProxy(web, "/bff/", "http://bff:8081/bff/", "nginx");
    frontendProxy(web, "/bff", "http://localhost:8081", "dev-server");
    frontendProxy(web, "/media/", "http://files:8080", "nginx");
    frontendCall(web, "GET", "/bff/api/orders", null, 3);
    // A path no proxy takes is the page's own.
    frontendCall(web, "GET", "/assets/logo.svg", null, 4);
    add(
        DeploymentExtractor.NAME,
        "repo:shop",
        DeploymentExtractor.DEPLOYMENT_CONTAINER,
        FactObject.string("shop-web"),
        "{\"platform\":\"compose\",\"environment\":\"default\",\"kind\":\"service\","
            + "\"build\":\"./web-ui\",\"hosts\":[\"shop-web\"],\"ports\":[\"4200:80\"]}",
        "shop:compose.yaml#40",
        1.0);

    assertThat(derive(CrossRepoLinks.CALLS_HTTP))
        .containsExactlyInAnyOrder(
            "frontend:shop/web-ui CALLS_HTTP service:g:bff {\"httpMethod\":\"GET\","
                + "\"matchedBy\":\"host\",\"path\":\"/bff/api/orders\",\"protocol\":\"JSON/HTTP\","
                + "\"proxy\":\"/bff/\",\"url\":\"http://bff:8081/bff/api/orders\"} 0.9",
            "frontend:shop/web-ui CALLS_HTTP service:g:files {\"matchedBy\":\"host\","
                + "\"protocol\":\"JSON/HTTP\",\"proxy\":\"/media/\",\"url\":\"http://files:8080\"}"
                + " 0.9");
    assertThat(derive(Deployments.DEPLOYED_IN))
        .containsExactly(
            "frontend:shop/web-ui deployed-in Docker Compose (default)"
                + " {\"container\":\"shop-web\",\"environment\":\"default\",\"platform\":\"compose\"}"
                + " 1.0");
    assertThat(derive(DataLinker.DISPLAY_NAME))
        .containsExactly("frontend:shop/web-ui display-name shop-web 1.0");
    assertThat(FrontendLinker.folder("deploy/compose.yaml", "../web-ui Dockerfile.prod"))
        .isEqualTo("web-ui");
    assertThat(FrontendLinker.folder("compose.yaml", "../elsewhere")).isNull();
  }

  // ---------------------------------------------------------------------------------------------
  // People

  private void evidence(String subject, String kind, String attributes, String where) {
    add(
        PeopleExtractor.NAME,
        subject,
        PeopleExtractor.PERSON_EVIDENCE,
        FactObject.string(kind),
        attributes,
        where,
        0.7);
  }

  private void signInEndpoint(String path) {
    add(
        SpringExtractor.NAME,
        "method:a.AuthController#in()",
        SpringExtractor.ENDPOINT,
        FactObject.string("POST " + path),
        "{\"httpMethod\":\"POST\",\"path\":\""
            + path
            + "\",\"controller\":\"a.AuthController\","
            + "\"annotation\":\"org.springframework.web.bind.annotation.PostMapping\","
            + "\"framework\":\"spring-mvc\"}",
        "auth:src/main/java/a/AuthController.java#9",
        1.0);
  }

  private Map<String, Fact> people() {
    Map<String, Fact> result = new TreeMap<>();
    linked().stream()
        .filter(f -> f.subject().type().equals("person") && f.predicate().equals("person-basis"))
        .forEach(f -> result.put(f.subject().key(), f));
    return result;
  }

  private String nameOf(String person) {
    return linked().stream()
        .filter(f -> f.subject().toString().equals("person:" + person))
        .filter(f -> f.predicate().equals("display-name"))
        .map(f -> f.object().value())
        .findFirst()
        .orElseThrow();
  }

  @Test
  void membersAndTheInvitedAreInferredPeopleLinkedToTheScreensOfTheFrontendThatHasThem() {
    evidence(
        "type:m.RequiresOrganizationMembership",
        "membership",
        "{\"scope\":\"Organization\",\"type\":\"m.RequiresOrganizationMembership\"}",
        "main:src/main/java/m/RequiresOrganizationMembership.java#7");
    evidence(
        "type:m.OrganizationInvitation",
        "invitation",
        "{\"type\":\"m.OrganizationInvitation\"}",
        "main:src/main/java/m/OrganizationInvitation.java#6");
    evidence(
        "repo:web",
        "route",
        "{\"framework\":\"angular\",\"path\":\"/invitation/:invitationId\"}",
        "web:src/app/app.routes.ts#5");
    evidence(
        "repo:web",
        "route",
        "{\"framework\":\"angular\",\"path\":\"/registration\"}",
        "web:src/app/app.routes.ts#6");
    evidence(
        "repo:web",
        "route",
        "{\"framework\":\"angular\",\"path\":\"/tasks\"}",
        "web:src/app/app.routes.ts#7");
    signInEndpoint("/api/auth/sign-in");

    Map<String, Fact> people = people();

    assertThat(people.keySet()).containsExactly("invited-user", "organization-member");
    assertThat(nameOf("organization-member")).isEqualTo("Organization member");
    assertThat(nameOf("invited-user")).isEqualTo("Invited user");
    Fact member = people.get("organization-member");
    assertThat(member.object().value()).isEqualTo("member");
    assertThat(member.attributes().get("frontends").toString()).isEqualTo("[\"web\"]");
    assertThat(member.attributes().get("repos").toString())
        .isEqualTo("[\"auth\",\"main\",\"web\"]");
    assertThat(member.attributes().get("description").asString())
        .startsWith("The code has members of an organization: it checks membership in")
        .contains("RequiresOrganizationMembership")
        .contains("It signs up and in at POST /api/auth/sign-in.")
        .contains("web has the screens /registration.")
        .doesNotContain("/tasks");
    assertThat(people.get("invited-user").attributes().get("description").asString())
        .contains("OrganizationInvitation")
        .contains("web has the screens /invitation/:invitationId.")
        .doesNotContain("sign");
    assertThat(member.confidence()).isEqualTo(0.7);
    // Every fact it cites is a fact of the snapshot.
    Set<String> known = facts.stream().map(Fact::id).collect(java.util.stream.Collectors.toSet());
    for (Fact person : people.values()) {
      for (var id : person.attributes().get("cites")) {
        assertThat(known).contains(id.asString());
      }
    }
  }

  @Test
  void eachRoleTheCodeChecksIsAPersonNamedByTheRoleAcrossTheWaysItIsWritten() {
    evidence(
        "method:m.Orders#all()",
        "role",
        "{\"name\":\"EVENT_STAFF\",\"source\":\"PreAuthorize\"}",
        "m:src/main/java/m/Orders.java#12");
    evidence(
        "method:m.Orders#scan()",
        "role",
        "{\"name\":\"EVENT_STAFF\",\"source\":\"Secured\"}",
        "m:src/main/java/m/Orders.java#20");
    evidence(
        "repo:kc",
        "role",
        "{\"name\":\"Event_staff\",\"source\":\"keycloak-realm\"}",
        "kc:realm.json#4");
    evidence(
        "repo:kc", "role", "{\"name\":\"admin\",\"source\":\"keycloak-realm\"}", "kc:realm.json#8");

    assertThat(people().keySet()).containsExactly("admin", "event-staff");
    assertThat(nameOf("event-staff")).isEqualTo("Event staff");
    assertThat(nameOf("admin")).isEqualTo("Admin");
    assertThat(people().get("event-staff").attributes().get("cites")).hasSize(3);
    assertThat(people().get("admin").attributes().get("description").asString())
        .isEqualTo("The code checks the role admin (a role of the Keycloak realm, realm.json).");
  }

  @Test
  void signingUpAndInWithNoMemberOrRoleMakesTheUser() {
    signInEndpoint("/api/auth/sign-in");
    signInEndpoint("/register/{id}");
    signInEndpoint("/orders");

    assertThat(people().keySet()).containsExactly("user");
    assertThat(people().get("user").object().value()).isEqualTo("account");
    assertThat(people().get("user").attributes().get("description").asString())
        .contains("POST /api/auth/sign-in")
        .contains("POST /register/{id}")
        .doesNotContain("/orders");
  }

  @Test
  void aDescriptionNamesOnlyTheFactsItCites() {
    evidence(
        "type:m.RequiresOrganizationMembership",
        "membership",
        "{\"scope\":\"Organization\",\"type\":\"m.RequiresOrganizationMembership\"}",
        "main:src/main/java/m/RequiresOrganizationMembership.java#7");
    for (int n = 0; n < 20; n++) {
      evidence(
          "repo:web",
          "route",
          "{\"framework\":\"angular\",\"path\":\"/x" + n + "/login\"}",
          "web:src/app/app.routes.ts#" + (n + 1));
    }
    add(
        SpringExtractor.NAME,
        "method:a.AuthController#up()",
        SpringExtractor.ENDPOINT,
        FactObject.string("POST /sign-up"),
        "{\"httpMethod\":\"POST\",\"path\":\"/sign-up\",\"controller\":\"a.AuthController\","
            + "\"annotation\":\"x\",\"framework\":\"spring-mvc\"}",
        "auth:src/main/java/a/AuthController.java#9",
        1.0);
    signInEndpoint("/login");

    Fact member = people().get("organization-member");

    assertThat(member.attributes().get("cites")).hasSize(PeopleLinker.MAX_CITED);
    String description = member.attributes().get("description").asString();
    Map<String, Fact> byId = new TreeMap<>();
    facts.forEach(f -> byId.put(f.id(), f));
    // Every screen and endpoint the sentence names is a cited fact, and the rest are not named.
    List<String> citedPaths = new java.util.ArrayList<>();
    for (var id : member.attributes().get("cites")) {
      Fact cited = byId.get(id.asString());
      citedPaths.add(cited.attributes().path("path").asString(""));
    }
    for (java.util.regex.Matcher m =
            java.util.regex.Pattern.compile("(/[a-z0-9/-]+)").matcher(description);
        m.find(); ) {
      assertThat(citedPaths).contains(m.group(1));
    }
    assertThat(member.attributes().get("frontends").toString()).isEqualTo("[\"web\"]");
  }

  @Test
  void rolesAreWordedAsAnnotationsAndCallsAreChecks() {
    evidence(
        "method:m.A#a()",
        "role",
        "{\"name\":\"ADMIN\",\"source\":\"RolesAllowed\"}",
        "m:src/main/java/m/A.java#5");
    evidence(
        "method:m.A#b()",
        "role",
        "{\"name\":\"CLERK\",\"source\":\"hasRole\"}",
        "m:src/main/java/m/A.java#9");

    assertThat(people().get("admin").attributes().get("description").asString())
        .isEqualTo("The code checks the role ADMIN (@RolesAllowed in A.java).");
    assertThat(people().get("clerk").attributes().get("description").asString())
        .isEqualTo("The code checks the role CLERK (a hasRole check in A.java).");
  }

  @Test
  void aRoleOnlyServiceAccountsHoldIsNoPerson() {
    evidence(
        "repo:kc",
        "role",
        "{\"name\":\"WORKER\",\"source\":\"keycloak-realm\",\"serviceAccountOnly\":true}",
        "kc:realm.json#4");
    evidence(
        "method:m.A#reg()",
        "role",
        "{\"name\":\"WORKER\",\"source\":\"PreAuthorize\"}",
        "m:src/main/java/m/A.java#5");
    evidence(
        "repo:kc",
        "role",
        "{\"name\":\"editor\",\"source\":\"keycloak-realm\"}",
        "kc:realm.json#8");

    assertThat(people().keySet()).containsExactly("editor");
  }

  @Test
  void aRoleThatGuardsOnlyOperatorEndpointsIsNoPersonButOneGuardingOthersIs() {
    for (String[] role :
        new String[][] {
          {"OPS", "/actuator/env"}, {"STAFF", "/api/internal/x"}, {"ADMIN", "/api/users"}
        }) {
      String controller = "m.C" + role[0];
      add(
          SpringExtractor.NAME,
          "method:" + controller + "#h()",
          SpringExtractor.ENDPOINT,
          FactObject.string("GET " + role[1]),
          "{\"httpMethod\":\"GET\",\"path\":\""
              + role[1]
              + "\",\"controller\":\""
              + controller
              + "\",\"annotation\":\"x\",\"framework\":\"spring-mvc\"}",
          "m:src/main/java/m/C.java#3",
          1.0);
      evidence(
          "type:" + controller,
          "role",
          "{\"name\":\"" + role[0] + "\",\"source\":\"Secured\"}",
          "m:src/main/java/m/C.java#2");
    }
    // A role checked in code with no endpoint to tell by is kept.
    evidence(
        "method:m.D#x()",
        "role",
        "{\"name\":\"CLERK\",\"source\":\"Secured\"}",
        "m:src/main/java/m/D.java#2");

    assertThat(people().keySet()).containsExactly("admin", "clerk");
  }

  @Test
  void aRoleAndAMembershipOfOneNameAreOnePersonThatTheMembershipDescribes() {
    evidence(
        "method:m.A#a()",
        "role",
        "{\"name\":\"member\",\"source\":\"Secured\"}",
        "m:src/main/java/m/A.java#5");
    evidence(
        "type:m.Member",
        "membership",
        "{\"type\":\"m.Member\"}",
        "m:src/main/java/m/Member.java#3");
    signInEndpoint("/login");

    assertThat(people().keySet()).containsExactly("member");
    Fact person = people().get("member");
    assertThat(person.object().value()).isEqualTo("member");
    assertThat(person.attributes().get("cites")).hasSize(3);
    assertThat(person.attributes().get("description").asString())
        .startsWith("The code has members: it checks membership in Member (Member.java).");
  }

  @Test
  void aCodeWithNoSignOfUsersHasNoPeople() {
    unit("shop", "shop-app", "shop");
    signInEndpoint("/orders");
    evidence("repo:web", "route", "{\"framework\":\"vue\",\"path\":\"/cart\"}", "web:router.js#3");

    assertThat(people()).isEmpty();
  }

  @Test
  void aCodeWithManyRolesMakesPeopleOfTheMostChecked() {
    for (int n = 0; n < PeopleLinker.MAX_ROLES + 3; n++) {
      evidence(
          "method:m.A#m" + n + "()",
          "role",
          "{\"name\":\"R" + (char) ('A' + n) + "\",\"source\":\"Secured\"}",
          "m:src/main/java/m/A.java#" + (n + 1));
    }
    evidence(
        "method:m.A#again()",
        "role",
        "{\"name\":\"RC\",\"source\":\"Secured\"}",
        "m:src/main/java/m/A.java#99");

    assertThat(people()).hasSize(PeopleLinker.MAX_ROLES).containsKey("rc");
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

  // ---------------------------------------------------------------------------------------------
  // Identity providers and shared secrets

  private static final String KEYCLOAK =
      "{\"platform\":\"compose\",\"environment\":\"default\",\"kind\":\"service\","
          + "\"image\":\"quay.io/keycloak/keycloak:26.7.4\",\"product\":\"Keycloak\","
          + "\"version\":\"26.7.4\",\"role\":\"identity\",\"hosts\":[\"keycloak\"],"
          + "\"ports\":[\"8080:8080\"]}";

  private void jwtSecret(String repo, String method, String keys, String uses) {
    add(
        IdentityExtractor.NAME,
        "method:" + method,
        IdentityExtractor.JWT_SECRET_KEY,
        FactObject.string(keys.split(",")[0]),
        "{\"library\":\"jjwt\",\"call\":\"Keys.hmacShaKeyFor\",\"keys\":[\""
            + keys.replace(",", "\",\"")
            + "\"],\"uses\":["
            + uses
            + "]}",
        repo
            + ":src/main/java/"
            + method.substring(0, method.indexOf('#')).replace('.', '/')
            + ".java#30",
        1.0);
  }

  @Test
  void anIssuerOnThisMachineIsTheKeycloakAComposeFilePublishesThere() {
    unit("s", "gateway", "gateway");
    config(
        "s",
        "gateway",
        "application.properties",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri",
        "http://localhost:8080/realms/shop",
        "\"written\":\"${ISSUER:http://localhost:8080/realms/shop}\"");
    unit("s", "bff", "bff");
    config("s", "bff", "application.properties", "server.port", "8081", null);
    config(
        "s",
        "bff",
        "application.properties",
        "spring.security.oauth2.client.provider.keycloak.issuer-uri",
        "http://localhost:8080/realms/shop",
        null);
    config(
        "s",
        "bff",
        "application.properties",
        "spring.security.oauth2.client.registration.keycloak.client-id",
        "bff",
        null);
    config(
        "s",
        "bff",
        "application.properties",
        "spring.security.oauth2.client.registration.internal.provider",
        "keycloak",
        null);
    config(
        "s",
        "bff",
        "application.properties",
        "spring.security.oauth2.client.registration.internal.authorization-grant-type",
        "client_credentials",
        null);
    container("s", "keycloak", KEYCLOAK);

    assertThat(derive(CrossRepoLinks.USES_IDENTITY_PROVIDER))
        .containsExactly(
            "service:g:bff USES_IDENTITY_PROVIDER external-system:keycloak {\"key\":"
                + "\"spring.security.oauth2.client.provider.keycloak.issuer-uri\","
                + "\"matchedBy\":\"published-port\",\"protocol\":\"OAuth2 over HTTP\","
                + "\"purpose\":\"gets-tokens\",\"url\":\"http://localhost:8080/realms/shop\"}"
                + " 0.8",
            "service:g:bff USES_IDENTITY_PROVIDER external-system:keycloak {\"key\":"
                + "\"spring.security.oauth2.client.provider.keycloak.issuer-uri\","
                + "\"matchedBy\":\"published-port\",\"protocol\":\"OIDC over HTTP\","
                + "\"purpose\":\"signs-in-users\","
                + "\"url\":\"http://localhost:8080/realms/shop\"} 0.8",
            "service:g:gateway USES_IDENTITY_PROVIDER external-system:keycloak {\"key\":"
                + "\"spring.security.oauth2.resourceserver.jwt.issuer-uri\","
                + "\"matchedBy\":\"published-port\",\"protocol\":\"OIDC over HTTP\","
                + "\"purpose\":\"validates-tokens\","
                + "\"url\":\"http://localhost:8080/realms/shop\"} 0.8");
    assertThat(derive())
        .contains(
            "external-system:keycloak display-name Keycloak 1.0",
            "external-system:keycloak deployed-in Docker Compose (default)"
                + " {\"container\":\"keycloak\",\"environment\":\"default\","
                + "\"platform\":\"compose\"} 1.0");
  }

  @Test
  void anIssuerTheEnvironmentNamesIsTheKeycloakContainerAnsweringItsHost() {
    unit("s", "orders", "orders");
    config(
        "s",
        "orders",
        "application.properties",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
        "${ISSUER}/protocol/openid-connect/certs",
        "\"written\":\"${ISSUER}/protocol/openid-connect/certs\",\"unresolved\":[\"ISSUER\"]");
    container(
        "s",
        "orders",
        "{\"platform\":\"compose\",\"environment\":\"default\",\"kind\":\"service\","
            + "\"build\":\".\",\"hosts\":[\"orders\"],"
            + "\"env\":{\"ISSUER\":\"http://keycloak:8080/realms/shop\"}}");
    container("s", "keycloak", KEYCLOAK);

    assertThat(derive(CrossRepoLinks.USES_IDENTITY_PROVIDER))
        .singleElement()
        .asString()
        .startsWith("service:g:orders USES_IDENTITY_PROVIDER external-system:keycloak")
        .contains("\"matchedBy\":\"container\",\"protocol\":\"JWKS over HTTP\"")
        .contains(
            "\"purpose\":\"validates-tokens\","
                + "\"url\":\"http://keycloak:8080/realms/shop/protocol/openid-connect/certs\"");
  }

  @Test
  void aJwkSetOfAnotherUnitLinksToItsEndpointAndOneOfItsOwnIsNoLine() {
    unit("p", "auth", "auth");
    config("p", "auth", "application.yml", "server.port", "8081", null);
    type("p", "auth", "p.JwkSetController");
    endpoint("p", "p.JwkSetController", "jwks()", "GET", "/api/auth/.well-known/jwks.json");
    config(
        "p",
        "auth",
        "application.yml",
        "platform.security.jwt.jwk-set-uri",
        "http://localhost:8081/api/auth/.well-known/jwks.json",
        null);
    unit("p", "events", "events");
    config(
        "p",
        "events",
        "application.yml",
        "platform.security.jwt.jwk-set-uri",
        "http://localhost:8081/api/auth/.well-known/jwks.json",
        null);
    // An issuer that is not a URL names no provider.
    config("p", "events", "application.yml", "platform.security.jwt.issuer", "urn:p:auth", null);

    assertThat(derive(CrossRepoLinks.USES_IDENTITY_PROVIDER))
        .containsExactly(
            "service:g:events USES_IDENTITY_PROVIDER service:g:auth {\"key\":"
                + "\"platform.security.jwt.jwk-set-uri\",\"matchedBy\":\"port\","
                + "\"protocol\":\"JWKS over HTTP\",\"purpose\":\"validates-tokens\","
                + "\"target\":\"method:p.JwkSetController#jwks()\","
                + "\"url\":\"http://localhost:8081/api/auth/.well-known/jwks.json\"} 0.8");
  }

  @Test
  void anIssuerWithADomainAndAProviderKnownByNameAreExternalSystems() {
    unit("w", "web", "web");
    config(
        "w",
        "web",
        "application.properties",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri",
        "https://login.example.com/realms/w",
        null);
    config(
        "w",
        "web",
        "application.properties",
        "spring.security.oauth2.client.registration.google.client-id",
        "web-client",
        null);
    // A local issuer with nothing behind it names nothing.
    unit("w", "other", "other");
    config(
        "w",
        "other",
        "application.properties",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri",
        "http://localhost:9999/realms/w",
        null);

    assertThat(derive(CrossRepoLinks.USES_IDENTITY_PROVIDER))
        .extracting(l -> l.substring(0, l.indexOf(" {")))
        .containsExactly(
            "service:g:web USES_IDENTITY_PROVIDER external-system:accounts.google.com",
            "service:g:web USES_IDENTITY_PROVIDER external-system:login.example.com");
  }

  @Test
  void aKeycloakAdminClientManagesUsersAtTheUrlItsConfigGives() {
    unit("a", "admin", "admin");
    type("a", "admin", "a.KeycloakConfig");
    config(
        "a",
        "admin",
        "application.properties",
        "keycloak.admin.server-url",
        "https://sso.example.com",
        null);
    add(
        IdentityExtractor.NAME,
        "method:a.KeycloakConfig#keycloak()",
        IdentityExtractor.IDENTITY_ADMIN_CLIENT,
        FactObject.string("${keycloak.admin.server-url}"),
        "{\"product\":\"Keycloak\",\"url\":\"${keycloak.admin.server-url}\"}",
        "a:src/main/java/a/KeycloakConfig.java#27",
        1.0);

    assertThat(derive(CrossRepoLinks.USES_IDENTITY_PROVIDER))
        .containsExactly(
            "service:g:admin USES_IDENTITY_PROVIDER external-system:sso.example.com"
                + " {\"matchedBy\":\"external\",\"protocol\":\"Keycloak Admin REST over HTTPS\","
                + "\"purpose\":\"manages-users\","
                + "\"source\":\"method:a.KeycloakConfig#keycloak()\","
                + "\"url\":\"https://sso.example.com\"} 1.0");
  }

  @Test
  void unitsWhoseJwtSecretComesFromTheSamePlaceholderShareIt() {
    unit("c", "auth", "auth");
    type("c", "auth", "c.auth.JwtService");
    config(
        "c",
        "auth",
        "application.properties",
        "app.token.signing.key",
        "${auth.token.signing.key}",
        "\"unresolved\":[\"auth.token.signing.key\"]");
    jwtSecret("c", "c.auth.JwtService#key()", "app.token.signing.key", "\"sign\",\"verify\"");
    unit("c", "gateway", "gateway");
    type("c", "gateway", "c.gateway.JwtService");
    config(
        "c",
        "gateway",
        "application.properties",
        "app.token.signing.key",
        "${AUTH_TOKEN_SIGNING_KEY}",
        "\"unresolved\":[\"AUTH_TOKEN_SIGNING_KEY\"]");
    jwtSecret("c", "c.gateway.JwtService#key()", "app.token.signing.key", "\"verify\"");
    // Another unit with its own secret, written in its config, shares nothing.
    unit("c", "files", "files");
    type("c", "files", "c.files.Tokens");
    config("c", "files", "application.properties", "files.jwt.key", "abc", null);
    jwtSecret("c", "c.files.Tokens#key()", "files.jwt.key", "\"verify\"");

    assertThat(derive(CrossRepoLinks.SHARED_SECRET))
        .containsExactly(
            "service:g:auth shared-secret app.token.signing.key {\"algorithm\":\"HMAC\","
                + "\"basis\":\"placeholder\",\"library\":\"jjwt\","
                + "\"placeholders\":[\"auth.token.signing.key\"],"
                + "\"source\":\"method:c.auth.JwtService#key()\","
                + "\"uses\":[\"sign\",\"verify\"],\"with\":[\"service:g:gateway\"]} 0.9",
            "service:g:gateway shared-secret app.token.signing.key {\"algorithm\":\"HMAC\","
                + "\"basis\":\"placeholder\",\"library\":\"jjwt\","
                + "\"placeholders\":[\"auth.token.signing.key\"],"
                + "\"source\":\"method:c.gateway.JwtService#key()\","
                + "\"uses\":[\"verify\"],\"with\":[\"service:g:auth\"]} 0.9");
    assertThat(derive()).noneMatch(l -> l.contains("abc"));
  }

  @Test
  void maskedSecretsUnderTheSameKeyAreSharedByKeyAndDifferentValuesAreNot() {
    unit("c", "one", "one");
    type("c", "one", "c.one.Jwt");
    config("c", "one", "application.properties", "jwt.secret", "******", "\"masked\":true");
    jwtSecret("c", "c.one.Jwt#key()", "jwt.secret", "\"sign\"");
    unit("c", "two", "two");
    type("c", "two", "c.two.Jwt");
    config("c", "two", "application.properties", "jwt.secret", "******", "\"masked\":true");
    jwtSecret("c", "c.two.Jwt#key()", "jwt.secret", "\"verify\"");
    unit("c", "three", "three");
    type("c", "three", "c.three.Jwt");
    config("c", "three", "application.properties", "jwt.key", "first", null);
    jwtSecret("c", "c.three.Jwt#key()", "jwt.key", "\"verify\"");
    unit("c", "four", "four");
    type("c", "four", "c.four.Jwt");
    config("c", "four", "application.properties", "jwt.key", "second", null);
    jwtSecret("c", "c.four.Jwt#key()", "jwt.key", "\"verify\"");

    assertThat(derive(CrossRepoLinks.SHARED_SECRET))
        .extracting(
            l -> l.substring(0, l.indexOf(" {")) + " " + l.substring(l.lastIndexOf(' ') + 1))
        .containsExactly(
            "service:g:one shared-secret jwt.secret 0.7",
            "service:g:two shared-secret jwt.secret 0.7");
  }

  @Test
  void theSameSecretValueInConfigIsSharedWithoutTheValueInAnyFact() {
    unit("c", "one", "one");
    type("c", "one", "c.one.Jwt");
    config("c", "one", "application.properties", "jwt.key", "same-value-0123", null);
    jwtSecret("c", "c.one.Jwt#key()", "jwt.key", "\"sign\"");
    unit("c", "two", "two");
    type("c", "two", "c.two.Jwt");
    config("c", "two", "application.properties", "tokens.hmac", "same-value-0123", null);
    jwtSecret("c", "c.two.Jwt#key()", "tokens.hmac", "\"verify\"");

    assertThat(derive(CrossRepoLinks.SHARED_SECRET))
        .extracting(l -> l.substring(0, l.indexOf(" {")))
        .containsExactly(
            "service:g:one shared-secret jwt.key", "service:g:two shared-secret tokens.hmac");
    assertThat(derive(CrossRepoLinks.SHARED_SECRET))
        .allMatch(l -> l.contains("\"basis\":\"value\""));
    assertThat(linked()).noneMatch(f -> f.toString().contains("same-value-0123"));
  }
}

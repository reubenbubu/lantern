package io.lantern.extract.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.repo.Repo;
import io.lantern.extract.Fixtures;
import io.lantern.extract.RepoBuild;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The config extractor's rules on small repositories written for each case, read without builds.
 */
class ConfigExtractorTest {

  @TempDir Path temp;

  private static final String POM =
      "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId>"
          + "<artifactId>app</artifactId><version>1</version></project>";

  private static final String RESOURCES = "src/main/resources/";

  private Repo repo(Map<String, String> files) throws IOException {
    Map<String, String> all = new LinkedHashMap<>();
    all.put("pom.xml", POM);
    all.putAll(files);
    return Fixtures.repo(temp.resolve("app"), "app", all);
  }

  private static List<String> extract(Repo repo) throws IOException {
    List<Fact> facts =
        new ConfigExtractor().extract(List.of(new RepoBuild(repo, Fixtures.head(repo), null)), 1);
    Fixtures.checkEnvelope(facts, List.of(repo), 1, ConfigExtractor.NAME, ConfigExtractor.VERSION);
    return Fixtures.render(facts).lines().toList();
  }

  private static ModuleConfig config(Repo repo) {
    List<ModuleConfig> configs =
        ConfigExtractor.read(new RepoBuild(repo, Fixtures.head(repo), null));
    assertThat(configs).hasSize(1);
    return configs.get(0);
  }

  @Test
  void yamlIsFlattenedWithTheLineOfEachValue() throws IOException {
    Repo repo =
        repo(
            Map.of(
                RESOURCES + "application.yml",
                """
                spring:
                  application:
                    name: orders
                shop:
                  hosts:
                    - a
                    - b
                  port: 8080
                  enabled: true
                """));

    assertThat(extract(repo))
        .containsExactly(
            "module:g:app config-value 8080 {\"key\":\"shop.port\"}"
                + " @app:src/main/resources/application.yml#8-8 1.0",
            "module:g:app config-value a {\"key\":\"shop.hosts[0]\"}"
                + " @app:src/main/resources/application.yml#6-6 1.0",
            "module:g:app config-value b {\"key\":\"shop.hosts[1]\"}"
                + " @app:src/main/resources/application.yml#7-7 1.0",
            "module:g:app config-value orders {\"key\":\"spring.application.name\"}"
                + " @app:src/main/resources/application.yml#3-3 1.0",
            "module:g:app config-value true {\"key\":\"shop.enabled\"}"
                + " @app:src/main/resources/application.yml#9-9 1.0");
  }

  @Test
  void filesOverrideEachOtherInSpringBootsOrder() throws IOException {
    Repo repo =
        repo(
            Map.of(
                RESOURCES + "bootstrap.yml",
                "spring.application.name: from-bootstrap\na: bootstrap\nb: bootstrap\n",
                RESOURCES + "application.yml",
                "a: yml\nb: yml\nc: yml\nd: yml\n",
                RESOURCES + "application.properties",
                "b=properties\nc=properties\n",
                RESOURCES + "config/application.yml",
                "c: config\n",
                RESOURCES + "application-default.yml",
                "d: default-profile\n"));

    Map<String, ConfigValue> values = config(repo).values(null);

    // CFG-2: a name set only in bootstrap.yml still holds.
    assertThat(values.get("spring.application.name").value()).isEqualTo("from-bootstrap");
    assertThat(values.get("a").value()).isEqualTo("yml");
    assertThat(values.get("b").value()).isEqualTo("properties");
    assertThat(values.get("c").value()).isEqualTo("config");
    assertThat(values.get("d").value()).isEqualTo("default-profile");
    assertThat(values.get("d").where().path()).isEqualTo(RESOURCES + "application-default.yml");
    assertThat(config(repo).profiles()).isEmpty();
  }

  @Test
  void aProfileGivesOnlyTheValuesThatDiffer() throws IOException {
    Repo repo =
        repo(
            Map.of(
                RESOURCES + "application.yml",
                """
                host: payments
                url: http://${host}:8080
                same: x
                tax: ${TAX_SERVICE_URL}
                """,
                RESOURCES + "application-local.yml",
                """
                host: localhost
                same: x
                only-local: y
                """));

    assertThat(extract(repo))
        .containsExactly(
            "module:g:app config-value ${TAX_SERVICE_URL}"
                + " {\"key\":\"tax\",\"unresolved\":[\"TAX_SERVICE_URL\"]}"
                + " @app:src/main/resources/application.yml#4-4 1.0",
            "module:g:app config-value http://localhost:8080"
                + " {\"key\":\"url\",\"profile\":\"local\",\"written\":\"http://${host}:8080\"}"
                + " @app:src/main/resources/application.yml#2-2 1.0",
            "module:g:app config-value http://payments:8080"
                + " {\"key\":\"url\",\"written\":\"http://${host}:8080\"}"
                + " @app:src/main/resources/application.yml#2-2 1.0",
            "module:g:app config-value localhost {\"key\":\"host\",\"profile\":\"local\"}"
                + " @app:src/main/resources/application-local.yml#1-1 1.0",
            "module:g:app config-value payments {\"key\":\"host\"}"
                + " @app:src/main/resources/application.yml#1-1 1.0",
            "module:g:app config-value x {\"key\":\"same\"}"
                + " @app:src/main/resources/application.yml#3-3 1.0",
            "module:g:app config-value y {\"key\":\"only-local\",\"profile\":\"local\"}"
                + " @app:src/main/resources/application-local.yml#3-3 1.0");
  }

  @Test
  void documentsApplyByTheirProfileExpressions() throws IOException {
    Repo repo =
        repo(
            Map.of(
                RESOURCES + "application.yml",
                """
                v: base
                ---
                spring:
                  config:
                    activate:
                      on-profile: "!prod"
                v: not-prod
                ---
                spring:
                  config:
                    activate:
                      on-profile: [prod, staging]
                v: prod-or-staging
                ---
                spring:
                  profiles: legacy
                v: legacy
                ---
                spring:
                  config:
                    activate:
                      on-cloud-platform: kubernetes
                v: cloud
                ---
                spring:
                  config:
                    activate:
                      on-profile: "prod & (("
                v: broken
                """,
                RESOURCES + "application.properties",
                """
                w=base
                #---
                spring.config.activate.on-profile=prod
                w=prod
                """));
    ModuleConfig config = config(repo);

    assertThat(config.profiles()).containsExactly("legacy", "prod", "staging");
    assertThat(config.values(null).get("v").value()).isEqualTo("not-prod");
    assertThat(config.values("prod").get("v").value()).isEqualTo("prod-or-staging");
    assertThat(config.values("staging").get("v").value()).isEqualTo("prod-or-staging");
    assertThat(config.values("legacy").get("v").value()).isEqualTo("legacy");
    assertThat(config.values(null).get("w").value()).isEqualTo("base");
    assertThat(config.values("prod").get("w").value()).isEqualTo("prod");
    assertThat(config.values(null)).doesNotContainKeys("spring.config.activate.on-profile");
  }

  @Test
  void resolvingATextUsesTheProfilesConfig() throws IOException {
    Repo repo =
        repo(
            Map.of(
                RESOURCES + "application.properties",
                "shop.queue=shipments.dispatched\nshop.base=http://payments:8080\n",
                RESOURCES + "application-local.properties",
                "shop.base=http://localhost:8082\n"));
    ModuleConfig config = config(repo);

    assertThat(config.resolve(null, "${shop.queue}").value()).isEqualTo("shipments.dispatched");
    assertThat(config.resolve(null, "${shop.base}/api").value())
        .isEqualTo("http://payments:8080/api");
    assertThat(config.resolve("local", "${shop.base}/api").value())
        .isEqualTo("http://localhost:8082/api");
    Resolution unresolved = config.resolve(null, "${clients.tax.url}/v2/quotes");
    assertThat(unresolved.value()).isEqualTo("${clients.tax.url}/v2/quotes");
    assertThat(unresolved.unresolved()).containsExactly("clients.tax.url");
  }

  @Test
  void aProfilesListReplacesTheDefaultListWhole() throws IOException {
    Repo repo =
        repo(
            Map.of(
                RESOURCES + "application.yml",
                """
                gateway:
                  routes:
                    - id: a
                      filters:
                        - StripPrefix=1
                    - id: b
                      filters:
                        - name: RateLimiter
                          args:
                            rate: 8
                  name: shop
                """,
                RESOURCES + "application-staging.yml",
                """
                gateway:
                  routes:
                    - id: c
                    - id: a
                      filters:
                        - StripPrefix=1
                """));
    ModuleConfig config = config(repo);

    assertThat(config.values("staging").keySet())
        .containsExactly(
            "gateway.name",
            "gateway.routes[0].id",
            "gateway.routes[1].filters[0]",
            "gateway.routes[1].id");
    assertThat(config.values(null).get("gateway.routes[1].filters[0].name").value())
        .isEqualTo("RateLimiter");
  }

  @Test
  void secretsWrittenInTheFileAreMasked() throws IOException {
    Repo repo =
        repo(
            Map.of(
                RESOURCES + "application.properties",
                """
                spring.datasource.password=s3cret
                spring.datasource.username=orders
                paygate.api-key=${PAYGATE_API_KEY}
                other.client-secret=${CLIENT_SECRET:letmein}
                shop.routing-key=order.created
                jwt.secret-key=k1
                jwt.secretKey=k2
                encrypt.key=k3
                old.passwords[0]=p1
                app.db-password=s3cret
                other.datasource.password=${app.db-password}
                db.url=jdbc:postgresql://db/shop?password=${other.datasource.password}
                """));

    assertThat(extract(repo))
        .containsExactlyInAnyOrder(
            "module:g:app config-value ${PAYGATE_API_KEY}"
                + " {\"key\":\"paygate.api-key\",\"unresolved\":[\"PAYGATE_API_KEY\"]}"
                + " @app:src/main/resources/application.properties#3-3 1.0",
            "module:g:app config-value ****** {\"key\":\"other.client-secret\",\"masked\":true}"
                + " @app:src/main/resources/application.properties#4-4 1.0",
            "module:g:app config-value ****** {\"key\":\"spring.datasource.password\","
                + "\"masked\":true} @app:src/main/resources/application.properties#1-1 1.0",
            "module:g:app config-value ****** {\"key\":\"jwt.secret-key\",\"masked\":true}"
                + " @app:src/main/resources/application.properties#6-6 1.0",
            "module:g:app config-value ****** {\"key\":\"jwt.secretKey\",\"masked\":true}"
                + " @app:src/main/resources/application.properties#7-7 1.0",
            "module:g:app config-value ****** {\"key\":\"encrypt.key\",\"masked\":true}"
                + " @app:src/main/resources/application.properties#8-8 1.0",
            "module:g:app config-value ****** {\"key\":\"old.passwords[0]\",\"masked\":true}"
                + " @app:src/main/resources/application.properties#9-9 1.0",
            "module:g:app config-value ****** {\"key\":\"app.db-password\",\"masked\":true}"
                + " @app:src/main/resources/application.properties#10-10 1.0",
            // A secret reached through a placeholder is masked too, in a secret's key or not.
            "module:g:app config-value ****** {\"key\":\"other.datasource.password\","
                + "\"masked\":true} @app:src/main/resources/application.properties#11-11 1.0",
            "module:g:app config-value ****** {\"key\":\"db.url\",\"masked\":true}"
                + " @app:src/main/resources/application.properties#12-12 1.0",
            "module:g:app config-value order.created {\"key\":\"shop.routing-key\"}"
                + " @app:src/main/resources/application.properties#5-5 1.0",
            "module:g:app config-value orders {\"key\":\"spring.datasource.username\"}"
                + " @app:src/main/resources/application.properties#2-2 1.0");
  }

  @Test
  void credentialsWrittenInsideUrlsAreRemoved() throws IOException {
    Repo repo =
        repo(
            Map.of(
                RESOURCES + "application.properties",
                """
                spring.datasource.url=jdbc:postgresql://user:pass@db/shop?password=x&ssl=true
                legacy.url=${DB_URL:jdbc:oracle:thin:scott/tiger@//db:1521/X}
                broker.uri=amqp://guest:guest@rabbit:5672/orders
                cache.url=${cache.base}/0
                cache.base=redis://:pw@cache:6379
                odd.url=jdbc:mysql://address=(host=db)(password=p)/shop
                plain.url=http://localhost:8080/api
                spring.rabbitmq.addresses=guest:guest@r1:5672,guest:guest@r2
                api.url=${PROTO:https}://bot:tok@api.example.com/v1
                blog.url=https://medium.com/@team/post?a=1?b=2
                """));

    assertThat(extract(repo))
        .containsExactlyInAnyOrder(
            "module:g:app config-value amqp://rabbit:5672/orders"
                + " {\"key\":\"broker.uri\",\"masked\":true}"
                + " @app:src/main/resources/application.properties#3-3 1.0",
            "module:g:app config-value http://localhost:8080/api {\"key\":\"plain.url\"}"
                + " @app:src/main/resources/application.properties#7-7 1.0",
            "module:g:app config-value jdbc:oracle:thin:@//db:1521/X"
                + " {\"key\":\"legacy.url\",\"masked\":true,"
                + "\"written\":\"${DB_URL:jdbc:oracle:thin:@//db:1521/X}\"}"
                + " @app:src/main/resources/application.properties#2-2 1.0",
            "module:g:app config-value jdbc:postgresql://db/shop?ssl=true"
                + " {\"key\":\"spring.datasource.url\",\"masked\":true}"
                + " @app:src/main/resources/application.properties#1-1 1.0",
            // A URL a placeholder resolves into loses its credentials too.
            "module:g:app config-value redis://cache:6379"
                + " {\"key\":\"cache.base\",\"masked\":true}"
                + " @app:src/main/resources/application.properties#5-5 1.0",
            "module:g:app config-value redis://cache:6379/0"
                + " {\"key\":\"cache.url\",\"masked\":true,\"written\":\"${cache.base}/0\"}"
                + " @app:src/main/resources/application.properties#4-4 1.0",
            "module:g:app config-value r1:5672,r2"
                + " {\"key\":\"spring.rabbitmq.addresses\",\"masked\":true}"
                + " @app:src/main/resources/application.properties#8-8 1.0",
            "module:g:app config-value https://api.example.com/v1"
                + " {\"key\":\"api.url\",\"masked\":true,"
                + "\"written\":\"${PROTO:https}://api.example.com/v1\"}"
                + " @app:src/main/resources/application.properties#9-9 1.0",
            // A URL without credentials is recorded byte for byte, not marked masked.
            "module:g:app config-value https://medium.com/@team/post?a=1?b=2"
                + " {\"key\":\"blog.url\"}"
                + " @app:src/main/resources/application.properties#10-10 1.0",
            // A credential in a form not understood masks the whole value.
            "module:g:app config-value ****** {\"key\":\"odd.url\",\"masked\":true}"
                + " @app:src/main/resources/application.properties#6-6 1.0");
  }

  @Test
  void secretOptionsInJaasConfigAreRemoved() throws IOException {
    Repo repo =
        repo(
            Map.of(
                RESOURCES + "application.properties",
                """
                spring.kafka.properties.sasl.jaas.config=a.PlainLoginModule required \\
                  username="app" password="x";
                kafka.jaas=a.PlainLoginModule required username="app" password="${kafka.pw}";
                kafka.pw=p1
                kafka.env=a.PlainLoginModule required password="${KAFKA_PASSWORD}";
                kafka.plain=a.PlainLoginModule required username="app";
                kafka.spaced=a.PlainLoginModule required password=${space.pw} user=u;
                space.pw=my secret
                kafka.quoted=a.PlainLoginModule required password="${quote.pw}" user=u;
                quote.pw=a"b
                """));

    assertThat(extract(repo))
        .containsExactlyInAnyOrder(
            "module:g:app config-value a.PlainLoginModule required username=\"app\";"
                + " {\"key\":\"spring.kafka.properties.sasl.jaas.config\",\"masked\":true}"
                + " @app:src/main/resources/application.properties#1-1 1.0",
            // An option a placeholder resolves goes from the written form too.
            "module:g:app config-value a.PlainLoginModule required username=\"app\";"
                + " {\"key\":\"kafka.jaas\",\"masked\":true}"
                + " @app:src/main/resources/application.properties#3-3 1.0",
            "module:g:app config-value p1 {\"key\":\"kafka.pw\"}"
                + " @app:src/main/resources/application.properties#4-4 1.0",
            // An environment variable says where the secret comes from and stays.
            "module:g:app config-value a.PlainLoginModule required password=\"${KAFKA_PASSWORD}\";"
                + " {\"key\":\"kafka.env\",\"unresolved\":[\"KAFKA_PASSWORD\"]}"
                + " @app:src/main/resources/application.properties#5-5 1.0",
            // A substituted secret goes whole, whatever it holds.
            "module:g:app config-value a.PlainLoginModule required user=u;"
                + " {\"key\":\"kafka.spaced\",\"masked\":true}"
                + " @app:src/main/resources/application.properties#7-7 1.0",
            "module:g:app config-value my secret {\"key\":\"space.pw\"}"
                + " @app:src/main/resources/application.properties#8-8 1.0",
            "module:g:app config-value a.PlainLoginModule required user=u;"
                + " {\"key\":\"kafka.quoted\",\"masked\":true}"
                + " @app:src/main/resources/application.properties#9-9 1.0",
            "module:g:app config-value a\"b {\"key\":\"quote.pw\"}"
                + " @app:src/main/resources/application.properties#10-10 1.0",
            // A value without a secret is recorded byte for byte.
            "module:g:app config-value a.PlainLoginModule required username=\"app\";"
                + " {\"key\":\"kafka.plain\"}"
                + " @app:src/main/resources/application.properties#6-6 1.0");
  }

  @Test
  void aFileSpringBootCannotReadIsLeftOut() throws IOException {
    Repo repo =
        repo(
            Map.of(
                RESOURCES + "application.yml",
                "a: [unclosed\n",
                RESOURCES + "application.properties",
                "b=1\n"));

    assertThat(extract(repo))
        .containsExactly(
            "module:g:app config-value 1 {\"key\":\"b\"}"
                + " @app:src/main/resources/application.properties#1-1 1.0");
  }

  @Test
  void configBelongsToItsModuleAndOtherFilesAreNotRead() throws IOException {
    Map<String, String> files = new LinkedHashMap<>();
    files.put(
        "pom.xml",
        "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId>"
            + "<artifactId>parent</artifactId><version>1</version><packaging>pom</packaging>"
            + "<modules><module>svc</module></modules></project>");
    files.put(
        "svc/pom.xml",
        "<project><modelVersion>4.0.0</modelVersion><parent><groupId>g</groupId>"
            + "<artifactId>parent</artifactId><version>1</version></parent>"
            + "<artifactId>svc</artifactId></project>");
    files.put("svc/" + RESOURCES + "application.yml", "a: 1\n");
    files.put("svc/src/test/resources/application.yml", "a: test\n");
    files.put("svc/" + RESOURCES + "other.yml", "b: 2\n");
    files.put("svc/" + RESOURCES + "nested/application.yml", "c: 3\n");
    files.put("unlisted/" + RESOURCES + "application.yml", "d: 4\n");
    Repo repo = Fixtures.repo(temp.resolve("multi"), "multi", files);

    List<Fact> facts =
        new ConfigExtractor().extract(List.of(new RepoBuild(repo, Fixtures.head(repo), null)), 1);

    assertThat(Fixtures.render(facts).lines())
        .containsExactly(
            "module:g:svc config-value 1 {\"key\":\"a\"}"
                + " @multi:svc/src/main/resources/application.yml#1-1 1.0");
  }

  private static final String PROCESSOR =
      """
      package lib;

      import java.util.Map;
      import org.springframework.boot.env.EnvironmentPostProcessor;

      public class Defaults implements EnvironmentPostProcessor {
        static final String ISSUER = "spring.security.oauth2.resourceserver.jwt.issuer-uri";

        public void postProcessEnvironment(Object environment, Object application) {
          Map.of(
              ISSUER, "${ISSUER_URI:http://localhost:8080/realms/r}",
              "client.api-key", "${KEY:abc123}",
              "client." + "name", "lib");
          Map<String, Object> map = new java.util.HashMap<>();
          map.put("computed", name());
          map.put("not a key", "x");
        }
      }
      """;

  @Test
  void literalDefaultsOfAnEnvironmentPostProcessorAreFactsWithTheirSource() throws IOException {
    Repo repo =
        repo(
            Map.of(
                "src/main/java/lib/Defaults.java",
                PROCESSOR,
                RESOURCES + "META-INF/spring.factories",
                "org.springframework.boot.env.EnvironmentPostProcessor=\\\n  lib.Defaults\n"));

    List<String> lines = extract(repo);

    assertThat(lines)
        .containsExactly(
            "module:g:app config-value ****** {\"key\":\"client.api-key\",\"masked\":true,"
                + "\"source\":\"environment-post-processor\"}"
                + " @app:src/main/java/lib/Defaults.java#12-12 1.0",
            "module:g:app config-value http://localhost:8080/realms/r {\"key\":"
                + "\"spring.security.oauth2.resourceserver.jwt.issuer-uri\","
                + "\"source\":\"environment-post-processor\","
                + "\"written\":\"${ISSUER_URI:http://localhost:8080/realms/r}\"}"
                + " @app:src/main/java/lib/Defaults.java#11-11 1.0",
            "module:g:app config-value lib {\"key\":\"client.name\","
                + "\"source\":\"environment-post-processor\"}"
                + " @app:src/main/java/lib/Defaults.java#13-13 1.0");
  }

  @Test
  void importsFilesAndClassesThatAreNotPostProcessorsAreHandled() throws IOException {
    Repo repo =
        repo(
            Map.of(
                "src/main/java/lib/Defaults.java",
                PROCESSOR,
                "src/main/java/lib/Other.java",
                "package lib; public class Other { void f(java.util.Map<String,String> m)"
                    + " { m.put(\"x.y\", \"z\"); } }",
                RESOURCES
                    + "META-INF/spring/org.springframework.boot.autoconfigure."
                    + "AutoConfiguration.imports",
                "# comment\nlib.Other\nlib.Defaults\n"));

    assertThat(extract(repo)).hasSize(3).allMatch(l -> !l.contains("x.y"));
  }

  /** A config server, a client, and a config repository without Maven modules. */
  private List<RepoBuild> configServerSystem(boolean withServer) throws IOException {
    Repo server =
        Fixtures.repo(
            temp.resolve("server"),
            "server",
            Map.of(
                "pom.xml",
                POM.replace("<artifactId>app", "<artifactId>server"),
                RESOURCES + "application.yml",
                withServer
                    ? """
                    spring:
                      cloud:
                        config:
                          server:
                            native:
                              search-locations: file:///config/
                    """
                    : "server:\n  port: 8888\n"));
    Repo orders =
        Fixtures.repo(
            temp.resolve("orders"),
            "orders",
            Map.of(
                "pom.xml",
                POM.replace("<artifactId>app", "<artifactId>orders"),
                RESOURCES + "application.yml",
                """
                spring:
                  application:
                    name: orders-service
                  cloud:
                    config:
                      name: ${CONFIG_NAMES:orders,orders-service}
                """));
    Repo repository =
        Fixtures.repo(
            temp.resolve("config-repo"),
            "config-repo",
            Map.of(
                "application.yml",
                """
                eureka:
                  client:
                    service-url:
                      defaultZone: http://discovery:8761/eureka/
                """,
                "orders-service.yml",
                """
                spring:
                  datasource:
                    url: jdbc:postgresql://${DB_HOST:orders-db}:5432/orders
                    password: '{cipher}AQBx7b1e'
                    username: ${ORDERS_USER:'{cipher}AQBx7b1f'}
                app:
                  hint: '{cipher}AQBx7b1g'
                """,
                "orders-service.properties",
                "spring.datasource.url=jdbc:postgresql://orders-db:5432/orders_main\n",
                "application-dev.yml",
                "logging:\n  level:\n    root: DEBUG\n",
                "orders-dev.yml",
                "orders:\n  mode: dev\n",
                "orders-service/orders-service.yml",
                "nested:\n  read: false\n",
                "orders-service-dev.yml",
                "spring:\n  datasource:\n    url: jdbc:postgresql://localhost:5432/orders\n",
                "docker-compose.yml",
                "services:\n  db:\n    image: postgres\n",
                "billing.yml",
                "billing:\n  url: http://billing\n",
                "README.md",
                "# Config\n"));
    return List.of(
        new RepoBuild(server, Fixtures.head(server), null),
        new RepoBuild(orders, Fixtures.head(orders), null),
        new RepoBuild(repository, Fixtures.head(repository), null));
  }

  @Test
  void aConfigRepositoryServesItsFilesToTheApplicationsTheyAreNamedAfter() throws IOException {
    List<RepoBuild> repos = configServerSystem(true);
    List<Fact> facts = new ConfigExtractor().extract(repos, 1);
    Fixtures.checkEnvelope(
        facts,
        repos.stream().map(RepoBuild::repo).toList(),
        1,
        ConfigExtractor.NAME,
        ConfigExtractor.VERSION);

    List<String> lines =
        Fixtures.render(facts).lines().filter(l -> l.startsWith("repo:config-repo ")).toList();
    assertThat(lines)
        .contains(
            // The shared file every application gets.
            "repo:config-repo config-value http://discovery:8761/eureka/"
                + " {\"application\":\"application\","
                + "\"key\":\"eureka.client.service-url.defaultZone\","
                + "\"source\":\"config-repository\"}"
                + " @config-repo:application.yml#4-4 1.0",
            // Properties win over YAML for the same application.
            "repo:config-repo config-value jdbc:postgresql://orders-db:5432/orders_main"
                + " {\"application\":\"orders-service\",\"key\":\"spring.datasource.url\","
                + "\"source\":\"config-repository\"}"
                + " @config-repo:orders-service.properties#1-1 1.0",
            // The longest known name the file starts with: orders-service, not orders.
            "repo:config-repo config-value jdbc:postgresql://localhost:5432/orders"
                + " {\"application\":\"orders-service\",\"key\":\"spring.datasource.url\","
                + "\"profile\":\"dev\",\"source\":\"config-repository\"}"
                + " @config-repo:orders-service-dev.yml#3-3 1.0",
            // The shared file's profile.
            "repo:config-repo config-value DEBUG"
                + " {\"application\":\"application\",\"key\":\"logging.level.root\","
                + "\"profile\":\"dev\",\"source\":\"config-repository\"}"
                + " @config-repo:application-dev.yml#3-3 1.0",
            // Each name of spring.cloud.config.name, its default resolved.
            "repo:config-repo config-value dev"
                + " {\"application\":\"orders\",\"key\":\"orders.mode\","
                + "\"profile\":\"dev\",\"source\":\"config-repository\"}"
                + " @config-repo:orders-dev.yml#2-2 1.0");
    // Encrypted values are masked whatever the key, also when reached through a placeholder.
    assertThat(lines)
        .filteredOn(l -> l.contains("\"key\":\"app.hint\"") || l.contains("datasource.username"))
        .hasSize(2)
        .allMatch(l -> l.contains(" ****** ") && l.contains("\"masked\":true"));
    assertThat(Fixtures.render(facts))
        .doesNotContain("AQBx7b1")
        .doesNotContain("billing")
        .doesNotContain("nested.read");
  }

  @Test
  void withoutAConfigServerARepositoryWithoutModulesIsNotReadAsOne() throws IOException {
    List<Fact> facts = new ConfigExtractor().extract(configServerSystem(false), 1);

    assertThat(facts).noneMatch(f -> f.subject().toString().startsWith("repo:"));
  }

  @Test
  void aNativeServersFolderInItsOwnRepositoryIsReadAsAConfigRepository() throws IOException {
    Repo server =
        Fixtures.repo(
            temp.resolve("platform-config"),
            "platform-config",
            Map.of(
                "pom.xml",
                POM,
                RESOURCES + "application.yml",
                """
                spring:
                  application:
                    name: config-server
                  cloud:
                    config:
                      server:
                        native:
                          search-locations: file:./config-repo, classpath:/shared, file:/etc/x
                """,
                "config-repo/orders-service.yml",
                "orders:\n  mode: served\n",
                "config-repo/unknown-app.yml",
                "unknown:\n  read: false\n",
                "other/orders-service.yml",
                "orders:\n  mode: elsewhere\n"));
    Repo orders =
        Fixtures.repo(
            temp.resolve("orders"),
            "orders",
            Map.of(
                "pom.xml",
                POM.replace("<artifactId>app", "<artifactId>orders"),
                RESOURCES + "application.yml",
                "spring:\n  application:\n    name: orders-service\n"));

    List<Fact> facts =
        new ConfigExtractor()
            .extract(
                List.of(
                    new RepoBuild(server, Fixtures.head(server), null),
                    new RepoBuild(orders, Fixtures.head(orders), null)),
                1);

    assertThat(Fixtures.render(facts).lines().filter(l -> l.startsWith("repo:platform-config ")))
        .containsExactly(
            "repo:platform-config config-value served"
                + " {\"application\":\"orders-service\",\"key\":\"orders.mode\","
                + "\"source\":\"config-repository\"}"
                + " @platform-config:config-repo/orders-service.yml#2-2 1.0");
  }
}

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
}

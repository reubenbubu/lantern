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
import io.lantern.core.vocabulary.VocabularyType;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.deployment.DeploymentExtractor;
import io.lantern.extract.docs.DocsExtractor;
import io.lantern.extract.frontend.FrontendExtractor;
import io.lantern.extract.gateway.GatewayExtractor;
import io.lantern.extract.http.HttpClientExtractor;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.extract.maven.MavenExtractor;
import io.lantern.extract.messaging.MessagingExtractor;
import io.lantern.extract.people.PeopleExtractor;
import io.lantern.extract.persistence.PersistenceExtractor;
import io.lantern.extract.redis.RedisExtractor;
import io.lantern.extract.spring.SpringExtractor;
import io.lantern.extract.sql.SqlExtractor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/** The rules for deployable units, on facts written for each case. */
class DeployableUnitsTest {

  private static final String COMMIT = "a".repeat(40);
  private static final String NAME = DeployableUnits.NAME_KEY;

  private final List<Fact> facts = new ArrayList<>();

  // ---------------------------------------------------------------------------------------------
  // Writing the extractors' facts

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
  private static Evidence evidence(String where) {
    String repo = where.substring(0, where.indexOf(':'));
    String rest = where.substring(where.indexOf(':') + 1);
    Evidence.LineRange lines = null;
    if (rest.contains("#")) {
      int line = Integer.parseInt(rest.substring(rest.indexOf('#') + 1));
      lines = new Evidence.LineRange(line, line);
      rest = rest.substring(0, rest.indexOf('#'));
    }
    return Evidence.of(repo, rest, lines, null);
  }

  /** A module building a service or library, in a repository, with its packaging. */
  private void module(String repo, String container, String packaging) {
    String key = container.substring(container.indexOf(':') + 1);
    String dir = key.substring(key.indexOf(':') + 1);
    add(
        MavenExtractor.NAME,
        container,
        BuiltInTypes.CONTAINS,
        FactObject.entity(EntityId.parse("module:" + key)),
        null,
        repo + ":" + dir + "/pom.xml",
        1.0);
    add(
        MavenExtractor.NAME,
        "module:" + key,
        MavenExtractor.PACKAGING,
        FactObject.string(packaging),
        null,
        repo + ":" + dir + "/pom.xml",
        1.0);
  }

  private void dependency(String repo, String from, String to, String attributes, int line) {
    add(
        MavenExtractor.NAME,
        "module:" + from,
        MavenExtractor.BUILD_DEPENDS_ON,
        FactObject.entity(EntityId.parse(to)),
        attributes,
        repo + ":" + from.substring(from.indexOf(':') + 1) + "/pom.xml#" + line,
        1.0);
  }

  private void javaRelease(String repo, String module, String release) {
    add(
        MavenExtractor.NAME,
        "module:" + module,
        MavenExtractor.JAVA_RELEASE,
        FactObject.string(release),
        null,
        repo + ":pom.xml#7",
        1.0);
  }

  /** A Spring Boot application class in a module, through its package. */
  private void bootMain(String repo, String module, String type) {
    String pkg = type.substring(0, type.lastIndexOf('.'));
    String file = repo + ":src/main/java/" + type.replace('.', '/') + ".java";
    add(
        JavaExtractor.NAME,
        "module:" + module,
        BuiltInTypes.CONTAINS,
        FactObject.entity(EntityId.parse("package:" + module + ":" + pkg)),
        null,
        file,
        1.0);
    add(
        JavaExtractor.NAME,
        "package:" + module + ":" + pkg,
        BuiltInTypes.CONTAINS,
        FactObject.entity(EntityId.parse("type:" + type)),
        null,
        file,
        1.0);
    add(
        SpringExtractor.NAME,
        "type:" + type,
        SpringExtractor.MAIN_CLASS,
        FactObject.string(SpringExtractor.SPRING_BOOT),
        null,
        file + "#5",
        1.0);
  }

  private void config(String repo, String module, String key, String value, String profile) {
    config(repo, module, "application.yml", key, value, profile);
  }

  /** A config value, defined in the given file of the module's resources. */
  private void config(
      String repo, String module, String file, String key, String value, String profile) {
    add(
        ConfigExtractor.NAME,
        "module:" + module,
        ConfigExtractor.CONFIG_VALUE,
        FactObject.string(value),
        profile == null
            ? "{\"key\":\"" + key + "\"}"
            : "{\"key\":\"" + key + "\",\"profile\":\"" + profile + "\"}",
        repo
            + ":"
            + module.substring(module.indexOf(':') + 1)
            + "/src/main/resources/"
            + file
            + "#3",
        1.0);
  }

  // ---------------------------------------------------------------------------------------------
  // Reading what the step derives

  private List<String> derive() {
    List<Fact> derived = new DeployableUnits().derive(facts, 1);
    Vocabulary vocabulary = vocabulary();
    for (Fact fact : derived) {
      vocabulary.validate(fact);
      assertThat(fact.origin().name()).isEqualTo(DeployableUnits.NAME);
      assertThat(fact.validity()).isEqualTo(Validity.from(1));
    }
    return derived.stream()
        .map(
            f -> {
              String where = f.evidence().repo() + ":" + f.evidence().path();
              if (f.evidence().lines() != null) {
                where += "#" + f.evidence().lines().start();
              }
              return f.subject()
                  + " "
                  + f.predicate()
                  + " "
                  + f.object().value()
                  + (f.attributes().isEmpty() ? "" : " " + f.attributes())
                  + " @"
                  + where
                  + " "
                  + f.confidence();
            })
        .toList();
  }

  /** The built-in vocabulary with the extractors' and this step's types, as the app applies it. */
  static Vocabulary vocabulary() {
    Map<String, VocabularyType> types = new TreeMap<>();
    BuiltInTypes.definitions().forEach(t -> types.put(t.name(), t));
    Vocabulary vocabulary = new Vocabulary(1, types.values());
    for (List<VocabularyType> step :
        List.of(
            MavenExtractor.vocabulary(),
            JavaExtractor.vocabulary(),
            ConfigExtractor.vocabulary(),
            DocsExtractor.vocabulary(),
            SqlExtractor.vocabulary(),
            SpringExtractor.vocabulary(),
            PersistenceExtractor.vocabulary(),
            HttpClientExtractor.vocabulary(),
            MessagingExtractor.vocabulary(),
            RedisExtractor.vocabulary(),
            PeopleExtractor.vocabulary(),
            DeploymentExtractor.vocabulary(),
            FrontendExtractor.vocabulary(),
            GatewayExtractor.vocabulary(),
            DeployableUnits.vocabulary(),
            CrossRepoLinks.vocabulary(),
            ClaimChecks.vocabulary())) {
      vocabulary.changes(step).forEach(t -> types.put(t.name(), t));
      vocabulary = new Vocabulary(vocabulary.revision() + 1, types.values());
    }
    return vocabulary;
  }

  // ---------------------------------------------------------------------------------------------
  // Cases

  @Test
  void aThinServiceAndItsLibraryAreOneUnitNamedByTheLibrarysConfig() {
    module("r", "service:g:orders-service", "jar");
    module("r", "library:g:orders-lib", "jar");
    dependency("r", "g:orders-service", "module:g:orders-lib", "{\"scope\":\"compile\"}", 12);
    dependency(
        "r",
        "g:orders-lib",
        "artifact:org.springframework.boot:spring-boot-starter-web",
        "{\"scope\":\"compile\",\"version\":\"3.5.14\"}",
        20);
    dependency(
        "r",
        "g:orders-lib",
        "artifact:org.springframework.boot:spring-boot-autoconfigure",
        "{\"scope\":\"compile\",\"version\":\"3.5.14\"}",
        25);
    add(
        MavenExtractor.NAME,
        "service:g:orders-service",
        MavenExtractor.PACKAGES,
        FactObject.entity(EntityId.parse("library:g:orders-lib")),
        null,
        "r:orders-service/pom.xml#12",
        0.8);
    javaRelease("r", "g:orders-lib", "21");
    bootMain("r", "g:orders-lib", "g.orders.OrdersApplication");
    config("r", "g:orders-lib", "spring.application.name", "orders", null);
    config("r", "g:orders-lib", "spring.application.name", "orders-local", "local");
    config("r", "g:orders-lib", "server.port", "8080", null);

    assertThat(derive())
        .containsExactly(
            "service:g:orders-service BUNDLES library:g:orders-lib {\"main\":true}"
                + " @r:orders-service/pom.xml#12 1.0",
            "service:g:orders-service application-name orders {\"module\":\"module:g:orders-lib\"}"
                + " @r:orders-lib/src/main/resources/application.yml#3 1.0",
            "service:g:orders-service application-name orders-local"
                + " {\"module\":\"module:g:orders-lib\",\"view\":\"local\"}"
                + " @r:orders-lib/src/main/resources/application.yml#3 1.0",
            "service:g:orders-service deployable-unit spring-boot"
                + " {\"mainClass\":\"type:g.orders.OrdersApplication\",\"packaging\":\"jar\"}"
                + " @r:src/main/java/g/orders/OrdersApplication.java#5 1.0",
            "service:g:orders-service technology Spring Boot 3.5.14 / Java 21"
                + " {\"framework\":\"Spring Boot\",\"frameworkVersion\":\"3.5.14\",\"java\":\"21\"}"
                + " @r:orders-lib/pom.xml#25 1.0");
  }

  @Test
  void aGatewayNamesSpringCloudGatewayInItsTechnology() {
    module("r", "service:g:gateway", "jar");
    dependency(
        "r",
        "g:gateway",
        "artifact:org.springframework.boot:spring-boot-starter-webflux",
        "{\"scope\":\"compile\",\"version\":\"3.5.14\"}",
        10);
    dependency(
        "r",
        "g:gateway",
        "artifact:org.springframework.cloud:spring-cloud-starter-gateway-server-webflux",
        "{\"scope\":\"compile\",\"version\":\"4.3.0\"}",
        11);
    dependency(
        "r",
        "g:gateway",
        "artifact:org.springframework.boot:spring-boot-starter",
        "{\"scope\":\"compile\",\"version\":\"3.5.14\"}",
        12);
    javaRelease("r", "g:gateway", "21");
    bootMain("r", "g:gateway", "g.gateway.Application");

    assertThat(derive())
        .anyMatch(
            f ->
                f.startsWith(
                    "service:g:gateway technology Spring Boot 3.5.14 / Spring Cloud Gateway 4.3.0"
                        + " / Java 21 "));
  }

  @Test
  void aTestScopedStarterOrWebClientInAServletUnitIsNotAFramework() {
    module("r", "service:g:site", "jar");
    dependency(
        "r",
        "g:site",
        "artifact:org.springframework.boot:spring-boot-starter-web",
        "{\"scope\":\"compile\"}",
        10);
    dependency(
        "r",
        "g:site",
        "artifact:org.springframework.boot:spring-boot-starter-webflux",
        "{\"scope\":\"compile\"}",
        11);
    dependency(
        "r",
        "g:site",
        "artifact:org.springframework.boot:spring-boot-starter-websocket",
        "{\"scope\":\"test\"}",
        12);
    javaRelease("r", "g:site", "21");
    bootMain("r", "g:site", "g.site.Application");

    assertThat(derive())
        .anyMatch(f -> f.startsWith("service:g:site technology Spring Boot / Java 21 "));
  }

  @Test
  void aMonolithBundlesEveryLibraryOnItsRuntimeClasspathOnce() {
    module("r", "service:g:app", "jar");
    module("r", "library:g:model", "jar");
    module("r", "library:g:integration", "jar");
    module("r", "library:g:util", "jar");
    module("r", "library:g:extra", "jar");
    module("r", "library:g:testing", "jar");
    module("r", "service:g:other", "jar");
    dependency("r", "g:app", "module:g:model", "{\"scope\":\"compile\"}", 10);
    dependency("r", "g:app", "module:g:integration", "{\"scope\":\"runtime\"}", 11);
    dependency("r", "g:app", "module:g:testing", "{\"scope\":\"test\"}", 12);
    dependency("r", "g:app", "module:g:other", "{\"scope\":\"compile\"}", 13);
    dependency(
        "r",
        "g:app",
        "module:g:model",
        "{\"scope\":\"compile\",\"artifactType\":\"test-jar\"}",
        14);
    dependency("r", "g:integration", "module:g:model", "{\"scope\":\"compile\"}", 10);
    dependency("r", "g:integration", "module:g:util", "{\"scope\":\"compile\"}", 11);
    dependency(
        "r", "g:integration", "module:g:extra", "{\"scope\":\"compile\",\"optional\":true}", 12);
    javaRelease("r", "g:app", "17");
    bootMain("r", "g:app", "g.app.App");
    config("r", "g:app", "spring.application.name", "shop", null);
    // A library's config is not loaded when the application has its own.
    config("r", "g:integration", "spring.application.name", "integration", null);

    List<String> derived = derive();

    assertThat(derived.stream().filter(f -> f.startsWith("service:g:app BUNDLES ")))
        .containsExactly(
            "service:g:app BUNDLES library:g:integration @r:app/pom.xml#11 1.0",
            "service:g:app BUNDLES library:g:model @r:app/pom.xml#10 1.0",
            "service:g:app BUNDLES library:g:util {\"via\":[\"library:g:integration\"]}"
                + " @r:app/pom.xml#11 1.0");
    assertThat(derived)
        .contains(
            "service:g:app application-name shop {\"module\":\"module:g:app\"}"
                + " @r:app/src/main/resources/application.yml#3 1.0",
            "service:g:app technology Spring Boot / Java 17"
                + " {\"framework\":\"Spring Boot\",\"java\":\"17\"} @r:pom.xml#7 1.0");
    // The other service is a unit of its own, named by its artifact ID as no config names it.
    assertThat(derived)
        .contains(
            "service:g:other application-name other {\"defaulted\":true} @r:other/pom.xml 0.5",
            "service:g:other deployable-unit executable-jar {\"packaging\":\"jar\"}"
                + " @r:other/pom.xml 1.0");
  }

  @Test
  void aWarWithoutAnApplicationClassIsAUnitWithoutTechnology() {
    module("r", "service:g:web", "war");

    assertThat(derive())
        .containsExactly(
            "service:g:web application-name web {\"defaulted\":true} @r:web/pom.xml 0.5",
            "service:g:web deployable-unit war {\"packaging\":\"war\"} @r:web/pom.xml 1.0");
  }

  @Test
  void aLibraryWithAnApplicationClassNoServiceBundlesIsAUnitOfItsOwn() {
    module("r", "library:g:worker", "jar");
    module("r", "library:g:core", "jar");
    module("r", "service:g:app", "jar");
    module("r", "library:g:packaged", "jar");
    dependency("r", "g:worker", "module:g:core", "{\"scope\":\"compile\"}", 9);
    dependency("r", "g:app", "module:g:packaged", "{\"scope\":\"compile\"}", 9);
    bootMain("r", "g:worker", "g.worker.Worker");
    bootMain("r", "g:packaged", "g.packaged.App");

    List<String> derived = derive();

    assertThat(derived.stream().filter(f -> f.startsWith("service:g:worker ")))
        .containsExactly(
            "service:g:worker BUNDLES library:g:core @r:worker/pom.xml#9 1.0",
            "service:g:worker BUNDLES library:g:worker {\"main\":true}"
                + " @r:src/main/java/g/worker/Worker.java#5 0.8",
            "service:g:worker application-name worker {\"defaulted\":true}"
                + " @r:src/main/java/g/worker/Worker.java#5 0.4",
            "service:g:worker deployable-unit spring-boot"
                + " {\"mainClass\":\"type:g.worker.Worker\",\"packaging\":\"jar\"}"
                + " @r:src/main/java/g/worker/Worker.java#5 0.8",
            "service:g:worker technology Spring Boot {\"framework\":\"Spring Boot\"}"
                + " @r:src/main/java/g/worker/Worker.java#5 0.8");
    // The packaged library's application class starts the service that bundles it.
    assertThat(derived)
        .noneMatch(f -> f.startsWith("service:g:packaged "))
        .contains(
            "service:g:app BUNDLES library:g:packaged {\"main\":true} @r:app/pom.xml#9 1.0",
            "service:g:app deployable-unit spring-boot"
                + " {\"mainClass\":\"type:g.packaged.App\",\"packaging\":\"jar\"}"
                + " @r:src/main/java/g/packaged/App.java#5 1.0");
  }

  @Test
  void factsFromAnotherRepositoryLendTheUnitTheirValueButNotTheirEvidence() {
    module("svc", "service:g:svc", "jar");
    module("lib", "library:g:lib", "jar");
    dependency("svc", "g:svc", "module:g:lib", "{\"scope\":\"compile\"}", 9);
    bootMain("lib", "g:lib", "g.lib.App");
    config("lib", "g:lib", "spring.application.name", "thing", null);

    assertThat(derive())
        .containsExactly(
            "service:g:svc BUNDLES library:g:lib {\"main\":true} @svc:svc/pom.xml#9 1.0",
            "service:g:svc application-name thing {\"module\":\"module:g:lib\"}"
                + " @svc:svc/pom.xml 1.0",
            "service:g:svc deployable-unit spring-boot"
                + " {\"mainClass\":\"type:g.lib.App\",\"packaging\":\"jar\"} @svc:svc/pom.xml 1.0",
            "service:g:svc technology Spring Boot {\"framework\":\"Spring Boot\"}"
                + " @svc:svc/pom.xml 1.0");
  }

  @Test
  void theApplicationAndBootstrapFilesComeEachFromTheFirstModuleThatHasThem() {
    module("r", "service:g:svc", "jar");
    module("r", "library:g:lib", "jar");
    dependency("r", "g:svc", "module:g:lib", "{\"scope\":\"compile\"}", 9);
    // The service's application.yml hides the library's, but not the library's bootstrap.yml.
    config("r", "g:svc", "application.yml", "server.port", "8080", null);
    config("r", "g:svc", "application-cloud.yml", NAME, "svc-cloud", "cloud");
    config("r", "g:lib", "application.yml", NAME, "hidden", null);
    config("r", "g:lib", "bootstrap.yml", NAME, "from-bootstrap", null);
    config("r", "g:lib", "bootstrap-dev.yml", NAME, "from-bootstrap-dev", "dev");

    assertThat(derive().stream().filter(f -> f.contains(" application-name ")))
        .containsExactly(
            "service:g:svc application-name from-bootstrap {\"module\":\"module:g:lib\"}"
                + " @r:lib/src/main/resources/bootstrap.yml#3 1.0",
            "service:g:svc application-name from-bootstrap-dev"
                + " {\"module\":\"module:g:lib\",\"view\":\"dev\"}"
                + " @r:lib/src/main/resources/bootstrap-dev.yml#3 1.0",
            "service:g:svc application-name svc-cloud"
                + " {\"module\":\"module:g:svc\",\"view\":\"cloud\"}"
                + " @r:svc/src/main/resources/application-cloud.yml#3 1.0");
  }

  @Test
  void aNameInTheApplicationFilesWinsOverTheBootstrapFilesInEveryProfile() {
    module("r", "service:g:svc", "jar");
    config("r", "g:svc", "bootstrap.yml", NAME, "boot", null);
    config("r", "g:svc", "bootstrap-dev.yml", NAME, "boot-dev", "dev");
    config("r", "g:svc", "application.yml", NAME, "app", null);

    assertThat(derive().stream().filter(f -> f.contains(" application-name ")))
        .containsExactly(
            "service:g:svc application-name app {\"module\":\"module:g:svc\"}"
                + " @r:svc/src/main/resources/application.yml#3 1.0");
  }

  @Test
  void withoutDependencyVersionsSpringBootsVersionComesFromItsParentPom() {
    module("r", "service:g:svc", "jar");
    add(
        MavenExtractor.NAME,
        "module:g:svc",
        MavenExtractor.PARENT,
        FactObject.string("g:parent:1"),
        null,
        "r:svc/pom.xml#4",
        0.7);
    add(
        MavenExtractor.NAME,
        "module:g:parent",
        MavenExtractor.PARENT,
        FactObject.string("org.springframework.boot:spring-boot-starter-parent:3.5.14"),
        null,
        "r:pom.xml#4",
        0.7);
    dependency(
        "r",
        "g:svc",
        "artifact:org.springframework.boot:spring-boot-starter-web",
        "{\"scope\":\"compile\"}",
        12);

    assertThat(derive())
        .contains(
            "service:g:svc technology Spring Boot 3.5.14"
                + " {\"framework\":\"Spring Boot\",\"frameworkVersion\":\"3.5.14\"}"
                + " @r:pom.xml#4 1.0");
  }

  /** A module's version, as its POM declares it. */
  private void version(String repo, String module, String version) {
    add(
        MavenExtractor.NAME,
        "module:" + module,
        MavenExtractor.VERSION_ATTRIBUTE,
        FactObject.string(version),
        null,
        repo + ":" + module.substring(module.indexOf(':') + 1) + "/pom.xml#8",
        1.0);
  }

  @Test
  void unitsAskingForOtherVersionsOfALibraryThanItBuildsAreAVersionSkew() {
    module("a", "service:g:alpha", "jar");
    module("b", "service:g:beta", "jar");
    module("c", "service:g:gamma", "jar");
    module("lib", "library:com.acme:core", "jar");
    module("lib", "library:com.acme:rbac", "jar");
    version("lib", "com.acme:core", "0.3.0-SNAPSHOT");
    version("lib", "com.acme:rbac", "0.3.0-SNAPSHOT");
    String jitPack = "\"coordinates\":\"com.github.Acme:shared-lib\",\"scope\":\"compile\"";
    dependency(
        "a",
        "g:alpha",
        "module:com.acme:core",
        "{" + jitPack + ",\"version\":\"0.2.0-SNAPSHOT\"}",
        9);
    dependency(
        "b",
        "g:beta",
        "module:com.acme:core",
        "{" + jitPack + ",\"version\":\"0.3.0-SNAPSHOT\"}",
        9);
    dependency(
        "c",
        "g:gamma",
        "module:com.acme:rbac",
        "{\"scope\":\"compile\",\"version\":\"0.3.0-SNAPSHOT\"}",
        9);
    // rbac reaches core through its own dependency, at the version the library builds.
    dependency(
        "lib",
        "com.acme:rbac",
        "module:com.acme:core",
        "{\"scope\":\"compile\",\"version\":\"0.3.0-SNAPSHOT\"}",
        12);

    List<String> derived = derive();

    assertThat(derived.stream().filter(f -> f.contains(" version-skew ")))
        .containsExactly(
            "library:com.acme:core version-skew 0.3.0-SNAPSHOT"
                + " {\"pins\":{\"service:g:alpha\":\"0.2.0-SNAPSHOT\","
                + "\"service:g:beta\":\"0.3.0-SNAPSHOT\",\"service:g:gamma\":\"0.3.0-SNAPSHOT\"},"
                + "\"versions\":[\"0.2.0-SNAPSHOT\",\"0.3.0-SNAPSHOT\"]}"
                + " @lib:core/pom.xml#8 1.0");
  }

  @Test
  void noFactsNoUnits() {
    assertThat(new DeployableUnits().derive(List.of(), 1)).isEmpty();
  }
}

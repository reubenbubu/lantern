package io.lantern.link;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.repo.Repo;
import io.lantern.core.vocabulary.Vocabulary;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.java.JavaAnalysis;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.extract.maven.MavenExtractor;
import io.lantern.extract.spring.SpringExtractor;
import io.lantern.sandbox.BuildSandbox;
import io.lantern.sandbox.MavenMirror;
import io.lantern.sandbox.SandboxSettings;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * The deployable units of the synthetic test set project-0 (docs/adr/0029), checked against the
 * cases its README lists, named by id: with the repositories built in the sandbox, and read without
 * builds (degraded mode). Runs only when {@code lantern.test.project0} names the folder {@code
 * testsets/project-0/setup.sh} set it up in; see Commands in {@code CLAUDE.md}. Needs Docker and
 * Maven Central, or the Maven mirror of the test properties; the builds use the default shared
 * Maven cache directory.
 */
@EnabledIfSystemProperty(named = "lantern.test.project0", matches = ".+")
class Project0DeployableUnitsIT {

  private static final String SHOP = "service:com.acme.shop:";

  @TempDir static Path temp;

  private static List<String> built;
  private static List<String> unbuilt;

  @BeforeAll
  static void derive() throws IOException {
    BuildSandbox sandbox = new BuildSandbox(sandboxSettings(temp.resolve("builds")));
    Map<String, Repo> project = Project0.repos();
    List<RepoBuild> withoutBuilds = Project0.unbuilt(project);
    List<RepoBuild> withBuilds = Project0.built(project, sandbox);
    built = render(extractAndDerive(withBuilds));
    unbuilt = render(extractAndDerive(withoutBuilds));
  }

  /** The facts the app derives from, made as the app makes them, and the derived ones. */
  private static List<Fact> extractAndDerive(List<RepoBuild> repos) {
    List<Fact> facts = new ArrayList<>(new MavenExtractor().extract(repos, 1));
    JavaAnalysis code = JavaExtractor.analyze(repos);
    facts.addAll(new JavaExtractor().extract(code, 1));
    facts.addAll(new ConfigExtractor().extract(repos, 1));
    facts.addAll(new SpringExtractor().extract(code, 1));
    List<Fact> derived = new DeployableUnits().derive(facts, 1);
    Vocabulary vocabulary = DeployableUnitsTest.vocabulary();
    derived.forEach(vocabulary::validate);
    return derived;
  }

  /** One line per fact, as in DeployableUnitsTest, without the confidence. */
  private static List<String> render(List<Fact> facts) {
    return facts.stream()
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
                  + where;
            })
        .toList();
  }

  /** The sandbox settings of the extractors' tests, with the Maven mirror if one is set. */
  private static SandboxSettings sandboxSettings(Path buildsDirectory) {
    String file = System.getProperty("lantern.test.properties");
    if (file == null || file.isBlank()) {
      file = System.getenv("LANTERN_TEST_PROPERTIES");
    }
    SandboxSettings settings = SandboxSettings.defaults(buildsDirectory);
    if (file == null || file.isBlank()) {
      return settings;
    }
    try {
      return settings.withMavenMirror(MavenMirror.from(Path.of(file)));
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read the test properties " + file, e);
    }
  }

  private static List<String> units(List<String> facts) {
    return facts.stream()
        .filter(f -> f.contains(" deployable-unit "))
        .map(f -> f.substring(0, f.indexOf(" deployable-unit ")))
        .toList();
  }

  /** Checks facts both with builds and without. */
  private static void assertBoth(String... lines) {
    assertThat(built).as("with builds").contains(lines);
    assertThat(unbuilt).as("without builds").contains(lines);
  }

  @Test
  void repo1FourDeployableServices() {
    for (List<String> facts : List.of(built, unbuilt)) {
      assertThat(units(facts))
          .containsExactly(
              SHOP + "catalog-service",
              SHOP + "fulfilment",
              SHOP + "payments",
              SHOP + "storefront-app");
    }
  }

  @Test
  void mvn1ThinServiceAndItsLibraryAreOneUnit() {
    assertBoth(
        SHOP
            + "catalog-service BUNDLES library:com.acme.shop:catalog-lib {\"main\":true}"
            + " @catalog:pom.xml#20");
    assertThat(built).noneMatch(f -> f.startsWith("service:com.acme.shop:catalog-lib "));
  }

  @Test
  void mvn2MonolithBundlesBothLibrariesWithoutAMainOne() {
    assertBoth(
        SHOP
            + "storefront-app BUNDLES library:com.acme.shop:storefront-integration"
            + " @storefront:storefront-app/pom.xml#21",
        SHOP
            + "storefront-app BUNDLES library:com.acme.shop:storefront-model"
            + " @storefront:storefront-app/pom.xml#17");
    assertThat(built.stream().filter(f -> f.contains(" BUNDLES "))).hasSize(3);
  }

  @Test
  void spr3EachUnitIsStartedByItsApplicationClass() {
    String unit =
        "%sdeployable-unit spring-boot {\"mainClass\":\"type:com.acme.shop.%s\","
            + "\"packaging\":\"jar\"} @%s";
    assertBoth(
        unit.formatted(
            SHOP + "catalog-service ",
            "catalog.CatalogApplication",
            "catalog:src/main/java/com/acme/shop/catalog/CatalogApplication.java#6"),
        unit.formatted(
            SHOP + "fulfilment ",
            "fulfilment.FulfilmentApplication",
            "fulfilment:src/main/java/com/acme/shop/fulfilment/FulfilmentApplication.java#6"),
        unit.formatted(
            SHOP + "payments ",
            "payments.PaymentsApplication",
            "payments:src/main/java/com/acme/shop/payments/PaymentsApplication.java#7"),
        unit.formatted(
            SHOP + "storefront-app ",
            "storefront.StorefrontApplication",
            "storefront:storefront-app/src/main/java/com/acme/shop/storefront/"
                + "StorefrontApplication.java#7"));
  }

  @Test
  void cfg1AndCfg2UnitsAreNamedBySpringApplicationName() {
    assertBoth(
        SHOP
            + "catalog-service application-name catalog-service"
            + " {\"module\":\"module:com.acme.shop:catalog-service\"}"
            + " @catalog:src/main/resources/bootstrap.yml#3",
        SHOP
            + "fulfilment application-name fulfilment"
            + " {\"module\":\"module:com.acme.shop:fulfilment\"}"
            + " @fulfilment:src/main/resources/application.yml#3",
        SHOP
            + "payments application-name payments"
            + " {\"module\":\"module:com.acme.shop:payments\"}"
            + " @payments:src/main/resources/application.properties#1",
        SHOP
            + "storefront-app application-name storefront"
            + " {\"module\":\"module:com.acme.shop:storefront-app\"}"
            + " @storefront:storefront-app/src/main/resources/application.yml#3");
    assertThat(built.stream().filter(f -> f.contains(" application-name "))).hasSize(4);
  }

  @Test
  void mvn3TechnologyNamesSpringBootAndTheJavaRelease() {
    String technology =
        "%stechnology Spring Boot 3.5.14 / Java %s {\"framework\":\"Spring Boot\","
            + "\"frameworkVersion\":\"3.5.14\",\"java\":\"%2$s\"} @%s";
    assertThat(built)
        .contains(
            // catalog's Spring Boot and Java come through its parent, built by catalog-lib
            // (BUILD-1).
            technology.formatted(SHOP + "catalog-service ", "21", "catalog:pom.xml"),
            technology.formatted(SHOP + "fulfilment ", "21", "fulfilment:pom.xml#36"),
            technology.formatted(SHOP + "payments ", "17", "payments:pom.xml#28"),
            technology.formatted(
                SHOP + "storefront-app ", "21", "storefront:storefront-model/pom.xml#17"));
    // Without builds, the version comes from Spring Boot's parent POM, which each repository's
    // top POM names, catalog's through its parent from catalog-lib (BUILD-1).
    assertThat(unbuilt)
        .contains(
            technology.formatted(SHOP + "catalog-service ", "21", "catalog:pom.xml"),
            technology.formatted(SHOP + "fulfilment ", "21", "fulfilment:pom.xml#7"),
            technology.formatted(SHOP + "payments ", "17", "payments:pom.xml#7"),
            technology.formatted(SHOP + "storefront-app ", "21", "storefront:pom.xml#7"));
  }

  @Test
  void withAndWithoutBuildsOnlyTheTechnologysEvidenceDiffers() {
    assertThat(unbuilt.stream().map(Project0DeployableUnitsIT::withoutTechnologyEvidence))
        .containsExactlyElementsOf(
            built.stream().map(Project0DeployableUnitsIT::withoutTechnologyEvidence).toList());
  }

  private static String withoutTechnologyEvidence(String fact) {
    return fact.contains(" technology ") ? fact.substring(0, fact.lastIndexOf(" @")) : fact;
  }
}

package io.lantern.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.repo.Git;
import io.lantern.core.repo.Repo;
import io.lantern.core.repo.RepoSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Builds every repository of the synthetic test set project-0 (ADR 0029) in the sandbox, in
 * dependency order with the builds of the repositories each one uses (ADR 0041), each on the JDK
 * its POM asks for. Runs only when {@code lantern.test.project0} names the folder {@code
 * testsets/project-0/setup.sh} set the repositories up in, for example {@code ./mvnw verify -pl
 * lantern-sandbox -am -Dlantern.test.project0=$HOME/workspace/lantern-test-projects/project-0}.
 * Needs Docker and Maven Central, or the Maven mirror of {@link TestMirror}; the builds use the
 * default shared Maven cache directory, which keeps downloads between runs.
 */
@EnabledIfSystemProperty(named = "lantern.test.project0", matches = ".+")
class Project0BuildIT {

  /** The commits setup.sh makes from the pinned source. */
  private static final Map<String, String> COMMITS =
      Map.of(
          "catalog", "fc39b6e72803baa33b9e0e3d7ac8cf32fabce394",
          "catalog-lib", "5bc5ec4ba4ffec38da7c0aaf3596be3365ec192f",
          "fulfilment", "d7a159078e44f5de32d3b3f4aa5fa0ef9cb24921",
          "payments", "871bf725e98d45d11ea093749943bb555b65bae8",
          "storefront", "fa592e452e659d26597defe3519ba0ec3bfc5014");

  private static final Docker DOCKER = new Docker("docker");
  private static final Duration TIMEOUT = Duration.ofMinutes(2);
  private static final String RUN_LABEL = "lantern.test.run";
  private static final String RUN =
      HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextLong());

  @TempDir static Path temp;

  private static Map<String, Repo> repos;
  private static BuildOrder order;
  private static Map<String, BuildResult> results;

  @BeforeAll
  static void buildInOrder() throws IOException {
    String setUp = System.getProperty("lantern.test.project0");
    assertThat(Path.of(setUp).isAbsolute())
        .as("lantern.test.project0 must be an absolute path: " + setUp)
        .isTrue();
    repos = new TreeMap<>();
    for (Map.Entry<String, String> e : COMMITS.entrySet()) {
      String name = e.getKey();
      Path path = Path.of(setUp).resolve(name).toRealPath();
      Git.Result head = Git.run(path, "rev-parse", "HEAD");
      assertThat(head.ok()).as("%s: git rev-parse HEAD: %s", name, head.err()).isTrue();
      assertThat(head.out())
          .as("%s is not at the pinned commit; run testsets/project-0/setup.sh again", name)
          .isEqualTo(e.getValue());
      Git.Result status = Git.run(path, "status", "--porcelain");
      assertThat(status.ok()).as("%s: git status: %s", name, status.err()).isTrue();
      assertThat(status.out()).as("%s has local changes", name).isEmpty();
      repos.put(name, new Repo(name, path, RepoSettings.DEFAULT));
    }
    order = BuildOrder.of(repos, COMMITS);
    BuildSandbox sandbox = new BuildSandbox(settings("builds"));
    results = new HashMap<>();
    for (String name : order.order()) {
      List<UpstreamBuild> upstream =
          order.upstream(name).stream()
              .map(u -> new UpstreamBuild(repos.get(u), results.get(u)))
              .toList();
      results.put(name, sandbox.build(repos.get(name), COMMITS.get(name), upstream));
    }
  }

  @AfterEach
  void nothingLeftRunning() {
    String filter = "label=" + RUN_LABEL + "=" + RUN;
    assertThat(DOCKER.check(TIMEOUT, List.of("ps", "--all", "--quiet", "--filter", filter)).out())
        .as("sandbox containers left behind")
        .isEmpty();
    assertThat(DOCKER.check(TIMEOUT, List.of("network", "ls", "--quiet", "--filter", filter)).out())
        .as("sandbox networks left behind")
        .isEmpty();
  }

  /** Each repository and the JDK its POM asks for. */
  @ParameterizedTest
  @CsvSource({"catalog, 21", "catalog-lib, 21", "fulfilment, 21", "payments, 17", "storefront, 21"})
  void builds(String name, int jdk) {
    BuildResult result = results.get(name);

    assertThat(result.status())
        .as(() -> name + ": " + log(result))
        .isEqualTo(BuildStatus.SUCCEEDED);
    assertThat(result.jdk()).isEqualTo(jdk);
    assertThat(result.modules()).isNotEmpty();
    assertThat(result.effectivePom()).isNotNull();
    assertThat(result.shadowed()).as("artifacts the Maven cache already held").isEmpty();
  }

  /** BUILD-1: catalog builds only with catalog-lib's build, which gives its parent and library. */
  @Test
  void build1CatalogBuildsWithTheLibraryRepositorysBuild() throws IOException {
    assertThat(order.cycles()).isEmpty();
    assertThat(order.order()).containsSubsequence("catalog-lib", "catalog");
    assertThat(order.upstream("catalog")).containsExactly("catalog-lib");
    COMMITS.keySet().stream()
        .filter(name -> !name.equals("catalog"))
        .forEach(name -> assertThat(order.upstream(name)).as(name).isEmpty());

    BuildResult catalog = results.get("catalog");
    assertThat(catalog.status()).as(() -> log(catalog)).isEqualTo(BuildStatus.SUCCEEDED);
    assertThat(catalog.degraded()).isFalse();
    assertThat(catalog.jdk()).as("java.version of the parent from catalog-lib").isEqualTo(21);
    assertThat(catalog.upstream()).containsExactly("catalog-lib@" + COMMITS.get("catalog-lib"));
    assertThat(catalog.modules())
        .singleElement()
        .satisfies(
            m -> {
              assertThat(m.artifactId()).isEqualTo("catalog-service");
              assertThat(m.classpath())
                  .contains(
                      "lib/com/acme/shop/catalog-lib/1.0.0-SNAPSHOT/"
                          + "catalog-lib-1.0.0-SNAPSHOT.jar");
            });

    // Built alone, as before ADR 0041, it fails: nothing publishes its parent POM.
    BuildSandbox alone = new BuildSandbox(settings("alone"));
    BuildResult without = alone.build(repos.get("catalog"), COMMITS.get("catalog"));
    assertThat(without.status()).isEqualTo(BuildStatus.FAILED);
    assertThat(without.degraded()).isTrue();
    assertThat(Files.readString(without.log())).contains("com.acme.shop:catalog-parent");
  }

  private static SandboxSettings settings(String directory) {
    return SandboxSettings.defaults(temp.resolve(directory))
        .withOwnerLabel(RUN_LABEL, RUN)
        .withMinFreeDiskBytes(0)
        .withMavenMirror(TestMirror.configured());
  }

  private static String log(BuildResult result) {
    try {
      return "build failed: " + result.failure() + "\n" + Files.readString(result.log());
    } catch (IOException e) {
      return "build failed: " + result.failure();
    }
  }
}

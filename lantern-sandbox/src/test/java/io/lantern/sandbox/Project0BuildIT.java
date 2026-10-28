package io.lantern.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.repo.Git;
import io.lantern.core.repo.Repo;
import io.lantern.core.repo.RepoSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Builds every repository of the synthetic test set project-0 (ADR 0029) in the sandbox, each on
 * the JDK its POM asks for. Runs only when {@code lantern.test.project0} names the folder {@code
 * testsets/project-0/setup.sh} set the repositories up in, for example {@code ./mvnw verify -pl
 * lantern-sandbox -am -Dlantern.test.project0=$HOME/workspace/lantern-test-projects/project-0}.
 * Needs Docker and Maven Central; the builds share one Maven cache volume, created for this test
 * and removed after it.
 */
@EnabledIfSystemProperty(named = "lantern.test.project0", matches = ".+")
class Project0BuildIT {

  private static final Docker DOCKER = new Docker("docker");
  private static final Duration TIMEOUT = Duration.ofMinutes(2);
  private static final String RUN_LABEL = "lantern.test.run";
  private static final String RUN =
      HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextLong());

  private static String volume;

  @TempDir static Path temp;

  @BeforeAll
  static void createVolume() {
    volume = "lantern-test-m2-" + HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt());
    DOCKER.check(TIMEOUT, List.of("volume", "create", volume));
  }

  @AfterAll
  static void removeVolume() {
    DOCKER.quietly(TIMEOUT, List.of("volume", "rm", "--force", volume));
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

  /** Each repository with the commit setup.sh makes from the pinned source, and its JDK. */
  @ParameterizedTest
  @CsvSource({
    "catalog, 8637326f638af796f242548b68216c8729e1a5f7, 21",
    "fulfilment, d7a159078e44f5de32d3b3f4aa5fa0ef9cb24921, 21",
    "payments, 871bf725e98d45d11ea093749943bb555b65bae8, 17",
    "storefront, fa592e452e659d26597defe3519ba0ec3bfc5014, 21"
  })
  void builds(String name, String commit, int jdk) throws IOException {
    String setUp = System.getProperty("lantern.test.project0");
    assertThat(Path.of(setUp).isAbsolute())
        .as("lantern.test.project0 must be an absolute path: " + setUp)
        .isTrue();
    Path path = Path.of(setUp).resolve(name).toRealPath();
    Git.Result head = Git.run(path, "rev-parse", "HEAD");
    assertThat(head.ok()).as("%s: git rev-parse HEAD: %s", name, head.err()).isTrue();
    assertThat(head.out())
        .as("%s is not at the pinned commit; run testsets/project-0/setup.sh again", name)
        .isEqualTo(commit);
    Git.Result status = Git.run(path, "status", "--porcelain");
    assertThat(status.ok()).as("%s: git status: %s", name, status.err()).isTrue();
    assertThat(status.out()).as("%s has local changes", name).isEmpty();
    Repo repo = new Repo(name, path, RepoSettings.DEFAULT);
    SandboxSettings settings =
        SandboxSettings.defaults(temp.resolve("builds"))
            .withMavenCacheVolume(volume)
            .withOwnerLabel(RUN_LABEL, RUN)
            .withMinFreeDiskBytes(0);

    BuildResult result = new BuildSandbox(settings).build(repo, commit);

    assertThat(result.status())
        .as(() -> name + ": " + log(result))
        .isEqualTo(BuildStatus.SUCCEEDED);
    assertThat(result.jdk()).isEqualTo(jdk);
    assertThat(result.modules()).isNotEmpty();
    assertThat(result.effectivePom()).isNotNull();
  }

  private static String log(BuildResult result) {
    try {
      return "build failed: " + result.failure() + "\n" + Files.readString(result.log());
    } catch (IOException e) {
      return "build failed: " + result.failure();
    }
  }
}

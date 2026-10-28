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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Builds the OpenWMS repositories of the test set that build on their own in the sandbox; the
 * others need an unpublished snapshot, a host the sandbox does not reach, or have a broken parent
 * reference (#30). Runs only when {@code lantern.test.openwms} names the directory {@code
 * testsets/openwms/clone.sh} cloned them into, for example {@code ./mvnw verify -pl lantern-sandbox
 * -am -Dlantern.test.openwms=$PWD/workspace/openwms}.
 */
@EnabledIfSystemProperty(named = "lantern.test.openwms", matches = ".+")
class OpenWmsBuildIT {

  private static final Docker DOCKER = new Docker("docker");
  private static final Duration TIMEOUT = Duration.ofMinutes(2);

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

  @ParameterizedTest
  @ValueSource(
      strings = {
        "ameba-lib",
        "org.openwms.parent",
        "org.openwms.common.service",
        "org.openwms.common.service.lib",
        "org.openwms.core.test.arch",
        "org.openwms.core.uaa",
        "org.openwms.core.uaa.lib",
        "org.openwms.core.units",
        "org.openwms.tms.transportation",
        "org.openwms.wms.receiving.lib"
      })
  void builds(String name) throws IOException {
    Path path = Path.of(System.getProperty("lantern.test.openwms")).resolve(name).toRealPath();
    Repo repo = new Repo(name, path, RepoSettings.DEFAULT);
    String commit = Git.run(path, "rev-parse", "HEAD").out();
    SandboxSettings settings =
        SandboxSettings.defaults(temp.resolve("builds")).withMavenCacheVolume(volume);

    BuildResult result = new BuildSandbox(settings).build(repo, commit);

    assertThat(result.status())
        .as("%s: %s%n%s", name, result.failure(), Files.readString(result.log()))
        .isEqualTo(BuildStatus.SUCCEEDED);
    assertThat(result.modules()).isNotEmpty();
    assertThat(result.effectivePom()).isNotNull();
  }
}

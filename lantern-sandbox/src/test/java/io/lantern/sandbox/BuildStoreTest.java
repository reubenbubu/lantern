package io.lantern.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Finding and replacing stored builds, without running one (#786). */
class BuildStoreTest {

  private static final String COMMIT = "d".repeat(40);

  @TempDir Path builds;

  private Path stored(String text) throws IOException {
    Path directory = builds.resolve("repo").resolve(COMMIT);
    Files.createDirectories(directory);
    Files.writeString(directory.resolve(BuildSandbox.RESULT_FILE), text);
    return directory;
  }

  private BuildResult result(Path directory) {
    return new BuildResult(
        "repo",
        COMMIT,
        BuildStatus.FAILED,
        "No pom.xml at the top of the repository",
        0,
        "not chosen",
        null,
        null,
        Instant.parse("2026-10-28T10:15:30Z"),
        Duration.ZERO,
        null,
        List.of(),
        directory);
  }

  @Test
  void findsABuildOfThisBuildStep() throws IOException {
    Path directory = builds.resolve("repo").resolve(COMMIT);
    BuildResult result = result(directory);
    stored(BuildJson.write(result));

    assertThat(new BuildSandbox(SandboxSettings.defaults(builds)).find("repo", COMMIT))
        .contains(result);
  }

  @Test
  void aBuildOfAnotherBuildStepCountsAsMissing() throws IOException {
    Path directory = builds.resolve("repo").resolve(COMMIT);
    String current = BuildJson.write(result(directory));
    BuildSandbox sandbox = new BuildSandbox(SandboxSettings.defaults(builds));

    stored(current.replaceFirst("\"buildStep\" : \\d+,", ""));
    assertThat(sandbox.find("repo", COMMIT)).isEmpty();

    stored(
        current.replaceFirst(
            "\"buildStep\" : \\d+", "\"buildStep\" : " + (BuildSandbox.BUILD_STEP_VERSION + 1)));
    assertThat(sandbox.find("repo", COMMIT)).isEmpty();
  }

  @Test
  void replacingAStoredBuildLeavesOnlyTheNewOne() throws IOException {
    Path directory = stored("old");
    Path partial = Files.createTempDirectory(directory.getParent(), ".partial-");
    Files.writeString(partial.resolve(BuildSandbox.RESULT_FILE), "new");

    BuildSandbox.replace(partial, directory);

    assertThat(Files.readString(directory.resolve(BuildSandbox.RESULT_FILE))).isEqualTo("new");
    try (var left = Files.list(directory.getParent())) {
      assertThat(left).containsExactly(directory);
    }
  }
}

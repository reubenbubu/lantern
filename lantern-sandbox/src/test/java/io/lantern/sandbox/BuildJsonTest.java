package io.lantern.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BuildJsonTest {

  private static final Path DIR = Path.of("/builds/repo/commit");

  @Test
  void roundTripsAFailedBuild() {
    BuildResult result =
        new BuildResult(
            "repo",
            "a".repeat(40),
            BuildStatus.FAILED,
            "Maven exited with code 1",
            21,
            "default; pom.xml does not say",
            "maven:3.9.16-eclipse-temurin-21",
            1,
            Instant.parse("2026-10-28T10:15:30Z"),
            Duration.ofSeconds(42),
            "work/target/lantern/effective-pom.xml",
            List.of(
                new BuildModule(
                    "core",
                    "com.example",
                    "core",
                    "jar",
                    "1.0",
                    List.of("api"),
                    List.of("work/api/target/classes", "lib/a.jar"),
                    null,
                    "work/core/target/generated-sources",
                    "work/core/target/lantern/dependency-tree.txt")),
            DIR);

    assertThat(BuildJson.read(BuildJson.write(result), DIR)).isEqualTo(result);
  }

  @Test
  void roundTripsABuildThatNeverStarted() {
    BuildResult result =
        new BuildResult(
            "repo",
            "b".repeat(64),
            BuildStatus.FAILED,
            "No pom.xml at the top of the repository",
            0,
            "not chosen",
            null,
            null,
            Instant.parse("2026-10-28T10:15:30Z"),
            Duration.ofMillis(5),
            null,
            List.of(),
            DIR);

    assertThat(BuildJson.read(BuildJson.write(result), DIR)).isEqualTo(result);
  }

  @Test
  void roundTripsTheBuildsOfOtherRepositoriesItWasGiven() {
    BuildResult result =
        new BuildResult(
            "service",
            "c".repeat(40),
            BuildStatus.SUCCEEDED,
            null,
            21,
            "default",
            "maven:3.9.16-eclipse-temurin-21",
            0,
            Instant.parse("2026-10-28T10:15:30Z"),
            Duration.ofSeconds(3),
            null,
            List.of(),
            DIR,
            Set.of("lib@" + "d".repeat(40), "base@" + "e".repeat(40)),
            List.of("com.example:lib:1.0"));

    String json = BuildJson.write(result);

    assertThat(BuildJson.read(json, DIR)).isEqualTo(result);
    // A build stored before ADR 0041 records neither.
    BuildResult old =
        BuildJson.read(json.replaceAll("(?s),\\s*\"upstream\".*\\]\\s*}", "\n}"), DIR);
    assertThat(old.upstream()).isEmpty();
    assertThat(old.shadowed()).isEmpty();
  }
}

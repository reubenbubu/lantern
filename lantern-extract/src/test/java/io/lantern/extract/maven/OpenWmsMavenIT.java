package io.lantern.extract.maven;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.repo.Git;
import io.lantern.core.repo.Repo;
import io.lantern.core.repo.RepoSettings;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.extract.Fixtures;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.TestDocker;
import io.lantern.sandbox.BuildSandbox;
import io.lantern.sandbox.SandboxSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Builds the whole OpenWMS test set in the sandbox and extracts its Maven facts. Six of the 16
 * repositories fail to build (#30, ADR 0020); four still leave their effective POM and two are read
 * from their POM as written. Runs only when {@code lantern.test.openwms} names the directory {@code
 * testsets/openwms/clone.sh} cloned them into, for example {@code ./mvnw verify -pl lantern-extract
 * -am -Dlantern.test.openwms=$PWD/workspace/openwms}.
 */
@EnabledIfSystemProperty(named = "lantern.test.openwms", matches = ".+")
class OpenWmsMavenIT {

  private static String volume;

  @TempDir static Path temp;

  @BeforeAll
  static void createVolume() {
    volume = TestDocker.createVolume();
  }

  @AfterAll
  static void removeVolume() {
    TestDocker.removeVolume(volume);
  }

  @Test
  void findsTheFiveServicesEachLinkedToItsLibrary() throws Exception {
    Path root = Path.of(System.getProperty("lantern.test.openwms"));
    List<Repo> repos = new ArrayList<>();
    try (Stream<Path> dirs = Files.list(root)) {
      for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
        repos.add(new Repo(dir.getFileName().toString(), dir.toRealPath(), RepoSettings.DEFAULT));
      }
    }
    assertThat(repos).hasSize(16);
    BuildSandbox sandbox =
        new BuildSandbox(
            SandboxSettings.defaults(temp.resolve("builds")).withMavenCacheVolume(volume));
    List<RepoBuild> inputs = new ArrayList<>();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<RepoBuild>> builds = new ArrayList<>();
      for (Repo repo : repos) {
        builds.add(
            executor.submit(
                () -> {
                  String commit = Git.run(repo.path(), "rev-parse", "HEAD").out();
                  return new RepoBuild(repo, commit, sandbox.build(repo, commit));
                }));
      }
      for (Future<RepoBuild> build : builds) {
        inputs.add(build.get());
      }
    }

    List<Fact> facts = new MavenExtractor().extract(inputs, 1);
    Fixtures.checkEnvelope(facts, repos, 1, MavenExtractor.NAME, MavenExtractor.VERSION);

    List<String> services =
        facts.stream()
            .filter(f -> f.subject().type().equals(BuiltInTypes.SERVICE))
            .filter(f -> f.predicate().equals(BuiltInTypes.CONTAINS))
            .map(f -> f.subject().key())
            .distinct()
            .sorted()
            .toList();
    assertThat(services)
        .containsExactly(
            "org.openwms:org.openwms.common.service",
            "org.openwms:org.openwms.core.uaa",
            "org.openwms:org.openwms.tms.routing",
            "org.openwms:org.openwms.tms.transportation",
            "org.openwms:org.openwms.wms.receiving");

    Map<String, String> packaged =
        facts.stream()
            .filter(f -> f.predicate().equals(MavenExtractor.PACKAGES))
            .collect(
                Collectors.toMap(
                    f -> f.subject().key(),
                    f -> f.object().entityId().toString(),
                    (a, b) -> a,
                    TreeMap::new));
    // tms.transportation has its code in its own module; there is no separate library.
    assertThat(packaged)
        .containsExactly(
            Map.entry(
                "org.openwms:org.openwms.common.service",
                "library:org.openwms:org.openwms.common.service.lib"),
            Map.entry(
                "org.openwms:org.openwms.core.uaa", "library:org.openwms:org.openwms.core.uaa.lib"),
            Map.entry(
                "org.openwms:org.openwms.tms.routing",
                "library:org.openwms:org.openwms.tms.routing.osip"),
            Map.entry(
                "org.openwms:org.openwms.wms.receiving",
                "library:org.openwms:org.openwms.wms.receiving.lib"));
  }
}

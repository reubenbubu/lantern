package io.lantern.extract.java;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.repo.Repo;
import io.lantern.extract.Fixtures;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.TestDocker;
import io.lantern.sandbox.BuildResult;
import io.lantern.sandbox.BuildSandbox;
import io.lantern.sandbox.SandboxSettings;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Golden-fact tests on the fixtures built in the sandbox, so modules whose build succeeded are read
 * through javac with the build's classpath and Lombok. Needs Docker and network access to Maven
 * Central. The builds share a Maven cache volume created for these tests and removed after them.
 */
class JavaExtractorIT {

  private static final long SNAPSHOT = 9;

  private static String volume;

  @TempDir Path temp;

  @BeforeAll
  static void createVolume() {
    volume = TestDocker.createVolume();
  }

  @AfterAll
  static void removeVolume() {
    TestDocker.removeVolume(volume);
  }

  private List<RepoBuild> build(List<Repo> repos) {
    BuildSandbox sandbox =
        new BuildSandbox(
            SandboxSettings.defaults(temp.resolve("builds")).withMavenCacheVolume(volume));
    List<RepoBuild> inputs = new ArrayList<>();
    for (Repo repo : repos) {
      String commit = Fixtures.head(repo);
      BuildResult build = sandbox.build(repo, commit);
      inputs.add(new RepoBuild(repo, commit, build));
    }
    return inputs;
  }

  private static List<Fact> extract(List<RepoBuild> inputs) throws IOException {
    List<Fact> facts = new JavaExtractor().extract(inputs, SNAPSHOT);
    Fixtures.checkEnvelope(
        facts,
        inputs.stream().map(RepoBuild::repo).toList(),
        SNAPSHOT,
        JavaExtractor.NAME,
        JavaExtractor.VERSION);
    // Running again on the same input gives the same facts.
    assertThat(new JavaExtractor().extract(inputs, SNAPSHOT)).isEqualTo(facts);
    return facts;
  }

  @Test
  void multiModule() throws IOException {
    Repo repo = Fixtures.repo("multi-module", temp.resolve("shop"), "shop");
    List<RepoBuild> inputs = build(List.of(repo));
    assertThat(inputs.getFirst().build().degraded()).isFalse();

    Fixtures.assertGolden("java/multi-module.txt", extract(inputs));
  }

  @Test
  void lombok() throws IOException {
    Repo repo = Fixtures.repo("lombok", temp.resolve("orders"), "orders");
    List<RepoBuild> inputs = build(List.of(repo));
    assertThat(inputs.getFirst().build().degraded()).isFalse();

    // Lombok's constructors, getters, builder and their calls are all there.
    Fixtures.assertGolden("java/lombok.txt", extract(inputs));
  }

  @Test
  void libAndService() throws IOException {
    Repo lib = Fixtures.repo("lib-and-service/lib", temp.resolve("lib"), "greeting-lib");
    Repo service =
        Fixtures.repo("lib-and-service/service", temp.resolve("service"), "greeting-service");
    List<RepoBuild> inputs = build(List.of(lib, service));
    // The service's build fails, since the library is not published, so it is parsed instead.
    assertThat(inputs.get(0).build().degraded()).isFalse();
    assertThat(inputs.get(1).build().degraded()).isTrue();

    Fixtures.assertGolden("java/lib-and-service.txt", extract(inputs));
  }
}

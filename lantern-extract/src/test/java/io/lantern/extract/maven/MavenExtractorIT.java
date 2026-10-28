package io.lantern.extract.maven;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.repo.Repo;
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
 * Golden-fact tests on the fixtures built in the sandbox, so modules are read from effective POMs.
 * Needs Docker and network access to Maven Central. The builds share a Maven cache volume created
 * for these tests and removed after them.
 */
class MavenExtractorIT {

  private static final long SNAPSHOT = 7;

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

  private List<Fact> buildAndExtract(List<Repo> repos) throws IOException {
    BuildSandbox sandbox =
        new BuildSandbox(
            SandboxSettings.defaults(temp.resolve("builds")).withMavenCacheVolume(volume));
    List<RepoBuild> inputs = new ArrayList<>();
    for (Repo repo : repos) {
      String commit = Fixtures.head(repo);
      BuildResult build = sandbox.build(repo, commit);
      assertThat(build.effectivePom()).as("effective POM of %s", repo.name()).isNotNull();
      inputs.add(new RepoBuild(repo, commit, build));
    }
    List<Fact> facts = new MavenExtractor().extract(inputs, SNAPSHOT);
    Fixtures.checkEnvelope(facts, repos, SNAPSHOT);
    return facts;
  }

  @Test
  void multiModule() throws IOException {
    Repo repo = Fixtures.repo("multi-module", temp.resolve("shop"), "shop");

    Fixtures.assertGolden("multi-module.txt", buildAndExtract(List.of(repo)));
  }

  @Test
  void lombok() throws IOException {
    Repo repo = Fixtures.repo("lombok", temp.resolve("orders"), "orders");

    Fixtures.assertGolden("lombok.txt", buildAndExtract(List.of(repo)));
  }

  @Test
  void libAndService() throws IOException {
    Repo lib = Fixtures.repo("lib-and-service/lib", temp.resolve("lib"), "greeting-lib");
    Repo service =
        Fixtures.repo("lib-and-service/service", temp.resolve("service"), "greeting-service");

    // The service's build fails, since the library is not published, but leaves its effective POM.
    Fixtures.assertGolden("lib-and-service.txt", buildAndExtract(List.of(lib, service)));
  }
}

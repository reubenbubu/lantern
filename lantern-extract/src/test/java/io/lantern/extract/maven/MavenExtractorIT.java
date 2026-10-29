package io.lantern.extract.maven;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.repo.Repo;
import io.lantern.extract.Fixtures;
import io.lantern.extract.RepoBuild;
import io.lantern.sandbox.BuildResult;
import io.lantern.sandbox.BuildSandbox;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Golden-fact tests on the fixtures built in the sandbox, so modules are read from effective POMs.
 * Needs Docker and network access to Maven Central. The builds use the default shared Maven cache
 * directory, which keeps downloads between runs.
 */
class MavenExtractorIT {

  private static final long SNAPSHOT = 7;

  @TempDir Path temp;

  private List<Fact> buildAndExtract(List<Repo> repos) throws IOException {
    BuildSandbox sandbox = new BuildSandbox(Fixtures.sandboxSettings(temp.resolve("builds")));
    List<RepoBuild> inputs = new ArrayList<>();
    for (Repo repo : repos) {
      String commit = Fixtures.head(repo);
      BuildResult build = sandbox.build(repo, commit);
      assertThat(build.effectivePom()).as("effective POM of %s", repo.name()).isNotNull();
      inputs.add(new RepoBuild(repo, commit, build));
    }
    List<Fact> facts = new MavenExtractor().extract(inputs, SNAPSHOT);
    Fixtures.checkEnvelope(facts, repos, SNAPSHOT, MavenExtractor.NAME, MavenExtractor.VERSION);
    return facts;
  }

  @Test
  void multiModule() throws IOException {
    Repo repo = Fixtures.repo("multi-module", temp.resolve("shop"), "shop");

    Fixtures.assertGolden("maven/multi-module.txt", buildAndExtract(List.of(repo)));
  }

  @Test
  void lombok() throws IOException {
    Repo repo = Fixtures.repo("lombok", temp.resolve("orders"), "orders");

    Fixtures.assertGolden("maven/lombok.txt", buildAndExtract(List.of(repo)));
  }

  @Test
  void libAndService() throws IOException {
    Repo lib = Fixtures.repo("lib-and-service/lib", temp.resolve("lib"), "greeting-lib");
    Repo service =
        Fixtures.repo("lib-and-service/service", temp.resolve("service"), "greeting-service");

    // The service's build fails, since the library is not published, but leaves its effective POM.
    Fixtures.assertGolden("maven/lib-and-service.txt", buildAndExtract(List.of(lib, service)));
  }
}

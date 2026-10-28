package io.lantern.extract.maven;

import io.lantern.core.fact.Fact;
import io.lantern.core.repo.Repo;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Golden-fact tests on the fixtures without builds, so every module is read from its POM as
 * written. {@link MavenExtractorIT} builds the fixtures in the sandbox and reads effective POMs.
 */
class MavenExtractorTest {

  private static final long SNAPSHOT = 3;

  @TempDir Path temp;

  private List<Fact> extract(List<Repo> repos) throws IOException {
    List<RepoBuild> inputs =
        repos.stream().map(r -> new RepoBuild(r, Fixtures.head(r), null)).toList();
    List<Fact> facts = new MavenExtractor().extract(inputs, SNAPSHOT);
    Fixtures.checkEnvelope(facts, repos, SNAPSHOT);
    return facts;
  }

  @Test
  void multiModule() throws IOException {
    Repo repo = Fixtures.repo("multi-module", temp.resolve("shop"), "shop");

    Fixtures.assertGolden("multi-module-written.txt", extract(List.of(repo)));
  }

  @Test
  void lombok() throws IOException {
    Repo repo = Fixtures.repo("lombok", temp.resolve("orders"), "orders");

    Fixtures.assertGolden("lombok-written.txt", extract(List.of(repo)));
  }

  @Test
  void libAndService() throws IOException {
    Repo lib = Fixtures.repo("lib-and-service/lib", temp.resolve("lib"), "greeting-lib");
    Repo service =
        Fixtures.repo("lib-and-service/service", temp.resolve("service"), "greeting-service");

    Fixtures.assertGolden("lib-and-service-written.txt", extract(List.of(lib, service)));
  }
}

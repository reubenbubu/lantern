package io.lantern.link;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.repo.Git;
import io.lantern.core.repo.Repo;
import io.lantern.core.repo.RepoSettings;
import io.lantern.extract.RepoBuild;
import io.lantern.sandbox.BuildOrder;
import io.lantern.sandbox.BuildResult;
import io.lantern.sandbox.BuildSandbox;
import io.lantern.sandbox.BuildStatus;
import io.lantern.sandbox.UpstreamBuild;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The synthetic test set project-0 (docs/adr/0029), as {@code testsets/project-0/setup.sh} set it
 * up in the folder the system property {@code lantern.test.project0} names.
 */
final class Project0 {

  /** The commits setup.sh makes from the pinned source, as Project0BuildIT checks them. */
  static final Map<String, String> COMMITS =
      Collections.unmodifiableMap(
          new TreeMap<>(
              Map.of(
                  "catalog", "fc39b6e72803baa33b9e0e3d7ac8cf32fabce394",
                  "catalog-lib", "5bc5ec4ba4ffec38da7c0aaf3596be3365ec192f",
                  "fulfilment", "d7a159078e44f5de32d3b3f4aa5fa0ef9cb24921",
                  "payments", "871bf725e98d45d11ea093749943bb555b65bae8",
                  "storefront", "fa592e452e659d26597defe3519ba0ec3bfc5014")));

  private Project0() {}

  /** Each repository, by name, checked to be at the commit setup.sh makes. */
  static Map<String, Repo> repos() throws IOException {
    String setUp = System.getProperty("lantern.test.project0");
    assertThat(Path.of(setUp).isAbsolute())
        .as("lantern.test.project0 must be an absolute path: " + setUp)
        .isTrue();
    Map<String, Repo> repos = new TreeMap<>();
    for (Map.Entry<String, String> e : COMMITS.entrySet()) {
      Path path = Path.of(setUp).resolve(e.getKey()).toRealPath();
      Git.Result head = Git.run(path, "rev-parse", "HEAD");
      assertThat(head.out())
          .as("%s is not at the pinned commit; run testsets/project-0/setup.sh again", e.getKey())
          .isEqualTo(e.getValue());
      repos.put(e.getKey(), new Repo(e.getKey(), path, RepoSettings.DEFAULT));
    }
    return Collections.unmodifiableMap(repos);
  }

  /** Each repository at its commit without a build (degraded mode), by name. */
  static List<RepoBuild> unbuilt(Map<String, Repo> repos) {
    return repos.values().stream()
        .map(repo -> new RepoBuild(repo, COMMITS.get(repo.name()), null))
        .toList();
  }

  /**
   * Each repository built in the sandbox, in dependency order, each build given the builds of the
   * repositories it uses (ADR 0041), as the analysis run builds them; listed by name. Every build
   * must succeed.
   */
  static List<RepoBuild> built(Map<String, Repo> repos, BuildSandbox sandbox) {
    BuildOrder order = BuildOrder.of(repos, COMMITS);
    Map<String, BuildResult> results = new HashMap<>();
    for (String name : order.order()) {
      List<UpstreamBuild> upstream =
          order.upstream(name).stream()
              .map(u -> new UpstreamBuild(repos.get(u), results.get(u)))
              .toList();
      BuildResult result = sandbox.build(repos.get(name), COMMITS.get(name), upstream);
      assertThat(result.status()).as(name).isEqualTo(BuildStatus.SUCCEEDED);
      results.put(name, result);
    }
    return repos.values().stream()
        .map(repo -> new RepoBuild(repo, COMMITS.get(repo.name()), results.get(repo.name())))
        .toList();
  }
}

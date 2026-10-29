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
import java.nio.file.Files;
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

  /** The file setup.sh writes into the set-up folder, holding the pin it set the folder up from. */
  private static final String PIN_FILE = ".lantern-pin";

  /** The pins of the test set, the one place its commits are named (ADR 0029). */
  private static final Path PINS = Path.of("..", "testsets", "project-0", "pins.txt");

  /** The made-up repositories, as the source's README lists them. */
  private static final List<String> NAMES =
      List.of("catalog", "catalog-lib", "fulfilment", "payments", "storefront");

  private Project0() {}

  /**
   * Each repository, by name, in a folder setup.sh set up from the current pin in pins.txt, which
   * it records in the folder as {@value #PIN_FILE}.
   */
  static Map<String, Repo> repos() throws IOException {
    String setUp = System.getProperty("lantern.test.project0");
    assertThat(Path.of(setUp).isAbsolute())
        .as("lantern.test.project0 must be an absolute path: " + setUp)
        .isTrue();
    String pin = pin();
    Path pinFile = Path.of(setUp).resolve(PIN_FILE);
    String setUpFrom = Files.isRegularFile(pinFile) ? Files.readString(pinFile).strip() : null;
    assertThat(setUpFrom)
        .as(
            "project-0 in %s was not set up from the pin %s in %s (it holds %s); run"
                + " testsets/project-0/setup.sh again (docs/adr/0029)",
            setUp, pin, PINS.normalize(), setUpFrom == null ? "no " + PIN_FILE : setUpFrom)
        .isEqualTo(pin);
    Map<String, Repo> repos = new TreeMap<>();
    for (String name : NAMES) {
      Path path = Path.of(setUp).resolve(name).toRealPath();
      repos.put(name, new Repo(name, path, RepoSettings.DEFAULT));
    }
    return Collections.unmodifiableMap(repos);
  }

  /** The commit each repository is at, by name, read from the repository. */
  static Map<String, String> commits(Map<String, Repo> repos) {
    Map<String, String> commits = new TreeMap<>();
    for (Repo repo : repos.values()) {
      Git.Result head = Git.run(repo.path(), "rev-parse", "HEAD");
      assertThat(head.ok()).as("%s: git rev-parse HEAD: %s", repo.name(), head.err()).isTrue();
      commits.put(repo.name(), head.out());
    }
    return Collections.unmodifiableMap(commits);
  }

  /** Each repository at its commit without a build (degraded mode), by name. */
  static List<RepoBuild> unbuilt(Map<String, Repo> repos) {
    Map<String, String> commits = commits(repos);
    return repos.values().stream()
        .map(repo -> new RepoBuild(repo, commits.get(repo.name()), null))
        .toList();
  }

  /**
   * Each repository built in the sandbox, in dependency order, each build given the builds of the
   * repositories it uses (ADR 0041), as the analysis run builds them; listed by name. Every build
   * must succeed.
   */
  static List<RepoBuild> built(Map<String, Repo> repos, BuildSandbox sandbox) {
    Map<String, String> commits = commits(repos);
    BuildOrder order = BuildOrder.of(repos, commits);
    Map<String, BuildResult> results = new HashMap<>();
    for (String name : order.order()) {
      List<UpstreamBuild> upstream =
          order.upstream(name).stream()
              .map(u -> new UpstreamBuild(repos.get(u), results.get(u)))
              .toList();
      BuildResult result = sandbox.build(repos.get(name), commits.get(name), upstream);
      assertThat(result.status()).as(name).isEqualTo(BuildStatus.SUCCEEDED);
      results.put(name, result);
    }
    return repos.values().stream()
        .map(repo -> new RepoBuild(repo, commits.get(repo.name()), results.get(repo.name())))
        .toList();
  }

  /** The commit the one line of pins.txt pins the source to. */
  private static String pin() throws IOException {
    List<String> lines =
        Files.readAllLines(PINS).stream()
            .map(String::strip)
            .filter(line -> !line.isEmpty() && !line.startsWith("#"))
            .toList();
    assertThat(lines).as(PINS.toString()).hasSize(1);
    return lines.getFirst().split("\\s+")[2];
  }
}

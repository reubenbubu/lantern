package io.lantern.link;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.repo.Git;
import io.lantern.core.repo.Repo;
import io.lantern.core.repo.RepoSettings;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.config.CodeConfig;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.config.ModuleConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The link-side lookup agrees with the extractors' own resolution (#556) on the development test
 * sets: for every config key of every module, written as a placeholder, in every view. Runs only
 * when {@code lantern.test.projects} names the folder the sets' {@code setup.sh} set them up in;
 * reads the repositories from git, builds nothing. The held-out sets are never read.
 */
@EnabledIfSystemProperty(named = "lantern.test.projects", matches = ".+")
class CodeValuesIT {

  private static final List<String> SETS =
      List.of("project-0", "project-1", "project-2", "project-3", "project-4", "project-8");

  @Test
  void theLookupAgreesWithTheExtractorsOnTheDevelopmentSets() throws IOException {
    Path root = Path.of(System.getProperty("lantern.test.projects"));
    int compared = 0;
    List<String> differences = new ArrayList<>();
    List<String> lossy = new ArrayList<>();
    for (String set : SETS) {
      List<RepoBuild> repos = repos(root.resolve(set));
      if (repos.isEmpty()) {
        continue;
      }
      List<Fact> facts = new ConfigExtractor().extract(repos, 1);
      Set<String> stripped = stripped(facts);
      CodeValues values = CodeValues.of(facts);
      CodeConfig config = new CodeConfig();
      for (RepoBuild repo : repos) {
        for (ModuleConfig module : ConfigExtractor.read(repo)) {
          CodeConfig.View expected = config.forModule(repo, module.directory());
          CodeValues.View actual = values.forModule(module.module(), repo.repo().name());
          Set<String> views = new LinkedHashSet<>(expected.views());
          views.addAll(actual.views());
          Set<String> keys = new LinkedHashSet<>();
          for (String view : views) {
            keys.addAll(expected.values(view).keySet());
          }
          for (String view : views) {
            for (String key : keys) {
              String text = "${" + key + "}";
              CodeConfig.CodeValue want = expected.value(expected.resolve(view, text), text);
              CodeValues.Value got = actual.value(view, text);
              if (stripped.contains(module.module() + " " + key)) {
                // The config fact lost part of the value as if it were a URL's credentials.
                lossy.add(set + " " + module.module() + " " + view + " " + text);
                continue;
              }
              compared++;
              if (!got.value().equals(want.value())
                  || !got.unresolved().equals(want.unresolved())
                  || got.masked() != want.masked()) {
                differences.add(
                    set
                        + " "
                        + repo.repo().name()
                        + "/"
                        + module.directory()
                        + " "
                        + view
                        + " "
                        + text
                        + ": "
                        + got
                        + " vs "
                        + want);
              }
            }
          }
        }
      }
    }
    System.out.println("CodeValuesIT compared " + compared + " values; not comparable: " + lossy);
    assertThat(compared).isGreaterThan(0);
    assertThat(differences).isEmpty();
  }

  /**
   * The module and key of each config fact recorded with credentials removed rather than masked
   * whole, whose value the facts no longer hold in full.
   */
  private static Set<String> stripped(List<Fact> facts) {
    Set<String> keys = new java.util.HashSet<>();
    for (Fact f : facts) {
      if (f.predicate().equals(ConfigExtractor.CONFIG_VALUE)
          && f.attributes().path("masked").asBoolean(false)
          && !f.object().value().equals(ConfigExtractor.MASK)) {
        keys.add(f.subject() + " " + f.attributes().get("key").stringValue());
      }
    }
    return keys;
  }

  private static List<RepoBuild> repos(Path set) throws IOException {
    if (!Files.isDirectory(set)) {
      return List.of();
    }
    List<RepoBuild> repos = new ArrayList<>();
    try (Stream<Path> children = Files.list(set)) {
      for (Path dir : children.sorted().toList()) {
        if (!Files.exists(dir.resolve(".git"))) {
          continue;
        }
        Git.Result head = Git.run(dir, "rev-parse", "HEAD");
        assertThat(head.ok()).as(dir + ": " + head.err()).isTrue();
        Repo repo = new Repo(dir.getFileName().toString(), dir.toRealPath(), RepoSettings.DEFAULT);
        repos.add(new RepoBuild(repo, head.out(), null));
      }
    }
    return repos;
  }
}

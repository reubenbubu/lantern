package io.lantern.link;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.fact.Origin;
import io.lantern.core.repo.Git;
import io.lantern.core.repo.Repo;
import io.lantern.core.repo.RepoSettings;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.config.CodeConfig;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.java.JavaAnalysis;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.extract.spring.SpringExtractor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * On the development test sets, the messaging facts linking records from the config facts are those
 * the messaging extractor's resolving gives against the modules' config files, as the extractor
 * resolved them itself until #559; and every send, listener, binding and declaration the extractors
 * record points at a destination entity, with no value resolved from config. Runs only when {@code
 * lantern.test.projects} names the folder the sets' {@code setup.sh} set them up in; reads the
 * repositories from git, builds nothing, and writes the facts of each set before and after linking
 * to {@code target/endpoints/}. The held-out sets are never read.
 */
@EnabledIfSystemProperty(named = "lantern.test.projects", matches = ".+")
class EndpointsIT {

  private static final List<String> SETS =
      List.of("project-0", "project-1", "project-2", "project-3", "project-4", "project-8");

  private static final Set<String> SPRING_FACTS =
      Set.of(SpringExtractor.ENDPOINT, SpringExtractor.SCHEDULED);

  @Test
  void linkingGivesTheEndpointsTheExtractorGaveOnTheDevelopmentSets() throws IOException {
    Path root = Path.of(System.getProperty("lantern.test.projects"));
    Path out = Files.createDirectories(Path.of("target", "endpoints"));
    int compared = 0;
    for (String set : SETS) {
      List<RepoBuild> repos = repos(root.resolve(set));
      if (repos.isEmpty()) {
        continue;
      }
      JavaAnalysis code = JavaExtractor.analyze(repos);
      List<Fact> written = new SpringExtractor().extract(code, 1);
      List<Fact> facts = new ArrayList<>(new ConfigExtractor().extract(repos, 1));
      facts.addAll(written);
      for (Fact f : written) {
        assertThat(f.attributes().has("profile")).as(set + " " + f).isFalse();
      }
      List<Fact> linked =
          new ValueResolution()
              .derive(facts, 1).stream().filter(f -> SPRING_FACTS.contains(f.predicate())).toList();
      // Every endpoint has its entity, handled by the endpoint's method.
      Set<String> handled = new java.util.HashSet<>();
      for (Fact f : written) {
        if (f.predicate().equals(SpringExtractor.HANDLED_BY)) {
          handled.add(f.subject() + " " + f.object().value());
        }
      }
      for (Fact f : linked) {
        if (f.predicate().equals(SpringExtractor.ENDPOINT)) {
          assertThat(handled)
              .as(set + " " + f)
              .anyMatch(h -> h.startsWith(f.subject() + " ") || h.endsWith(" " + f.subject()));
        }
      }
      List<String> sortedLinked = sorted(render(linked));
      Files.write(out.resolve(set + "-written.txt"), sorted(render(written)));
      Files.write(out.resolve(set + "-linked.txt"), sortedLinked);
      assertThat(sortedLinked).as(set).isEqualTo(sorted(render(fromFiles(code, written))));
      compared += linked.size();
    }
    System.out.println("EndpointsIT compared " + compared + " facts");
    assertThat(compared).isGreaterThan(0);
  }

  /** The written facts resolved against the modules' config files, as the extractor did. */
  private static List<Fact> fromFiles(JavaAnalysis code, List<Fact> written) {
    CodeConfig configs = new CodeConfig();
    Map<String, JavaAnalysis.Module> modules = new HashMap<>();
    code.modules().forEach(m -> modules.put(m.id().toString(), m));
    return SpringExtractor.resolve(
        written,
        (module, repo) -> {
          JavaAnalysis.Module m = modules.get(module.toString());
          return configs.forModule(m.repo(), m.directory());
        },
        origin(),
        1);
  }

  private static Origin origin() {
    return new ValueResolution().origin();
  }

  private static List<String> render(List<Fact> facts) {
    List<String> lines = new ArrayList<>();
    for (Fact f : facts) {
      lines.add(
          f.subject()
              + " "
              + f.predicate()
              + " "
              + f.object().value()
              + " "
              + f.attributes()
              + " @"
              + f.evidence().repo()
              + ":"
              + f.evidence().path()
              + (f.evidence().lines() == null ? "" : "#" + f.evidence().lines().start()));
    }
    return lines;
  }

  private static List<String> sorted(List<String> lines) {
    return lines.stream().sorted().toList();
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

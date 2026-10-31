package io.lantern.link;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.repo.Git;
import io.lantern.core.repo.Repo;
import io.lantern.core.repo.RepoSettings;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.config.ModuleValues;
import io.lantern.extract.java.JavaAnalysis;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.extract.persistence.PersistenceExtractor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * On the development test sets, the datasources and data stores linking records from the config
 * facts are those the persistence extractor's reading gives from the modules' config files, as the
 * extractor read them itself until #560; and each has its entity, connected to an address where its
 * config names a server. Runs only when {@code lantern.test.projects} names the folder the sets'
 * {@code setup.sh} set them up in; reads the repositories from git, builds nothing, and writes the
 * facts of each set to {@code target/datasources/}. The held-out sets are never read.
 */
@EnabledIfSystemProperty(named = "lantern.test.projects", matches = ".+")
class DatasourcesIT {

  private static final List<String> SETS =
      List.of("project-0", "project-1", "project-2", "project-3", "project-4", "project-8");

  private static final Set<String> PREDICATES =
      Set.of(
          PersistenceExtractor.DATASOURCE,
          PersistenceExtractor.DATA_STORE,
          PersistenceExtractor.HAS_DATASOURCE,
          PersistenceExtractor.CONNECTS_TO);

  @Test
  void linkingGivesTheDatasourcesTheExtractorGaveOnTheDevelopmentSets() throws IOException {
    Path root = Path.of(System.getProperty("lantern.test.projects"));
    Path out = Files.createDirectories(Path.of("target", "datasources"));
    int compared = 0;
    for (String set : SETS) {
      List<RepoBuild> repos = repos(root.resolve(set));
      if (repos.isEmpty()) {
        continue;
      }
      JavaAnalysis code = JavaExtractor.analyze(repos);
      List<Fact> extracted = new PersistenceExtractor().extract(code, 1);
      assertThat(extracted).as(set).noneMatch(f -> PREDICATES.contains(f.predicate()));
      List<Fact> facts = new ArrayList<>(new ConfigExtractor().extract(repos, 1));
      facts.addAll(extracted);

      List<Fact> linked =
          new ValueViews()
              .derive(facts, 1).stream().filter(f -> PREDICATES.contains(f.predicate())).toList();
      Files.write(out.resolve(set + ".txt"), sorted(render(linked)));

      // The datasources and data stores the extractor read from the config files of the
      // repositories with code; linking reads every module with config facts.
      Set<RepoBuild> withCode = new LinkedHashSet<>();
      code.modules().forEach(m -> withCode.add(m.repo()));
      List<ModuleValues> configs = new ArrayList<>();
      for (RepoBuild repo : withCode) {
        ConfigExtractor.read(repo).forEach(c -> configs.add(ModuleValues.of(c)));
      }
      List<Fact> fromFiles =
          PersistenceExtractor.datasources(configs, Set.of(), new ValueViews().origin(), 1);
      Set<String> modules = new HashSet<>();
      configs.forEach(c -> modules.add(c.module().toString()));
      List<String> linkedOfThose =
          sorted(
              render(
                  linked.stream()
                      .filter(f -> !f.predicate().equals(PersistenceExtractor.CONNECTS_TO))
                      .filter(f -> modules.contains(f.subject().toString()))
                      .toList()));
      List<String> expected =
          sorted(
              render(
                  fromFiles.stream()
                      .filter(f -> !f.predicate().equals(PersistenceExtractor.CONNECTS_TO))
                      .toList()));
      assertThat(linkedOfThose).as(set).isEqualTo(expected);
      compared += expected.size();

      // Every datasource and data store has its entity, and every entity an address.
      Set<String> has = new HashSet<>();
      Set<String> connected = new HashSet<>();
      for (Fact f : linked) {
        if (f.predicate().equals(PersistenceExtractor.HAS_DATASOURCE)) {
          has.add(f.subject() + " " + f.attributes().get("key").stringValue());
        }
        if (f.predicate().equals(PersistenceExtractor.CONNECTS_TO)) {
          connected.add(f.subject().toString());
          // A literal address is a host and port, never a piece of a placeholder.
          String address = f.object().entityId().key();
          assertThat(address.contains("${") || !address.contains("}")).as(set + " " + f).isTrue();
        }
      }
      for (Fact f : linked) {
        if (f.predicate().equals(PersistenceExtractor.HAS_DATASOURCE)) {
          assertThat(connected).as(set + " " + f).contains(f.object().value());
        }
      }
      // Modules of repositories without code, which only linking reads.
      long others =
          linked.stream()
              .filter(f -> !f.predicate().equals(PersistenceExtractor.CONNECTS_TO))
              .filter(f -> !modules.contains(f.subject().toString()))
              .count();
      System.out.println("DatasourcesIT " + set + ": facts of modules without code: " + others);
      for (Fact f : linked) {
        if (f.predicate().equals(PersistenceExtractor.DATASOURCE)
            || f.predicate().equals(PersistenceExtractor.DATA_STORE)) {
          assertThat(has)
              .as(set + " " + f)
              .contains(f.subject() + " " + f.attributes().get("key").stringValue());
        }
      }
    }
    System.out.println("DatasourcesIT compared " + compared + " facts");
    assertThat(compared).isGreaterThan(0);
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

package io.lantern.compose;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.document.Document;
import io.lantern.core.document.DocumentJson;
import io.lantern.core.document.Section;
import io.lantern.core.document.TemplateRef;
import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.repo.Git;
import io.lantern.core.repo.Repo;
import io.lantern.core.repo.RepoSettings;
import io.lantern.core.snapshot.Snapshot;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.extract.maven.MavenExtractor;
import io.lantern.summarize.InMemorySummaryStore;
import io.lantern.summarize.Summarizer;
import io.lantern.summarize.Summary;
import io.lantern.summarize.SummaryTree;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Composes an Architecture Explorer document and an Executive Brief for every repository of the
 * OpenWMS test set, extracted without builds and summarized by the fake model, and writes them to
 * {@code target/openwms-documents/<repo>.json} and {@code <repo>.brief.json} so the frontend can be
 * checked against real composer output. Runs only when {@code lantern.test.openwms} names the
 * directory {@code testsets/openwms/clone.sh} cloned them into, for example {@code ./mvnw verify
 * -pl lantern-compose -am -Dlantern.test.openwms=$PWD/workspace/openwms}.
 */
@EnabledIfSystemProperty(named = "lantern.test.openwms", matches = ".+")
class OpenWmsComposeIT {

  private static final TemplateRef TEMPLATE = new TemplateRef("architecture-explorer", "1");
  private static final TemplateRef BRIEF = new TemplateRef("executive-brief", "1");

  @Test
  void composesADocumentForEveryRepository() throws Exception {
    Path root = Path.of(System.getProperty("lantern.test.openwms"));
    List<RepoBuild> repos = new ArrayList<>();
    Map<String, String> commits = new TreeMap<>();
    try (Stream<Path> dirs = Files.list(root)) {
      for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
        String name = dir.getFileName().toString();
        String commit = Git.run(dir, "rev-parse", "HEAD").out();
        repos.add(
            new RepoBuild(new Repo(name, dir.toRealPath(), RepoSettings.DEFAULT), commit, null));
        commits.put(name, commit);
      }
    }
    assertThat(repos).hasSize(16);

    List<Fact> facts = new ArrayList<>(new MavenExtractor().extract(repos, 1));
    facts.addAll(new JavaExtractor().extract(repos, 1));
    Map<EntityId, Summary> summaries =
        new Summarizer(FixtureSnapshot.fakeClient(), new InMemorySummaryStore())
            .summarize(SummaryTree.fromFacts(facts))
            .summaries();
    Snapshot snapshot = new Snapshot(1, commits, 3, "test", Instant.EPOCH);

    Path out = Path.of("target", "openwms-documents");
    Files.createDirectories(out);
    for (String repo : commits.keySet()) {
      Document document =
          new Composer("0.1.0-test")
              .compose(ViewDefinition.perRepo(repo, repo, TEMPLATE), snapshot, facts, summaries);
      assertThat(document.sections().stream().map(Section::id))
          .as("sections of %s", repo)
          .containsExactly(
              "overview", "context", "containers", "components", "dependencies", "structure");
      String json = DocumentJson.write(document);
      assertThat(DocumentJson.read(json)).isEqualTo(document);
      Files.writeString(out.resolve(repo + ".json"), json);

      Document brief =
          new Composer("0.1.0-test")
              .compose(
                  ViewDefinition.executiveBrief(repo + ".brief", repo, BRIEF),
                  snapshot,
                  facts,
                  summaries);
      assertThat(brief.sections().stream().map(Section::id))
          .as("sections of the brief of %s", repo)
          .containsExactly("overview", "headlines", "risks", "context", "containers");
      String briefJson = DocumentJson.write(brief);
      assertThat(DocumentJson.read(briefJson)).isEqualTo(brief);
      Files.writeString(out.resolve(repo + ".brief.json"), briefJson);
    }
  }
}

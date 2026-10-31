package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.compose.Composer;
import io.lantern.compose.ViewDefinition;
import io.lantern.core.document.Document;
import io.lantern.core.document.DocumentJson;
import io.lantern.core.document.TemplateRef;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactRepository;
import io.lantern.core.fact.Origin;
import io.lantern.core.repo.Git;
import io.lantern.core.repo.Repo;
import io.lantern.core.repo.RepoRegistry;
import io.lantern.core.repo.RepoSettings;
import io.lantern.core.snapshot.Snapshot;
import io.lantern.core.snapshot.SnapshotStore;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.extract.maven.MavenExtractor;
import io.lantern.link.Linker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Two snapshots of repositories that are unchanged between two commits give the same claims and the
 * same observations, so the documents of both cite the same IDs for every element and line (ADR
 * 0091). The fixture lib-and-service is extracted by the Maven and Java extractors, without a
 * build, and linked by the standard linker, through the store and its blob lookup.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ClaimsAndObservationsIT {

  private static final TemplateRef TEMPLATE = new TemplateRef("c4-model-sequence-diagrams", "1");

  @TempDir Path temp;

  @Autowired FactRepository facts;
  @Autowired RepoRegistry registry;
  @Autowired SnapshotStore snapshots;

  @Test
  void anUnchangedRepositoryAtANewCommitKeepsEveryClaimAndObservation() throws IOException {
    Path fixtures = Path.of(System.getProperty("lantern.test.fixtures"));
    Map<String, Path> dirs = new TreeMap<>();
    for (String part : List.of("lib", "service")) {
      String name = "claims-" + part;
      Path dir = temp.resolve(name);
      copy(fixtures.resolve("lib-and-service").resolve(part), dir);
      git(dir, "init", "-q");
      git(dir, "add", "--all");
      commit(dir, "one");
      registry.register(name, dir.toRealPath(), RepoSettings.DEFAULT);
      dirs.put(name, dir);
    }

    Snapshot first = analyze(dirs);
    List<Fact> before = facts.findValidAt(first.number(), dirs.keySet());
    dirs.values().forEach(dir -> commit(dir, "two"));
    Snapshot second = analyze(dirs);
    List<Fact> after = facts.findValidAt(second.number(), dirs.keySet());

    assertThat(second.commits().values()).doesNotContainAnyElementsOf(first.commits().values());
    assertThat(before).isNotEmpty();
    assertThat(before).anyMatch(f -> f.evidence().blob() != null);
    // Elements (the deployable unit) and lines (what it bundles and its build dependencies).
    assertThat(before).anyMatch(f -> f.origin().name().equals("deployable-units"));
    assertThat(before).anyMatch(f -> f.predicate().equals("BUILD_DEPENDS_ON"));
    // Every fact found again is the same observation, still holding from the first snapshot.
    assertThat(after).containsExactlyElementsOf(before);
    assertThat(after).allSatisfy(f -> assertThat(f.validity().from()).isEqualTo(first.number()));
    assertThat(after).extracting(Fact::claimId).doesNotContainNull();
    // So the documents cite the same IDs for every element and line.
    assertThat(document(second, after)).isEqualTo(document(first, before));
  }

  /** Extracts and links the repositories at their HEAD, as the analysis run records them. */
  private Snapshot analyze(Map<String, Path> dirs) {
    Map<String, String> revisions = new TreeMap<>();
    dirs.keySet().forEach(name -> revisions.put(name, RepoRegistry.HEAD));
    Snapshot snapshot = snapshots.create(revisions);
    List<RepoBuild> builds = new ArrayList<>();
    dirs.forEach(
        (name, dir) ->
            builds.add(
                new RepoBuild(
                    new Repo(name, dir.toAbsolutePath(), RepoSettings.DEFAULT),
                    snapshot.commits().get(name),
                    null)));
    List<Fact> found = new ArrayList<>(new MavenExtractor().extract(builds, snapshot.number()));
    found.addAll(new JavaExtractor().extract(builds, snapshot.number()));
    Linker linker = Linker.standard(facts);
    FactRepository.SnapshotUpdate extracted =
        facts.recordSnapshot(
            snapshot.number(), Origin.Kind.STATIC, dirs.keySet(), linker.origins(), found);
    Linker.Result linked = linker.link(snapshot.number(), Set.copyOf(dirs.keySet()));
    if (snapshot.number() > 1) {
      assertThat(extracted.added() + extracted.changed() + extracted.closed()).isZero();
      assertThat(linked.added() + linked.changed() + linked.closed()).isZero();
    }
    snapshots.markAnalyzed(snapshot.number(), dirs.keySet());
    return snapshot;
  }

  /** The system's C4 report without what names the snapshot. */
  private static String document(Snapshot snapshot, List<Fact> facts) {
    Document document =
        new Composer("1")
            .compose(
                ViewDefinition.c4System("system:c4", "System", TEMPLATE),
                snapshot,
                facts,
                Map.of());
    String json = DocumentJson.write(document);
    for (Map.Entry<String, String> commit : snapshot.commits().entrySet()) {
      json = json.replace(commit.getValue(), "<" + commit.getKey() + ">");
    }
    return json.replaceAll("\"snapshot\":\\d+", "\"snapshot\":0");
  }

  private static void commit(Path dir, String message) {
    git(
        dir,
        "-c",
        "user.name=t",
        "-c",
        "user.email=t@t",
        "-c",
        "commit.gpgsign=false",
        "commit",
        "-q",
        "--allow-empty",
        "-m",
        message);
  }

  private static void git(Path dir, String... args) {
    Git.Result result = Git.run(dir, args);
    assertThat(result.ok()).as(result.err()).isTrue();
  }

  private static void copy(Path from, Path to) throws IOException {
    try (Stream<Path> paths = Files.walk(from)) {
      for (Path path : paths.toList()) {
        Path target = to.resolve(from.relativize(path).toString());
        if (Files.isDirectory(path)) {
          Files.createDirectories(target);
        } else {
          Files.copy(path, target);
        }
      }
    }
  }
}

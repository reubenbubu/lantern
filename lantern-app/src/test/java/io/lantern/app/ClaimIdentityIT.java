package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactRepository;
import io.lantern.core.vocabulary.VocabularyRegistry;
import io.lantern.sandbox.MavenMirror;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Analyzes a test set with the fake model and checks claim identity on every fact of its snapshot
 * ({@link ClaimIdentityCheck}, ADR 0090). Runs only when {@code lantern.test.claims-set} names, as
 * an absolute path, a folder a set's {@code setup.sh} set up, such as {@code
 * ~/workspace/lantern-test-projects/project-8}: every git repository in it for a synthetic set (one
 * made from a single pinned source), else every repository its pins name. A held-out set fails it
 * unless {@code lantern.test.held-out} is {@code true} (docs/design.md, Test sets). Writes the
 * count of facts and claims per predicate to {@code target/claims/<set>.tsv}.
 */
@SpringBootTest(
    properties = {
      "lantern.llm.models.fake.provider=fake",
      "lantern.llm.models.fake.id=fake-model",
      "lantern.llm.models.fake.context-window=1000000",
      "lantern.llm.models.fake.prices.input=0",
      "lantern.llm.models.fake.prices.output=0",
      "lantern.llm.tiers.high-volume=fake",
      "lantern.llm.tiers.reasoning=fake",
      "lantern.llm.anthropic.api-key="
    })
@Import({TestcontainersConfiguration.class, TestSetScoreIT.Fake.class})
@EnabledIfSystemProperty(
    named = "lantern.test.claims-set",
    matches = ".+",
    disabledReason = "lantern.test.claims-set names no test set folder")
class ClaimIdentityIT {

  @TempDir static Path WORKSPACE;

  private static final Path REPOSITORY = Path.of("..").toAbsolutePath().normalize();

  @DynamicPropertySource
  static void workspace(DynamicPropertyRegistry registry) throws IOException {
    registry.add("lantern.workspace", WORKSPACE::toString);
    String file = System.getProperty("lantern.test.properties");
    if (file == null || file.isBlank()) {
      file = System.getenv("LANTERN_TEST_PROPERTIES");
    }
    MavenMirror mirror = file == null || file.isBlank() ? null : MavenMirror.from(Path.of(file));
    if (mirror != null) {
      registry.add(MavenMirror.PROPERTY + ".id", mirror::id);
      registry.add(MavenMirror.PROPERTY + ".url", () -> mirror.url().toString());
    }
  }

  @Autowired AnalysisRun run;
  @Autowired FactRepository facts;
  @Autowired VocabularyRegistry vocabulary;

  @Test
  void everyFactOfTheSetsSnapshotHasAStableClaimIdentity() throws IOException {
    Path folder =
        Path.of(System.getProperty("lantern.test.claims-set")).toAbsolutePath().normalize();
    String set = folder.getFileName().toString();
    Path pins = REPOSITORY.resolve("testsets").resolve(set).resolve("pins.txt");
    assertThat(TestSetScoreIT.heldOut(pins) && !Boolean.getBoolean("lantern.test.held-out"))
        .as(set + " is held out (docs/design.md, Test sets)")
        .isFalse();

    AnalysisRun.Result result = run.run(set, null, repos(folder, pins));
    // A failed build leaves its repository in degraded mode; its facts are checked all the same.
    List<Fact> all =
        facts.findValidAt(result.snapshot().number(), result.snapshot().commits().keySet());

    assertThat(all).isNotEmpty();
    assertThat(ClaimIdentityCheck.problems(vocabulary.current(), all)).isEmpty();
    write(set, all);
  }

  private void write(String set, List<Fact> all) throws IOException {
    Map<String, long[]> counts = new TreeMap<>();
    Map<String, java.util.Set<String>> claims = new TreeMap<>();
    for (Fact fact : all) {
      counts.computeIfAbsent(fact.predicate(), k -> new long[1])[0]++;
      claims
          .computeIfAbsent(fact.predicate(), k -> new java.util.HashSet<>())
          .add(vocabulary.current().claimId(fact));
    }
    StringBuilder out = new StringBuilder("predicate\tfacts\tclaims\n");
    counts.forEach(
        (p, n) ->
            out.append(p)
                .append('\t')
                .append(n[0])
                .append('\t')
                .append(claims.get(p).size())
                .append('\n'));
    Path file = Path.of("target", "claims", set + ".tsv");
    Files.createDirectories(file.getParent());
    Files.writeString(file, out);
  }

  private static Map<String, Path> repos(Path folder, Path pins) throws IOException {
    Map<String, Path> repos = new LinkedHashMap<>();
    if (Files.exists(folder.resolve(".lantern-pin"))) {
      try (Stream<Path> dirs = Files.list(folder)) {
        dirs.filter(d -> Files.isDirectory(d.resolve(".git")))
            .sorted()
            .forEach(d -> repos.put(d.getFileName().toString(), d));
      }
    } else {
      for (String line : Files.readAllLines(pins)) {
        if (!line.isBlank() && !line.startsWith("#")) {
          String name = line.trim().split("\\s+")[0];
          repos.put(name, folder.resolve(name));
        }
      }
    }
    assertThat(repos).isNotEmpty();
    return repos;
  }
}

package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.compose.ViewDefinitionStore;
import io.lantern.core.analysis.AnalysisLock;
import io.lantern.core.fact.FactRepository;
import io.lantern.core.repo.RepoRegistry;
import io.lantern.core.repo.SystemRegistry;
import io.lantern.core.snapshot.SnapshotStore;
import io.lantern.harness.AnswerStore;
import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.Role;
import io.lantern.sandbox.BuildSandbox;
import io.lantern.sandbox.BuildStatus;
import io.lantern.sandbox.MavenMirror;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs Lantern on a test set with a fake model twice, the second time with one role (Summarize)
 * switched to another model, and scores both system reports against the set's reference with the
 * scoring harness (lantern-web/scripts/score.mjs; ADR 0072), then compares the two scorecards side
 * by side (#252). Free: both models are fakes; the second is priced, so the run record shows a
 * cost.
 *
 * <p>Runs only when {@code lantern.test.set} names, as an absolute path, a folder a set's {@code
 * setup.sh} set up, such as {@code ~/workspace/lantern-test-projects/project-4}; the set is the
 * folder's name, and {@code testsets/<set>/} must have pins and a reference. Every repository the
 * pins name is analyzed, those without a {@code pom.xml} without a build (#254). A held-out set
 * (project-5 to project-7) fails the test unless {@code lantern.test.held-out} is {@code true}
 * (docs/design.md, Test sets). Both runs share one database, so the second makes new calls only for
 * the switched role: everything else is reused.
 *
 * <p>Writes to {@code target/test-sets/<set>/}: for each model, the system report and the run
 * record in {@code <model>/}, the scorecard as {@code <model>.scorecard.json} and {@code .md}, and
 * {@code compare.md}. Builds run in the real sandbox with the default Maven cache; the workspace is
 * a temporary directory.
 */
@SpringBootTest(
    properties = {
      "lantern.llm.models.fake.provider=fake",
      "lantern.llm.models.fake.id=fake-model",
      "lantern.llm.models.fake.context-window=1000000",
      "lantern.llm.models.fake.prices.input=0",
      "lantern.llm.models.fake.prices.output=0",
      "lantern.llm.models.fake-priced.provider=fake",
      "lantern.llm.models.fake-priced.id=fake-priced",
      "lantern.llm.models.fake-priced.context-window=1000000",
      "lantern.llm.models.fake-priced.prices.input=3",
      "lantern.llm.models.fake-priced.prices.output=15",
      "lantern.llm.tiers.high-volume=fake",
      "lantern.llm.tiers.reasoning=fake",
      "lantern.llm.anthropic.api-key="
    })
@Import({TestcontainersConfiguration.class, TestSetScoreIT.Fake.class})
@EnabledIfSystemProperty(
    named = "lantern.test.set",
    matches = ".+",
    disabledReason = "lantern.test.set names no test set folder")
class TestSetScoreIT {

  /** The workspace; JUnit removes it after the class. */
  @TempDir static Path WORKSPACE;

  private static final Path REPOSITORY = Path.of("..").toAbsolutePath().normalize();
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @TestConfiguration(proxyBeanMethods = false)
  static class Fake {
    /** The fake model of {@link Project0EndToEndIT}, which keeps the static labels and prose. */
    @Bean
    FakeLlmProvider fakeLlmProvider() {
      return new Project0EndToEndIT.Fake().fakeLlmProvider();
    }
  }

  /** The workspace, and the Maven mirror of the test properties file, as EndToEndIT sets them. */
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
  @Autowired ApplicationContext context;
  @Autowired LlmProperties properties;
  @Autowired JdbcLlmCallLog callLog;
  @Autowired FakeLlmProvider fake;

  @Test
  void scoresTheSetTwiceWithSummarizeOnAnotherModel() throws Exception {
    Path setFolder = Path.of(System.getProperty("lantern.test.set")).toAbsolutePath().normalize();
    String set = setFolder.getFileName().toString();
    Path testset = REPOSITORY.resolve("testsets").resolve(set);
    assertThat(heldOut(testset.resolve("pins.txt")) && !Boolean.getBoolean("lantern.test.held-out"))
        .as(
            set
                + " is held out: nobody analyzes it before Phase 2's real-model acceptance"
                + " (docs/design.md, Test sets); set lantern.test.held-out=true only then")
        .isFalse();
    assertThat(testset.resolve("reference/reference.c4.json")).isRegularFile();
    Map<String, Path> repos = repos(testset.resolve("pins.txt"), setFolder);
    Path out = Path.of("target", "test-sets", set).toAbsolutePath();
    deleteRecursively(out);

    AnalysisRun.Result first = run.run(set, null, repos);
    first
        .builds()
        .forEach(
            (name, build) -> assertThat(build.status()).as(name).isEqualTo(BuildStatus.SUCCEEDED));
    Path firstCard = keep(first, out, "fake-model");

    // The same settings with Summarize on the priced fake, as --lantern.llm.roles.summarize would
    // set it for the app.
    Map<Role, String> roles = new LinkedHashMap<>(properties.roles());
    roles.put(Role.SUMMARIZE, "fake-priced");
    LlmProperties switched =
        new LlmProperties(
            properties.models(),
            properties.tiers(),
            roles,
            properties.anthropic(),
            properties.monthlyBudgetUsd(),
            properties.retry(),
            properties.runBudgetUsd(),
            properties.runBudgetShares());
    int sentBefore = fake.sent().size();
    AnalysisRun.Result second;
    try (LlmAccess access =
        LlmConfiguration.create(switched, List.of(fake), callLog, Clock.systemUTC())) {
      second = withLlm(access).run(set, null, repos);
    }
    Path secondCard = keep(second, out, "fake-priced");

    // Only the switched role made calls, all of them to the new model.
    assertThat(fake.sent().subList(sentBefore, fake.sent().size()))
        .isNotEmpty()
        .allSatisfy(sent -> assertThat(sent.model()).isEqualTo("fake-priced"))
        .allSatisfy(sent -> assertThat(sent.request().role()).isEqualTo(Role.SUMMARIZE));

    JsonNode a = JSON.readTree(Files.readString(firstCard));
    JsonNode b = JSON.readTree(Files.readString(secondCard));
    assertThat(model(a, "SUMMARIZE")).isEqualTo("fake/fake-model");
    assertThat(model(b, "SUMMARIZE")).isEqualTo("fake/fake-priced");
    assertThat(model(b, "COMPOSE")).isEqualTo("fake/fake-model");
    assertThat(a.at("/run/costUsd").decimalValue()).isZero();
    assertThat(b.at("/run/costUsd").decimalValue()).isPositive();
    // The fakes answer alike, so the reports score alike.
    assertThat(b.get("scores").get("connections")).isEqualTo(a.get("scores").get("connections"));

    Path compare = out.resolve("compare.md");
    node("compare", firstCard.toString(), secondCard.toString(), "--out", compare.toString());
    assertThat(Files.readString(compare))
        .contains("| SUMMARIZE | fake/fake-model (")
        .contains("fake/fake-priced (")
        .contains("| Run cost | $0 (");
  }

  /** The AnalysisRun of the app, with the given LLM access instead of the app's. */
  private AnalysisRun withLlm(LlmAccess access) {
    return new AnalysisConfiguration()
        .analysisRun(
            context.getBean(RepoRegistry.class),
            context.getBean(SystemRegistry.class),
            context.getBean(SnapshotStore.class),
            context.getBean(AnalysisLock.class),
            context.getBean(BuildSandbox.class),
            context.getBean(FactRepository.class),
            context.getBean(PlatformTransactionManager.class),
            access,
            callLog,
            context.getBean(AnswerStore.class),
            context.getBean(LlmProperties.class),
            context.getBean(ViewDefinitionStore.class),
            context.getBean(HtmlRenderer.class),
            context.getBean(C4ReportRenderer.class),
            context.getBean(JdbcExtractionCache.class),
            context.getBean(io.lantern.link.JdbcRuleRuns.class),
            context.getEnvironment().getRequiredProperty("lantern.version"),
            WORKSPACE);
  }

  /**
   * Copies the run's system report and run record to {@code <out>/<label>/}, scores the report and
   * returns the scorecard's JSON file.
   */
  private static Path keep(AnalysisRun.Result result, Path out, String label)
      throws IOException, InterruptedException {
    Path dir = Files.createDirectories(out.resolve(label));
    Path report =
        result.documents().stream()
            .map(AnalysisRun.Rendered::file)
            .filter(file -> file.getFileName().toString().equals("_system.c4.html"))
            .findFirst()
            .orElseThrow();
    Files.copy(report, dir.resolve("_system.c4.html"), StandardCopyOption.REPLACE_EXISTING);
    Files.copy(
        result.runRecord(), dir.resolve(RunRecord.FILE), StandardCopyOption.REPLACE_EXISTING);
    String set = out.getFileName().toString();
    node(
        dir.resolve("_system.c4.html").toString(),
        REPOSITORY.resolve("testsets").resolve(set).toString(),
        "--run",
        dir.resolve(RunRecord.FILE).toString(),
        "--out",
        out.resolve(label).toString());
    Path card = out.resolve(label + ".scorecard.json");
    assertThat(card).isRegularFile();
    return card;
  }

  /** "provider/model" of a role in a scorecard's run. */
  private static String model(JsonNode card, String role) {
    for (JsonNode r : card.at("/run/roles")) {
      if (r.get("role").asString().equals(role)) {
        return r.get("provider").asString() + "/" + r.get("model").asString();
      }
    }
    throw new AssertionError("no role " + role + " in " + card.get("run"));
  }

  /** Runs the scoring script with Node: the one the build installed, else the one on the PATH. */
  private static void node(String... args) throws IOException, InterruptedException {
    Path installed = REPOSITORY.resolve("lantern-web/target/node/node");
    List<String> command = new ArrayList<>();
    command.add(Files.isExecutable(installed) ? installed.toString() : "node");
    command.add(REPOSITORY.resolve("lantern-web/scripts/score.mjs").toString());
    command.addAll(List.of(args));
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.waitFor(2, TimeUnit.MINUTES)).as("score.mjs finished").isTrue();
    assertThat(process.exitValue()).as(output).isZero();
  }

  /** The set's repositories, by name, from its pins, in the folder setup.sh put them in. */
  private static Map<String, Path> repos(Path pins, Path folder) throws IOException {
    Map<String, Path> repos = new LinkedHashMap<>();
    for (String line : Files.readAllLines(pins)) {
      if (line.isBlank() || line.startsWith("#")) {
        continue;
      }
      String name = line.trim().split("\\s+")[0];
      Path repo = folder.resolve(name);
      assertThat(repo.resolve(".git")).as(repo + " is set up").exists();
      repos.put(name, repo);
    }
    assertThat(repos).isNotEmpty();
    return repos;
  }

  /** Whether the set's pins mark it held out (docs/design.md, Test sets). */
  static boolean heldOut(Path pins) throws IOException {
    return Files.readAllLines(pins).stream()
        .anyMatch(line -> line.startsWith("#") && line.contains("HELD-OUT"));
  }

  private static void deleteRecursively(Path dir) throws IOException {
    if (!Files.exists(dir)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(dir)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    }
  }
}

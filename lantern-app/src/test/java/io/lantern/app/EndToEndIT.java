package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.lantern.core.document.Document;
import io.lantern.core.document.DocumentJson;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactRepository;
import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.LlmRequest;
import io.lantern.sandbox.BuildSandbox;
import io.lantern.sandbox.BuildStatus;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Runs the whole pipeline (register, snapshot, build, extract, summarize, compose, render) twice
 * over the same commits, with a fake LLM, and checks that both templates are produced and that the
 * second run reuses every build, fact and summary and makes no LLM call. Also checks that analyzing
 * one repository keeps the facts of another analyzed before (ADR 0018).
 *
 * <p>Always runs on the {@code multi-module} fixture. Also runs on OpenWMS tms.transportation when
 * {@code lantern.test.openwms} names the directory {@code testsets/openwms/clone.sh} cloned the
 * test set into, and then writes what the LLM was asked to {@code
 * target/end-to-end/org.openwms.tms.transportation-llm-calls.txt}, so the cost of a real run can be
 * judged before making one.
 *
 * <p>Builds run in the real sandbox with a Maven cache volume made for these tests and removed
 * after them; the workspace is a temporary directory.
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
@Import({TestcontainersConfiguration.class, EndToEndIT.Fake.class})
class EndToEndIT {

  private static final String VOLUME =
      "lantern-test-m2-" + HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt());
  private static final Path WORKSPACE = tempDirectory();

  @TestConfiguration(proxyBeanMethods = false)
  static class Fake {
    /** A fake model that cites the first fact and the first part's summary it is given. */
    @Bean
    FakeLlmProvider fakeLlmProvider() {
      return new FakeLlmProvider()
          .answering(
              request -> {
                String prompt = request.messages().getFirst().text();
                List<String> sentences = new ArrayList<>();
                if (prompt.contains("[S1]")) {
                  sentences.add("It builds on its parts. [S1]");
                }
                if (prompt.contains("[F1]")) {
                  sentences.add("It is described by its facts. [F1]");
                }
                return String.join(" ", sentences);
              });
    }
  }

  @DynamicPropertySource
  static void workspace(DynamicPropertyRegistry registry) {
    registry.add("lantern.workspace", WORKSPACE::toString);
    registry.add("lantern.sandbox.maven-cache-volume", () -> VOLUME);
  }

  @Autowired AnalysisRun run;
  @Autowired FakeLlmProvider fake;
  @Autowired FactRepository facts;

  @TempDir Path temp;

  @AfterAll
  static void cleanUp() throws IOException {
    docker("volume", "rm", "--force", VOLUME);
    try (Stream<Path> paths = Files.walk(WORKSPACE)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    }
    assertThat(docker("ps", "--all", "--quiet", "--filter", "label=" + BuildSandbox.LABEL))
        .as("sandbox containers left behind")
        .isEmpty();
  }

  @Test
  void runsTheFixtureTwiceAndTheSecondRunMakesNoLlmCall() throws IOException {
    Path repo = fixtureRepo("multi-module", "shop");

    Twice twice = runTwice("shop", repo);

    assertThat(twice.first().builds().get("shop").status()).isEqualTo(BuildStatus.SUCCEEDED);
    assertThat(twice.first().documents())
        .extracting(d -> d.file().getFileName().toString())
        .containsExactly("shop.html", "shop.brief.html");

    // The same pipeline through the command line: everything is reused again.
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    AnalyzeCommand command =
        new AnalyzeCommand(run, new PrintStream(bytes, true, StandardCharsets.UTF_8));
    command.run(new DefaultApplicationArguments("analyze", repo.toString()));
    String output = bytes.toString(StandardCharsets.UTF_8);
    assertThat(command.getExitCode()).as(output).isZero();
    assertThat(output)
        .contains("build SUCCEEDED (reused)")
        .contains("Summaries: 0 written by the LLM")
        .contains("LLM calls: 0, tokens: 0, cost: $0")
        .contains("shop.brief.html");
  }

  @Test
  void analyzingAnotherRepositoryKeepsTheFactsOfTheFirst() throws IOException {
    // Two repositories, the service depending on the library.
    Path lib = fixtureRepo("lib-and-service/lib", "greeting-lib");
    Path service = fixtureRepo("lib-and-service/service", "greeting-service");

    AnalysisRun.Result first = run.run(Map.of("greeting-lib", lib));
    List<Fact> libFacts = validIn(first, "greeting-lib");
    assertThat(libFacts).hasSize(first.facts());

    // The second run analyzes only the service; the library is carried forward, its facts keep
    // holding, and the service's dependency on it is found as internal.
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    AnalyzeCommand command =
        new AnalyzeCommand(run, new PrintStream(bytes, true, StandardCharsets.UTF_8));
    command.run(new DefaultApplicationArguments("analyze", service.toString()));
    String output = bytes.toString(StandardCharsets.UTF_8);
    assertThat(command.getExitCode()).as(output).isZero();
    assertThat(output)
        .contains("greeting-lib@" + first.snapshot().commits().get("greeting-lib"))
        .contains("carried forward, not analyzed");
    long second = first.snapshot().number() + 1;
    assertThat(validIn(second, "greeting-lib")).isEqualTo(libFacts);
    List<Fact> serviceFacts = validIn(second, "greeting-service");
    assertThat(serviceFacts)
        .anySatisfy(
            fact -> {
              assertThat(fact.predicate()).isEqualTo("BUILD_DEPENDS_ON");
              assertThat(fact.object().value())
                  .isEqualTo("module:com.example.greeting:greeting-lib");
            });

    // Analyzing the library again at the same commit reuses everything and starts no new fact
    // version, and the service's facts keep holding.
    int sent = fake.sent().size();
    AnalysisRun.Result third = run.run(Map.of("greeting-lib", lib));
    assertThat(fake.sent()).as("LLM requests in the third run").hasSize(sent);
    assertThat(third.snapshot().number()).isEqualTo(second + 1);
    assertThat(third.builds()).containsOnlyKeys("greeting-lib");
    assertThat(third.reusedBuilds()).containsExactly("greeting-lib");
    assertThat(third.facts()).isEqualTo(first.facts());
    assertThat(third.newFacts()).isZero();
    assertThat(third.summariesGenerated()).isZero();
    assertThat(third.documents())
        .extracting(d -> d.file().getFileName().toString())
        .containsExactly("greeting-lib.html", "greeting-lib.brief.html");
    assertThat(validIn(third, "greeting-lib")).isEqualTo(libFacts);
    assertThat(validIn(third, "greeting-service")).isEqualTo(serviceFacts);
    for (Fact fact : libFacts) {
      assertThat(facts.findBySubject(fact.subject()))
          .filteredOn(f -> f.id().equals(fact.id()))
          .as("versions of %s", fact.id())
          .containsExactly(fact);
    }

    // Analyzed together, both find exactly the facts they found apart.
    AnalysisRun.Result together = run.run(Map.of("greeting-lib", lib, "greeting-service", service));
    assertThat(fake.sent()).as("LLM requests when analyzed together").hasSize(sent);
    assertThat(together.newFacts()).isZero();
    assertThat(validIn(together, "greeting-lib")).isEqualTo(libFacts);
    assertThat(validIn(together, "greeting-service")).isEqualTo(serviceFacts);
  }

  private List<Fact> validIn(AnalysisRun.Result result, String repo) {
    return validIn(result.snapshot().number(), repo);
  }

  /** The stored facts of the repository that hold in the snapshot. */
  private List<Fact> validIn(long snapshot, String repo) {
    return facts.findValidAt(snapshot).stream()
        .filter(fact -> fact.evidence().repo().equals(repo))
        .toList();
  }

  @Test
  void runsOpenWmsTransportationTwiceAndTheSecondRunMakesNoLlmCall() throws IOException {
    String openwms = System.getProperty("lantern.test.openwms");
    assumeTrue(openwms != null && !openwms.isBlank(), "lantern.test.openwms is not set");
    String name = "org.openwms.tms.transportation";

    int before = fake.sent().size();
    Twice twice = runTwice(name, Path.of(openwms, name));
    List<FakeLlmProvider.Sent> sent = fake.sent().subList(before, fake.sent().size());

    assertThat(twice.first().builds().get(name).status())
        .as(Files.readString(twice.first().builds().get(name).log()))
        .isEqualTo(BuildStatus.SUCCEEDED);
    assertThat(twice.first().documents())
        .extracting(d -> d.file().getFileName().toString())
        .containsExactly(name + ".html", name + ".brief.html");
    writeCallReport(name, sent);
  }

  /** What two runs over the same commits did. */
  private record Twice(AnalysisRun.Result first, AnalysisRun.Result second) {}

  private Twice runTwice(String name, Path repo) throws IOException {
    int sentBefore = fake.sent().size();
    AnalysisRun.Result first = run.run(Map.of(name, repo));
    int calls = fake.sent().size() - sentBefore;

    assertThat(first.facts()).isPositive();
    assertThat(first.newFacts()).isEqualTo(first.facts());
    assertThat(first.summariesGenerated()).isPositive();
    assertThat(first.summariesReused()).isZero();
    assertThat(calls).isEqualTo(first.summariesGenerated());
    assertThat(first.llm().calls()).isEqualTo(calls);
    assertThat(first.documents()).hasSize(2);
    assertThat(first.documents())
        .extracting(d -> d.document().template().name())
        .containsExactly("architecture-explorer", "executive-brief");
    for (AnalysisRun.Rendered rendered : first.documents()) {
      String html = Files.readString(rendered.file());
      assertThat(html)
          .contains("<script id=\"lantern-document\" type=\"application/json\">{")
          .contains("\"name\":\"" + rendered.document().template().name() + "\"");
    }

    int sentAfterFirst = fake.sent().size();
    AnalysisRun.Result second = run.run(Map.of(name, repo));

    assertThat(fake.sent()).as("LLM requests in the second run").hasSize(sentAfterFirst);
    assertThat(second.llm().calls()).as("LLM calls logged in the second run").isZero();
    assertThat(second.snapshot().number()).isEqualTo(first.snapshot().number() + 1);
    assertThat(second.snapshot().commits()).isEqualTo(first.snapshot().commits());
    assertThat(second.reusedBuilds()).containsExactly(name);
    assertThat(second.facts()).isEqualTo(first.facts());
    assertThat(second.newFacts()).isZero();
    assertThat(second.summariesGenerated()).isZero();
    assertThat(second.summariesReused()).isEqualTo(first.summariesGenerated());
    // The documents differ only in the snapshot they are of.
    for (int i = 0; i < 2; i++) {
      Document before = first.documents().get(i).document();
      Document after = second.documents().get(i).document();
      assertThat(after.source().snapshot()).isEqualTo(second.snapshot().number());
      assertThat(DocumentJson.write(after))
          .isEqualTo(
              DocumentJson.write(
                  new Document(
                      before.id(),
                      before.title(),
                      before.audience(),
                      before.template(),
                      after.source(),
                      before.entities(),
                      before.facts(),
                      before.sections())));
    }
    return new Twice(first, second);
  }

  /**
   * Writes how many calls a real run would make and how many tokens they would use: input as
   * estimated from the prompts' length (about four characters per token), and output at most the
   * calls' output limits. The fake's own answers are short, so its output tokens say nothing.
   */
  private static void writeCallReport(String name, List<FakeLlmProvider.Sent> sent)
      throws IOException {
    Map<String, long[]> byLevel = new TreeMap<>();
    long input = 0;
    long outputLimit = 0;
    long largestInput = 0;
    for (FakeLlmProvider.Sent call : sent) {
      LlmRequest request = call.request();
      String[] purpose = request.purpose().split(" ", 3);
      long[] level = byLevel.computeIfAbsent(purpose[1].toLowerCase(Locale.ROOT), k -> new long[3]);
      level[0]++;
      level[1] += request.estimatedInputTokens();
      level[2] += request.maxOutputTokens();
      input += request.estimatedInputTokens();
      outputLimit += request.maxOutputTokens();
      largestInput = Math.max(largestInput, request.estimatedInputTokens());
    }
    StringBuilder report = new StringBuilder();
    report
        .append("LLM calls a run of ")
        .append(name)
        .append(" makes (all role SUMMARIZE, high-volume tier):\n");
    report.append("calls: ").append(sent.size()).append('\n');
    report.append("input tokens (estimated): ").append(input).append('\n');
    report.append("output tokens (at most): ").append(outputLimit).append('\n');
    report.append("largest single input (estimated): ").append(largestInput).append('\n');
    byLevel.forEach(
        (level, counts) ->
            report
                .append(level)
                .append(": ")
                .append(counts[0])
                .append(" calls, ")
                .append(counts[1])
                .append(" input tokens, at most ")
                .append(counts[2])
                .append(" output tokens\n"));
    Path out = Path.of("target", "end-to-end");
    Files.createDirectories(out);
    Files.writeString(out.resolve(name + "-llm-calls.txt"), report.toString());
    System.out.print(report);
  }

  /** A git repository with the fixture committed, in a directory with the given name. */
  private Path fixtureRepo(String fixtureName, String name) throws IOException {
    Path fixture = Path.of(System.getProperty("lantern.test.fixtures"), fixtureName);
    Path repo = temp.resolve(name);
    copy(fixture, repo);
    TestGitRepo git = TestGitRepo.init(repo);
    git.git("add", "--all");
    git.git("commit", "--quiet", "-m", "Fixture");
    return repo;
  }

  private static void copy(Path source, Path target) throws IOException {
    try (Stream<Path> paths = Files.walk(source)) {
      for (Path from : paths.sorted().toList()) {
        Files.copy(from, target.resolve(source.relativize(from).toString()));
      }
    }
  }

  private static Path tempDirectory() {
    try {
      return Files.createTempDirectory("lantern-e2e-workspace-");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String docker(String... args) {
    List<String> command = new ArrayList<>(List.of("docker"));
    command.addAll(List.of(args));
    try {
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      process.getOutputStream().close();
      String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (process.waitFor() != 0) {
        throw new IllegalStateException(String.join(" ", command) + ": " + out);
      }
      return out.strip();
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot run docker", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while running docker", e);
    }
  }
}

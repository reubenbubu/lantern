package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.lantern.compose.ViewDefinitionStore;
import io.lantern.core.analysis.AnalysisLock;
import io.lantern.core.document.Document;
import io.lantern.core.document.DocumentJson;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactRepository;
import io.lantern.core.fact.Origin;
import io.lantern.core.repo.Repo;
import io.lantern.core.repo.RepoRegistry;
import io.lantern.core.repo.RepoSettings;
import io.lantern.core.repo.SystemRegistry;
import io.lantern.core.snapshot.SnapshotStore;
import io.lantern.core.types.Types;
import io.lantern.harness.AnswerStore;
import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.Role;
import io.lantern.sandbox.BuildResult;
import io.lantern.sandbox.BuildSandbox;
import io.lantern.sandbox.BuildStatus;
import io.lantern.sandbox.MavenMirror;
import io.lantern.sandbox.SandboxException;
import io.lantern.sandbox.UpstreamBuild;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the whole pipeline (register, snapshot, build, extract, summarize, compose, render) twice
 * over the same commits, with a fake LLM, and checks that both templates are produced and that the
 * second run reuses every build, fact and summary and makes no LLM call. Also checks that analyzing
 * one repository keeps the facts of another analyzed before, and that a run that fails before
 * storing its facts leaves its commit out of later snapshots (ADR 0018), and that a stored failed
 * build is built again while a successful one is reused (#58).
 *
 * <p>Always runs on the {@code multi-module} fixture. Also runs on the Maven repository that {@code
 * lantern.test.repo} names, as an absolute path, if set. It registers that repository as {@code
 * given-<directory>}, so the name cannot clash with a fixture's, and writes what the LLM was asked
 * to {@code target/end-to-end/given-<directory>-llm-calls.txt}, so the cost of a real run can be
 * judged before making one.
 *
 * <p>Builds run in the real sandbox with the application's default Maven cache directory, which
 * keeps downloads between runs, and with an owner label of their own, so the check that nothing is
 * left behind sees only their containers; the workspace is a temporary directory.
 */
@SpringBootTest(
    properties = {
      "lantern.llm.models.fake.provider=fake",
      "lantern.llm.models.fake.id=fake-model",
      "lantern.llm.models.fake.context-window=1000000",
      "lantern.llm.models.fake.capabilities=tool-use",
      "lantern.llm.models.fake.prices.input=0",
      "lantern.llm.models.fake.prices.output=0",
      "lantern.llm.tiers.high-volume=fake",
      "lantern.llm.tiers.reasoning=fake",
      "lantern.llm.anthropic.api-key="
    })
@Import({TestcontainersConfiguration.class, EndToEndIT.Fake.class})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EndToEndIT {

  /** The workspace; JUnit removes it after the class, whether its tests passed or not. */
  @TempDir static Path WORKSPACE;

  private static final String RUN_LABEL = "lantern.test.run";
  private static final String RUN =
      HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextLong());
  private static final String STORE_FAILED = "Storing the facts failed";

  /**
   * How the fake support judge answers (#751): {@code null} as every fake run does, every text
   * supported; {@code once}, Describe's first responsibilities rejected and their rewording
   * accepted; {@code twice}, both rejected.
   */
  static final java.util.concurrent.atomic.AtomicReference<String> JUDGE =
      new java.util.concurrent.atomic.AtomicReference<>();

  private static final Pattern JUDGED =
      Pattern.compile("<item id=\"(I[0-9]+)\">\n<text>([^<]*)</text>");

  /** The purpose the fake words first, as long as Purpose's checks ask (10 to 25 words). */
  static final String PURPOSE = "Greets the people who call it, as a small Java library would.";

  /** What the fake words the purpose as when asked again, for the run's judge. */
  static String retried() {
    return "Greets each of the people who call it, as the README of the library says ("
        + JUDGE.get()
        + ").";
  }

  /** The fake support judge's answer while {@link #JUDGE} is set, else null. */
  static String judging(LlmRequest request) {
    String mode = JUDGE.get();
    if (mode == null || !request.system().contains("Task: check support.")) {
      return null;
    }
    Matcher items = JUDGED.matcher(request.messages().getFirst().text());
    List<String> entries = new ArrayList<>();
    while (items.find()) {
      String text = items.group(2);
      boolean rejected = text.equals(PURPOSE) || (mode.equals("twice") && text.equals(retried()));
      entries.add(
          "\"%s\": {\"claims\": [{\"claim\": \"%s\", \"verdict\": \"%s\"}]}"
              .formatted(
                  items.group(1),
                  rejected ? "It greets people." : "The text.",
                  rejected ? "unsupported" : "supported"));
    }
    return "{\"items\": {" + String.join(", ", entries) + "}}";
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Fake {
    /**
     * A fake model that cites the first fact and the first part's summary it is given, picks the
     * first candidate of a link left open, citing it, answers Describe as the {@code fake-llm}
     * profile does (ADR 0103), and words a flow's walkthrough and the system's purpose, citing the
     * first fact (ADR 0058, ADR 0060).
     */
    @Bean
    FakeLlmProvider fakeLlmProvider() {
      return new FakeLlmProvider()
          // A link left open is resolved to its first candidate, through submit (ADR 0105).
          .callingTools(request -> FakeLlmConfiguration.resolve(request, "K1"))
          .answering(
              request -> {
                String judged = judging(request);
                if (judged != null) {
                  return judged;
                }
                String check = FakeLlmConfiguration.modelCheck(request);
                if (check != null) {
                  return check;
                }
                // Describe in the epic's form, as the fake-llm profile answers it (ADR 0103).
                String described = FakeLlmConfiguration.describe(request);
                if (described != null) {
                  return described;
                }
                String prompt = request.messages().getFirst().text();
                if (request.role() == Role.RESOLVE) {
                  return "Done.";
                }
                // No flow judged, so each scope draws its highest ranked; each narrated (ADR 0108).
                if (request.role() == Role.FLOW_SIGNIFICANCE) {
                  return FakeLlmConfiguration.flows(request);
                }
                if (request.role() == Role.COMPOSE && request.system().contains("narrate flows")) {
                  return "{\"items\": {\"I1\": {\"walkthrough\": {\"text\": \"It answers a"
                      + " greeting.\", \"cites\": [\"F1\"]}}}}";
                }
                // The system's purpose, citing the item's first reference (ADR 0109).
                if (request.system().contains(FakeLlmConfiguration.PURPOSE_TASK)) {
                  java.util.regex.Matcher ref =
                      java.util.regex.Pattern.compile("\\[([FA][0-9]+)\\]").matcher(prompt);
                  // Asked again after the support judge rejected it, it words it anew, citing
                  // the README's claim.
                  String purpose =
                      JUDGE.get() == null
                          ? "Greets people."
                          : prompt.contains("<retry") ? retried() : PURPOSE;
                  if (JUDGE.get() != null) {
                    ref =
                        java.util.regex.Pattern.compile("\\[([FA][0-9]+)\\] readme:")
                            .matcher(prompt);
                  }
                  return ref.find()
                      ? "{\"items\": {\"I1\": {\"purpose\": {\"text\": \""
                          + purpose
                          + "\","
                          + " \"cites\": [\""
                          + ref.group(1)
                          + "\"]}}}}"
                      : "{\"items\": {\"I1\": {\"omitted\": true}}}";
                }
                if (request.role() == Role.INFER_INTENT) {
                  String cite = "{\"text\": \"%s\", \"cites\": [\"F1\"]}";
                  String answer =
                      "{\"unit\": "
                          + cite.formatted("Greets its callers.")
                          + ", \"element\": "
                          + cite.formatted("Keeps the greetings.")
                          + ", \"purpose\": "
                          + cite.formatted("Greets people.");
                  if (prompt.contains("[C1]")) {
                    answer +=
                        ", \"components\": {\"C1\": " + cite.formatted("Takes requests.") + "}";
                  }
                  if (prompt.contains("[R1]")) {
                    answer +=
                        ", \"relationships\": {\"R1\": "
                            + cite.formatted("Hands over its work")
                            + "}";
                  }
                  return answer + "}";
                }
                // Summarize in the epic's form (ADR 0104).
                String summary =
                    FakeLlmConfiguration.summarize(
                        request,
                        "Builds on its parts, as the summaries of what it contains say.",
                        "Described by its facts, as the analysis of its code found them.");
                return summary != null ? summary : "{}";
              });
    }

    /**
     * Replaces the sandbox of AnalysisConfiguration with one built from the same settings plus this
     * test class's owner label.
     */
    @Bean
    static BeanPostProcessor ownerLabel(
        ObjectProvider<SandboxProperties> sandbox, Environment environment) {
      return new BeanPostProcessor() {
        @Override
        public Object postProcessAfterInitialization(Object bean, String name) {
          if (!(bean instanceof BuildSandbox)) {
            return bean;
          }
          return new BuildSandbox(
              AnalysisConfiguration.sandboxSettings(
                      Path.of(environment.getRequiredProperty("lantern.workspace")),
                      sandbox.getObject())
                  .withOwnerLabel(RUN_LABEL, RUN));
        }
      };
    }
  }

  /**
   * The workspace, and the Maven mirror set in the Lantern properties file that the system property
   * {@code lantern.test.properties}, or else the environment variable {@code
   * LANTERN_TEST_PROPERTIES}, names, if any; without one, builds download from Maven Central (ADR
   * 0030).
   */
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
  @Autowired FakeLlmProvider fake;
  @Autowired FactRepository facts;
  @Autowired BuildSandbox sandbox;
  @Autowired ApplicationContext context;

  @TempDir Path temp;

  @AfterAll
  static void cleanUp() {
    assertThat(docker("ps", "--all", "--quiet", "--filter", "label=" + RUN_LABEL + "=" + RUN))
        .as("sandbox containers left behind")
        .isEmpty();
  }

  /** First, so no other test has summarized the same code under another repository's name. */
  @Test
  @Order(1)
  void runsTheFixtureTwiceAndTheSecondRunMakesNoLlmCall() throws IOException {
    Path repo = fixtureRepo("multi-module", "shop");

    Twice twice = runTwice("shop", "shop", repo);

    assertThat(twice.first().builds().get("shop").status()).isEqualTo(BuildStatus.SUCCEEDED);
    assertThat(twice.first().documents())
        .extracting(d -> d.file().getFileName().toString())
        .containsExactly("shop.html", "shop.brief.html", "shop.c4.html", "_system.c4.html");
    // Filed under the system, titled by the one repository's name (ADR 0071).
    assertThat(twice.first().documents())
        .allSatisfy(
            d ->
                assertThat(d.file().getParent())
                    .isEqualTo(
                        WORKSPACE
                            .resolve("documents")
                            .resolve("shop")
                            .resolve(Long.toString(twice.first().snapshot().number()))));
    assertThat(twice.first().documents().get(3).document().title()).isEqualTo("shop");
    // The run record next to the documents names every role's model and counts the run's calls.
    JsonNode record =
        JsonMapper.builder().build().readTree(Files.readString(twice.first().runRecord()));
    assertThat(twice.first().runRecord().getParent())
        .isEqualTo(twice.first().documents().getFirst().file().getParent());
    assertThat(record.get("calls").asLong()).isEqualTo(twice.first().llm().calls());
    assertThat(record.get("roles")).hasSize(Role.values().length);
    record
        .get("roles")
        .forEach(role -> assertThat(role.get("model").asString()).isEqualTo("fake-model"));

    // The same pipeline through the command line: everything is reused again.
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    AnalyzeCommand command =
        new AnalyzeCommand(run, new PrintStream(bytes, true, StandardCharsets.UTF_8));
    command.run(new DefaultApplicationArguments("analyze", "--system=shop", repo.toString()));
    String output = bytes.toString(StandardCharsets.UTF_8);
    assertThat(command.getExitCode()).as(output).isZero();
    assertThat(output)
        .contains("build SUCCEEDED (reused)")
        .contains("Summaries: 0 written by the LLM")
        .contains("LLM calls: 0, tokens: 0, cost: $0")
        .contains("shop.brief.html")
        .contains("shop.c4.html")
        .contains("_system.c4.html")
        .contains("run.json");
  }

  /**
   * The harness's gate (knowledge-build epic, section 13; ADR 0100) on a fixture of its own, so it
   * runs where project-0 is not set up (#769): a one-file change asks again only items that rest on
   * that file, or show an earlier answer that changed, and a rerun of the changed commit asks none.
   * The fixture summarizes its modules and itself. A change to its README asks again exactly the
   * repository's summary and the system's purpose, which show its claims; a change to a Javadoc
   * line no item shows asks nothing.
   */
  @Test
  void aOneFileChangeAsksAgainOnlyTheItemsRestingOnThatFile() throws IOException {
    Path repo = fixtureRepo("multi-module", "gate-shop");
    TestGitRepo git = TestGitRepo.init(repo);
    git.commit("README.md", "# Gate shop\n\nThe shop prices its products.\n");
    run.run("gate", null, Map.of("gate-shop", repo));
    int sent = fake.sent().size();
    run.run("gate", null, Map.of("gate-shop", repo));
    assertThat(fake.sent()).as("calls of an unchanged rerun").hasSize(sent);

    // The README's claims are shown to the repository's summary only: exactly what is asked again
    // (knowledge-build epic, section 13).
    String readme = "README.md";
    git.commit(readme, "# Gate shop\n\nThe shop charges one price for every product.\n");
    AnalysisRun.Result changed = run.run("gate", null, Map.of("gate-shop", repo));
    assertThat(askedAgain(changed, readme)).containsExactlyInAnyOrder(README_ASKS);

    int afterChange = fake.sent().size();
    run.run("gate", null, Map.of("gate-shop", repo));
    assertThat(fake.sent()).as("calls of a rerun of the changed commit").hasSize(afterChange);

    // One line of a Java file that no item shows and no fact rests on (the fixture summarizes no
    // type; its modules' summaries show facts, not source): nothing is asked again.
    String java = "core/src/main/java/com/example/shop/core/FixedPriceService.java";
    String text = Files.readString(repo.resolve(java));
    git.commit(
        java,
        text.replace(
            "/** Charges the same price for every product. */",
            "/** Charges one fixed price for every product. */"));
    AnalysisRun.Result javaChanged = run.run("gate", null, Map.of("gate-shop", repo));
    assertThat(askedAgain(javaChanged, java)).isEmpty();
  }

  /**
   * What a README change asks again, as role and subject: the repository's summary and the system's
   * purpose, the two items shown its claims.
   */
  private static final String[] README_ASKS = {
    "summarize repo:gate-shop", "purpose system:snapshot"
  };

  /**
   * The items a run asked again, as role and subject, each for the changed file or an earlier
   * answer that changed, and for nothing else (no new item, prompt or model).
   */
  private static List<String> askedAgain(AnalysisRun.Result result, String path)
      throws IOException {
    JsonNode record = JsonMapper.builder().build().readTree(Files.readString(result.runRecord()));
    List<String> asked = new ArrayList<>();
    record
        .get("harness")
        .forEach(
            role -> {
              role.get("askedSubjects")
                  .forEach(
                      subject -> asked.add(role.get("role").asString() + " " + subject.asString()));
              role.get("askedWhy")
                  .forEach(
                      why -> {
                        String item =
                            role.get("role").asString() + " " + why.get("subject").asString();
                        assertThat(
                                why.has("new")
                                    || why.has("unheld")
                                    || why.has("other")
                                    || why.has("prompt")
                                    || why.has("model"))
                            .as(item + " asked for " + why)
                            .isFalse();
                        List<String> files = new ArrayList<>();
                        why.path("files").forEach(f -> files.add(f.asString()));
                        // The changed file, or only an earlier answer it shows that changed.
                        assertThat(!files.isEmpty() || why.path("answers").asInt() > 0)
                            .as(item + " asked for " + why)
                            .isTrue();
                        assertThat(files).as(item).isSubsetOf("gate-shop:" + path);
                      });
            });
    return asked;
  }

  /**
   * A text the support judge rejects is asked again once, told why; rejected again, the documents
   * show Lantern's plain wording, and the run record counts it (the owner's decision of 4 Nov 2026
   * on #751). The fixture's one text Describe words is the system's purpose, which the judge always
   * checks; when it is rejected twice, the README's first descriptive paragraph takes its place.
   */
  @Test
  void aPurposeTheJudgeRejectsIsAskedAgainOnceAndThenLeftToThePlainWording() throws IOException {
    for (String mode : List.of("once", "twice")) {
      JUDGE.set(mode);
      try {
        // A repository of its own, so no other test's answers are reused for it.
        Path lib = fixtureRepo("lib-and-service/lib", mode + "-greeting");
        TestGitRepo.init(lib)
            .commit("README.md", "# Greeting\n\nThe library greets the people who call it.\n");
        int sent = fake.sent().size();

        AnalysisRun.Result result =
            run.run("judged-" + mode, null, Map.of(mode + "-greeting", lib));

        // The purpose was asked again, told the claim the judge found unsupported.
        assertThat(fake.sent().subList(sent, fake.sent().size()))
            .extracting(x -> x.request().messages().getFirst().text())
            .anySatisfy(
                text ->
                    assertThat(text)
                        .contains("<retry")
                        .contains("\"It greets people.\"")
                        .contains("The library greets the people who call it."));
        String overview =
            DocumentJson.write(result.documents().getLast().document()).replaceAll("\\s+", " ");
        JsonNode purpose =
            JsonMapper.builder()
                .build()
                .readTree(Files.readString(result.runRecord()))
                .path("supportVerdicts")
                .path("purpose");
        assertThat(purpose.path("retried").asInt()).isEqualTo(1);
        if (mode.equals("once")) {
          // The second answer is shown.
          assertThat(overview).contains(retried()).doesNotContain(PURPOSE);
          assertThat(purpose.path("fellBack").asInt()).isZero();
          assertThat(purpose.path("supported").asInt()).isEqualTo(1);
        } else {
          // Rejected again: the README's paragraph, quoted, and the run record counts one
          // fallback.
          assertThat(overview)
              .contains("Its README says: \u201cThe library greets the people who call it.\u201d")
              .doesNotContain(retried())
              .doesNotContain(PURPOSE);
          assertThat(purpose.path("fellBack").asInt()).isEqualTo(1);
          assertThat(purpose.path("unsupported").asInt()).isEqualTo(1);
        }
        // No document shows a support mark.
        for (AnalysisRun.Rendered document : result.documents()) {
          assertThat(Files.readString(document.file()))
              .doesNotContain("Not supported by its sources");
        }
      } finally {
        JUDGE.set(null);
      }
    }
  }

  @Test
  void analyzingAnotherRepositoryKeepsTheFactsOfTheFirst() throws IOException {
    // Two repositories, the service depending on the library.
    Path lib = fixtureRepo("lib-and-service/lib", "greeting-lib");
    Path service = fixtureRepo("lib-and-service/service", "greeting-service");
    // A README and a decision record, whose facts the docs extractor gives origin kind readme, not
    // static (#165).
    TestGitRepo.init(lib).commit("README.md", "# Greeting\n\nThe library greets people.\n");
    TestGitRepo.init(lib)
        .commit(
            "docs/adr/0001-plain-java.md",
            "# 1. Plain Java\n\n## Status\n\nAccepted\n\n## Decision\n\nNo framework.\n");

    AnalysisRun.Result first = run.run("greeting", null, Map.of("greeting-lib", lib));
    List<Fact> libFacts = validIn(first, "greeting-lib");
    assertThat(libFacts).hasSize(first.facts());
    List<Fact> readmeFacts =
        libFacts.stream().filter(f -> f.origin().kind() == Origin.Kind.README).toList();
    assertThat(readmeFacts)
        .filteredOn(f -> f.predicate().equals(Types.CLAIM))
        .extracting(f -> f.object().value())
        .containsExactly("The library greets people.");
    assertThat(readmeFacts)
        .filteredOn(f -> f.subject().type().equals(Types.DECISION))
        .extracting(f -> f.predicate() + " " + f.object().value())
        .contains(Types.TITLE + " Plain Java");

    // The second run analyzes only the service; the library is carried forward, its facts keep
    // holding, and the service's dependency on it is found as internal. The service builds, with
    // the artifacts of the library's stored build (ADR 0041).
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    AnalyzeCommand command =
        new AnalyzeCommand(run, new PrintStream(bytes, true, StandardCharsets.UTF_8));
    command.run(
        new DefaultApplicationArguments("analyze", "--system=greeting", service.toString()));
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
    // The linker folds the carried-forward library into the service's deployable unit.
    assertThat(serviceFacts)
        .extracting(f -> f.subject() + " " + f.predicate() + " " + f.object().value())
        .contains(
            "service:com.example.greeting:greeting-service deployable-unit executable-jar",
            "service:com.example.greeting:greeting-service BUNDLES"
                + " library:com.example.greeting:greeting-lib",
            "service:com.example.greeting:greeting-service application-name greeting-service");

    // Analyzing the library again at the same commit reuses everything and starts no new fact
    // version, and the service's facts keep holding. The service is carried forward, so it is not
    // built again.
    String serviceCommit =
        context.getBean(RepoRegistry.class).resolve("greeting-service", RepoRegistry.HEAD);
    BuildResult serviceBuild = sandbox.find("greeting-service", serviceCommit).orElseThrow();
    assertThat(serviceBuild.status()).as(serviceBuild.failure()).isEqualTo(BuildStatus.SUCCEEDED);
    assertThat(serviceBuild.modules().getFirst().classpath())
        .contains("lib/com/example/greeting/greeting-lib/1.0.0/greeting-lib-1.0.0.jar");
    int sent = fake.sent().size();
    AnalysisRun.Result third = run.run("greeting", null, Map.of("greeting-lib", lib));
    assertThat(sandbox.find("greeting-service", serviceCommit))
        .as("the carried-forward service's stored build")
        .contains(serviceBuild);
    assertThat(fake.sent()).as("LLM requests in the third run").hasSize(sent);
    // The service's summaries keep holding though only the library was analyzed (ADR 0104).
    assertThat(facts.findValidAt(third.snapshot().number(), java.util.Set.of("greeting-service")))
        .as("summaries of the carried-forward service")
        .anySatisfy(f -> assertThat(f.predicate()).isEqualTo(Types.SUMMARY));
    assertThat(third.snapshot().number()).isEqualTo(second + 1);
    assertThat(third.builds()).containsOnlyKeys("greeting-lib");
    assertThat(third.reusedBuilds()).containsExactly("greeting-lib");
    assertThat(third.facts()).isEqualTo(first.facts());
    assertThat(third.newFacts()).isZero();
    assertThat(third.summariesGenerated()).isZero();
    assertThat(third.documents())
        .extracting(d -> d.file().getFileName().toString())
        .containsExactly(
            "greeting-lib.html",
            "greeting-lib.brief.html",
            "greeting-lib.c4.html",
            "_system.c4.html");
    assertThat(validIn(third, "greeting-lib")).isEqualTo(libFacts);
    assertThat(validIn(third, "greeting-service")).isEqualTo(serviceFacts);
    for (Fact fact : libFacts) {
      assertThat(facts.findBySubject(fact.subject()))
          .filteredOn(f -> f.id().equals(fact.id()))
          .as("versions of %s", fact.id())
          .containsExactly(fact);
    }

    // Analyzed together, both find exactly the facts they found apart.
    AnalysisRun.Result together =
        run.run("greeting", null, Map.of("greeting-lib", lib, "greeting-service", service));
    assertThat(fake.sent()).as("LLM requests when analyzed together").hasSize(sent);
    assertThat(together.newFacts()).isZero();
    assertThat(together.reusedBuilds()).containsExactly("greeting-lib", "greeting-service");
    assertThat(validIn(together, "greeting-lib")).isEqualTo(libFacts);
    assertThat(validIn(together, "greeting-service")).isEqualTo(serviceFacts);

    // A run at a new commit of the library that fails while storing its facts stores neither the
    // facts nor the mark that the library was analyzed, so the next run carries the library
    // forward at the commit its facts come from, not at the failed run's commit (#61).
    String analyzedLib = first.snapshot().commits().get("greeting-lib");
    String failedLib =
        TestGitRepo.init(lib).commit("README.md", "# Greeting\n\nThe library says hello.\n");
    assertThatThrownBy(
            () -> failingToStoreFacts().run("greeting", null, Map.of("greeting-lib", lib)))
        .hasMessage(STORE_FAILED);
    AnalysisRun.Result afterFailure =
        run.run("greeting", null, Map.of("greeting-service", service));
    assertThat(afterFailure.snapshot().number()).isEqualTo(together.snapshot().number() + 2);
    assertThat(afterFailure.snapshot().commits())
        .containsEntry("greeting-lib", analyzedLib)
        .doesNotContainValue(failedLib);
    assertThat(fake.sent()).as("LLM requests after the failed run").hasSize(sent);
    assertThat(validIn(afterFailure, "greeting-lib")).isEqualTo(libFacts);
    assertThat(validIn(afterFailure, "greeting-service")).isEqualTo(serviceFacts);
    assertThat(afterFailure.reusedBuilds()).containsExactly("greeting-service");

    // Once the library moves to a new commit, the service's stored build was made with classes
    // the snapshot no longer has, so it is built again with the library's new build (ADR 0041).
    AnalysisRun.Result moved =
        run.run("greeting", null, Map.of("greeting-lib", lib, "greeting-service", service));
    assertThat(moved.snapshot().commits()).containsEntry("greeting-lib", failedLib);
    // The failed run built the library at that commit already.
    assertThat(moved.reusedBuilds()).containsExactly("greeting-lib");
    BuildResult rebuiltService = moved.builds().get("greeting-service");
    assertThat(rebuiltService.status()).isEqualTo(BuildStatus.SUCCEEDED);
    assertThat(rebuiltService.upstream()).containsExactly("greeting-lib@" + failedLib);
    assertThat(rebuiltService.startedAt()).isAfter(serviceBuild.startedAt());
    // The README's claim of the old commit stops holding, and the new commit's claim holds; the
    // decision record, unchanged, keeps its facts, which name no commit (ADR 0091).
    List<Fact> movedLib = validIn(moved, "greeting-lib");
    assertThat(movedLib)
        .doesNotContainAnyElementsOf(
            readmeFacts.stream().filter(f -> f.predicate().equals(Types.CLAIM)).toList())
        .containsAll(
            readmeFacts.stream().filter(f -> f.subject().type().equals(Types.DECISION)).toList());
    assertThat(movedLib)
        .filteredOn(
            f -> f.origin().kind() == Origin.Kind.README && f.predicate().equals(Types.CLAIM))
        .extracting(f -> f.object().value())
        .containsExactly("The library says hello.");
    for (Fact fact : readmeFacts) {
      assertThat(facts.findById(fact.id()).orElseThrow().validity().to())
          .as("end of %s", fact.id())
          .isEqualTo(fact.predicate().equals(Types.CLAIM) ? moved.snapshot().number() : null);
    }
  }

  @Test
  void oneAnalysisCoversOneNamedSystem() throws IOException {
    // Two systems in one database: alpha with one repository, beta with a library and a service.
    Path parts = fixtureRepo("multi-module", "alpha-parts");
    Path lib = fixtureRepo("lib-and-service/lib", "beta-lib");
    Path service = fixtureRepo("lib-and-service/service", "beta-service");
    TestGitRepo.init(lib).commit("README.md", "# Beta Shop — Library\n\nIt greets.\n");
    TestGitRepo.init(service).commit("README.md", "Intro\n\n# Beta shop: Service ##\n");
    AnalysisRun.Result alpha = run.run("alpha", null, Map.of("alpha-parts", parts));
    List<Fact> partsFacts = validIn(alpha, "alpha-parts");
    assertThat(partsFacts).isNotEmpty();

    // Analyzing beta carries nothing of alpha forward, its report shows nothing of alpha, and
    // alpha's facts, the linker's included, keep holding (ADR 0071).
    AnalysisRun.Result beta =
        run.run("beta", null, Map.of("beta-lib", lib, "beta-service", service));
    assertThat(beta.snapshot().commits()).containsOnlyKeys("beta-lib", "beta-service");
    assertThat(validIn(beta, "alpha-parts")).isEqualTo(partsFacts);
    Document betaReport = beta.documents().getLast().document();
    assertThat(betaReport.id()).isEqualTo(AnalysisRun.SYSTEM_C4);
    assertThat(betaReport.source().commits()).containsOnlyKeys("beta-lib", "beta-service");
    assertThat(DocumentJson.write(betaReport))
        .doesNotContain("alpha-parts")
        .doesNotContain("com.example.shop")
        .doesNotContain("PriceService");
    assertThat(betaReport.facts()).allMatch(f -> f.evidence().repo().startsWith("beta-"));
    // Without a title given, the words the repositories' README headings start with.
    assertThat(beta.title()).isEqualTo("Beta Shop");
    assertThat(betaReport.title()).isEqualTo("Beta Shop");
    assertThat(beta.documents().getLast().file())
        .isEqualTo(
            WORKSPACE
                .resolve("documents")
                .resolve("beta")
                .resolve(Long.toString(beta.snapshot().number()))
                .resolve("_system.c4.html"));

    // A title given is stored and kept by later runs.
    AnalysisRun.Result titled = run.run("beta", "Beta Store", Map.of("beta-lib", lib));
    assertThat(titled.documents().getLast().document().title()).isEqualTo("Beta Store");
    AnalysisRun.Result kept = run.run("beta", null, Map.of("beta-lib", lib));
    assertThat(kept.title()).isEqualTo("Beta Store");
    assertThat(kept.snapshot().commits()).containsOnlyKeys("beta-lib", "beta-service");

    // A repository belongs to one system. A refused run changes nothing: the repository keeps
    // its path, and no system is created.
    assertThatThrownBy(() -> run.run("beta", null, Map.of("alpha-parts", parts)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("belongs to the system alpha");
    RepoRegistry registry = context.getBean(RepoRegistry.class);
    Path partsPath = registry.find("alpha-parts").orElseThrow().path();
    assertThatThrownBy(() -> run.run("gamma", null, Map.of("alpha-parts", lib)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("belongs to the system alpha");
    assertThat(registry.find("alpha-parts").orElseThrow().path()).isEqualTo(partsPath);
    assertThat(context.getBean(SystemRegistry.class).exists("gamma")).isFalse();

    // A removed repository is not carried forward, and may then join another system.
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    AnalyzeCommand command =
        new AnalyzeCommand(run, new PrintStream(bytes, true, StandardCharsets.UTF_8));
    command.run(new DefaultApplicationArguments("remove", "--system=beta", "beta-service"));
    assertThat(command.getExitCode()).as(bytes.toString(StandardCharsets.UTF_8)).isZero();
    bytes.reset();
    command.run(
        new DefaultApplicationArguments("analyze", "--system=beta", "--title=", lib.toString()));
    String output = bytes.toString(StandardCharsets.UTF_8);
    assertThat(command.getExitCode()).as(output).isZero();
    // A blank --title clears the stored one; one repository is left, so its name is the title.
    assertThat(output).contains("System beta (beta-lib)");
    long removedSnapshot = context.getBean(SnapshotStore.class).latest().orElseThrow().number();
    assertThat(context.getBean(SnapshotStore.class).find(removedSnapshot).orElseThrow().commits())
        .containsOnlyKeys("beta-lib");
    run.remove("alpha", List.of("alpha-parts"));
    AnalysisRun.Result moved = run.run("beta", null, Map.of("alpha-parts", parts));
    assertThat(moved.snapshot().commits()).containsOnlyKeys("alpha-parts", "beta-lib");
  }

  @Test
  void analyzesARepositoryWithoutAMavenBuildWithoutBuildingIt() throws IOException {
    // A frontend without a pom.xml, next to a Maven library (#254).
    Path lib = fixtureRepo("lib-and-service/lib", "web-lib");
    Path frontend = Files.createDirectories(temp.resolve("web-frontend"));
    TestGitRepo git = TestGitRepo.init(frontend);
    git.commit("package.json", "{\"name\": \"web-frontend\"}\n");
    String commit =
        git.commit("README.md", "# Web Frontend\n\nThe frontend shows the greetings.\n");
    // A run before #254 stored a failed build of it, which is not used.
    Repo registered =
        context
            .getBean(RepoRegistry.class)
            .register("web-frontend", frontend, RepoSettings.DEFAULT);
    BuildResult failed = sandbox.build(registered, commit);
    assertThat(failed.failure()).isEqualTo("No pom.xml at the top of the repository");
    // Java code built with Gradle, which Lantern cannot analyze without a top pom.xml.
    Path gradle = Files.createDirectories(temp.resolve("web-gradle"));
    TestGitRepo gradleGit = TestGitRepo.init(gradle);
    gradleGit.commit("settings.gradle", "rootProject.name = 'web-gradle'\n");
    String gradleCommit = gradleGit.commit("src/main/java/web/App.java", "class App {}\n");

    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    AnalyzeCommand command =
        new AnalyzeCommand(run, new PrintStream(bytes, true, StandardCharsets.UTF_8));
    command.run(
        new DefaultApplicationArguments(
            "analyze", "--system=web", lib.toString(), frontend.toString(), gradle.toString()));
    String output = bytes.toString(StandardCharsets.UTF_8);
    assertThat(command.getExitCode()).as(output).isZero();
    assertThat(output)
        .contains("  web-frontend@" + commit + ": analyzed without a build (no pom.xml)\n")
        .contains(
            "  web-gradle@"
                + gradleCommit
                + ": warning: has Java sources, Gradle build files but no top-level pom.xml: its"
                + " code is not analyzed\n")
        .contains("web-lib@")
        .doesNotContain("degraded mode");
    // Its README was read, and its stored failed build is not used.
    assertThat(sandbox.find("web-frontend", commit)).contains(failed);
    assertThat(AnalysisRun.storedBuild(sandbox, registered, commit)).isNull();
    long snapshot = context.getBean(SnapshotStore.class).latest().orElseThrow().number();
    assertThat(validIn(snapshot, "web-frontend"))
        .filteredOn(f -> f.predicate().equals(Types.CLAIM))
        .extracting(f -> f.subject() + " " + f.object().value())
        .containsExactly("readme:web-frontend:README.md The frontend shows the greetings.");

    // Again, for the run's result: the library's build is reused and nothing is new.
    AnalysisRun.Result again =
        run.run(
            "web", null, Map.of("web-lib", lib, "web-frontend", frontend, "web-gradle", gradle));
    assertThat(again.withoutBuild()).containsExactlyInAnyOrder("web-frontend", "web-gradle");
    assertThat(again.codeNotAnalyzed())
        .isEqualTo(Map.of("web-gradle", List.of("Java sources", "Gradle build files")));
    assertThat(again.builds()).containsOnlyKeys("web-lib");
    assertThat(again.reusedBuilds()).containsExactly("web-lib");
    assertThat(again.newFacts()).isZero();
    assertThat(again.documents())
        .extracting(d -> d.file().getFileName().toString())
        .contains("web-frontend.html", "web-frontend.brief.html", "web-frontend.c4.html");

    // Carried forward, it is still not built, and its facts keep holding.
    AnalysisRun.Result carried = run.run("web", null, Map.of("web-lib", lib));
    assertThat(carried.snapshot().commits()).containsEntry("web-frontend", commit);
    assertThat(carried.withoutBuild()).isEmpty();
    assertThat(carried.builds()).containsOnlyKeys("web-lib");
    assertThat(validIn(carried, "web-frontend")).isEqualTo(validIn(again, "web-frontend"));
  }

  @Test
  void rebuildsAStoredFailedBuildAndReusesASuccessfulOne() throws IOException {
    Path lib = fixtureRepo("lib-and-service/lib", "flaky-lib");
    Repo repo =
        context.getBean(RepoRegistry.class).register("flaky-lib", lib, RepoSettings.DEFAULT);
    String commit = context.getBean(RepoRegistry.class).resolve("flaky-lib", RepoRegistry.HEAD);
    BuildResult built = sandbox.build(repo, commit);
    assertThat(built.status()).isEqualTo(BuildStatus.SUCCEEDED);

    // Stored as if the build had failed, for example because a download failed for a moment.
    Path file = built.directory().resolve(BuildSandbox.RESULT_FILE);
    String json = Files.readString(file);
    Files.writeString(
        file,
        json.replaceFirst("\"status\" : \"SUCCEEDED\"", "\"status\" : \"FAILED\"")
            .replaceFirst("\"failure\" : null", "\"failure\" : \"A download failed\""));
    BuildResult failed = sandbox.find("flaky-lib", commit).orElseThrow();
    assertThat(failed.status()).isEqualTo(BuildStatus.FAILED);

    // A sandbox that cannot build does not fail the run: the stored failed build is used, and
    // the facts are found in degraded mode.
    AnalysisRun.Result first =
        analysisRun(transaction(), unavailableSandbox())
            .run("flaky", null, Map.of("flaky-lib", lib));
    assertThat(first.reusedBuilds()).containsExactly("flaky-lib");
    assertThat(first.builds().get("flaky-lib")).isEqualTo(failed);
    List<Fact> degraded = validIn(first, "flaky-lib");

    // The next run builds it again instead of reusing the failure. The build succeeds, so facts
    // found with more confidence start a new version.
    AnalysisRun.Result second = run.run("flaky", null, Map.of("flaky-lib", lib));
    BuildResult rebuilt = second.builds().get("flaky-lib");
    assertThat(second.reusedBuilds()).isEmpty();
    assertThat(rebuilt.status()).isEqualTo(BuildStatus.SUCCEEDED);
    assertThat(rebuilt.startedAt()).isAfter(built.startedAt());
    assertThat(sandbox.find("flaky-lib", commit)).contains(rebuilt);
    Map<String, Fact> before = new TreeMap<>();
    degraded.forEach(fact -> before.put(fact.id(), fact));
    List<Fact> raised =
        validIn(second, "flaky-lib").stream()
            .filter(
                fact ->
                    before.containsKey(fact.id())
                        && fact.confidence() > before.get(fact.id()).confidence())
            .toList();
    assertThat(raised).isNotEmpty();
    for (Fact fact : raised) {
      assertThat(facts.findBySubject(fact.subject()))
          .filteredOn(f -> f.id().equals(fact.id()))
          .as("versions of %s", fact.id())
          .extracting(Fact::confidence)
          .containsExactlyInAnyOrder(before.get(fact.id()).confidence(), fact.confidence());
    }

    // A successful build is reused.
    AnalysisRun.Result third = run.run("flaky", null, Map.of("flaky-lib", lib));
    assertThat(third.reusedBuilds()).containsExactly("flaky-lib");
    assertThat(third.builds().get("flaky-lib")).isEqualTo(rebuilt);
  }

  /** A sandbox whose builds all fail as if Docker were unavailable. */
  private BuildSandbox unavailableSandbox() {
    return new BuildSandbox(
        AnalysisConfiguration.sandboxSettings(WORKSPACE, context.getBean(SandboxProperties.class))
            .withOwnerLabel(RUN_LABEL, RUN)) {
      @Override
      public BuildResult build(Repo repo, String commit, List<UpstreamBuild> upstream) {
        throw new SandboxException("Docker is unavailable");
      }
    };
  }

  private TransactionTemplate transaction() {
    return new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
  }

  /**
   * An analysis run like the app's, but whose fact-storing transaction runs and is then rolled back
   * with an exception, as if storing the facts had failed.
   */
  private AnalysisRun failingToStoreFacts() {
    TransactionTemplate failing =
        new TransactionTemplate(context.getBean(PlatformTransactionManager.class)) {
          @Override
          public <T> T execute(TransactionCallback<T> action) {
            return super.execute(
                status -> {
                  action.doInTransaction(status);
                  throw new IllegalStateException(STORE_FAILED);
                });
          }
        };
    return analysisRun(failing, sandbox);
  }

  /** An analysis run like the app's, with the given transactions and sandbox. */
  private AnalysisRun analysisRun(TransactionTemplate transaction, BuildSandbox sandbox) {
    return new AnalysisRun(
        context.getBean(RepoRegistry.class),
        context.getBean(SystemRegistry.class),
        context.getBean(SnapshotStore.class),
        context.getBean(AnalysisLock.class),
        sandbox,
        facts,
        transaction,
        context.getBean(LlmAccess.class),
        context.getBean(JdbcLlmCallLog.class),
        context.getBean(AnswerStore.class),
        io.lantern.harness.RunBudget.NONE,
        context.getBean(ViewDefinitionStore.class),
        context.getBean(HtmlRenderer.class),
        context.getBean(C4ReportRenderer.class),
        context.getBean(JdbcExtractionCache.class),
        context.getBean(io.lantern.link.JdbcRuleRuns.class),
        context.getEnvironment().getRequiredProperty("lantern.version"),
        WORKSPACE.resolve("documents"));
  }

  private List<Fact> validIn(AnalysisRun.Result result, String repo) {
    return validIn(result.snapshot().number(), repo);
  }

  /**
   * The stored facts of the repository that hold in the snapshot, without what the LLM roles wrote
   * about them, such as its summaries (ADR 0104).
   */
  private List<Fact> validIn(long snapshot, String repo) {
    return facts.findValidAt(snapshot).stream()
        .filter(fact -> fact.evidence().repo().equals(repo))
        .filter(fact -> fact.origin().kind() != Origin.Kind.LLM)
        .toList();
  }

  @Test
  void runsAGivenRepositoryTwiceAndTheSecondRunMakesNoLlmCall() throws IOException {
    String path = System.getProperty("lantern.test.repo");
    assumeTrue(path != null && !path.isBlank(), "lantern.test.repo is not set");
    Path repo = Path.of(path).normalize();
    assertThat(repo.isAbsolute())
        .as("lantern.test.repo must be an absolute path: " + path)
        .isTrue();
    assertThat(repo.getFileName()).as("lantern.test.repo names no directory: " + path).isNotNull();
    // Prefixed so it cannot be one of the fixture repositories the other tests register.
    String name = "given-" + repo.getFileName();

    int before = fake.sent().size();
    Twice twice = runTwice(name, name, repo);
    List<FakeLlmProvider.Sent> sent = fake.sent().subList(before, fake.sent().size());

    assertThat(twice.first().builds().get(name).status())
        .as(Files.readString(twice.first().builds().get(name).log()))
        .isEqualTo(BuildStatus.SUCCEEDED);
    assertThat(twice.first().documents())
        .extracting(d -> d.file().getFileName().toString())
        .containsExactly(
            name + ".html", name + ".brief.html", name + ".c4.html", "_system.c4.html");
    writeCallReport(name, sent);
  }

  /**
   * Checks that the run made the given number of calls, and that each logged call is counted under
   * its role in the run's report and by what the run asked for: a summary, a link left open, or a
   * wording (#245). Holds when no answer is rejected or retried, as with the fake model.
   */
  static void assertEveryCallCountedUnderItsRole(AnalysisRun.Result run, int calls) {
    Map<Role, Long> logged = run.llm().callsByRole();
    assertThat(run.llm().calls()).as("LLM calls logged").isEqualTo(calls);
    assertThat(logged.values().stream().mapToLong(Long::longValue).sum())
        .as("LLM calls logged under a role: " + logged)
        .isEqualTo(calls);
    // The support judge on a writer's model is logged under that model's role (#751): the
    // writers' own calls are the rest.
    Map<Role, Long> byRole = new java.util.EnumMap<>(Role.class);
    byRole.putAll(logged);
    long judgedOnWriters = 0;
    for (io.lantern.harness.RoleRecord record : run.roles()) {
      if (record.role().startsWith("support-judge-")) {
        Role model =
            Role.valueOf(
                record
                    .role()
                    .substring("support-judge-".length())
                    .replace('-', '_')
                    .toUpperCase(java.util.Locale.ROOT));
        byRole.merge(model, -(long) record.calls(), Long::sum);
        judgedOnWriters += record.calls();
      }
    }
    // Types are summarized on the bulk role, the units above them on the other (ADR 0104).
    assertThat(
            byRole.getOrDefault(Role.SUMMARIZE, 0L) + byRole.getOrDefault(Role.SUMMARIZE_TYPES, 0L))
        .as("summaries")
        .isEqualTo(run.summariesGenerated());
    // Resolve asks a unit's links left open in conversations of a few turns each (ADR 0105).
    long resolveCalls =
        run.roles().stream()
            .filter(r -> r.role().equals(io.lantern.link.LinkResolver.ROLE.name()))
            .mapToLong(io.lantern.harness.RoleRecord::calls)
            .sum();
    assertThat(byRole.getOrDefault(Role.RESOLVE, 0L)).as("links left open").isEqualTo(resolveCalls);
    assertThat(resolveCalls > 0).as("links asked").isEqualTo(run.linksAsked() > 0);
    // Describe asks up to 10 elements a request (ADR 0103), and repairs, so its calls are not
    // its items.
    long wordings =
        byRole.getOrDefault(Role.COMPOSE, 0L)
            + byRole.getOrDefault(Role.FLOW_SIGNIFICANCE, 0L)
            + byRole.getOrDefault(Role.INFER_INTENT, 0L)
            + byRole.getOrDefault(Role.DESCRIBE_ELEMENTS, 0L);
    // Each wording call is one the wording roles' records counted.
    long recorded =
        run.roles().stream()
            .filter(
                r ->
                    java.util.Set.of(
                            "describe-unit",
                            "describe-element",
                            "flow-significance",
                            "flow-narrative",
                            "purpose")
                        .contains(r.role()))
            .mapToLong(io.lantern.harness.RoleRecord::calls)
            .sum();
    assertThat(wordings).as("wordings").isEqualTo(recorded);
    assertThat(wordings > 0).as("wordings asked").isEqualTo(run.wordingsAsked() > 0);
    // Each Investigate call is one its records counted (ADR 0110).
    long investigateCalls = byRole.getOrDefault(Role.INVESTIGATE, 0L);
    assertThat(investigateCalls)
        .as("investigate calls")
        .isEqualTo(
            run.roles().stream()
                .filter(r -> r.role().startsWith("investigate-"))
                .mapToLong(io.lantern.harness.RoleRecord::calls)
                .sum());
    // The model checks (ADR 0101) and Name groups make the rest.
    assertThat(calls)
        .isEqualTo(
            run.summariesGenerated()
                + resolveCalls
                + wordings
                + investigateCalls
                + byRole.getOrDefault(Role.JUDGE, 0L)
                + judgedOnWriters
                + byRole.getOrDefault(Role.SCREEN, 0L)
                // Name groups names the groups a set has, when it has any.
                + byRole.getOrDefault(Role.NAME_GROUPS, 0L));
  }

  /** What two runs over the same commits did. */
  private record Twice(AnalysisRun.Result first, AnalysisRun.Result second) {}

  private Twice runTwice(String system, String name, Path repo) throws IOException {
    int sentBefore = fake.sent().size();
    AnalysisRun.Result first = run.run(system, null, Map.of(name, repo));
    int calls = fake.sent().size() - sentBefore;

    assertThat(first.facts()).isPositive();
    assertThat(first.newFacts()).isEqualTo(first.facts());
    assertThat(first.summariesGenerated()).isPositive();
    assertThat(first.summariesReused()).isZero();
    assertEveryCallCountedUnderItsRole(first, calls);
    assertThat(first.documents()).hasSize(4);
    assertThat(first.documents())
        .extracting(d -> d.document().template().name())
        .containsExactly(
            "architecture-explorer",
            "executive-brief",
            C4ReportRenderer.SPEC,
            C4ReportRenderer.SPEC);
    assertThat(first.documents().get(3).document().id()).isEqualTo(AnalysisRun.SYSTEM_C4);
    for (AnalysisRun.Rendered rendered : first.documents().subList(0, 2)) {
      String html = Files.readString(rendered.file());
      assertThat(html)
          .contains("<script id=\"lantern-document\" type=\"application/json\">{")
          .contains("\"name\":\"" + rendered.document().template().name() + "\"");
    }
    // The C4 report carries the report spec's data, built from its document, instead.
    AnalysisRun.Rendered report = first.documents().get(2);
    assertThat(Files.readString(report.file()))
        .contains(
            "<script type=\"application/json\" id=\"arc-data\">\n"
                + C4ReportRenderer.write(C4ReportRenderer.fromClassPath().data(report.document()))
                    .replace("<", "\\u003c")
                + "\n</script>");

    int sentAfterFirst = fake.sent().size();
    AnalysisRun.Result second = run.run(system, null, Map.of(name, repo));

    assertThat(fake.sent()).as("LLM requests in the second run").hasSize(sentAfterFirst);
    assertThat(second.llm().calls()).as("LLM calls logged in the second run").isZero();
    assertThat(second.snapshot().number()).isEqualTo(first.snapshot().number() + 1);
    assertThat(second.snapshot().commits()).isEqualTo(first.snapshot().commits());
    assertThat(second.reusedBuilds()).containsExactly(name);
    assertThat(second.facts()).isEqualTo(first.facts());
    assertThat(second.newFacts()).isZero();
    assertThat(second.summariesGenerated()).isZero();
    assertThat(second.summariesReused()).isEqualTo(first.summariesGenerated());
    assertThat(second.wordingsAsked()).isZero();
    assertThat(second.linksAsked()).isZero();
    assertThat(second.linksReused()).isEqualTo(first.linksAsked() + first.linksReused());
    // The first run may already reuse wordings of repositories carried forward from earlier runs,
    // such as the system's purpose when nothing it shows changed (ADR 0060).
    assertThat(second.wordingsReused()).isEqualTo(first.wordingsAsked() + first.wordingsReused());
    // The documents differ only in the snapshot they are of.
    for (int i = 0; i < 4; i++) {
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
      long[] level =
          byLevel.computeIfAbsent(
              request.role() + " " + purpose[1].toLowerCase(Locale.ROOT), k -> new long[3]);
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
        .append(
            " makes (roles SUMMARIZE and COMPOSE, high-volume tier, and INFER_INTENT"
                + " and RESOLVE, reasoning tier):\n");
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

package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.lantern.compose.ViewDefinitionStore;
import io.lantern.compose.WordingStore;
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
import io.lantern.extract.docs.DocsExtractor;
import io.lantern.link.ResolutionStore;
import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.Role;
import io.lantern.sandbox.BuildResult;
import io.lantern.sandbox.BuildSandbox;
import io.lantern.sandbox.BuildStatus;
import io.lantern.sandbox.MavenMirror;
import io.lantern.sandbox.SandboxException;
import io.lantern.sandbox.UpstreamBuild;
import io.lantern.summarize.SummaryStore;
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

  @TestConfiguration(proxyBeanMethods = false)
  static class Fake {
    /**
     * A fake model that cites the first fact and the first part's summary it is given, picks the
     * first candidate of a link left open, citing it, and words a unit, its first component and its
     * first relationship, a data store or destination, a flow's walkthrough and the system's
     * purpose, citing the first fact (ADR 0050, ADR 0056, ADR 0058, ADR 0060).
     */
    @Bean
    FakeLlmProvider fakeLlmProvider() {
      return new FakeLlmProvider()
          .answering(
              request -> {
                String prompt = request.messages().getFirst().text();
                if (request.role() == Role.RESOLVE) {
                  return "{\"target\": \"C1\", \"confidence\": \"low\", \"cites\": [\"C1\"],"
                      + " \"rationale\": \"The first candidate.\"}";
                }
                if (request.role() == Role.COMPOSE) {
                  return "{\"walkthrough\": {\"text\": \"It answers a greeting.\","
                      + " \"cites\": [\"F1\"]}}";
                }
                if (request.role() == Role.INFER_INTENT) {
                  String cite = "{\"text\": \"%s\", \"cites\": [\"F1\"]}";
                  String answer =
                      "{\"unit\": "
                          + cite.formatted("It greets its callers.")
                          + ", \"element\": "
                          + cite.formatted("It keeps the greetings.")
                          + ", \"purpose\": "
                          + cite.formatted("It greets people.");
                  if (prompt.contains("[C1]")) {
                    answer +=
                        ", \"components\": {\"C1\": " + cite.formatted("It takes requests.") + "}";
                  }
                  if (prompt.contains("[R1]")) {
                    answer +=
                        ", \"relationships\": {\"R1\": "
                            + cite.formatted("Hands over its work")
                            + "}";
                  }
                  return answer + "}";
                }
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
        .filteredOn(f -> f.predicate().equals(DocsExtractor.CLAIM))
        .extracting(f -> f.object().value())
        .containsExactly("The library greets people.");
    assertThat(readmeFacts)
        .filteredOn(f -> f.subject().type().equals(DocsExtractor.DECISION))
        .extracting(f -> f.predicate() + " " + f.object().value())
        .contains(DocsExtractor.TITLE + " Plain Java");

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
            readmeFacts.stream().filter(f -> f.predicate().equals(DocsExtractor.CLAIM)).toList())
        .containsAll(
            readmeFacts.stream()
                .filter(f -> f.subject().type().equals(DocsExtractor.DECISION))
                .toList());
    assertThat(movedLib)
        .filteredOn(
            f ->
                f.origin().kind() == Origin.Kind.README
                    && f.predicate().equals(DocsExtractor.CLAIM))
        .extracting(f -> f.object().value())
        .containsExactly("The library says hello.");
    for (Fact fact : readmeFacts) {
      assertThat(facts.findById(fact.id()).orElseThrow().validity().to())
          .as("end of %s", fact.id())
          .isEqualTo(
              fact.predicate().equals(DocsExtractor.CLAIM) ? moved.snapshot().number() : null);
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
        .filteredOn(f -> f.predicate().equals(DocsExtractor.CLAIM))
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
        context.getBean(SummaryStore.class),
        context.getBean(ResolutionStore.class),
        context.getBean(WordingStore.class),
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

  /** The stored facts of the repository that hold in the snapshot. */
  private List<Fact> validIn(long snapshot, String repo) {
    return facts.findValidAt(snapshot).stream()
        .filter(fact -> fact.evidence().repo().equals(repo))
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
    Map<Role, Long> byRole = run.llm().callsByRole();
    assertThat(run.llm().calls()).as("LLM calls logged").isEqualTo(calls);
    assertThat(byRole.values().stream().mapToLong(Long::longValue).sum())
        .as("LLM calls logged under a role: " + byRole)
        .isEqualTo(calls);
    assertThat(byRole.getOrDefault(Role.SUMMARIZE, 0L))
        .as("summaries")
        .isEqualTo(run.summariesGenerated());
    assertThat(byRole.getOrDefault(Role.RESOLVE, 0L))
        .as("links left open")
        .isEqualTo(run.linksAsked());
    assertThat(byRole.getOrDefault(Role.COMPOSE, 0L) + byRole.getOrDefault(Role.INFER_INTENT, 0L))
        .as("wordings")
        .isEqualTo(run.wordingsAsked());
    assertThat(calls).isEqualTo(run.summariesGenerated() + run.linksAsked() + run.wordingsAsked());
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
                + C4ReportRenderer.write(C4ReportRenderer.toJson(report.document()).data())
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

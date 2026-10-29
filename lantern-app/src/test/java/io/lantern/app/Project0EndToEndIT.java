package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.document.Document;
import io.lantern.core.document.DocumentJson;
import io.lantern.core.repo.Git;
import io.lantern.llm.FakeLlmProvider;
import io.lantern.sandbox.BuildResult;
import io.lantern.sandbox.BuildSandbox;
import io.lantern.sandbox.BuildStatus;
import io.lantern.sandbox.MavenMirror;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * Runs the whole pipeline (register, snapshot, build, extract, summarize, compose, render) over all
 * repositories of the synthetic test set project-0 (docs/adr/0029) as one snapshot, twice, with a
 * fake LLM, as {@link EndToEndIT} does for the fixtures. Checks that every repository builds, in
 * dependency order, that each gets its documents, that the second run reuses every build, fact and
 * summary and makes no LLM call, and that the system's C4 report is valid for its report spec and
 * shows the units and lines the set's cases call for.
 *
 * <p>project-0 is private, so this runs only where it is set up: in the folder {@code
 * lantern.test.project0} names, where every repository must be at the commit {@code
 * testsets/project-0/setup.sh} makes, or else in the folder that script sets it up in by default,
 * {@code ~/workspace/lantern-test-projects/project-0}. When that property is not set and the
 * default folder is missing or not at those commits, the test is skipped, so {@code ./mvnw verify}
 * passes on a machine without it.
 *
 * <p>Writes the system's document and its report spec data to {@code target/project-0}, to look at.
 * Builds run in the real sandbox with the application's default Maven cache directory and an owner
 * label of their own; the workspace is a temporary directory.
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
@Import({TestcontainersConfiguration.class, Project0EndToEndIT.Fake.class})
@EnabledIf(
    value = "project0IsSetUp",
    disabledReason =
        "project-0 is not set up at its pinned commits in ~/workspace/lantern-test-projects/project-0"
            + " and lantern.test.project0 is not set; run testsets/project-0/setup.sh"
            + " (docs/adr/0029)")
class Project0EndToEndIT {

  /** The commits setup.sh makes from the pinned source, as Project0BuildIT checks them. */
  private static final Map<String, String> COMMITS =
      new TreeMap<>(
          Map.of(
              "catalog", "fc39b6e72803baa33b9e0e3d7ac8cf32fabce394",
              "catalog-lib", "5bc5ec4ba4ffec38da7c0aaf3596be3365ec192f",
              "fulfilment", "d7a159078e44f5de32d3b3f4aa5fa0ef9cb24921",
              "payments", "871bf725e98d45d11ea093749943bb555b65bae8",
              "storefront", "fa592e452e659d26597defe3519ba0ec3bfc5014"));

  private static final Path OUT = Path.of("target", "project-0");
  private static final String RUN_LABEL = "lantern.test.run";
  private static final String RUN =
      HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextLong());

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

  /** The workspace, and the Maven mirror of the test properties file, as EndToEndIT sets them. */
  @DynamicPropertySource
  static void workspace(DynamicPropertyRegistry registry) throws IOException {
    registry.add("lantern.workspace", Workspace.PATH::toString);
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

  @AfterAll
  static void cleanUp() throws IOException {
    try (Stream<Path> paths = Files.walk(Workspace.PATH)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    }
    assertThat(docker("ps", "--all", "--quiet", "--filter", "label=" + RUN_LABEL + "=" + RUN))
        .as("sandbox containers left behind")
        .isEmpty();
  }

  @Test
  void analyzesProject0TwiceAndTheSecondRunMakesNoLlmCall()
      throws IOException, InterruptedException {
    Map<String, Path> repos = project0();

    AnalysisRun.Result first = run.run(repos);
    int calls = fake.sent().size();

    assertThat(first.snapshot().commits()).isEqualTo(COMMITS);
    assertThat(first.reusedBuilds()).isEmpty();
    first
        .builds()
        .forEach(
            (name, build) ->
                assertThat(build.status())
                    .as(name + ": " + build.failure())
                    .isEqualTo(BuildStatus.SUCCEEDED));
    assertThat(first.builds()).containsOnlyKeys(COMMITS.keySet());
    // BUILD-1: catalog builds only with catalog-lib's build (ADR 0041).
    assertThat(first.builds().get("catalog").upstream())
        .containsExactly("catalog-lib@" + COMMITS.get("catalog-lib"));
    assertThat(first.facts()).isPositive();
    assertThat(first.newFacts()).isEqualTo(first.facts());
    assertThat(first.summariesGenerated()).isPositive();
    assertThat(calls).isEqualTo(first.summariesGenerated());
    assertThat(first.llm().calls()).isEqualTo(calls);
    List<String> files = new ArrayList<>();
    for (String name : COMMITS.keySet()) {
      files.addAll(List.of(name + ".html", name + ".brief.html", name + ".c4.html"));
    }
    files.add("_system.c4.html");
    assertThat(first.documents())
        .extracting(d -> d.file().getFileName().toString())
        .containsExactlyElementsOf(files);

    AnalysisRun.Result second = run.run(repos);

    assertThat(fake.sent()).as("LLM requests in the second run").hasSize(calls);
    assertThat(second.llm().calls()).as("LLM calls logged in the second run").isZero();
    assertThat(second.snapshot().commits()).isEqualTo(COMMITS);
    assertThat(second.reusedBuilds()).containsExactlyInAnyOrderElementsOf(COMMITS.keySet());
    for (String name : COMMITS.keySet()) {
      BuildResult reused = second.builds().get(name);
      assertThat(reused).as(name).isEqualTo(first.builds().get(name));
    }
    assertThat(second.facts()).isEqualTo(first.facts());
    assertThat(second.newFacts()).isZero();
    assertThat(second.summariesGenerated()).isZero();
    assertThat(second.summariesReused()).isEqualTo(first.summariesGenerated());
    // The documents differ only in the snapshot they are of.
    assertThat(second.documents()).hasSameSizeAs(first.documents());
    for (int i = 0; i < first.documents().size(); i++) {
      Document before = first.documents().get(i).document();
      Document after = second.documents().get(i).document();
      assertThat(DocumentJson.write(after))
          .as(after.id())
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

    checkSystemReport(second.documents().getLast().document());
  }

  /**
   * Writes the system's document and its report spec data to {@code target/project-0} and checks
   * the data is valid for the report spec and shows the elements, pages and lines it should.
   */
  private static void checkSystemReport(Document document)
      throws IOException, InterruptedException {
    assertThat(document.id()).isEqualTo(AnalysisRun.SYSTEM_C4);
    Files.createDirectories(OUT);
    Files.writeString(OUT.resolve("system.json"), DocumentJson.write(document));
    JsonNode data = C4ReportRenderer.toJson(document).data();
    Path json = OUT.resolve("system.c4.json");
    Files.writeString(json, C4ReportRenderer.write(C4ReportRenderer.toJson(document).data()));

    // Valid for the report spec, as lantern-web's tests check the renderer's golden files.
    Process check =
        new ProcessBuilder(
                Path.of("../lantern-web/target/node/node").toAbsolutePath().toString(),
                "../lantern-web/scripts/report-spec-check.mjs",
                C4ReportRenderer.SPEC,
                json.toString())
            .redirectErrorStream(true)
            .start();
    String problems = new String(check.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(check.waitFor()).as(problems).isZero();

    Map<String, Long> kinds = new TreeMap<>();
    data.get("elements").forEach(e -> kinds.merge(e.get("kind").asString(), 1L, Long::sum));
    // Four units; the catalog and shop databases (the local profile's localhost one is a note);
    // the shop.orders exchange (its queues folded into its lines), two JMS queues and a Kafka
    // topic; PayGate, by its default sandbox host (HTTP-4); and the components by role.
    assertThat(kinds)
        .containsExactlyEntriesOf(
            new TreeMap<>(
                Map.of(
                    "component", 10L,
                    "container", 4L,
                    "database", 2L,
                    "external", 1L,
                    "queue", 4L,
                    "system", 1L)));
    List<String> pages = new ArrayList<>();
    data.get("pages").forEach(page -> pages.add(page.get("id").asString()));
    assertThat(pages)
        .containsExactly(
            "context",
            "containers",
            "components-service-com-acme-shop-catalog-service",
            "components-service-com-acme-shop-fulfilment",
            "components-service-com-acme-shop-payments",
            "components-service-com-acme-shop-storefront-app");
    JsonNode containers = data.get("pages").get(1).get("sections").get(0);
    assertThat(containers.get("relationships").toString())
        // HTTP-1: Feign by client name, with the endpoint and protocol.
        .contains(
            "{\"from\":\"service-com-acme-shop-storefront-app\","
                + "\"to\":\"service-com-acme-shop-catalog-service\","
                + "\"label\":\"Calls GET /api/products/{id}\",\"technology\":\"JSON/HTTP\"}")
        // SPR-4: the audit trail is sent only under its profile.
        .contains(
            "\"label\":\"Sends messages (only with the Spring profile audit)\","
                + "\"technology\":\"JMS\"")
        // MSG-1: RabbitMQ through the exchange, the bound queues on the line.
        .contains(
            "{\"from\":\"exchange-rabbitmq-shop-orders\",\"to\":\"service-com-acme-shop-payments\","
                + "\"label\":\"Delivers order.*, refund.#\","
                + "\"technology\":\"AMQP · payments.order-events, payments.refunds\"}");
    assertThat(data.get("pages").get(1).toString())
        .contains(
            "Under the Spring profile local, storefront connects to shop on localhost instead of"
                + " shop on shop-db.")
        .contains(
            "Under the Spring profile prod, payments calls api.paygate.example.com instead of"
                + " sandbox.paygate.example.com.");
  }

  /**
   * Whether to run: {@code lantern.test.project0} is set, or project-0 is set up at the pinned
   * commits in the default folder. Checked before the application starts, so a skipped run starts
   * no database.
   */
  static boolean project0IsSetUp() {
    return given() != null || problem(defaultFolder()) == null;
  }

  /**
   * Each project-0 repository, by name: in the folder {@code lantern.test.project0} names, or else
   * in the default folder; each must be at its pinned commit.
   */
  private static Map<String, Path> project0() throws IOException {
    String given = given();
    if (given != null) {
      assertThat(Path.of(given).isAbsolute())
          .as("lantern.test.project0 must be an absolute path: " + given)
          .isTrue();
    }
    Path setUp = given == null ? defaultFolder() : Path.of(given);
    String problem = problem(setUp);
    assertThat(problem)
        .as("project-0 is not set up: %s; run testsets/project-0/setup.sh again", problem)
        .isNull();
    Map<String, Path> repos = new TreeMap<>();
    for (String name : COMMITS.keySet()) {
      repos.put(name, setUp.resolve(name).toRealPath());
    }
    return repos;
  }

  /** The folder {@code lantern.test.project0} names, or null if it is not set. */
  private static String given() {
    String given = System.getProperty("lantern.test.project0");
    return given == null || given.isBlank() ? null : given;
  }

  /** The folder testsets/project-0/setup.sh sets project-0 up in by default (ADR 0029). */
  private static Path defaultFolder() {
    return Path.of(System.getProperty("user.home"), "workspace", "lantern-test-projects")
        .resolve("project-0");
  }

  /** Why the folder does not hold every repository at its pinned commit, or null if it does. */
  private static String problem(Path setUp) {
    for (Map.Entry<String, String> e : COMMITS.entrySet()) {
      Path path = setUp.resolve(e.getKey());
      if (!Files.isDirectory(path.resolve(".git"))) {
        return path + " is not a git repository";
      }
      String head;
      try {
        head = Git.run(path, "rev-parse", "HEAD").out();
      } catch (RuntimeException ex) {
        return path + ": " + ex.getMessage();
      }
      if (!head.equals(e.getValue())) {
        return path + " is at " + head + ", not at the pinned commit " + e.getValue();
      }
    }
    return null;
  }

  /** The workspace, a temporary directory made only when the test runs. */
  private static final class Workspace {
    static final Path PATH;

    static {
      try {
        PATH = Files.createTempDirectory("lantern-project0-workspace-");
      } catch (IOException e) {
        throw new UncheckedIOException(e);
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

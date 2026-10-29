package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.document.Document;
import io.lantern.core.document.DocumentJson;
import io.lantern.core.repo.Git;
import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.Role;
import io.lantern.sandbox.BuildResult;
import io.lantern.sandbox.BuildSandbox;
import io.lantern.sandbox.BuildStatus;
import io.lantern.sandbox.MavenMirror;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 * shows the units, lines and flows the set's cases call for.
 *
 * <p>project-0 is private, so this runs only where it is set up: in the folder {@code
 * lantern.test.project0} names, or else in the folder {@code testsets/project-0/setup.sh} sets it
 * up in by default, {@code ~/workspace/lantern-test-projects/project-0}. When that property is not
 * set and the default folder does not exist, the test is skipped, so {@code ./mvnw verify} passes
 * on a machine without project-0. A folder that exists must have been set up from the pin in {@code
 * testsets/project-0/pins.txt}, which setup.sh records in it as {@value #PIN_FILE}; otherwise the
 * test fails and says to run setup.sh. The pin is the one place for the commits; the repositories'
 * own commits are read from them.
 *
 * <p>Writes the system's document, its report spec data and its HTML file to {@code
 * target/project-0}, to look at. Builds run in the real sandbox with the application's default
 * Maven cache directory and an owner label of their own; the workspace is a temporary directory.
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
    value = "project0IsPresent",
    disabledReason =
        "project-0 is not set up: ~/workspace/lantern-test-projects/project-0 does not exist and"
            + " lantern.test.project0 is not set; see testsets/project-0/setup.sh (docs/adr/0029)")
class Project0EndToEndIT {

  /** The file setup.sh writes into the set-up folder, holding the pin it set the folder up from. */
  static final String PIN_FILE = ".lantern-pin";

  /** The pins of the test set, the one place its commits are named (ADR 0029). */
  private static final Path PINS = Path.of("..", "testsets", "project-0", "pins.txt");

  /** The made-up repositories, as the source's README lists them. */
  private static final List<String> NAMES =
      List.of("catalog", "catalog-lib", "fulfilment", "payments", "storefront");

  private static final Path OUT = Path.of("target", "project-0");
  private static final String RUN_LABEL = "lantern.test.run";
  private static final String RUN =
      HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextLong());

  @TestConfiguration(proxyBeanMethods = false)
  static class Fake {
    /**
     * A fake model that cites the first fact and the first part's summary it is given, and words
     * nothing when asked for responsibilities and intent phrases (ADR 0050), so the report keeps
     * the static labels this test checks.
     */
    @Bean
    FakeLlmProvider fakeLlmProvider() {
      return new FakeLlmProvider()
          .answering(
              request -> {
                String prompt = request.messages().getFirst().text();
                if (request.role() == Role.INFER_INTENT) {
                  return "{}";
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
    Map<String, String> commits = new TreeMap<>();
    repos.forEach((name, path) -> commits.put(name, Git.run(path, "rev-parse", "HEAD").out()));

    AnalysisRun.Result first = run.run(repos);
    int calls = fake.sent().size();

    assertThat(first.snapshot().commits()).isEqualTo(commits);
    assertThat(first.reusedBuilds()).isEmpty();
    first
        .builds()
        .forEach(
            (name, build) ->
                assertThat(build.status())
                    .as(name + ": " + build.failure())
                    .isEqualTo(BuildStatus.SUCCEEDED));
    assertThat(first.builds()).containsOnlyKeys(commits.keySet());
    // BUILD-1: catalog builds only with catalog-lib's build (ADR 0041).
    assertThat(first.builds().get("catalog").upstream())
        .containsExactly("catalog-lib@" + commits.get("catalog-lib"));
    assertThat(first.facts()).isPositive();
    assertThat(first.newFacts()).isEqualTo(first.facts());
    assertThat(first.summariesGenerated()).isPositive();
    assertThat(first.wordingsAsked()).isPositive();
    assertThat(calls).isEqualTo(first.summariesGenerated() + first.wordingsAsked());
    assertThat(first.llm().calls()).isEqualTo(calls);
    List<String> files = new ArrayList<>();
    for (String name : commits.keySet()) {
      files.addAll(List.of(name + ".html", name + ".brief.html", name + ".c4.html"));
    }
    files.add("_system.c4.html");
    assertThat(first.documents())
        .extracting(d -> d.file().getFileName().toString())
        .containsExactlyElementsOf(files);

    AnalysisRun.Result second = run.run(repos);

    assertThat(fake.sent()).as("LLM requests in the second run").hasSize(calls);
    assertThat(second.llm().calls()).as("LLM calls logged in the second run").isZero();
    assertThat(second.snapshot().commits()).isEqualTo(commits);
    assertThat(second.reusedBuilds()).containsExactlyInAnyOrderElementsOf(commits.keySet());
    for (String name : commits.keySet()) {
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

    AnalysisRun.Rendered system = second.documents().getLast();
    checkSystemReport(system.document());
    Files.copy(system.file(), OUT.resolve("system.c4.html"), StandardCopyOption.REPLACE_EXISTING);
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
    // topic; PayGate, by its default sandbox host (HTTP-4); and the components by role. catalog's
    // ProductRepository is reached only from ProductController (through Spring Data's findById),
    // so catalog has one component and no component page.
    assertThat(kinds)
        .containsExactlyEntriesOf(
            new TreeMap<>(
                Map.of(
                    "component", 8L,
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
            "components-service-com-acme-shop-fulfilment",
            "components-service-com-acme-shop-payments",
            "components-service-com-acme-shop-storefront-app",
            "sequences-storefront-ordercontroller-place",
            "sequences-payments-settlementjob-settle",
            "sequences-storefront-ordercontroller-order");
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

    // FLOW-1: placing an order, stitched across every unit through HTTP-1, HTTP-2, HTTP-4,
    // MSG-2, MSG-4, HTTP-3 and MSG-1, the tax call (HTTP-5) a note, the audit send (SPR-4) in a
    // frame of its profile, and the calls to the catalog and shop databases through Spring Data's
    // inherited methods (#176). ProductController.find runs once per flow, so the stock check
    // does not repeat its findById.
    assertThat(steps(data.get("pages").get(5)))
        .containsExactly(
            "note all: Starts when storefront receives POST /api/orders"
                + " (OrderController.place).",
            "storefront-app > catalog-service: Calls ProductController.product"
                + " [GET /api/products/{id}]",
            "catalog-service > database-catalog-db-5432-catalog:"
                + " Calls ProductRepository.findById [JDBC]",
            "note all: storefront calls GET ${TAX_SERVICE_URL}/v2/quotes, which Lantern"
                + " could not link (unresolved placeholder TAX_SERVICE_URL); the trace does not"
                + " follow it.",
            "storefront-app > database-shop-db-5432-shop: Calls OrderRepository.save [JDBC]",
            "storefront-app > payments: Calls PaymentController.charge [POST /api/payments]",
            "payments > external-system-sandbox-paygate-example-com:"
                + " Calls sandbox.paygate.example.com [POST /v1/charges]",
            "storefront-app >> exchange-rabbitmq-shop-orders: Sends order.created [AMQP]",
            "note storefront-app, exchange-rabbitmq-shop-orders: storefront does not wait:"
                + " payments takes the message later.",
            "opt Spring profile audit",
            "storefront-app >> queue-jms-audit-orders: Sends a message [JMS]",
            "note storefront-app, queue-jms-audit-orders: storefront does not wait: fulfilment"
                + " takes the message later.",
            "end",
            "exchange-rabbitmq-shop-orders >> payments: Delivers to OrderEventsListener.orderEvent"
                + " [AMQP · payments.order-events]",
            "payments >> topic-kafka-payments-completed: Sends a message [Kafka]",
            "note payments, topic-kafka-payments-completed: payments does not wait: fulfilment"
                + " takes the message later.",
            "opt Spring profile audit",
            "queue-jms-audit-orders >> fulfilment: Delivers to AuditListener.orderPlaced [JMS]",
            "end",
            "topic-kafka-payments-completed >> fulfilment:"
                + " Delivers to PaymentCompletedListener.paymentCompleted [Kafka]",
            "fulfilment > catalog-service: Calls ProductController.stock"
                + " [GET /api/products/{id}/stock]",
            "fulfilment >> queue-jms-shipments-dispatched: Sends a message [JMS]",
            "note fulfilment, queue-jms-shipments-dispatched: fulfilment does not wait:"
                + " storefront takes the message later.",
            "queue-jms-shipments-dispatched >> storefront-app:"
                + " Delivers to ShipmentListener.dispatched [JMS]",
            "storefront-app > database-shop-db-5432-shop: Calls OrderRepository.findById [JDBC]",
            "storefront-app > database-shop-db-5432-shop: Calls OrderRepository.save [JDBC]");
    // FLOW-2: the nightly settlement (ENTRY-1) reads and writes the shared shop database (DB-2),
    // one of its tables storefront's (DB-5).
    assertThat(steps(data.get("pages").get(6)))
        .containsExactly(
            "note all: Starts when the schedule cron 0 0 2 * * * fires in payments"
                + " (SettlementJob.settle).",
            "payments > database-shop-db-5432-shop: Reads orders, payment [JDBC]",
            "payments > database-shop-db-5432-shop: Writes payment [JDBC]");
    // Looking up an order reads it through Spring Data's inherited findById (#176).
    assertThat(steps(data.get("pages").get(7)))
        .containsExactly(
            "note all: Starts when storefront receives GET /api/orders/{id}"
                + " (OrderController.order).",
            "storefront-app > database-shop-db-5432-shop: Calls OrderRepository.findById [JDBC]");
  }

  /** The steps of a sequence page, one line each, units by their artifact ID. */
  private static List<String> steps(JsonNode page) {
    List<String> lines = new ArrayList<>();
    for (JsonNode section : page.get("sections")) {
      if (!section.get("type").asString().equals("sequence")) {
        continue;
      }
      for (JsonNode step : section.get("steps")) {
        String line;
        if (step.has("note")) {
          List<String> over = new ArrayList<>();
          step.get("over").forEach(o -> over.add(o.asString()));
          List<String> all = new ArrayList<>();
          section.get("participants").forEach(p -> all.add(p.asString()));
          line =
              "note "
                  + (over.equals(all) ? "all" : String.join(", ", over))
                  + ": "
                  + step.get("note").asString();
        } else if (step.has("frame")) {
          line = step.get("frame").asString() + " " + step.get("label").asString();
        } else if (step.has("end")) {
          line = "end";
        } else {
          String arrow =
              step.has("type") && step.get("type").asString().equals("async") ? ">>" : ">";
          line =
              step.get("from").asString()
                  + " "
                  + arrow
                  + " "
                  + step.get("to").asString()
                  + ": "
                  + step.get("message").asString()
                  + (step.has("code") ? " [" + step.get("code").asString() + "]" : "");
        }
        lines.add(line.replace("service-com-acme-shop-", ""));
      }
    }
    return lines;
  }

  /**
   * Whether to run: {@code lantern.test.project0} is set, or the default folder exists. Checked
   * before the application starts, so a skipped run starts no database.
   */
  static boolean project0IsPresent() {
    return given() != null || Files.exists(defaultFolder());
  }

  /**
   * Each project-0 repository, by name: in the folder {@code lantern.test.project0} names, or else
   * in the default folder, which setup.sh must have set up from the current pin.
   */
  private static Map<String, Path> project0() throws IOException {
    String given = given();
    if (given != null) {
      assertThat(Path.of(given).isAbsolute())
          .as("lantern.test.project0 must be an absolute path: " + given)
          .isTrue();
    }
    Path setUp = given == null ? defaultFolder() : Path.of(given);
    String pin = pin();
    Path pinFile = setUp.resolve(PIN_FILE);
    String setUpFrom = Files.isRegularFile(pinFile) ? Files.readString(pinFile).strip() : null;
    assertThat(setUpFrom)
        .as(
            "project-0 in %s was not set up from the pin %s in %s (it holds %s); run"
                + " testsets/project-0/setup.sh, removing the old repositories first if it says"
                + " so (docs/adr/0029)",
            setUp, pin, PINS.normalize(), setUpFrom == null ? "no " + PIN_FILE : setUpFrom)
        .isEqualTo(pin);
    Map<String, Path> repos = new TreeMap<>();
    for (String name : NAMES) {
      Path path = setUp.resolve(name);
      assertThat(Files.isDirectory(path.resolve(".git")))
          .as("%s is not a git repository; run testsets/project-0/setup.sh again", path)
          .isTrue();
      repos.put(name, path.toRealPath());
    }
    return repos;
  }

  /** The commit the one line of pins.txt pins the source to. */
  private static String pin() throws IOException {
    List<String> lines =
        Files.readAllLines(PINS).stream()
            .map(String::strip)
            .filter(line -> !line.isEmpty() && !line.startsWith("#"))
            .toList();
    assertThat(lines).as(PINS.toString()).hasSize(1);
    return lines.getFirst().split("\\s+")[2];
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

package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.compose.Composer;
import io.lantern.compose.ViewDefinition;
import io.lantern.core.document.Document;
import io.lantern.core.document.DocumentJson;
import io.lantern.core.fact.Fact;
import io.lantern.core.repo.Git;
import io.lantern.core.repo.Repo;
import io.lantern.core.repo.RepoSettings;
import io.lantern.core.snapshot.Snapshot;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.docs.DocsExtractor;
import io.lantern.extract.http.HttpClientExtractor;
import io.lantern.extract.java.JavaAnalysis;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.extract.maven.MavenExtractor;
import io.lantern.extract.messaging.MessagingExtractor;
import io.lantern.extract.persistence.PersistenceExtractor;
import io.lantern.extract.spring.SpringExtractor;
import io.lantern.extract.sql.SqlExtractor;
import io.lantern.link.CrossRepoLinks;
import io.lantern.link.DeployableUnits;
import io.lantern.sandbox.BuildOrder;
import io.lantern.sandbox.BuildResult;
import io.lantern.sandbox.BuildSandbox;
import io.lantern.sandbox.BuildStatus;
import io.lantern.sandbox.MavenMirror;
import io.lantern.sandbox.SandboxSettings;
import io.lantern.sandbox.UpstreamBuild;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * The C4 report of the synthetic test set project-0 (docs/adr/0029) as one system, composed with
 * the views of ADR 0044 from the facts the app extracts and links, with the repositories built in
 * the sandbox. Runs only when {@code lantern.test.project0} names the folder {@code
 * testsets/project-0/setup.sh} set it up in; see Commands in {@code CLAUDE.md}.
 */
@EnabledIfSystemProperty(named = "lantern.test.project0", matches = ".+")
class Project0C4ReportIT {

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

  @TempDir static Path temp;

  private static List<Fact> facts;

  @BeforeAll
  static void extractAndLink() throws IOException {
    String setUp = System.getProperty("lantern.test.project0");
    assertThat(Path.of(setUp).isAbsolute())
        .as("lantern.test.project0 must be an absolute path: " + setUp)
        .isTrue();
    BuildSandbox sandbox = new BuildSandbox(sandboxSettings(temp.resolve("builds")));
    Map<String, Repo> project = new TreeMap<>();
    for (Map.Entry<String, String> e : COMMITS.entrySet()) {
      Path path = Path.of(setUp).resolve(e.getKey()).toRealPath();
      assertThat(Git.run(path, "rev-parse", "HEAD").out())
          .as("%s is not at the pinned commit; run testsets/project-0/setup.sh again", e.getKey())
          .isEqualTo(e.getValue());
      project.put(e.getKey(), new Repo(e.getKey(), path, RepoSettings.DEFAULT));
    }
    // In dependency order, each build given the builds of those it uses (ADR 0041).
    BuildOrder order = BuildOrder.of(project, COMMITS);
    Map<String, BuildResult> results = new HashMap<>();
    for (String name : order.order()) {
      List<UpstreamBuild> upstream =
          order.upstream(name).stream()
              .map(u -> new UpstreamBuild(project.get(u), results.get(u)))
              .toList();
      BuildResult result = sandbox.build(project.get(name), COMMITS.get(name), upstream);
      assertThat(result.status()).as(name).isEqualTo(BuildStatus.SUCCEEDED);
      results.put(name, result);
    }
    List<RepoBuild> repos = new ArrayList<>();
    project.forEach(
        (name, repo) -> repos.add(new RepoBuild(repo, COMMITS.get(name), results.get(name))));
    facts = new ArrayList<>(new MavenExtractor().extract(repos, 1));
    JavaAnalysis code = JavaExtractor.analyze(repos);
    facts.addAll(new JavaExtractor().extract(code, 1));
    facts.addAll(new ConfigExtractor().extract(repos, 1));
    facts.addAll(new DocsExtractor().extract(repos, 1));
    facts.addAll(new SqlExtractor().extract(repos, 1));
    facts.addAll(new SpringExtractor().extract(code, 1));
    facts.addAll(new PersistenceExtractor().extract(code, 1));
    facts.addAll(new HttpClientExtractor().extract(code, 1));
    facts.addAll(new MessagingExtractor().extract(code, 1));
    facts.addAll(new DeployableUnits().derive(facts, 1));
    facts.addAll(new CrossRepoLinks().derive(facts, 1));
  }

  /**
   * Writes the system's document, its report spec data and the HTML file to {@code
   * target/project-0}, to look at, and checks the elements, pages and lines it shows.
   */
  @Test
  void rendersTheSystemAsTheC4Report() throws IOException, InterruptedException {
    Files.createDirectories(OUT);
    Snapshot snapshot = new Snapshot(1, COMMITS, 1, "test", Instant.EPOCH);
    Document document =
        new Composer("test")
            .compose(
                ViewDefinition.c4System("system:c4", "project-0", AnalysisRun.C4_REPORT),
                snapshot,
                facts,
                Map.of());
    Files.writeString(OUT.resolve("system.json"), DocumentJson.write(document));
    Files.writeString(
        OUT.resolve("system.c4.json"),
        C4ReportRenderer.write(C4ReportRenderer.toJson(document).data()));
    Files.writeString(
        OUT.resolve("system.c4.html"), C4ReportRenderer.fromClassPath().render(document));

    JsonNode data = C4ReportRenderer.toJson(document).data();
    // Valid for the report spec, as lantern-web's tests check the renderer's golden files.
    Process check =
        new ProcessBuilder(
                Path.of("../lantern-web/target/node/node").toAbsolutePath().toString(),
                "../lantern-web/scripts/report-spec-check.mjs",
                C4ReportRenderer.SPEC,
                OUT.resolve("system.c4.json").toString())
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
            "components-service-com-acme-shop-storefront-app",
            "sequences-storefront-ordercontroller-place",
            "sequences-payments-settlementjob-settle");
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
    // frame of its profile. The shop database steps are missing: calls to Spring Data's own
    // methods are not in the call graph (#176).
    assertThat(steps(data.get("pages").get(6)))
        .containsExactly(
            "note all: Starts when storefront receives POST /api/orders"
                + " (OrderController.place).",
            "storefront-app > catalog-service: Calls ProductController.product"
                + " [GET /api/products/{id}]",
            "note all: storefront calls GET ${TAX_SERVICE_URL}/v2/quotes, which Lantern"
                + " could not link (unresolved placeholder TAX_SERVICE_URL); the trace does not"
                + " follow it.",
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
                + " Delivers to ShipmentListener.dispatched [JMS]");
    // FLOW-2: the nightly settlement (ENTRY-1) reads and writes the shared shop database (DB-2),
    // one of its tables storefront's (DB-5).
    assertThat(steps(data.get("pages").get(7)))
        .containsExactly(
            "note all: Starts when the schedule cron 0 0 2 * * * fires in payments"
                + " (SettlementJob.settle).",
            "payments > database-shop-db-5432-shop: Reads orders, payment [JDBC]",
            "payments > database-shop-db-5432-shop: Writes payment [JDBC]");
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

  /** The sandbox settings of the extractors' tests, with the Maven mirror if one is set. */
  private static SandboxSettings sandboxSettings(Path buildsDirectory) {
    String file = System.getProperty("lantern.test.properties");
    if (file == null || file.isBlank()) {
      file = System.getenv("LANTERN_TEST_PROPERTIES");
    }
    SandboxSettings settings = SandboxSettings.defaults(buildsDirectory);
    if (file == null || file.isBlank()) {
      return settings;
    }
    try {
      return settings.withMavenMirror(MavenMirror.from(Path.of(file)));
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read the test properties " + file, e);
    }
  }
}

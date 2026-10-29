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
import io.lantern.sandbox.BuildSandbox;
import io.lantern.sandbox.BuildStatus;
import io.lantern.sandbox.MavenMirror;
import io.lantern.sandbox.SandboxSettings;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
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
              "catalog", "8637326f638af796f242548b68216c8729e1a5f7",
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
    List<RepoBuild> repos = new ArrayList<>();
    for (Map.Entry<String, String> e : COMMITS.entrySet()) {
      Path path = Path.of(setUp).resolve(e.getKey()).toRealPath();
      assertThat(Git.run(path, "rev-parse", "HEAD").out())
          .as("%s is not at the pinned commit; run testsets/project-0/setup.sh again", e.getKey())
          .isEqualTo(e.getValue());
      Repo repo = new Repo(e.getKey(), path, RepoSettings.DEFAULT);
      RepoBuild build = new RepoBuild(repo, e.getValue(), sandbox.build(repo, e.getValue()));
      assertThat(build.build().status()).as(e.getKey()).isEqualTo(BuildStatus.SUCCEEDED);
      repos.add(build);
    }
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

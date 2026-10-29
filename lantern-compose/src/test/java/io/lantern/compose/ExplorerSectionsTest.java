package io.lantern.compose;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.document.Block;
import io.lantern.core.document.Callout;
import io.lantern.core.document.CrossReference;
import io.lantern.core.document.Document;
import io.lantern.core.document.Inline;
import io.lantern.core.document.ListBlock;
import io.lantern.core.document.QualityReport;
import io.lantern.core.document.Section;
import io.lantern.core.document.Table;
import io.lantern.core.document.TemplateRef;
import io.lantern.core.document.TextRun;
import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.snapshot.Snapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/** The Architecture Explorer's start-here list, catalog and change guidance (ADR 0048). */
class ExplorerSectionsTest {

  private static final TemplateRef EXPLORER = new TemplateRef(QualityBar.EXPLORER, "1");
  private static final Snapshot SNAPSHOT =
      new Snapshot(
          2, Map.of("shop", TestFacts.COMMIT, "base", TestFacts.COMMIT), 5, "0.1.0", Instant.EPOCH);

  private static final String APP_MODULE = "module:com.example:app";
  private static final String SCHEMA = "schema:com.example:app";
  private static final String DECISION = "decision:shop:docs/adr/0001-kafka.md";

  /**
   * {@link TestFacts#runtime()} with a main class, a README, a schema, settings and a rationale.
   */
  private static List<Fact> facts() {
    List<Fact> facts = new ArrayList<>(TestFacts.runtime().list());
    facts.addAll(
        new TestFacts()
            .relate(
                "shop",
                "package:com.example:app:com.example.web",
                "CONTAINS",
                "type:com.example.App")
            .relate("shop", APP_MODULE, "CONTAINS", SCHEMA)
            .attribute(
                "shop",
                DECISION,
                "decision-context",
                "Orders must reach the worker\neven when it is down.\n\nMore background.")
            .list());
    facts.add(
        fact(
            "type:com.example.App",
            "main-class",
            FactObject.string("spring-boot"),
            "{}",
            "src/main/java/com/example/App.java"));
    // A schema migrated by Flyway and plain SQL: the guidance follows Flyway, in its folder.
    facts.add(
        fact(SCHEMA, "kind", FactObject.string("sql"), "{}", "src/main/resources/schema.sql"));
    facts.add(
        fact(
            SCHEMA,
            "kind",
            FactObject.string("flyway"),
            "{}",
            "src/main/resources/db/migration/V1__orders.sql"));
    facts.add(
        fact(
            SCHEMA,
            "CONTAINS",
            FactObject.entity(EntityId.parse("table:com.example:app:orders")),
            "{}",
            "src/main/resources/db/migration/V1__orders.sql"));
    facts.add(
        fact(
            "readme:shop:docs/README.md",
            "claim",
            FactObject.string("Deep docs."),
            "{}",
            "docs/README.md"));
    facts.add(
        fact("readme:shop:README.md", "claim", FactObject.string("Shop."), "{}", "README.md"));
    facts.add(
        fact(
            APP_MODULE,
            "config-value",
            FactObject.string("8080"),
            "{\"key\": \"server.port\"}",
            "src/main/resources/application.yml"));
    facts.add(
        fact(
            APP_MODULE,
            "config-value",
            FactObject.string("80"),
            "{\"key\": \"server.port\", \"profile\": \"prod\"}",
            "src/main/resources/application-prod.yml"));
    return facts;
  }

  private static Fact fact(
      String subject, String predicate, FactObject object, String json, String path) {
    return Fact.create(
        EntityId.parse(subject),
        predicate,
        object,
        (ObjectNode) FactJson.parse(json),
        new Origin(Origin.Kind.STATIC, "test", "1"),
        new Evidence("shop", TestFacts.COMMIT, path, null, null),
        1.0,
        Validity.from(1));
  }

  private static Document explorer(List<Fact> facts) {
    return new Composer("1")
        .compose(ViewDefinition.perRepo("shop", "shop", EXPLORER), SNAPSHOT, facts, Map.of());
  }

  private static Section section(Document document, String id) {
    return document.sections().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
  }

  private static <T extends Block> List<T> blocks(Section section, Class<T> kind) {
    return section.blocks().stream().filter(kind::isInstance).map(kind::cast).toList();
  }

  private static String text(List<Inline> content) {
    return content.stream()
        .map(
            i ->
                switch (i) {
                  case TextRun t when t.style() == TextRun.Style.CODE -> "`" + t.text() + "`";
                  case TextRun t -> t.text();
                  case CrossReference r -> "[" + r.text() + "]";
                  default -> "";
                })
        .collect(Collectors.joining());
  }

  private static List<String> items(Section section) {
    return blocks(section, ListBlock.class).getFirst().items().stream()
        .map(i -> text(i.content()))
        .toList();
  }

  private static List<String> rows(Table table) {
    return table.rows().stream()
        .map(
            r ->
                r.cells().stream()
                    .map(ExplorerSectionsTest::text)
                    .collect(Collectors.joining(" | ")))
        .toList();
  }

  private static Table table(Section section, String title) {
    return blocks(section, Table.class).stream()
        .filter(t -> t.title().equals(title))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void startHereNamesTheMainFlowsEntryPointsWhereUnitsStartTheReadmeAndTheDecisionRecords() {
    Section start = section(explorer(facts()), "start_here");

    assertThat(start.title()).isEqualTo("Start here");
    assertThat(items(start))
        .containsExactly(
            "GET /orders on [shop-app]: [Controller.list] in `pom.xml`.",
            "Scheduled job (0 0 * * * *) on [shop-app]: [Jobs.run] in `pom.xml`.",
            "[shop-app] starts in [App] in `src/main/java/com/example/App.java`.",
            "Read `README.md` in shop.",
            "The decision records of shop are in `docs/adr/` (1 record).");
    // Every item cites the facts it is built from.
    assertThat(blocks(start, ListBlock.class).getFirst().items())
        .allSatisfy(item -> assertThat(item.facts()).isNotEmpty());
  }

  @Test
  void startHereListsTheFirstEntryPointsWhenNoFlowIsDrawn() {
    // Without the data access, send and HTTP call, no entry point reaches another container.
    List<Fact> facts =
        TestFacts.runtime().list().stream()
            .filter(
                f ->
                    !List.of("READS_TABLE", "PUBLISHES", "CALLS_HTTP", "SENDS_MESSAGES_TO")
                        .contains(f.predicate()))
            .toList();

    assertThat(items(section(explorer(facts), "start_here")))
        .containsExactly(
            "GET /orders on [shop-app]: [Controller.list] in `pom.xml`.",
            "Scheduled job (0 0 * * * *) on [shop-app]: [Jobs.run] in `pom.xml`.",
            "The decision records of shop are in `docs/adr/` (1 record).");
  }

  @Test
  void catalogListsContainersComponentsEntryPointsDestinationsTablesAndSettings() {
    Section catalog = section(explorer(facts()), "catalog");

    assertThat(catalog.title()).isEqualTo("Catalog");
    assertThat(blocks(catalog, Table.class))
        .extracting(Table::title)
        .containsExactly(
            "Containers",
            "Components",
            "Endpoints and other entry points",
            "Topics, queues and exchanges",
            "Tables",
            "Configuration");
    assertThat(rows(table(catalog, "Containers")))
        .containsExactly(
            "[shop-app] | Deployable unit | Spring Boot 3.5.0 / Java 21 | ",
            "[shop on db] | Database | PostgreSQL | [shop-app]");
    assertThat(rows(table(catalog, "Components")))
        .containsExactly(
            "[Controller] | Spring MVC REST controller | [shop-app]",
            "[Jobs] | Scheduled job | [shop-app]",
            "[JpaOrders] | Spring repository | [shop-app]");
    assertThat(rows(table(catalog, "Endpoints and other entry points")))
        .containsExactly(
            "`GET /orders` | HTTP endpoint | [Controller.list] | [shop-app] | `pom.xml`",
            "`0 0 * * * *` | Scheduled job | [Jobs.run] | [shop-app] | `pom.xml`");
    assertThat(rows(table(catalog, "Topics, queues and exchanges")))
        .containsExactly("[orders] | Kafka topic | [shop-app] | ");
    assertThat(rows(table(catalog, "Tables")))
        .containsExactly("[orders] | `com.example:app` | [shop-app]");
    assertThat(rows(table(catalog, "Configuration")))
        .containsExactly(
            "`server.port` | `8080` |  | [shop-app] | `src/main/resources/application.yml`",
            "`server.port` | `80` | prod | [shop-app] | `src/main/resources/application-prod.yml`");
    assertThat(blocks(catalog, Table.class))
        .flatExtracting(Table::rows)
        .allSatisfy(row -> assertThat(row.facts()).isNotEmpty());
  }

  @Test
  void changeGuidanceFollowsAnExampleTheCodeHasForEachKindOfChange() {
    Section guidance = section(explorer(facts()), "change_guidance");

    assertThat(guidance.title()).isEqualTo("Change guidance");
    assertThat(items(guidance))
        .containsExactly(
            "To add an endpoint, follow [Controller.list] in `pom.xml`.",
            "To add a scheduled job, follow [Jobs.run] in `pom.xml`.",
            "To send a message, follow [JpaOrders.all] in `pom.xml`, which sends to orders.",
            "To call another service over HTTP, follow [Controller.list] in `pom.xml`, which"
                + " calls pay.example.com.",
            "To change a table of shop-app, add a Flyway migration in"
                + " `src/main/resources/db/migration/`.",
            "To add a setting, add it to `src/main/resources/application.yml`, where most default"
                + " settings are.");
  }

  @Test
  void decisionsQuoteTheirRationaleFromTheRecordsContext() {
    Section containers = section(explorer(facts()), "containers");

    Table decisions = table(containers, "Decisions");
    assertThat(rows(decisions))
        .containsExactly(
            "ADR 1 | Use Kafka | Accepted | Orders go to Kafka. | Orders must reach the worker even"
                + " when it is down.");
  }

  @Test
  void aScopeWithoutDecisionRecordsSaysWhyItIsBuiltSoIsNotKnown() {
    List<Fact> facts =
        facts().stream().filter(f -> !f.subject().type().equals(C4Views.DECISION)).toList();

    Callout note =
        blocks(section(explorer(facts), "containers"), Callout.class).stream()
            .filter(c -> "Decisions".equals(c.title()))
            .findFirst()
            .orElseThrow();
    assertThat(note.variant()).isEqualTo(Callout.Variant.NOTE);
    assertThat(text(note.content()))
        .isEqualTo(
            "No decision records were found in shop, so the reasons for its design are not known.");
    assertThat(note.facts()).isEmpty();
  }

  @Test
  void aRepositoryWithoutRuntimeFactsSaysSoInEachSection() {
    Document document = explorer(TestFacts.shop().list());

    assertThat(blocks(section(document, "start_here"), Callout.class))
        .singleElement()
        .satisfies(
            c ->
                assertThat(text(c.content()))
                    .isEqualTo(
                        "No entry point, main class, readme or decision record was found, so"
                            + " there is no obvious place to start reading."));
    assertThat(blocks(section(document, "catalog"), Callout.class)).hasSize(1);
    assertThat(blocks(section(document, "change_guidance"), Callout.class)).hasSize(1);
  }

  @Test
  void theExplorerNowPassesItsOwnQualityBarChecks() {
    Document document = explorer(facts());

    QualityReport report =
        blocks(section(document, Composer.QUALITY), QualityReport.class).getFirst();
    assertThat(report.failures())
        .extracting(QualityReport.Failure::check)
        .doesNotContain(
            QualityBar.ORIENTATION,
            QualityBar.START_HERE,
            QualityBar.CONTAINER_FIRST,
            QualityBar.FLOWS,
            QualityBar.CATALOG,
            QualityBar.DECISIONS);
  }
}

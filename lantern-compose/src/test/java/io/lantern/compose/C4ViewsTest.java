package io.lantern.compose;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.document.Block;
import io.lantern.core.document.Callout;
import io.lantern.core.document.CrossReference;
import io.lantern.core.document.Diagram;
import io.lantern.core.document.Document;
import io.lantern.core.document.DocumentEntity;
import io.lantern.core.document.Inline;
import io.lantern.core.document.ListBlock;
import io.lantern.core.document.Metrics;
import io.lantern.core.document.QualityReport;
import io.lantern.core.document.Section;
import io.lantern.core.document.Table;
import io.lantern.core.document.TemplateRef;
import io.lantern.core.document.TextRun;
import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.snapshot.Snapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The C4 context, container and component views (docs/design.md, Diagrams; ADR 0044). */
class C4ViewsTest {

  private static final TemplateRef TEMPLATE = new TemplateRef("c4-model-sequence-diagrams", "1");
  private static final Snapshot SNAPSHOT =
      new Snapshot(
          2, Map.of("shop", TestFacts.COMMIT, "base", TestFacts.COMMIT), 5, "0.1.0", Instant.EPOCH);

  private final TestFacts facts = TestFacts.runtime();

  private Document repo(List<Fact> facts) {
    return new Composer("1")
        .compose(ViewDefinition.c4Report("shop.c4", "shop", TEMPLATE), SNAPSHOT, facts, Map.of());
  }

  private Document system(List<Fact> facts) {
    return new Composer("1")
        .compose(
            ViewDefinition.c4System("system:c4", "System", TEMPLATE), SNAPSHOT, facts, Map.of());
  }

  private static Section section(Document document, String id) {
    return document.sections().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
  }

  private static <T extends Block> T block(Section section, Class<T> kind) {
    return section.blocks().stream()
        .filter(kind::isInstance)
        .map(kind::cast)
        .findFirst()
        .orElseThrow();
  }

  private static List<String> nodes(Diagram diagram) {
    return diagram.nodes().stream()
        .map(
            n ->
                n.id()
                    + " \""
                    + n.label()
                    + "\""
                    + (n.technology() == null ? "" : " [" + n.technology() + "]")
                    + (n.parent() == null ? "" : " in " + n.parent()))
        .toList();
  }

  private static List<String> edges(Diagram diagram) {
    return diagram.edges().stream()
        .map(
            e ->
                e.from()
                    + " -> "
                    + e.to()
                    + ": "
                    + e.label()
                    + (e.technology() == null ? "" : " [" + e.technology() + "]")
                    + (e.profiles().isEmpty() ? "" : " " + e.profiles()))
        .toList();
  }

  private static String text(List<Inline> content) {
    return content.stream()
        .map(i -> i instanceof TextRun t ? t.text() : "")
        .collect(Collectors.joining());
  }

  @Test
  void contextShowsTheSystemTheExternalSystemsItCallsAndThatItsUsersAreUnknown() {
    Section context = section(repo(facts.list()), "context");
    Diagram diagram = block(context, Diagram.class);

    assertThat(diagram.diagramKind()).isEqualTo(Diagram.Kind.C4_CONTEXT);
    assertThat(nodes(diagram))
        .containsExactly(
            "external-system:pay.example.com \"pay.example.com\"",
            "system:snapshot \"base and shop\"");
    // No technology on a context diagram; the profile marks the line conditional.
    assertThat(edges(diagram))
        .containsExactly(
            "system:snapshot -> external-system:pay.example.com: Calls POST /charges [prod]");
    assertThat(diagram.edges().getFirst().facts())
        .containsExactly(facts.find("service:com.example:app", "CALLS_HTTP").id());
    assertThat(text(block(context, Callout.class).content()))
        .isEqualTo(
            "The code names no people or user roles, so who uses base and shop is not known.");
    assertThat(block(context, Metrics.class).items())
        .extracting(i -> i.label() + " " + i.value())
        .containsExactly(
            "Deployable units 1",
            "Databases 1",
            "Topics, queues and exchanges 1",
            "External systems 1");
  }

  @Test
  void containersAreTheUnitsWithTheirStoresQueuesAndNeighboursInsideTheSystem() {
    Diagram diagram = block(section(repo(facts.list()), "containers"), Diagram.class);

    assertThat(diagram.diagramKind()).isEqualTo(Diagram.Kind.C4_CONTAINER);
    assertThat(nodes(diagram))
        .containsExactly(
            "database:db:5432/shop \"shop on db\" [PostgreSQL] in system:snapshot",
            "external-system:pay.example.com \"pay.example.com\"",
            "service:com.example:app \"shop-app\" [Spring Boot 3.5.0 / Java 21] in system:snapshot",
            "service:com.example:worker \"worker\" [Spring Boot 3.5.0 / Java 21] in system:snapshot",
            "system:snapshot \"base and shop\"",
            "topic:kafka:orders \"orders\" [Kafka topic] in system:snapshot");
    assertThat(edges(diagram))
        .containsExactly(
            "service:com.example:app -> external-system:pay.example.com: Calls POST /charges"
                + " [HTTPS] [prod]",
            "service:com.example:app -> topic:kafka:orders: Sends messages [Kafka]",
            "service:com.example:app -> database:db:5432/shop: Stores its data in [JDBC]",
            "service:com.example:worker -> service:com.example:app: Calls GET /orders [JSON/HTTP]");
  }

  @Test
  void theSystemScopeShowsEveryUnitAndLine() {
    Document document = system(facts.list());
    Diagram diagram = block(section(document, "containers"), Diagram.class);

    assertThat(edges(diagram))
        .contains("topic:kafka:orders -> service:com.example:worker: Delivers messages [Kafka]");
    // The system's purpose, unknown without the LLM's wording (ADR 0060), then its repositories.
    assertThat(section(document, "overview").blocks()).hasSize(2);
    assertThat(section(document, "overview").blocks().getLast())
        .satisfies(
            b ->
                assertThat(((ListBlock) b).items())
                    .extracting(i -> text(i.content()))
                    .containsExactly(": runs ", ": runs "));
    assertThat(section(document, "overview").entity()).isEqualTo(RepoSections.SYSTEM);
  }

  @Test
  void unresolvedLinksAndDecisionRecordsFollowTheDiagram() {
    Section containers = section(repo(facts.list()), "containers");

    Callout unresolved = block(containers, Callout.class);
    assertThat(unresolved.variant()).isEqualTo(Callout.Variant.WARNING);
    assertThat(text(unresolved.content()))
        .isEqualTo("shop-app: GET ${TAX_URL}/quotes (unresolved placeholder TAX_URL).");
    Table decisions = block(containers, Table.class);
    assertThat(decisions.columns())
        .extracting(Table.Column::label)
        .containsExactly("ID", "Decision", "Status", "Outcome", "Rationale");
    assertThat(decisions.rows())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.entity())
                  .isEqualTo(EntityId.parse("decision:shop:docs/adr/0001-kafka.md"));
              assertThat(row.cells())
                  .extracting(C4ViewsTest::text)
                  .containsExactly(
                      "ADR 1", "Use Kafka", "Accepted", "Orders go to Kafka.", "Not recorded.");
            });
  }

  @Test
  void componentsAreFoundByRoleWithSharedBeansOnTheirOwn() {
    Section components = section(repo(facts.list()), "components");

    Section app = components.sections().getFirst();
    assertThat(app.entity()).isEqualTo(EntityId.parse("service:com.example:app"));
    assertThat(app.title()).isEqualTo("shop-app");
    Diagram diagram = block(app, Diagram.class);
    assertThat(diagram.diagramKind()).isEqualTo(Diagram.Kind.C4_COMPONENT);
    assertThat(nodes(diagram))
        .containsExactly(
            "database:db:5432/shop \"shop on db\" [PostgreSQL]",
            "external-system:pay.example.com \"pay.example.com\"",
            "service:com.example:app \"shop-app\" [Spring Boot 3.5.0 / Java 21]",
            "service:com.example:worker \"worker\" [Spring Boot 3.5.0 / Java 21]",
            "topic:kafka:orders \"orders\" [Kafka topic]",
            "type:com.example.domain.JpaOrders \"JpaOrders\" [Spring repository]"
                + " in service:com.example:app",
            "type:com.example.web.Controller \"Controller\" [Spring MVC REST controller]"
                + " in service:com.example:app",
            "type:com.example.web.Jobs \"Jobs\" [Scheduled job] in service:com.example:app");
    assertThat(edges(diagram))
        .containsExactlyInAnyOrder(
            "type:com.example.web.Controller -> type:com.example.domain.JpaOrders: Calls all",
            "type:com.example.web.Jobs -> type:com.example.domain.JpaOrders: Calls all",
            "type:com.example.domain.JpaOrders -> topic:kafka:orders: Sends messages [Kafka]",
            "type:com.example.domain.JpaOrders -> database:db:5432/shop: Reads [JDBC]",
            "type:com.example.web.Controller -> external-system:pay.example.com:"
                + " Calls POST /charges [HTTPS] [prod]",
            "service:com.example:worker -> type:com.example.web.Controller: Calls GET /orders"
                + " [JSON/HTTP]");
  }

  @Test
  void aUnitWithOneComponentGetsNoComponentDiagram() {
    // Without the scheduled job, the repository's bean is reached only from the controller.
    List<Fact> fewer =
        facts.list().stream().filter(f -> !f.predicate().equals("scheduled")).toList();

    assertThat(section(repo(fewer), "components").sections()).isEmpty();
  }

  @Test
  void unitsAreNamedByTheirApplicationNameAndDescribedByTheirMainModule() {
    EntityId module = EntityId.parse("module:com.example:app");
    String packaging = facts.find("module:com.example:app", "packaging").id();
    Map<EntityId, io.lantern.summarize.Summary> summaries =
        Map.of(
            module,
            ComposerTest.summary(
                module, "Serves orders. [fact:" + packaging + "]", List.of(packaging)));

    Document document =
        new Composer("1")
            .compose(
                ViewDefinition.c4Report("shop.c4", "shop", TEMPLATE),
                SNAPSHOT,
                facts.list(),
                summaries);

    DocumentEntity app =
        document.entities().stream()
            .filter(e -> e.id().toString().equals("service:com.example:app"))
            .findFirst()
            .orElseThrow();
    assertThat(app.name()).isEqualTo("shop-app");
    assertThat(text(app.description())).isEqualTo("Serves orders.");
    assertThat(app.description()).hasSize(2);
  }

  @Test
  void aContainerDiagramOfMoreThanFifteenElementsIsSplitPerUnit() {
    TestFacts many = TestFacts.runtime();
    for (int i = 0; i < 16; i++) {
      many.relate(
          "shop",
          "service:com.example:app",
          "PUBLISHES",
          "topic:kafka:t" + i,
          "{\"protocol\": \"Kafka\"}");
    }

    Document document = system(many.list());
    Section containers = section(document, "containers");

    assertThat(containers.blocks()).noneMatch(Diagram.class::isInstance);
    assertThat(containers.sections())
        .extracting(Section::title)
        .containsExactly("shop-app", "worker");
    Diagram worker = block(containers.sections().get(1), Diagram.class);
    assertThat(worker.title()).isEqualTo("Containers around worker of base and shop");
    // shop-app's own neighbourhood still has more than 15 elements: a quality gap, not hidden.
    QualityReport gaps = block(document.sections().getLast(), QualityReport.class);
    assertThat(gaps.failures())
        .filteredOn(f -> f.check().equals("At most 15 elements"))
        .singleElement()
        .satisfies(
            f ->
                assertThat(f.target())
                    .isEqualTo(
                        CrossReference.Target.block("containers.service:com.example:app.diagram")));
  }

  @Test
  void aProfileOnlyAlternativeOfADefaultLinkIsANoteNotABox() {
    TestFacts more = TestFacts.runtime();
    more.relate(
            "shop",
            "service:com.example:app",
            "USES_DATABASE",
            "database:localhost:5432/shop",
            "{\"protocol\": \"JDBC\", \"key\": \"spring.datasource.url\","
                + " \"profile\": \"local\"}")
        .relate(
            "shop",
            "service:com.example:app",
            "USES_DATABASE",
            "database:db:5432/shop",
            "{\"protocol\": \"JDBC\", \"key\": \"spring.datasource.url\","
                + " \"exceptProfiles\": [\"local\"]}");

    Section containers = section(repo(more.list()), "containers");

    assertThat(nodes(block(containers, Diagram.class)))
        .noneMatch(n -> n.startsWith("database:localhost"));
    Callout note =
        containers.blocks().stream()
            .filter(b -> b instanceof Callout c && "Under other profiles".equals(c.title()))
            .map(Callout.class::cast)
            .findFirst()
            .orElseThrow();
    assertThat(text(note.content()))
        .isEqualTo(
            "Under the Spring profile local, shop-app connects to shop on localhost instead of"
                + " shop on db.");
    assertThat(block(section(repo(more.list()), "context"), Metrics.class).items())
        .extracting(i -> i.label() + " " + i.value())
        .contains("Databases 1");
  }

  @Test
  void composesTheSameWhateverTheOrderOfTheFacts() {
    List<Fact> reversed = new ArrayList<>(facts.list());
    Collections.reverse(reversed);

    assertThat(io.lantern.core.document.DocumentJson.write(system(reversed)))
        .isEqualTo(io.lantern.core.document.DocumentJson.write(system(facts.list())));
  }

  @Test
  void aRepositoryWithoutUnitsSaysSo() {
    Section containers = section(repo(TestFacts.shop().list()), "containers");

    assertThat(text(block(containers, Callout.class).content()))
        .isEqualTo("No deployable unit was found in the code.");
  }
}

package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lantern.core.document.Block;
import io.lantern.core.document.Callout;
import io.lantern.core.document.CrossReference;
import io.lantern.core.document.Diagram;
import io.lantern.core.document.Document;
import io.lantern.core.document.DocumentEntity;
import io.lantern.core.document.DocumentJson;
import io.lantern.core.document.DocumentSource;
import io.lantern.core.document.ListBlock;
import io.lantern.core.document.Paragraph;
import io.lantern.core.document.Section;
import io.lantern.core.document.Table;
import io.lantern.core.document.TextRun;
import io.lantern.core.document.Tree;
import io.lantern.core.fact.EntityId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;

/**
 * The C4 report spec renderer, compared with golden files in {@code
 * src/test/resources/report-specs/c4-model-sequence-diagrams}. {@code lantern-web}'s tests check
 * the same golden files against the report spec's schema and the rules the template checks on load.
 * A mismatch writes the actual output to {@code target/golden-actual/}, to compare with or copy
 * over the golden file once checked.
 */
class C4ReportRendererTest {

  private static final Path GOLDEN =
      Path.of("src/test/resources/report-specs", C4ReportRenderer.SPEC);

  /** Every block kind, sequence diagrams and quality reports included. */
  private static final Path MODEL_FIXTURE =
      Path.of("../lantern-core/src/test/resources/schema/document/valid-document.json");

  /** The composer's documents of the fixture projects. */
  private static final Path COMPOSED = Path.of("../lantern-compose/src/test/resources/golden");

  @ParameterizedTest
  @CsvSource({
    "valid-document, ../lantern-core/src/test/resources/schema/document/valid-document.json",
    "shop, ../lantern-compose/src/test/resources/golden/shop.json",
    "greeting-service, ../lantern-compose/src/test/resources/golden/greeting-service.json",
    "orders, ../lantern-compose/src/test/resources/golden/orders.json",
    "runtime, ../lantern-compose/src/test/resources/golden/runtime.c4.json"
  })
  void rendersDocumentsAsTheGoldenFilesSay(String name, Path source) throws IOException {
    Document document = DocumentJson.read(Files.readString(source));

    String actual = C4ReportRenderer.write(C4ReportRenderer.toJson(document).data()) + "\n";

    Path golden = GOLDEN.resolve(name + ".json");
    String expected = Files.exists(golden) ? Files.readString(golden) : "";
    if (!actual.equals(expected)) {
      Path out = Path.of("target", "golden-actual", C4ReportRenderer.SPEC, name + ".json");
      Files.createDirectories(out.getParent());
      Files.writeString(out, actual);
    }
    assertThat(actual).as("compared with golden file %s", golden).isEqualTo(expected);
  }

  @Test
  void mapsLanternIdsToKebabCaseAndKeepsTheMapping() throws IOException {
    Document document = DocumentJson.read(Files.readString(MODEL_FIXTURE));

    C4ReportRenderer.Result result = C4ReportRenderer.toJson(document);

    assertThat(result.elements())
        .containsExactly(
            Map.entry("database-orders-db", EntityId.parse("database:orders-db")),
            Map.entry("library-orders-lib", EntityId.parse("library:orders-lib")),
            Map.entry("service-orders", EntityId.parse("service:orders")));
    assertThat(result.data().get("elements").propertyNames())
        .containsExactly("database-orders-db", "library-orders-lib", "service-orders");
  }

  @Test
  void drawsElementsAsTheKindTheirTypeOrTheDiagramsDrawThemAs() throws IOException {
    Document document = DocumentJson.read(Files.readString(COMPOSED.resolve("runtime.c4.json")));

    JsonNode data = C4ReportRenderer.toJson(document).data();
    JsonNode elements = data.get("elements");

    // The system is around the containers; a unit is a container inside it, tagged with its
    // Lantern type; data stores, topics and external systems are drawn as what they are; a
    // component is its main type, inside its unit.
    assertThat(elements.get("system-snapshot").get("kind").asString()).isEqualTo("system");
    JsonNode app = elements.get("service-com-example-app");
    assertThat(app.get("kind").asString()).isEqualTo("container");
    assertThat(app.get("parent").asString()).isEqualTo("system-snapshot");
    assertThat(app.get("tag").asString()).isEqualTo("Service");
    JsonNode database = elements.get("database-db-5432-shop");
    assertThat(database.get("kind").asString()).isEqualTo("database");
    assertThat(database.get("parent").asString()).isEqualTo("system-snapshot");
    assertThat(database.get("technology").asString()).isEqualTo("PostgreSQL");
    JsonNode topic = elements.get("topic-kafka-orders");
    assertThat(topic.get("kind").asString()).isEqualTo("queue");
    assertThat(topic.get("tag").asString()).isEqualTo("Topic");
    JsonNode external = elements.get("external-system-pay-example-com");
    assertThat(external.get("kind").asString()).isEqualTo("external");
    assertThat(external.has("tag")).isFalse();
    assertThat(external.has("parent")).isFalse();
    JsonNode controller = elements.get("type-com-example-web-controller");
    assertThat(controller.get("kind").asString()).isEqualTo("component");
    assertThat(controller.get("parent").asString()).isEqualTo("service-com-example-app");
    assertThat(controller.has("tag")).isFalse();

    // Lines carry their technology, and a profile they alone hold under in their label.
    JsonNode containers = data.get("pages").get(1).get("sections").get(0);
    assertThat(containers.get("boundary").asString()).isEqualTo("system-snapshot");
    assertThat(containers.get("relationships").toString())
        .contains(
            "{\"from\":\"service-com-example-app\",\"to\":\"external-system-pay-example-com\","
                + "\"label\":\"Calls POST /charges (only with the Spring profile prod)\","
                + "\"technology\":\"HTTPS\"}");
    // Each kind has its own catalogue; decision records become a decisions section, on a
    // documentation page of its own: C4 pages hold only C4 content (ADR 0086).
    assertThat(data.get("pages").get(1).get("sections").findValuesAsString("title"))
        .contains("Containers", "Data Stores and Queues", "External Systems")
        .doesNotContain("Decisions");
    JsonNode decisions =
        data.get("pages")
            .valueStream()
            .filter(p -> p.get("id").asString().equals("decisions"))
            .findFirst()
            .orElseThrow();
    assertThat(decisions.get("level").asString()).isEqualTo("documentation");
    assertThat(decisions.get("title").asString()).isEqualTo("Decisions and unknowns");
    JsonNode decision =
        decisions.findValues("items").stream()
            .filter(items -> items.get(0).has("status"))
            .findFirst()
            .orElseThrow()
            .get(0);
    assertThat(decision.toString())
        .isEqualTo(
            "{\"id\":\"ADR 1\",\"title\":\"Use Kafka\",\"body\":\"Orders go to Kafka.\\n\\n"
                + "**Rationale:** Not recorded.\",\"status\":\"Accepted\"}");
  }

  @Test
  void documentationIsOnDocumentationPagesAndNoC4PageHoldsIt() throws IOException {
    Document document =
        DocumentJson.read(Files.readString(COMPOSED.resolve("greeting-service.json")));

    JsonNode pages = C4ReportRenderer.toJson(document).data().get("pages");

    // The Explorer's decisions, catalog, change guidance, dependencies, structure and glossary are
    // each a documentation page, after the C4 and sequence pages (ADR 0086).
    assertThat(
            pages
                .valueStream()
                .filter(p -> p.get("level").asString().equals("documentation"))
                .map(p -> p.get("title").asString()))
        .containsExactly(
            "Decisions and unknowns",
            "Risks and findings",
            "Catalog",
            "Change guidance",
            "Dependencies",
            "Modules and packages",
            "Glossary");
    for (JsonNode page : pages) {
      if (page.get("level").asString().equals("documentation")) {
        assertThat(page.get("sections").findValuesAsString("type"))
            .doesNotContain("diagram", "sequence", "steps");
      } else {
        assertThat(page.get("sections").findValuesAsString("type")).doesNotContain("decisions");
        assertThat(page.get("sections").findValuesAsString("title"))
            .noneMatch(t -> t.matches("(?i).*(decision|deployment|glossary|catalog|risk).*"));
      }
    }
  }

  @Test
  void aSectionWithoutADiagramAfterTheFirstPageIsADocumentationPage() {
    EntityId system = EntityId.parse("repo:shop");
    Diagram context =
        new Diagram(
            "context.diagram",
            Diagram.Kind.C4_CONTEXT,
            null,
            List.of(node(system)),
            List.of(),
            List.of());
    Table risks =
        new Table(
            "risks.table",
            "Risks",
            List.of(new Table.Column("Impact"), new Table.Column("Finding")),
            List.of(
                new Table.Row(
                    null,
                    List.of(List.of(TextRun.of("High")), List.of(TextRun.of("A risk"))),
                    List.of())),
            List.of());
    Document document =
        document(
            List.of(entity(system)),
            section(
                "intro",
                null,
                List.of(new Paragraph("intro.text", List.of(TextRun.of("Hello.")), List.of())),
                List.of()),
            section("context", system, List.of(context), List.of()),
            section("risks", system, List.of(risks), List.of()));

    JsonNode pages = C4ReportRenderer.toJson(document).data().get("pages");

    // The introduction before the first page stays on the index; the risks become a
    // documentation page, about no element, while the context page stays about the system.
    assertThat(
            pages.valueStream().map(p -> p.get("id").asString() + ":" + p.get("level").asString()))
        .containsExactly("context:context", "risks:documentation");
    assertThat(pages.get(1).has("element")).isFalse();
    assertThat(pages.get(1).get("sections").get(0).get("type").asString()).isEqualTo("table");
    assertThat(pages.get(0).get("element").asString()).isEqualTo("repo-shop");
  }

  @Test
  void aParentRowsFoldedRowsAreItsSubRows() {
    EntityId system = EntityId.parse("repo:shop");
    Diagram context =
        new Diagram(
            "context.diagram",
            Diagram.Kind.C4_CONTEXT,
            null,
            List.of(node(system)),
            List.of(),
            List.of());
    Table risks =
        new Table(
            "risks.table",
            "Risks",
            List.of(new Table.Column("Impact"), new Table.Column("Finding")),
            List.of(
                new Table.Row(
                    null,
                    List.of(List.of(TextRun.of("High")), List.of(TextRun.of("A risk"))),
                    List.of()),
                new Table.Row(
                    null,
                    List.of(List.of(TextRun.of("Medium")), List.of(TextRun.of("Two of a kind"))),
                    List.of(),
                    List.of(
                        new Table.Row(
                            null,
                            List.of(List.of(TextRun.of("Medium")), List.of(TextRun.of("First"))),
                            List.of()),
                        new Table.Row(
                            null,
                            List.of(List.of(TextRun.of("Low")), List.of(TextRun.of("Second"))),
                            List.of())))),
            List.of());
    Document document =
        document(
            List.of(entity(system)),
            section("context", system, List.of(context), List.of()),
            section("risks", system, List.of(risks), List.of()));

    JsonNode table =
        C4ReportRenderer.toJson(document).data().get("pages").get(1).get("sections").get(0);

    // The parent stays one row; what is folded under it is listed by its index (#747).
    assertThat(table.get("rows").size()).isEqualTo(2);
    assertThat(table.get("subRows").toString())
        .isEqualTo("[{\"row\":1,\"rows\":[[\"Medium\",\"First\"],[\"Low\",\"Second\"]]}]");
  }

  @Test
  void marksWhatOnlyTheLlmFoundAsFoundByTheLlm() throws IOException {
    Document document = DocumentJson.read(Files.readString(MODEL_FIXTURE));

    JsonNode data = C4ReportRenderer.toJson(document).data();

    // The line and the table row whose only fact the resolver (an LLM) proposed are marked; the
    // elements, which static facts also name, are not.
    assertThat(data.findValues("relationships").getFirst().get(0).get("llm").asBoolean()).isTrue();
    assertThat(data.findValues("llmRows").getFirst().toString()).isEqualTo("[0]");
    assertThat(data.get("elements").valueStream()).noneMatch(e -> e.has("llm"));
  }

  private static io.lantern.core.fact.Fact fact(
      EntityId subject,
      String predicate,
      io.lantern.core.fact.FactObject object,
      io.lantern.core.fact.Origin.Kind kind) {
    return io.lantern.core.fact.Fact.create(
        subject,
        predicate,
        object,
        io.lantern.core.fact.FactJson.newObject(),
        new io.lantern.core.fact.Origin(kind, "test", "1"),
        io.lantern.core.fact.Evidence.of(
            new io.lantern.core.fact.Evidence.Location(
                "shop",
                "pom.xml",
                null,
                "a".repeat(40),
                null,
                io.lantern.core.fact.Evidence.Role.DECLARED)),
        1.0,
        io.lantern.core.fact.Validity.from(1));
  }

  @Test
  void marksWhatTheLlmFoundOnceAndNotWhatItOnlyWorded() {
    var llm = io.lantern.core.fact.Origin.Kind.LLM;
    EntityId system = EntityId.parse("system:snapshot");
    EntityId unit = EntityId.parse("service:shop");
    EntityId loki = EntityId.parse("external-system:loki");
    var purpose = fact(system, "purpose", io.lantern.core.fact.FactObject.string("system"), llm);
    var kind =
        fact(
            unit,
            "packaging",
            io.lantern.core.fact.FactObject.string("jar"),
            io.lantern.core.fact.Origin.Kind.STATIC);
    var found =
        fact(unit, "INVESTIGATED_CONNECTION", io.lantern.core.fact.FactObject.entity(loki), llm);
    var responsibility =
        fact(loki, "responsibility", io.lantern.core.fact.FactObject.string("c4"), llm);
    Diagram diagram =
        new Diagram(
            "containers.diagram",
            Diagram.Kind.C4_CONTAINER,
            null,
            List.of(node(system), node(unit, system), node(loki)),
            List.of(
                new Diagram.Edge(
                    "e1",
                    unit,
                    loki,
                    "Found by the LLM: Pushes logs",
                    null,
                    null,
                    List.of(),
                    List.of(found.id()))),
            List.of());
    Document document =
        new Document(
            "shop.c4",
            "shop",
            null,
            AnalysisRun.C4_REPORT,
            new DocumentSource(1, 1, "test", Map.of("r", "a".repeat(40)), LocalDate.of(2026, 1, 1)),
            List.of(
                new DocumentEntity(
                    system,
                    "Shop",
                    null,
                    List.of(TextRun.of("Sells books.")),
                    List.of(purpose.id())),
                new DocumentEntity(unit, "shop", system, List.of(), List.of(kind.id())),
                new DocumentEntity(
                    loki,
                    "loki",
                    null,
                    List.of(TextRun.of("Found by the LLM: Keeps the shop's logs.")),
                    List.of(responsibility.id(), found.id()))),
            List.of(purpose, kind, found, responsibility),
            List.of(section("containers", null, List.of(diagram), List.of())));

    JsonNode data = C4ReportRenderer.toJson(document).data();

    // The system's purpose is the LLM's wording, not its finding: no mark.
    assertThat(data.get("elements").get("system-snapshot").has("llm")).isFalse();
    assertThat(data.get("elements").get("service-shop").has("llm")).isFalse();
    // What only Investigate found is marked, Describe's sentence or not, and says so once.
    JsonNode box = data.get("elements").get("external-system-loki");
    assertThat(box.get("llm").asBoolean()).isTrue();
    assertThat(box.get("description").asString()).isEqualTo("Keeps the shop's logs.");
    JsonNode line = data.findValues("relationships").getFirst().get(0);
    assertThat(line.get("llm").asBoolean()).isTrue();
    assertThat(line.get("label").asString()).isEqualTo("Pushes logs");
  }

  @Test
  void bookkeepingOfTheLlmRolesMarksNothing() {
    var llm = io.lantern.core.fact.Origin.Kind.LLM;
    EntityId system = EntityId.parse("system:snapshot");
    var purpose = fact(system, "purpose", io.lantern.core.fact.FactObject.string("system"), llm);
    // On a budget-cut run the system holds what a role did not ask and Investigate did not see.
    var notAsked =
        fact(system, "not-asked", io.lantern.core.fact.FactObject.string("describe-unit"), llm);
    var notInvestigated =
        fact(system, "not-investigated", io.lantern.core.fact.FactObject.string("budget"), llm);
    Diagram diagram =
        new Diagram(
            "context.diagram",
            Diagram.Kind.C4_CONTEXT,
            null,
            List.of(node(system)),
            List.of(),
            List.of());
    Document document =
        new Document(
            "shop.c4",
            "shop",
            null,
            AnalysisRun.C4_REPORT,
            new DocumentSource(1, 1, "test", Map.of("r", "a".repeat(40)), LocalDate.of(2026, 1, 1)),
            List.of(
                new DocumentEntity(
                    system,
                    "Shop",
                    null,
                    List.of(TextRun.of("Sells books.")),
                    List.of(purpose.id(), notAsked.id(), notInvestigated.id()))),
            List.of(purpose, notAsked, notInvestigated),
            List.of(section("context", null, List.of(diagram), List.of())));

    JsonNode data = C4ReportRenderer.toJson(document).data();

    assertThat(data.get("elements").get("system-snapshot").has("llm")).isFalse();

    // With a finding of Investigate's among them, the same facts mark the box.
    var risk = fact(system, "investigated-risk", io.lantern.core.fact.FactObject.string("k"), llm);
    Document found =
        new Document(
            "shop.c4",
            "shop",
            null,
            AnalysisRun.C4_REPORT,
            new DocumentSource(1, 1, "test", Map.of("r", "a".repeat(40)), LocalDate.of(2026, 1, 1)),
            List.of(
                new DocumentEntity(
                    system,
                    "Shop",
                    null,
                    List.of(TextRun.of("Sells books.")),
                    List.of(purpose.id(), notAsked.id(), risk.id()))),
            List.of(purpose, notAsked, risk),
            List.of(section("context", null, List.of(diagram), List.of())));
    assertThat(
            C4ReportRenderer.toJson(found)
                .data()
                .get("elements")
                .get("system-snapshot")
                .get("llm")
                .asBoolean())
        .isTrue();
  }

  @Test
  void aRiskRowOfInvestigateKeepsItsBadgeAndLosesTheWordsThatSayIt() {
    var llm = io.lantern.core.fact.Origin.Kind.LLM;
    EntityId system = EntityId.parse("repo:shop");
    EntityId unit = EntityId.parse("service:shop");
    var risk = fact(unit, "investigated-risk", io.lantern.core.fact.FactObject.string("k"), llm);
    Diagram context =
        new Diagram(
            "context.diagram",
            Diagram.Kind.C4_CONTEXT,
            null,
            List.of(node(system)),
            List.of(),
            List.of());
    Table risks =
        new Table(
            "risks.table",
            "Risks and findings",
            List.of(new Table.Column("Impact"), new Table.Column("Finding")),
            List.of(
                new Table.Row(
                    null,
                    List.of(
                        List.of(TextRun.of("High")),
                        List.of(
                            new TextRun("Found by the LLM:", TextRun.Style.EMPHASIS),
                            TextRun.of(" "),
                            new TextRun("Orders trust a header.", TextRun.Style.STRONG))),
                    List.of(risk.id()))),
            List.of());
    Document document =
        new Document(
            "shop.c4",
            "shop",
            null,
            AnalysisRun.C4_REPORT,
            new DocumentSource(1, 1, "test", Map.of("r", "a".repeat(40)), LocalDate.of(2026, 1, 1)),
            List.of(entity(system), new DocumentEntity(unit, "shop", null, List.of(), List.of())),
            List.of(risk),
            List.of(
                section("context", system, List.of(context), List.of()),
                section("risks", system, List.of(risks), List.of())));

    JsonNode table =
        C4ReportRenderer.toJson(document).data().get("pages").get(1).get("sections").get(0);

    assertThat(table.get("llmRows").toString()).isEqualTo("[0]");
    assertThat(table.get("rows").get(0).get(1).asString()).isEqualTo("**Orders trust a header.**");
  }

  @Test
  void drawsEachElementAsTheKindTheComposerResolvedWithoutKnowingItsType() {
    // Types the renderer has never heard of: what they are is the composer's to say (ADR 0114).
    EntityId unit = EntityId.parse("service:shop");
    EntityId index = EntityId.parse("search-index:products");
    EntityId graph = EntityId.parse("graph-database:neo4j");
    Diagram diagram =
        new Diagram(
            "containers.diagram",
            Diagram.Kind.C4_CONTAINER,
            null,
            List.of(node(unit), node(index), node(graph)),
            List.of(),
            List.of());
    Document document =
        document(
            List.of(
                new DocumentEntity(unit, "shop", null, List.of(), List.of(), null, "Service"),
                new DocumentEntity(
                    index, "products", null, List.of(), List.of(), null, "Search index"),
                new DocumentEntity(
                    graph,
                    "neo4j",
                    null,
                    List.of(),
                    List.of(),
                    DocumentEntity.Kind.DATABASE,
                    null)),
            section("containers", null, List.of(diagram), List.of()));

    JsonNode elements = C4ReportRenderer.toJson(document).data().get("elements");

    JsonNode drawnIndex = elements.get("search-index-products");
    assertThat(drawnIndex.get("kind").asString()).isEqualTo("container");
    assertThat(drawnIndex.get("tag").asString()).isEqualTo("Search index");
    JsonNode drawnGraph = elements.get("graph-database-neo4j");
    assertThat(drawnGraph.get("kind").asString()).isEqualTo("database");
    assertThat(drawnGraph.has("tag")).isFalse();
  }

  @Test
  void refusesTwoLanternIdsThatBecomeTheSameId() {
    EntityId dotted = EntityId.parse("service:orders.api");
    EntityId dashed = EntityId.parse("service:orders-api");
    Diagram diagram =
        new Diagram(
            "containers.diagram",
            Diagram.Kind.C4_CONTAINER,
            null,
            List.of(node(dotted), node(dashed)),
            List.of(),
            List.of());
    Document document =
        new Document(
            "orders.c4",
            "orders",
            null,
            AnalysisRun.C4_REPORT,
            new DocumentSource(1, 1, "test", Map.of("r", "a".repeat(40)), LocalDate.of(2026, 1, 1)),
            List.of(entity(dotted), entity(dashed)),
            List.of(),
            List.of(
                new Section(
                    "containers",
                    "Containers",
                    Set.of(),
                    null,
                    List.of(),
                    List.of(diagram),
                    List.of())));

    assertThatThrownBy(() -> C4ReportRenderer.toJson(document))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'service:orders-api'")
        .hasMessageContaining("'service:orders.api'")
        .hasMessageContaining("'service-orders-api'");
  }

  @Test
  void drawsNoBoundaryWhoseChildrenCannotHaveItAsTheirParent() {
    // The system on the context diagram is drawn around a package on a component diagram, but a
    // component cannot sit inside a system, so neither the parent nor the boundary is kept.
    EntityId system = EntityId.parse("repo:shop");
    EntityId pkg = EntityId.parse("package:shop:com.example");
    Diagram context =
        new Diagram(
            "context.diagram",
            Diagram.Kind.C4_CONTEXT,
            null,
            List.of(node(system)),
            List.of(),
            List.of());
    Diagram components =
        new Diagram(
            "components.diagram",
            Diagram.Kind.C4_COMPONENT,
            null,
            List.of(node(system), node(pkg, system)),
            List.of(),
            List.of());
    Document document =
        document(
            List.of(entity(system), entity(pkg)),
            section("context", system, List.of(context), List.of()),
            section("components", system, List.of(components), List.of()));

    JsonNode data = C4ReportRenderer.toJson(document).data();

    assertThat(data.get("elements").get("repo-shop").get("kind").asString()).isEqualTo("system");
    assertThat(data.get("elements").get("package-shop-com-example").has("parent")).isFalse();
    assertThat(data.get("pages").get(1).get("sections").get(0).has("boundary")).isFalse();
  }

  @Test
  void givesABlockThatShowsNothingNoSectionAndLinksToTheNextOneShown() {
    EntityId system = EntityId.parse("repo:shop");
    Diagram context =
        new Diagram(
            "context.diagram",
            Diagram.Kind.C4_CONTEXT,
            null,
            List.of(node(system)),
            List.of(),
            List.of());
    Paragraph link =
        new Paragraph(
            "context.see",
            List.of(
                TextRun.of("See "),
                new CrossReference("the notes", CrossReference.Target.block("notes.empty"), false),
                TextRun.of(".")),
            List.of());
    Section notes =
        section(
            "notes",
            null,
            List.of(
                new Tree("notes.empty", "Nothing yet", List.of(), List.of()),
                new Paragraph("notes.text", List.of(TextRun.of("Some notes.")), List.of())),
            List.of());
    Document document =
        document(
            List.of(entity(system)),
            section("context", system, List.of(context, link), List.of(notes)));

    JsonNode sections =
        C4ReportRenderer.toJson(document).data().get("pages").get(0).get("sections");

    assertThat(sections.findValuesAsString("id"))
        .containsExactly("context-diagram", "context-diagram-systems", "context-see", "notes-text");
    assertThat(sections.get(2).get("body").asString())
        .isEqualTo("See [the notes](#/context/notes-text).");
    assertThat(sections.get(3).get("title").asString()).isEqualTo("Notes");
  }

  @Test
  void escapesLinkAndEmphasisSyntaxInTextAndKeepsTheLinksItMakesFromFacts() {
    EntityId system = EntityId.parse("repo:shop");
    Paragraph responsibility =
        new Paragraph(
            "context.text",
            List.of(
                TextRun.of("Serves [x](https://example.com) and **bold** "),
                new TextRun("files", TextRun.Style.EMPHASIS),
                TextRun.of(" under /files/**; "),
                new TextRun("/api/**", TextRun.Style.CODE),
                TextRun.of(". See "),
                new CrossReference(
                    "the [notes]", CrossReference.Target.block("context.text"), false),
                TextRun.of(".")),
            List.of());
    Diagram context =
        new Diagram(
            "context.diagram",
            Diagram.Kind.C4_CONTEXT,
            null,
            List.of(node(system)),
            List.of(),
            List.of());
    Document document =
        document(
            List.of(entity(system)),
            section("context", system, List.of(context, responsibility), List.of()));

    JsonNode sections =
        C4ReportRenderer.toJson(document).data().get("pages").get(0).get("sections");

    // The template's Markdown-lite shows the escaped characters as written (report-spec-c4 test).
    assertThat(sections.get(2).get("id").asString()).isEqualTo("context-text");
    assertThat(sections.get(2).get("body").asString())
        .isEqualTo(
            "Serves \\[x\\](https://example.com) and \\*\\*bold\\*\\* *files* under"
                + " /files/\\*\\*; `/api/\\*\\*`. See [the (notes)](#/context/context-text).");
  }

  @Test
  void escapesLinkAndEmphasisSyntaxInTableCellsListsTreesAndCalloutTitles() {
    EntityId system = EntityId.parse("repo:shop");
    String hostile = "a [x](https://example.com) **bold**";
    String escaped = "a \\[x\\](https://example.com) \\*\\*bold\\*\\*";
    Diagram context =
        new Diagram(
            "context.diagram",
            Diagram.Kind.C4_CONTEXT,
            null,
            List.of(node(system)),
            List.of(),
            List.of());
    Table table =
        new Table(
            "context.table",
            "Table",
            List.of(new Table.Column("Cell")),
            List.of(new Table.Row(null, List.of(List.of(TextRun.of(hostile))), List.of())),
            List.of());
    ListBlock list =
        new ListBlock(
            "context.list",
            false,
            List.of(new ListBlock.Item(List.of(TextRun.of(hostile)), List.of())),
            List.of());
    Tree tree =
        new Tree(
            "context.tree",
            "Tree",
            List.of(new Tree.Node(hostile, null, List.of(), List.of())),
            List.of());
    Callout callout =
        new Callout(
            "context.callout",
            Callout.Variant.WARNING,
            null,
            hostile,
            List.of(TextRun.of("Body.")),
            List.of());
    Document document =
        document(
            List.of(entity(system)),
            section("context", system, List.of(context, table, list, tree, callout), List.of()));

    String data = C4ReportRenderer.write(C4ReportRenderer.toJson(document).data());

    // No text reaches the template's Markdown-lite unescaped (#752).
    assertThat(data).doesNotContain("a [x](https://example.com)").doesNotContain("a **bold**");
    assertThat(data.split(java.util.regex.Pattern.quote(escaped.replace("\\", "\\\\")), -1))
        .hasSize(5);
  }

  private static Document document(List<DocumentEntity> entities, Section... sections) {
    return new Document(
        "shop.c4",
        "shop",
        null,
        AnalysisRun.C4_REPORT,
        new DocumentSource(1, 1, "test", Map.of("r", "a".repeat(40)), LocalDate.of(2026, 1, 1)),
        entities,
        List.of(),
        List.of(sections));
  }

  private static Section section(
      String id, EntityId entity, List<Block> blocks, List<Section> subsections) {
    String title = id.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + id.substring(1);
    return new Section(id, title, Set.of(), entity, List.of(), blocks, subsections);
  }

  private static Diagram.Node node(EntityId id, EntityId parent) {
    return new Diagram.Node(id, id.key(), null, parent, null, null, List.of());
  }

  private static Diagram.Node node(EntityId id) {
    return new Diagram.Node(id, id.key(), null, null, null, null, List.of());
  }

  private static DocumentEntity entity(EntityId id) {
    return new DocumentEntity(id, id.key(), null, List.of(), List.of());
  }

  @Test
  void writesTheDataIntoTheTemplateInPlaceOfTheSampleData() throws IOException {
    Document document = DocumentJson.read(Files.readString(MODEL_FIXTURE));

    String html = C4ReportRenderer.fromClassPath().render(document);

    String data = C4ReportRenderer.write(C4ReportRenderer.toJson(document).data());
    assertThat(html)
        .contains(
            "<script type=\"application/json\" id=\"arc-data\">\n"
                + data.replace("<", "\\u003c")
                + "\n</script>")
        .contains("const DATA = JSON.parse(document.getElementById('arc-data').textContent);");
  }

  @Test
  void templateEscapesEveryLessThan() {
    ReportSpecTemplate template =
        new ReportSpecTemplate(
            "<html><script type=\"application/json\" id=\"data\">{\"sample\":1}</script>"
                + "<script>main()</script></html>",
            "data");

    assertThat(template.render("{\"title\":\"</script><!-- a < b $1\"}"))
        .isEqualTo(
            "<html><script type=\"application/json\" id=\"data\">\n"
                + "{\"title\":\"\\u003c/script>\\u003c!-- a \\u003c b $1\"}\n"
                + "</script><script>main()</script></html>");
  }

  @Test
  void refusesATemplateWithoutExactlyOneDataElement() {
    String element = "<script type=\"application/json\" id=\"data\"></script>";
    assertThatThrownBy(() -> new ReportSpecTemplate("<html></html>", "data"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ReportSpecTemplate(element + element, "data"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @CsvSource({
    "service:orders, service-orders",
    "package:com.example:app:com.example.app.Web_Api, package-com-example-app-com-example-app-web-api",
    "repo:shop, repo-shop",
    "-x-:_y_, x-y"
  })
  void kebabCase(String lanternId, String reportId) {
    assertThat(ReportSpecIds.kebab(lanternId)).isEqualTo(reportId);
  }
}

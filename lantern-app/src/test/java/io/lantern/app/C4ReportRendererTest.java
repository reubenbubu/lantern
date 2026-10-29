package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lantern.core.document.Block;
import io.lantern.core.document.CrossReference;
import io.lantern.core.document.Diagram;
import io.lantern.core.document.Document;
import io.lantern.core.document.DocumentEntity;
import io.lantern.core.document.DocumentJson;
import io.lantern.core.document.DocumentSource;
import io.lantern.core.document.Paragraph;
import io.lantern.core.document.Section;
import io.lantern.core.document.TextRun;
import io.lantern.core.document.Tree;
import io.lantern.core.fact.EntityId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    // Each kind has its own catalogue; decision records become a decisions section.
    assertThat(data.get("pages").get(1).get("sections").findValuesAsString("title"))
        .contains("Containers", "Data Stores and Queues", "External Systems", "Decisions");
    JsonNode decision =
        data.get("pages").get(1).findValues("items").stream()
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
            new DocumentSource(1, 1, "test"),
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

  private static Document document(List<DocumentEntity> entities, Section... sections) {
    return new Document(
        "shop.c4",
        "shop",
        null,
        AnalysisRun.C4_REPORT,
        new DocumentSource(1, 1, "test"),
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

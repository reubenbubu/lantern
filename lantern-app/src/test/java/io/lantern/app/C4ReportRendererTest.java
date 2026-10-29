package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lantern.core.document.Diagram;
import io.lantern.core.document.Document;
import io.lantern.core.document.DocumentEntity;
import io.lantern.core.document.DocumentJson;
import io.lantern.core.document.DocumentSource;
import io.lantern.core.document.Section;
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
    "orders, ../lantern-compose/src/test/resources/golden/orders.json"
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
  void drawsElementsAsTheKindTheDiagramsDrawThemAs() throws IOException {
    Document document =
        DocumentJson.read(Files.readString(COMPOSED.resolve("greeting-service.json")));

    JsonNode elements = C4ReportRenderer.toJson(document).data().get("elements");

    // The repository is the system around the containers; a service is a container inside it,
    // tagged with its Lantern type; a package on a component diagram is a component.
    assertThat(elements.get("repo-greeting-service").get("kind").asString()).isEqualTo("system");
    assertThat(elements.get("repo-greeting-service").get("tag").asString()).isEqualTo("Repository");
    JsonNode service = elements.get("service-com-example-greeting-greeting-service");
    assertThat(service.get("kind").asString()).isEqualTo("container");
    assertThat(service.get("parent").asString()).isEqualTo("repo-greeting-service");
    assertThat(service.get("tag").asString()).isEqualTo("Service");
    assertThat(elements.properties())
        .filteredOn(e -> e.getKey().startsWith("package-"))
        .allSatisfy(
            e -> {
              assertThat(e.getValue().get("kind").asString()).isEqualTo("component");
              assertThat(e.getValue().get("tag").asString()).isEqualTo("Package");
            });
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

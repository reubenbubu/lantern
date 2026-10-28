package io.lantern.core.document;

import static io.lantern.core.document.SampleDocument.BUILDS;
import static io.lantern.core.document.SampleDocument.DB;
import static io.lantern.core.document.SampleDocument.LIB;
import static io.lantern.core.document.SampleDocument.ORDERS;
import static io.lantern.core.document.SampleDocument.facts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.FactJson;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class DocumentJsonTest {

  @Test
  void writesTheFixture() {
    JsonNode written = FactJson.parse(DocumentJson.write(SampleDocument.create()));

    assertThat(written).isEqualTo(FactJson.parse(SampleDocument.fixture()));
  }

  @Test
  void readsTheFixtureBackToTheSameDocument() {
    Document read = DocumentJson.read(SampleDocument.fixture());

    assertThat(read).isEqualTo(SampleDocument.create());
    assertThat(FactJson.parse(DocumentJson.write(read)))
        .isEqualTo(FactJson.parse(SampleDocument.fixture()));
  }

  @Test
  void sortsEntitiesFactsNodesEdgesAndFactIds() {
    Document document = SampleDocument.create();

    assertThat(document.entities()).extracting(e -> e.id().toString()).isSorted();
    assertThat(document.facts()).extracting(f -> f.id()).isSorted();
    Diagram diagram = (Diagram) document.sections().get(0).sections().get(0).blocks().get(0);
    assertThat(diagram.nodes()).extracting(n -> n.id().toString()).isSorted();
    assertThat(diagram.edges()).extracting(Diagram.Edge::id).isSorted();
    assertThat(diagram.facts()).isSorted();
  }

  /** Each case breaks the fixture in a way the schema forbids; lantern-web checks the same. */
  static Stream<JsonNode> invalidCases() {
    List<JsonNode> cases = new ArrayList<>();
    FactJson.parse(SampleDocument.resource("/schema/document/invalid-cases.json"))
        .forEach(cases::add);
    return cases.stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidCases")
  void refusesWhatTheSchemaForbids(JsonNode invalidCase) {
    JsonNode document = apply(invalidCase, FactJson.parse(SampleDocument.fixture()));

    assertThatThrownBy(() -> DocumentJson.fromJson(document))
        .as(invalidCase.get("description").stringValue())
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * Applies an invalid case to a copy of the fixture: sets the value at the JSON pointer, or
   * removes the field when the case has {@code "remove": true}.
   */
  static JsonNode apply(JsonNode invalidCase, JsonNode fixture) {
    String pointer = invalidCase.get("pointer").stringValue();
    int slash = pointer.lastIndexOf('/');
    JsonNode parent = fixture.at(pointer.substring(0, slash));
    String key = pointer.substring(slash + 1);
    boolean remove = invalidCase.has("remove") && invalidCase.get("remove").booleanValue();
    if (parent instanceof ObjectNode object) {
      if (remove) {
        assertThat(object.has(key)).as("field to remove exists: " + pointer).isTrue();
        object.remove(key);
      } else {
        object.set(key, invalidCase.get("value"));
      }
    } else if (parent instanceof ArrayNode array) {
      array.set(Integer.parseInt(key), invalidCase.get("value"));
    } else {
      throw new IllegalArgumentException("No object or array at " + pointer);
    }
    return fixture;
  }

  @Test
  void refusesFactsThatAreNotInTheDocument() {
    Paragraph cites =
        new Paragraph("p", List.of(TextRun.of("x"), new Citation(facts(BUILDS))), List.of());

    assertThatThrownBy(() -> document(List.of(), section(cites)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(BUILDS.id());
  }

  @Test
  void refusesEntitiesThatAreNotInTheDocument() {
    Section about = new Section("s", "S", Set.of(), DB, List.of(), List.of(), List.of());

    assertThatThrownBy(() -> document(List.of(entity(ORDERS, null)), about))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(DB.toString());
  }

  @Test
  void refusesAnEntityParentThatIsNotInTheDocument() {
    assertThatThrownBy(() -> document(List.of(entity(LIB, ORDERS)), emptySection()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(ORDERS.toString());
  }

  @Test
  void refusesEntityParentCycles() {
    assertThatThrownBy(
            () -> document(List.of(entity(LIB, ORDERS), entity(ORDERS, LIB)), emptySection()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cycle");
  }

  @Test
  void refusesCrossReferencesToMissingTargets() {
    for (CrossReference.Target target :
        List.of(
            CrossReference.Target.section("nowhere"),
            CrossReference.Target.block("nowhere"),
            CrossReference.Target.block("s"),
            CrossReference.Target.entity(DB))) {
      Paragraph ref = new Paragraph("p", List.of(new CrossReference("x", target)), List.of());

      assertThatThrownBy(() -> document(List.of(entity(ORDERS, null)), section(ref)))
          .as(target.toString())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Cross-reference");
    }
  }

  @Test
  void refusesDuplicateSectionAndBlockIds() {
    Paragraph sameAsSection = new Paragraph("s", List.of(TextRun.of("x")), List.of());

    assertThatThrownBy(() -> document(List.of(), section(sameAsSection)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Duplicate");
  }

  @Test
  void refusesDuplicateEntitiesAndFacts() {
    assertThatThrownBy(
            () -> document(List.of(entity(ORDERS, null), entity(ORDERS, null)), emptySection()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Duplicate entity");
    assertThatThrownBy(
            () ->
                new Document(
                    "d",
                    "D",
                    null,
                    new TemplateRef("t", "1"),
                    new DocumentSource(1, 0, "dev"),
                    List.of(),
                    List.of(BUILDS, BUILDS),
                    List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Duplicate fact");
  }

  @Test
  void refusesDiagramsWhoseEdgesOrParentsLeaveTheDiagram() {
    Diagram.Node orders = node(ORDERS, null);

    assertThatThrownBy(
            () ->
                diagram(
                    List.of(orders),
                    List.of(new Diagram.Edge("e", ORDERS, DB, null, null, List.of()))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Edge e");
    assertThatThrownBy(() -> diagram(List.of(node(LIB, ORDERS)), List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not a node");
    assertThatThrownBy(() -> diagram(List.of(node(LIB, ORDERS), node(ORDERS, LIB)), List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cycle");
    assertThatThrownBy(() -> diagram(List.of(orders, node(ORDERS, null)), List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Duplicate node");
    Diagram.Edge edge = new Diagram.Edge("e", ORDERS, ORDERS, null, null, List.of());
    assertThatThrownBy(() -> diagram(List.of(orders), List.of(edge, edge)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Duplicate edge");
  }

  @Test
  void refusesTableRowsWithTheWrongNumberOfCells() {
    assertThatThrownBy(
            () ->
                new Table(
                    "t",
                    null,
                    List.of(new Table.Column("A"), new Table.Column("B")),
                    List.of(new Table.Row(null, List.of(List.of()), List.of())),
                    List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cells");
  }

  @Test
  void refusesDiagramNodesForEntitiesNotInTheDocument() {
    Diagram diagram = diagram(List.of(node(ORDERS, null)), List.of());

    assertThatThrownBy(() -> document(List.of(), section(diagram)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(ORDERS.toString());
  }

  @Test
  void holdsMetricValuesInOneForm() {
    Metrics.Item a = new Metrics.Item("n", new java.math.BigDecimal("10.0"), null, null, List.of());
    Metrics.Item b = new Metrics.Item("n", new java.math.BigDecimal("1E+1"), null, null, List.of());

    assertThat(a).isEqualTo(b);
    assertThat(a.value().toString()).isEqualTo("10");
  }

  @Test
  void readsIntegersWrittenWithAZeroFractionAsTheSchemaDoes() {
    ObjectNode json = (ObjectNode) FactJson.parse(SampleDocument.fixture());
    json.put("schemaVersion", 1.0);
    ((ObjectNode) json.get("source")).put("snapshot", 3.0);

    assertThat(DocumentJson.fromJson(json)).isEqualTo(SampleDocument.create());
    ((ObjectNode) json.get("source")).put("snapshot", 3.5);
    assertThatThrownBy(() -> DocumentJson.fromJson(json))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void keepsLevelsInOrderAndLeavesThemOutWhenAllApply() {
    Document document = SampleDocument.create();
    JsonNode json = DocumentJson.toJson(document);

    assertThat(json.at("/sections/0/levels").toString()).isEqualTo("[\"L0\",\"L1\"]");
    assertThat(json.at("/sections/0/sections/0").has("levels")).isFalse();
  }

  private static Document document(List<DocumentEntity> entities, Section section) {
    return new Document(
        "d",
        "D",
        null,
        new TemplateRef("t", "1"),
        new DocumentSource(1, 0, "dev"),
        entities,
        List.of(),
        List.of(section));
  }

  private static Section section(Block block) {
    return new Section("s", "S", Set.of(), null, List.of(), List.of(block), List.of());
  }

  private static Section emptySection() {
    return new Section("s", "S", Set.of(), null, List.of(), List.of(), List.of());
  }

  private static DocumentEntity entity(EntityId id, EntityId parent) {
    return new DocumentEntity(id, id.key(), parent, List.of(), List.of());
  }

  private static Diagram.Node node(EntityId id, EntityId parent) {
    return new Diagram.Node(id, id.key(), null, parent, null, null, List.of());
  }

  private static Diagram diagram(List<Diagram.Node> nodes, List<Diagram.Edge> edges) {
    return new Diagram("d", Diagram.Kind.DEPENDENCY, null, nodes, edges, List.of());
  }
}

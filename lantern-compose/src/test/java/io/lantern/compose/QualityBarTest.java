package io.lantern.compose;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.document.Block;
import io.lantern.core.document.Callout;
import io.lantern.core.document.CrossReference;
import io.lantern.core.document.Diagram;
import io.lantern.core.document.Document;
import io.lantern.core.document.DocumentEntity;
import io.lantern.core.document.DocumentSource;
import io.lantern.core.document.Inline;
import io.lantern.core.document.Paragraph;
import io.lantern.core.document.QualityReport;
import io.lantern.core.document.Section;
import io.lantern.core.document.SequenceDiagram;
import io.lantern.core.document.Table;
import io.lantern.core.document.TemplateRef;
import io.lantern.core.document.TextRun;
import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Validity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class QualityBarTest {

  private static final EntityId SYSTEM = EntityId.parse("system:snapshot");
  private static final EntityId SHOP = EntityId.parse("service:com.example:shop");
  private static final EntityId PAY = EntityId.parse("service:com.example:pay");
  private static final EntityId DB = EntityId.parse("database:db:5432/shop");
  private static final EntityId PACKAGE = EntityId.parse("package:com.example:app:com.example");

  private static final Fact SHOP_UNIT = fact("service:com.example:shop", "deployable-unit", null);
  private static final Fact PAY_UNIT = fact("service:com.example:pay", "deployable-unit", null);
  private static final Fact CALL = fact("service:com.example:shop", "CALLS_HTTP", null);
  private static final Fact AUDIT = fact("service:com.example:shop", "PUBLISHES", "audit");

  private static Fact fact(String subject, String predicate, String profile) {
    ObjectNode attributes = FactJson.newObject();
    if (profile != null) {
      attributes.put("profile", profile);
    }
    return TestFacts.fact(
        "shop",
        subject,
        predicate,
        FactObject.string(predicate + (profile == null ? "" : profile)),
        attributes,
        Validity.from(1));
  }

  private static Diagram.Node node(EntityId id, String label, String technology, Fact... facts) {
    return new Diagram.Node(
        id,
        label,
        technology,
        id.equals(SYSTEM) ? null : SYSTEM,
        null,
        null,
        List.of(facts).stream().map(Fact::id).toList());
  }

  private static Diagram.Edge edge(
      String id, EntityId from, EntityId to, String label, String technology, Fact... facts) {
    return new Diagram.Edge(
        id,
        from,
        to,
        label,
        technology,
        null,
        List.of(),
        List.of(facts).stream().map(Fact::id).toList());
  }

  /** A container diagram that passes every diagram check. */
  private static Diagram goodContainers(String id) {
    return new Diagram(
        id,
        Diagram.Kind.C4_CONTAINER,
        "Containers of Shop",
        List.of(
            node(SYSTEM, "Shop", null),
            node(SHOP, "shop", "Spring Boot", SHOP_UNIT),
            node(PAY, "pay", "Spring Boot", PAY_UNIT)),
        List.of(edge("e1", SHOP, PAY, "Takes payment", "JSON/HTTP", CALL)),
        List.of());
  }

  private static Document document(String template, String audience, Section... sections) {
    List<Fact> facts = List.of(SHOP_UNIT, PAY_UNIT, CALL, AUDIT);
    Map<EntityId, DocumentEntity> entities = new TreeMap<>(Knowledge.BY_ID);
    List<EntityId> ids = new ArrayList<>(List.of(SYSTEM, SHOP, PAY, DB, PACKAGE));
    for (int i = 0; i < 13; i++) {
      ids.add(EntityId.parse("topic:kafka:t" + i));
    }
    for (EntityId id : ids) {
      entities.put(id, new DocumentEntity(id, id.key(), null, List.of(), List.of()));
    }
    return new Document(
        "doc",
        "Shop",
        audience,
        new TemplateRef(template, "1"),
        new DocumentSource(1, 1, "test"),
        List.copyOf(entities.values()),
        facts,
        List.of(sections));
  }

  private static Section section(String id, String title, Block... blocks) {
    return new Section(id, title, Set.of(), null, List.of(), List.of(blocks), List.of());
  }

  private static List<String> checks(Document document) {
    return QualityBar.failures(document).stream().map(QualityReport.Failure::check).toList();
  }

  private static List<String> failed(Document document, String check) {
    List<String> result = new ArrayList<>();
    for (QualityReport.Failure failure : QualityBar.failures(document)) {
      if (failure.check().equals(check)) {
        result.add(
            (failure.target() == null ? "document" : failure.target().id())
                + ": "
                + text(failure.detail()));
      }
    }
    return result;
  }

  private static String text(List<Inline> content) {
    StringBuilder text = new StringBuilder();
    content.forEach(i -> text.append(((TextRun) i).text()));
    return text.toString();
  }

  @Test
  void aGoodDiagramFailsOnlyTheDocumentsOwnGaps() {
    Document document =
        document(
            "c4-model-sequence-diagrams",
            "architect",
            section("containers", "Containers", goodContainers("d")));

    // The legend has no dashed boundary, its protocol is an acronym and there is no glossary.
    assertThat(checks(document))
        .containsExactly(QualityBar.LEGEND, QualityBar.ACRONYMS, QualityBar.PINNED);
    assertThat(failed(document, QualityBar.LEGEND))
        .containsExactly("d: The template's legend has no entry for the dashed boundary.");
  }

  @Test
  void aDocumentWithoutAnAudienceDoesNotStateItsReader() {
    Document document =
        document("c4-model-sequence-diagrams", null, section("s", "S", goodContainers("d")));

    assertThat(failed(document, QualityBar.READER)).hasSize(1);
  }

  @Test
  void lanternsOwnTemplatesDrawNoLegend() {
    Document document = document("other", "a", section("s", "S", goodContainers("d")));

    assertThat(failed(document, QualityBar.LEGEND))
        .containsExactly("d: The template draws no legend.");
  }

  @Test
  void checksEveryElementAndLineOfADiagram() {
    List<Diagram.Node> nodes = new ArrayList<>();
    nodes.add(node(SYSTEM, "Shop", null));
    nodes.add(node(SHOP, "shop", null, SHOP_UNIT));
    nodes.add(node(DB, "shop on db", "PostgreSQL"));
    nodes.add(node(PACKAGE, "com.example", null, SHOP_UNIT));
    for (int i = 0; i < 13; i++) {
      EntityId topic = EntityId.parse("topic:kafka:t" + i);
      nodes.add(node(topic, "t" + i, "Kafka", SHOP_UNIT));
    }
    Diagram diagram =
        new Diagram(
            "d",
            Diagram.Kind.C4_CONTAINER,
            "Everything",
            nodes,
            List.of(
                edge("e1", SHOP, DB, "Connects to", null),
                new Diagram.Edge(
                    "e2",
                    SHOP,
                    EntityId.parse("topic:kafka:t0"),
                    "Sends audit records",
                    "Kafka",
                    null,
                    List.of(),
                    List.of(AUDIT.id()))),
            List.of());
    List<Section> sections = new ArrayList<>();
    sections.add(section("s", "S", diagram));
    Document document =
        document("c4-model-sequence-diagrams", "a", sections.toArray(Section[]::new));

    assertThat(failed(document, QualityBar.TITLE))
        .containsExactly("d: \"Everything\" does not name the diagram's type.");
    assertThat(failed(document, QualityBar.ONE_LEVEL))
        .containsExactly("d: It also shows com.example (package).");
    assertThat(failed(document, QualityBar.ELEMENTS)).containsExactly("d: It shows 16 elements.");
    assertThat(failed(document, QualityBar.TECHNOLOGY))
        .containsExactly("d: No technology for com.example and shop.");
    assertThat(failed(document, QualityBar.LABELLED))
        .containsExactly(
            "d: Labelled only with a generic verb or not at all: shop to shop on db"
                + " (\"Connects to\").");
    assertThat(failed(document, QualityBar.LINE_FACTS))
        .containsExactly("d: No facts behind shop to shop on db.");
    assertThat(failed(document, QualityBar.CONDITIONAL))
        .containsExactly("d: Holds only under a profile it does not name: shop to t0 (audit).");
    assertThat(failed(document, QualityBar.PROTOCOL))
        .containsExactly("d: No protocol on shop to shop on db.");
    // The database rests on no fact, not even through a line.
    assertThat(failed(document, QualityBar.CLAIMS)).containsExactly("d: No fact for shop on db.");
  }

  @Test
  void aFailureNamesTenElementsAndCountsTheRest() {
    List<Table.Row> rows = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      rows.add(new Table.Row(null, List.of(List.of(TextRun.of("r" + i))), List.of()));
    }
    Table table = new Table("t", "Rows", List.of(new Table.Column("Name")), rows, List.of());
    Document document = document("c4-model-sequence-diagrams", "a", section("s", "S", table));

    assertThat(failed(document, QualityBar.CLAIMS))
        .containsExactly("t: No fact for r0, r1, r2, r3, r4, r5, r6, r7, r8, r9 and 2 more.");
  }

  @Test
  void aDiagramRepeatedInAnotherSectionFails() {
    Document document =
        document(
            "c4-model-sequence-diagrams",
            "a",
            section("a", "A", goodContainers("d1")),
            section("b", "B", goodContainers("d2")));

    assertThat(failed(document, QualityBar.REPEATED))
        .containsExactly("d2: It shows what \"Containers of Shop\" does.");
  }

  @Test
  void acronymsNeedAGlossary() {
    Paragraph text =
        new Paragraph(
            "p",
            List.of(
                TextRun.of("Speaks AMQP and JSON over HTTP, GET only. "),
                new TextRun("SQL", TextRun.Style.CODE),
                new io.lantern.core.document.Citation(List.of(CALL.id()))),
            List.of());
    Document without = document("c4-model-sequence-diagrams", "a", section("s", "S", text));
    Document with =
        document(
            "c4-model-sequence-diagrams",
            "a",
            section("s", "S", text),
            section(
                "glossary",
                "Glossary",
                new Paragraph(
                    "g",
                    List.of(TextRun.of("AMQP: a messaging protocol. JSON: a data format.")),
                    List.of())));

    assertThat(failed(without, QualityBar.ACRONYMS))
        .containsExactly("document: The document has no glossary; it uses AMQP, HTTP and JSON.");
    assertThat(failed(with, QualityBar.ACRONYMS))
        .containsExactly("document: The glossary does not explain HTTP.");
  }

  @Test
  void claimsWithoutFactsFailButNotesAndCountsOfNothingDoNot() {
    Document document =
        document(
            "c4-model-sequence-diagrams",
            "a",
            section(
                "s",
                "S",
                new Paragraph("p", List.of(TextRun.of("It is fast.")), List.of()),
                new Callout(
                    "n",
                    Callout.Variant.NOTE,
                    null,
                    null,
                    List.of(TextRun.of("Who uses it is not known.")),
                    List.of()),
                new Table(
                    "t",
                    "Things",
                    List.of(new Table.Column("Name")),
                    List.of(
                        new Table.Row(null, List.of(List.of(TextRun.of("one"))), List.of()),
                        new Table.Row(
                            null, List.of(List.of(TextRun.of("two"))), List.of(CALL.id()))),
                    List.of())));

    assertThat(failed(document, QualityBar.CLAIMS))
        .containsExactly("p: No fact for its text.", "t: No fact for one.");
  }

  @Test
  void sequenceDiagramsAreCheckedToo() {
    SequenceDiagram.Message inner =
        new SequenceDiagram.Message(
            SHOP, PAY, null, null, null, null, null, List.of(), List.of(), List.of());
    SequenceDiagram.Frame nested =
        new SequenceDiagram.Frame(
            SequenceDiagram.Operator.OPT,
            List.of(
                new SequenceDiagram.Operand(
                    null,
                    List.of(
                        new SequenceDiagram.Frame(
                            SequenceDiagram.Operator.LOOP,
                            List.of(new SequenceDiagram.Operand(null, List.of(inner), List.of())),
                            List.of())),
                    List.of())),
            List.of());
    SequenceDiagram flow =
        new SequenceDiagram(
            "f",
            null,
            List.of(
                new SequenceDiagram.Participant(SHOP, null, null, null, List.of()),
                new SequenceDiagram.Participant(PAY, null, null, null, List.of())),
            List.of(nested),
            List.of());
    Document document = document("c4-model-sequence-diagrams", "a", section("s", "S", flow));

    assertThat(checks(document))
        .contains(
            QualityBar.TITLE, QualityBar.FRAGMENTS, QualityBar.LABELLED, QualityBar.LINE_FACTS);
  }

  @Test
  void theExplorerNeedsItsOwnSections() {
    Document document =
        document(
            QualityBar.EXPLORER,
            "a",
            section(
                "overview",
                "Overview",
                new Paragraph(
                    "p",
                    List.of(
                        TextRun.of("word ".repeat(301)),
                        new io.lantern.core.document.Citation(List.of(CALL.id()))),
                    List.of())),
            section(
                "context",
                "Context",
                new Diagram(
                    "c",
                    Diagram.Kind.C4_CONTEXT,
                    "System context of Shop",
                    List.of(new Diagram.Node(SYSTEM, "Shop", null, null, null, null, List.of())),
                    List.of(),
                    List.of())),
            section("containers", "Containers", goodContainers("d")));

    assertThat(QualityBar.failures(document))
        .extracting(QualityReport.Failure::check, QualityReport.Failure::target)
        .contains(
            org.assertj.core.groups.Tuple.tuple(
                QualityBar.ORIENTATION, CrossReference.Target.section("overview")),
            org.assertj.core.groups.Tuple.tuple(
                QualityBar.START_HERE, CrossReference.Target.section("overview")),
            org.assertj.core.groups.Tuple.tuple(
                QualityBar.CONTAINER_FIRST, CrossReference.Target.block("c")),
            org.assertj.core.groups.Tuple.tuple(QualityBar.FLOWS, null),
            org.assertj.core.groups.Tuple.tuple(QualityBar.CATALOG, null),
            org.assertj.core.groups.Tuple.tuple(QualityBar.DECISIONS, null));
  }

  @Test
  void theBriefsRisksAreRankedByImpact() {
    Callout low =
        new Callout(
            "r1",
            Callout.Variant.RISK,
            Callout.Severity.LOW,
            "Small",
            List.of(TextRun.of("x")),
            List.of(CALL.id()));
    Callout high =
        new Callout(
            "r2",
            Callout.Variant.RISK,
            Callout.Severity.HIGH,
            "Big",
            List.of(TextRun.of("y")),
            List.of(CALL.id()));
    Document document = document(QualityBar.BRIEF, "a", section("risks", "Risks", low, high));

    assertThat(failed(document, QualityBar.RISKS))
        .containsExactly("risks: The risks are not in order of impact.");
    assertThat(checks(document))
        .contains(QualityBar.BLAST, QualityBar.CURRENCY, QualityBar.EVIDENCE)
        .doesNotContain(QualityBar.CATALOG);
  }
}

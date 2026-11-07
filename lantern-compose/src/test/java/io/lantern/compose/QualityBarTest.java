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
import io.lantern.derive.Knowledge;
import java.math.BigDecimal;
import java.time.LocalDate;
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

  private static final EntityId SALES = EntityId.parse("group:system/domain/sales");

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
    List<EntityId> ids = new ArrayList<>(List.of(SYSTEM, SHOP, PAY, DB, PACKAGE, SALES));
    for (int i = 0; i < DiagramLimits.DEFAULT.mostElements() - 2; i++) {
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
        new DocumentSource(1, 1, "test", Map.of("r", "a".repeat(40)), LocalDate.of(2026, 1, 1)),
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

    // Its protocol is an acronym and there is no glossary; the legend has the dashed boundary.
    assertThat(checks(document)).containsExactly(QualityBar.ACRONYMS);
  }

  @Test
  void theRolesTheCodeChecksAreNotAcronyms() {
    ObjectNode attributes = FactJson.newObject();
    attributes.put("name", "ADMIN");
    Fact role =
        TestFacts.fact(
            "shop",
            "type:com.example.AdminController",
            "person-evidence",
            FactObject.string("role"),
            attributes,
            Validity.from(1));
    Document plain =
        document(
            "c4-model-sequence-diagrams",
            "a",
            section(
                "s",
                "S",
                new Paragraph("p", List.of(TextRun.of("Only ADMIN deletes users.")), List.of())));
    Document withRole =
        new Document(
            plain.id(),
            plain.title(),
            plain.audience(),
            plain.template(),
            plain.source(),
            plain.entities(),
            java.util.stream.Stream.concat(plain.facts().stream(), java.util.stream.Stream.of(role))
                .toList(),
            plain.sections());

    assertThat(QualityBar.acronymsUsed(plain)).contains("ADMIN");
    assertThat(QualityBar.acronymsUsed(withRole)).doesNotContain("ADMIN");
  }

  @Test
  void theReportSpecsLegendHasTheAsyncArrowhead() {
    SequenceDiagram flow =
        new SequenceDiagram(
            "f",
            "Shop takes payment",
            List.of(
                new SequenceDiagram.Participant(SHOP, "shop", null, null, List.of()),
                new SequenceDiagram.Participant(PAY, "pay", null, null, List.of())),
            List.of(
                new SequenceDiagram.Message(
                    SHOP,
                    PAY,
                    "Takes payment",
                    null,
                    SequenceDiagram.MessageKind.ASYNC,
                    null,
                    null,
                    List.of(),
                    List.of(),
                    List.of(CALL.id()))),
            List.of());
    Document document =
        document("c4-model-sequence-diagrams", "a", section("s", "Main flows", flow));

    assertThat(failed(document, QualityBar.LEGEND)).isEmpty();
  }

  @Test
  void aDocumentWithoutAnAudienceDoesNotStateItsReader() {
    Document document =
        document("c4-model-sequence-diagrams", null, section("s", "S", goodContainers("d")));

    assertThat(failed(document, QualityBar.READER)).hasSize(1);
  }

  @Test
  void otherTemplatesDrawNoLegend() {
    Document document = document("other", "a", section("s", "S", goodContainers("d")));

    assertThat(failed(document, QualityBar.LEGEND))
        .containsExactly("d: The template draws no legend.");
  }

  @Test
  void lanternsOwnTemplatesDrawALegendButNotOfColors() {
    Diagram good = goodContainers("d");
    List<Diagram.Node> nodes = new ArrayList<>(good.nodes());
    Diagram.Node shop = nodes.get(1);
    nodes.set(
        1,
        new Diagram.Node(
            shop.id(),
            shop.label(),
            shop.technology(),
            shop.parent(),
            new Diagram.Style(null, null, "#ff0000"),
            null,
            shop.facts()));
    Diagram colored =
        new Diagram("c", good.diagramKind(), good.title(), nodes, good.edges(), good.facts());
    SequenceDiagram flow =
        new SequenceDiagram(
            "f",
            "Shop takes payment",
            List.of(
                new SequenceDiagram.Participant(SHOP, "shop", null, null, List.of()),
                new SequenceDiagram.Participant(PAY, "pay", null, null, List.of())),
            List.of(
                new SequenceDiagram.Message(
                    SHOP,
                    PAY,
                    "Takes payment",
                    null,
                    SequenceDiagram.MessageKind.ASYNC,
                    null,
                    null,
                    List.of(),
                    List.of(),
                    List.of(CALL.id()))),
            List.of());

    for (String template : List.of(QualityBar.EXPLORER, QualityBar.BRIEF)) {
      Document document =
          document(template, "a", section("s", "S", good), section("t", "T", colored, flow));

      assertThat(failed(document, QualityBar.LEGEND))
          .as(template)
          .containsExactly("c: The template's legend does not say what the colors mean.");
    }
  }

  @Test
  void aGlossaryDefinesWordsSoItNeedsNoFacts() {
    Document document =
        document(
            "c4-model-sequence-diagrams",
            "a",
            Glossary.BUNDLED.section("glossary", new java.util.TreeSet<>(Set.of("HTTP", "FOO"))));

    assertThat(failed(document, QualityBar.CLAIMS)).isEmpty();
    assertThat(failed(document, QualityBar.ACRONYMS)).isEmpty();
  }

  @Test
  void checksEveryElementAndLineOfADiagram() {
    List<Diagram.Node> nodes = new ArrayList<>();
    nodes.add(node(SYSTEM, "Shop", null));
    nodes.add(node(SHOP, "shop", null, SHOP_UNIT));
    nodes.add(node(DB, "shop on db", "PostgreSQL"));
    nodes.add(node(PACKAGE, "com.example", null, SHOP_UNIT));
    for (int i = 0; i < DiagramLimits.DEFAULT.mostElements() - 2; i++) {
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
    assertThat(failed(document, QualityBar.elements(DiagramLimits.DEFAULT)))
        .containsExactly(
            "d: It shows " + (DiagramLimits.DEFAULT.mostElements() + 1) + " elements.");
    // What a box or line lacks is asked of the model, not of the diagram (UnknownsTest).
    assertThat(failed(document, QualityBar.TECHNOLOGY)).isEmpty();
    assertThat(failed(document, QualityBar.LABELLED))
        .containsExactly(
            "d: Labelled only with a generic verb or not at all: shop to shop on db"
                + " (\"Connects to\").");
    assertThat(failed(document, QualityBar.LINE_FACTS))
        .containsExactly("d: No facts behind shop to shop on db.");
    assertThat(failed(document, QualityBar.CONDITIONAL))
        .containsExactly("d: Holds only under a profile it does not name: shop to t0 (audit).");
    assertThat(failed(document, QualityBar.PROTOCOL)).isEmpty();
    // The database rests on no fact, not even through a line.
    assertThat(failed(document, QualityBar.CLAIMS)).containsExactly("d: No fact for shop on db.");
  }

  @Test
  void aDiagramWithMoreLinesPerElementThanTheThresholdIsNotReadable() {
    List<Diagram.Edge> edges = new ArrayList<>();
    for (int i = 0; i <= DiagramLimits.DEFAULT.linesPerElement() * 2; i++) {
      edges.add(
          edge("e" + i, i % 2 == 0 ? SHOP : PAY, i % 2 == 0 ? PAY : SHOP, "Asks " + i, "HTTP"));
    }
    Diagram diagram =
        new Diagram(
            "d",
            Diagram.Kind.C4_CONTAINER,
            "Containers of Shop",
            List.of(
                node(SYSTEM, "Shop", null), node(SHOP, "shop", "Java"), node(PAY, "pay", "Java")),
            edges,
            List.of());
    Document document = document("c4-model-sequence-diagrams", "a", section("s", "S", diagram));

    assertThat(failed(document, QualityBar.elements(DiagramLimits.DEFAULT)))
        .containsExactly("d: It draws 5 lines between 2 elements.");
  }

  @Test
  void aDiagramsSizeIsJudgedByTheLimitsTheComposerIsGiven() {
    // Two elements and five lines: too many lines by default, within looser limits, and too many
    // elements by tighter ones (#836).
    List<Diagram.Edge> edges = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      edges.add(
          edge("e" + i, i % 2 == 0 ? SHOP : PAY, i % 2 == 0 ? PAY : SHOP, "Asks " + i, "HTTP"));
    }
    Diagram diagram =
        new Diagram(
            "d",
            Diagram.Kind.C4_CONTAINER,
            "Containers of Shop",
            List.of(
                node(SYSTEM, "Shop", null), node(SHOP, "shop", "Java"), node(PAY, "pay", "Java")),
            edges,
            List.of());
    Document document = document("c4-model-sequence-diagrams", "a", section("s", "S", diagram));
    DiagramLimits loose = new DiagramLimits(2, 0, 3, 0.8, 5, 2);
    DiagramLimits tight = new DiagramLimits(1, 0, 3, 0.8, 5, 2);

    assertThat(sizeFailures(document, DiagramLimits.DEFAULT))
        .containsExactly(
            "Size: at most "
                + DiagramLimits.DEFAULT.mostElements()
                + " elements and 2 lines per element: It draws 5 lines between 2 elements.");
    assertThat(sizeFailures(document, loose)).isEmpty();
    assertThat(sizeFailures(document, tight))
        .containsExactly("Size: at most 1 elements and 3 lines per element: It shows 2 elements.");
  }

  private static List<String> sizeFailures(Document document, DiagramLimits limits) {
    return QualityBar.failures(document, io.lantern.derive.Vocabularies.standard(), limits).stream()
        .filter(f -> f.check().startsWith("Size:"))
        .map(f -> f.check() + ": " + text(f.detail()))
        .toList();
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
  void aDomainBoxWithNoPageAboutItFails() {
    EntityId domain = SALES;
    Diagram withDomain =
        new Diagram(
            "d",
            Diagram.Kind.C4_CONTAINER,
            "Containers of Shop",
            List.of(
                node(SYSTEM, "Shop", null),
                node(SHOP, "shop", "Spring Boot", SHOP_UNIT),
                node(domain, "Sales", null)),
            List.of(edge("e1", SHOP, domain, "Takes payment", "JSON/HTTP", CALL)),
            List.of());
    Section about =
        new Section("sales", "Sales", Set.of(), domain, List.of(), List.of(), List.of());

    assertThat(
            failed(
                document("c4-model-sequence-diagrams", "a", section("s", "S", withDomain)),
                QualityBar.BOX_PAGE))
        .containsExactly("d: Sales has no page of its own.");
    assertThat(
            failed(
                document("c4-model-sequence-diagrams", "a", section("s", "S", withDomain), about),
                QualityBar.BOX_PAGE))
        .isEmpty();
  }

  private static Diagram containers(String shopName, String label, String payName, String payTech) {
    return new Diagram(
        "d",
        Diagram.Kind.C4_CONTAINER,
        "Containers of Shop",
        List.of(
            node(SYSTEM, "Shop", null),
            node(SHOP, shopName, "Spring Boot", SHOP_UNIT),
            node(PAY, payName, payTech, PAY_UNIT)),
        List.of(edge("e1", SHOP, PAY, label, "JSON/HTTP", CALL)),
        List.of());
  }

  private List<String> failedOn(Diagram diagram, String check) {
    return failed(document("c4-model-sequence-diagrams", "a", section("s", "S", diagram)), check);
  }

  @Test
  void aLabelListingRoutesFails() {
    assertThat(
            failedOn(
                containers("shop", "Calls GET /orders, POST /charges and 3 more", "pay", "Java"),
                QualityBar.PURPOSE))
        .containsExactly(
            "d: Labelled with routes: shop to pay (\"Calls GET /orders, POST /charges and 3"
                + " more\").");
    assertThat(failedOn(containers("shop", "Takes payment", "pay", "Java"), QualityBar.PURPOSE))
        .isEmpty();
  }

  @Test
  void aLabelNamingTheBoxItPointsToFails() {
    assertThat(
            failedOn(
                containers("shop", "Keeps payments in PostgreSQL", "pay", "PostgreSQL 17"),
                QualityBar.END_NAMED))
        .containsExactly(
            "d: Labels naming the box they point to: shop to pay (\"Keeps payments in"
                + " PostgreSQL\").");
    assertThat(
            failedOn(
                containers("shop", "Takes payment through the pay service", "pay", "Java"),
                QualityBar.END_NAMED))
        .hasSize(1);
    assertThat(
            failedOn(
                containers("shop", "Keeps payments", "pay", "PostgreSQL"), QualityBar.END_NAMED))
        .isEmpty();
  }

  @Test
  void aServiceNamedOnlyAsBuiltFails() {
    // A build's name of words reads as words in the report; one the report cannot read fails.
    assertThat(
            failedOn(
                containers("com.example.shop", "Takes payment", "pay-service", "Java"),
                QualityBar.READABLE))
        .containsExactly("d: Named only as built: com.example.shop.");
  }

  @Test
  void aBoxOrPageNameOverAboutFortyCharactersFails() {
    String longName = "workorder (billing.datasource.workorders.jdbc-url)";
    Diagram withLongBox =
        new Diagram(
            "d",
            Diagram.Kind.C4_CONTAINER,
            "Containers of Shop",
            List.of(node(SYSTEM, "Shop", null), node(SHOP, longName, "Spring Boot", SHOP_UNIT)),
            List.of(),
            List.of());
    Section longPage =
        new Section(
            "p",
            "How a customer places, pays for and tracks an order",
            Set.of(),
            null,
            List.of(),
            List.of(),
            List.of());

    assertThat(
            failed(
                document(
                    "c4-model-sequence-diagrams", "a", section("s", "S", withLongBox), longPage),
                QualityBar.SHORT_NAMES))
        .containsExactly(
            "document: How a customer places, pays for and tracks an order and " + longName + ".");
    assertThat(
            failed(
                document("c4-model-sequence-diagrams", "a", section("s", "S", goodContainers("d"))),
                QualityBar.SHORT_NAMES))
        .isEmpty();
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
  void theExplorersStartHereAndCatalogMustListSomething() {
    Callout nothing =
        new Callout("n", Callout.Variant.NOTE, null, null, List.of(TextRun.of("None.")), List.of());
    Document document =
        document(
            QualityBar.EXPLORER,
            "a",
            section(
                "overview", "Overview", new Paragraph("p", List.of(TextRun.of("Hi.")), List.of())),
            section("start_here", "Start here", nothing),
            section(
                "catalog",
                "Catalog",
                new Callout(
                    "m",
                    Callout.Variant.NOTE,
                    null,
                    null,
                    List.of(TextRun.of("None.")),
                    List.of())));

    assertThat(QualityBar.failures(document))
        .extracting(QualityReport.Failure::check, QualityReport.Failure::target)
        .contains(
            org.assertj.core.groups.Tuple.tuple(
                QualityBar.START_HERE, CrossReference.Target.section("start_here")),
            org.assertj.core.groups.Tuple.tuple(
                QualityBar.CATALOG, CrossReference.Target.section("catalog")));
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
        .containsExactly(
            "risks: No consequence or confidence for Small and Big.",
            "risks: The risks are not in order of impact.");
    assertThat(checks(document))
        .contains(QualityBar.BLAST, QualityBar.CURRENCY, QualityBar.EVIDENCE)
        .doesNotContain(QualityBar.CATALOG);
  }

  @Test
  void theBriefPassesWithItsSectionsAndExplainedRisksInOrder() {
    Callout high =
        new Callout(
            "r1",
            Callout.Variant.RISK,
            Callout.Severity.HIGH,
            "Big",
            List.of(TextRun.of("y")),
            List.of(CALL.id()),
            "It breaks.",
            BigDecimal.ONE);
    Callout low =
        new Callout(
            "r2",
            Callout.Variant.RISK,
            Callout.Severity.LOW,
            "Small",
            List.of(TextRun.of("x")),
            List.of(CALL.id()),
            "It bends.",
            new BigDecimal("0.5"));
    Document document =
        document(
            QualityBar.BRIEF,
            "a",
            section("risks", "Risks", high, low),
            section("blast_radius", "Blast radius"),
            section("technology_currency", "Technology currency"),
            section("quality_evidence", "Quality evidence"));

    assertThat(checks(document))
        .doesNotContain(
            QualityBar.RISKS, QualityBar.BLAST, QualityBar.CURRENCY, QualityBar.EVIDENCE);
  }
}

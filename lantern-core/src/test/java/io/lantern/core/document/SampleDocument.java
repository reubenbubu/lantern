package io.lantern.core.document;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * A document that uses every element and every optional field of the document model. Its JSON form
 * is the fixture {@code schema/document/valid-document.json}, which the schema tests in lantern-web
 * also validate.
 */
final class SampleDocument {

  static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
  static final String CONTENT_HASH =
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

  static final EntityId ORDERS = EntityId.parse("service:orders");
  static final EntityId LIB = EntityId.parse("library:orders-lib");
  static final EntityId DB = EntityId.parse("database:orders-db");

  static final Fact BUILDS =
      Fact.create(
          ORDERS,
          "CONTAINS",
          FactObject.entity(LIB),
          FactJson.newObject(),
          new Origin(Origin.Kind.STATIC, "maven", "1"),
          new Evidence("orders", COMMIT, "pom.xml", new Evidence.LineRange(10, 14), CONTENT_HASH),
          1.0,
          Validity.from(1));
  static final Fact READS =
      Fact.create(
          LIB,
          "READS_TABLE",
          FactObject.entity(DB),
          FactJson.newObject(),
          new Origin(Origin.Kind.LLM, "resolver", null),
          new Evidence("orders", COMMIT, null, null, null),
          0.75,
          Validity.from(1));

  private SampleDocument() {}

  static List<String> facts(Fact... facts) {
    return Arrays.stream(facts).map(Fact::id).toList();
  }

  static Document create() {
    Paragraph intro =
        new Paragraph(
            "overview-intro",
            List.of(
                TextRun.of("The "),
                new TextRun("orders", TextRun.Style.STRONG),
                new TextRun(" service ", null),
                new CrossReference("orders", CrossReference.Target.entity(ORDERS)),
                TextRun.of(" packages its library."),
                new Citation(facts(BUILDS)),
                new CrossReference("orders-lib", CrossReference.Target.entity(LIB), true)),
            facts(BUILDS));
    Metrics headline =
        new Metrics(
            "headline",
            List.of(
                new Metrics.Item("Services", BigDecimal.ONE, null, null, List.of()),
                new Metrics.Item("Coverage", new BigDecimal("72.5"), "%", ORDERS, facts(BUILDS))),
            List.of());
    Callout risk =
        new Callout(
            "risk-db",
            Callout.Variant.RISK,
            Callout.Severity.HIGH,
            "Shared database",
            List.of(new TextRun("orders-db", TextRun.Style.CODE), TextRun.of(" is shared.")),
            facts(READS));
    ListBlock highlights =
        new ListBlock(
            "highlights",
            false,
            List.of(
                new ListBlock.Item(List.of(TextRun.of("One deployable.")), facts(BUILDS)),
                new ListBlock.Item(
                    List.of(new TextRun("Reads one database.", TextRun.Style.EMPHASIS)),
                    List.of())),
            List.of());

    Diagram containers =
        new Diagram(
            "containers",
            Diagram.Kind.C4_CONTAINER,
            "Containers",
            List.of(
                new Diagram.Node(
                    ORDERS,
                    "orders",
                    null,
                    null,
                    new Diagram.Style("box", "server", "#1f77b4"),
                    new Diagram.Position(0, -40, true),
                    List.of()),
                new Diagram.Node(
                    LIB,
                    "orders-lib",
                    "Java, Spring Boot",
                    ORDERS,
                    new Diagram.Style("box", null, null),
                    new Diagram.Position(20, 10, false),
                    facts(BUILDS)),
                new Diagram.Node(
                    DB,
                    "orders-db",
                    null,
                    null,
                    new Diagram.Style(null, "database", "#ff7f0e"),
                    null,
                    List.of())),
            List.of(
                new Diagram.Edge(
                    "orders-lib-reads-db", LIB, DB, "reads", "READS_TABLE", facts(READS)),
                new Diagram.Edge("orders-to-db", ORDERS, DB, null, null, List.of())),
            facts(BUILDS, READS));
    Table dependencies =
        new Table(
            "dependencies",
            "Dependencies",
            List.of(new Table.Column("From"), new Table.Column("To")),
            List.of(
                new Table.Row(
                    LIB,
                    List.of(
                        List.of(
                            new CrossReference("orders-lib", CrossReference.Target.entity(LIB))),
                        List.of(TextRun.of("orders-db"))),
                    facts(READS)),
                new Table.Row(null, List.of(List.of(TextRun.of("orders")), List.of()), List.of())),
            List.of());
    Tree modules =
        new Tree(
            "modules",
            "Modules",
            List.of(
                new Tree.Node(
                    "orders",
                    ORDERS,
                    List.of(),
                    List.of(
                        new Tree.Node("orders-lib", LIB, facts(BUILDS), List.of()),
                        new Tree.Node("README.md", null, List.of(), List.of())))),
            facts(BUILDS));
    Tree emptyTree = new Tree("no-modules", null, List.of(), List.of());
    Table emptyTable =
        new Table("no-rows", null, List.of(new Table.Column("Name")), List.of(), List.of());
    Diagram dbOnly =
        new Diagram(
            "db-only",
            Diagram.Kind.DEPENDENCY,
            null,
            List.of(new Diagram.Node(DB, "orders-db", null, null, null, null, List.of())),
            List.of(),
            List.of());
    Paragraph seeAlso =
        new Paragraph(
            "see-also",
            List.of(
                TextRun.of("See "),
                new CrossReference("the overview", CrossReference.Target.section("overview")),
                TextRun.of(" and "),
                new CrossReference("the diagram", CrossReference.Target.block("containers")),
                TextRun.of(".")),
            List.of());
    Callout note =
        new Callout(
            "note-1", Callout.Variant.NOTE, null, null, List.of(TextRun.of("Draft.")), List.of());

    SequenceDiagram flow =
        new SequenceDiagram(
            "order-flow",
            "Place an order",
            List.of(
                new SequenceDiagram.Participant(
                    ORDERS,
                    "Orders",
                    "Spring Boot",
                    new Diagram.Style("box", "server", "#1f77b4"),
                    facts(BUILDS)),
                new SequenceDiagram.Participant(LIB, "orders-lib", null, null, List.of()),
                new SequenceDiagram.Participant(DB, null, null, null, List.of())),
            List.of(
                new SequenceDiagram.Message(
                    ORDERS,
                    LIB,
                    "Create order",
                    "POST /orders",
                    SequenceDiagram.MessageKind.SYNC,
                    true,
                    false,
                    List.of("prod", "staging"),
                    List.of(TextRun.of("Validates the order first."), new Citation(facts(BUILDS))),
                    facts(BUILDS)),
                new SequenceDiagram.Frame(
                    SequenceDiagram.Operator.ALT,
                    List.of(
                        new SequenceDiagram.Operand(
                            "in stock",
                            List.of(
                                message(LIB, LIB, "Check stock", null),
                                message(LIB, DB, "Save order", "INSERT orders")),
                            facts(READS)),
                        new SequenceDiagram.Operand(
                            null,
                            List.of(
                                new SequenceDiagram.Note(
                                    List.of(LIB, DB),
                                    List.of(TextRun.of("Nothing is saved.")),
                                    facts(READS))),
                            List.of())),
                    facts(READS)),
                new SequenceDiagram.Message(
                    LIB,
                    ORDERS,
                    "Order ID",
                    null,
                    SequenceDiagram.MessageKind.RETURN,
                    null,
                    null,
                    List.of(),
                    List.of(),
                    List.of()),
                new SequenceDiagram.Message(
                    ORDERS,
                    null,
                    "Order placed",
                    null,
                    SequenceDiagram.MessageKind.ASYNC,
                    null,
                    null,
                    List.of(),
                    List.of(),
                    List.of()),
                message(null, null, null, null),
                new SequenceDiagram.Note(
                    List.of(), List.of(TextRun.of("The rest happens later.")), List.of())),
            facts(BUILDS, READS));
    SequenceDiagram emptyFlow =
        new SequenceDiagram("no-flow", null, List.of(), List.of(), List.of());
    QualityReport quality =
        new QualityReport(
            "quality",
            "Quality report",
            List.of(
                new QualityReport.Failure(
                    "Every line is labelled with an intent phrase",
                    CrossReference.Target.block("containers"),
                    List.of(TextRun.of("The line from orders to orders-db has no label.")),
                    facts(READS)),
                new QualityReport.Failure(
                    "Every element has a responsibility sentence",
                    CrossReference.Target.entity(DB),
                    List.of(),
                    List.of()),
                new QualityReport.Failure("It states its reader", null, List.of(), List.of())),
            facts(BUILDS));
    QualityReport passed = new QualityReport("quality-passed", null, List.of(), List.of());
    Section flows =
        new Section(
            "flows",
            "Flows",
            Set.of(),
            null,
            List.of(),
            List.of(flow, emptyFlow, quality, passed),
            List.of());

    Section structure =
        new Section(
            "structure",
            "Structure",
            Set.of(),
            ORDERS,
            List.of(),
            List.of(
                containers, dependencies, modules, emptyTree, seeAlso, note, emptyTable, dbOnly),
            List.of());
    Section overview =
        new Section(
            "overview",
            "Overview",
            Set.of(Level.L1, Level.L0),
            null,
            facts(BUILDS),
            List.of(intro, headline, risk, highlights),
            List.of(structure));

    return new Document(
        "orders-explorer",
        "orders",
        "architect",
        new TemplateRef("architecture-explorer", "1"),
        new DocumentSource(3, 2, "0.1.0-SNAPSHOT"),
        List.of(
            new DocumentEntity(
                ORDERS,
                "orders",
                null,
                List.of(TextRun.of("Takes orders."), new Citation(facts(BUILDS))),
                facts(BUILDS)),
            new DocumentEntity(LIB, "orders-lib", ORDERS, List.of(), facts(BUILDS, READS)),
            new DocumentEntity(DB, "orders-db", null, List.of(), List.of())),
        List.of(READS, BUILDS),
        List.of(overview, flows));
  }

  /** A sync message with only the given details. */
  private static SequenceDiagram.Message message(
      EntityId from, EntityId to, String label, String code) {
    return new SequenceDiagram.Message(
        from, to, label, code, null, null, null, List.of(), List.of(), List.of());
  }

  /** The fixture text: the JSON form of {@link #create()}. */
  static String fixture() {
    return resource("/schema/document/valid-document.json");
  }

  static String resource(String name) {
    try (InputStream in = SampleDocument.class.getResourceAsStream(name)) {
      if (in == null) {
        throw new IllegalStateException("Missing test resource " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}

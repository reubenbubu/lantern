package io.lantern.compose;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.document.Block;
import io.lantern.core.document.Callout;
import io.lantern.core.document.Diagram;
import io.lantern.core.document.Document;
import io.lantern.core.document.DocumentEntity;
import io.lantern.core.document.Inline;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the LLM investigator found (ADR 0082) in every template: the Architecture Explorer's and the
 * C4 report's table of risks and findings, the Executive Brief's risk callouts, and the container
 * diagrams' lines and elements, each marked as found by the LLM.
 */
class InvestigatedFindingsTest {

  private static final TemplateRef TEMPLATE = new TemplateRef("t", "1");
  private static final Snapshot SNAPSHOT =
      new Snapshot(
          2, Map.of("shop", TestFacts.COMMIT, "base", TestFacts.COMMIT), 5, "0.1.0", Instant.EPOCH);
  private static final Origin INVESTIGATOR = new Origin(Origin.Kind.LLM, "investigator", "1");

  private final List<Fact> facts = new ArrayList<>(TestFacts.runtime().list());

  InvestigatedFindingsTest() {
    facts.add(
        investigated(
            "service:com.example:app",
            InvestigatedFindings.RISK,
            FactObject.string("Event published before commit"),
            "{\"finding\": \"It sends the event inside the transaction.\", \"consequence\":"
                + " \"Consumers can see a rolled-back order.\", \"impact\": \"medium\","
                + " \"confidence\": \"high\", \"units\": [\"service:com.example:app\"],"
                + " \"names\": [\"Transactional\"], \"cites\":"
                + " [\"shop:src/main/java/com/example/domain/JpaOrders.java:10-14\"], \"scope\":"
                + " \"system\", \"model\": \"fake/m\"}",
            0.6));
    facts.add(
        investigated(
            "service:com.example:worker",
            InvestigatedFindings.RISK,
            FactObject.string("Header trusted"),
            "{\"finding\": \"It trusts User-Id.\", \"consequence\": \"Anyone can act as"
                + " anyone.\", \"impact\": \"high\", \"confidence\": \"medium\", \"units\":"
                + " [\"service:com.example:worker\"], \"names\": [\"User-Id\"], \"cites\":"
                + " [\"base:src/main/java/Filter.java:5-5\"], \"scope\": \"system\"}",
            0.45));
    facts.add(
        investigated(
            "service:com.example:app",
            InvestigatedFindings.CONNECTION,
            FactObject.entity(EntityId.parse("external-system:rates.example.com")),
            "{\"interaction\": \"http\", \"description\": \"Fetches rates from\","
                + " \"technology\": \"HTTPS\", \"confidence\": \"medium\", \"names\":"
                + " [\"rates.example.com\"], \"cites\": [\"shop:src/main/resources/app.yml:4-4\"],"
                + " \"scope\": \"service:com.example:app\", \"link\": \"0123456789abcdef\"}",
            0.45));
  }

  private static Fact investigated(
      String subject, String predicate, FactObject object, String json, double confidence) {
    String at = subject.endsWith("worker") ? "base" : "shop";
    return Fact.create(
        EntityId.parse(subject),
        predicate,
        object,
        (ObjectNode) FactJson.parse(json),
        INVESTIGATOR,
        new Evidence(at, TestFacts.COMMIT, "src/Main.java", new Evidence.LineRange(1, 2), null),
        confidence,
        Validity.from(1));
  }

  private Document compose(ViewDefinition view) {
    return new Composer("1").compose(view, SNAPSHOT, facts, Map.of());
  }

  private static Section section(Document document, String id) {
    return document.sections().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
  }

  private static <T extends Block> List<T> blocks(Section section, Class<T> kind) {
    return section.blocks().stream().filter(kind::isInstance).map(kind::cast).toList();
  }

  private static String text(List<Inline> content) {
    StringBuilder out = new StringBuilder();
    content.forEach(i -> out.append(i instanceof TextRun run ? run.text() : ""));
    return out.toString();
  }

  private static void markedAsFoundByTheLlm(List<Inline> content) {
    assertThat(content.getFirst())
        .isEqualTo(new TextRun(InvestigatedFindings.FOUND_BY_LLM, TextRun.Style.EMPHASIS));
  }

  private static Stream<Diagram> diagrams(List<Section> sections) {
    return sections.stream()
        .flatMap(
            s ->
                Stream.concat(
                    s.blocks().stream().filter(Diagram.class::isInstance).map(Diagram.class::cast),
                    diagrams(s.sections())));
  }

  @Test
  void theExplorerListsTheRisksOfItsUnitsMarkedWithTheirEvidence() {
    Document explorer = compose(ViewDefinition.perRepo("shop", "shop", TEMPLATE));

    Table table = blocks(section(explorer, "findings"), Table.class).getFirst();
    assertThat(table.title()).isEqualTo("Risks and findings");
    assertThat(table.columns())
        .extracting(Table.Column::label)
        .containsExactly("Impact", "Finding", "Consequence", "Confidence", "Evidence");
    // Only the risk of shop's unit; worker's is base's.
    assertThat(table.rows())
        .singleElement()
        .satisfies(
            row -> {
              markedAsFoundByTheLlm(row.cells().get(1));
              assertThat(text(row.cells().get(1)))
                  .isEqualTo(
                      "Found by the LLM: Event published before commit. It sends the event inside the"
                          + " transaction.");
              assertThat(text(row.cells().get(0))).isEqualTo("Medium");
              assertThat(text(row.cells().get(3))).isEqualTo("60%");
              assertThat(row.cells().get(4))
                  .containsExactly(
                      new TextRun(
                          "shop:src/main/java/com/example/domain/JpaOrders.java:10-14",
                          TextRun.Style.CODE));
              assertThat(row.facts()).hasSize(1);
            });
  }

  @Test
  void theSystemReportListsEveryRiskMostSeriousFirst() {
    Document system = compose(ViewDefinition.c4System("system:c4", "System", TEMPLATE));

    Table table = blocks(section(system, "findings"), Table.class).getFirst();
    assertThat(table.rows())
        .extracting(row -> text(row.cells().get(0)))
        .containsExactly("High", "Medium");
  }

  @Test
  void theBriefShowsThemAsRiskCalloutsAmongItsOwn() {
    Document brief = compose(ViewDefinition.executiveBrief("shop.brief", "shop", TEMPLATE));

    List<Callout> risks = blocks(section(brief, "risks"), Callout.class);
    assertThat(risks)
        .filteredOn(c -> "Event published before commit".equals(c.title()))
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.variant()).isEqualTo(Callout.Variant.RISK);
              assertThat(c.severity()).isEqualTo(Callout.Severity.MEDIUM);
              markedAsFoundByTheLlm(c.content());
              assertThat(text(c.content()))
                  .isEqualTo(
                      "Found by the LLM: It sends the event inside the transaction. Evidence:"
                          + " shop:src/main/java/com/example/domain/JpaOrders.java:10-14");
              assertThat(c.consequence()).isEqualTo("Consumers can see a rolled-back order.");
              assertThat(c.confidence()).isEqualByComparingTo("0.6");
            });
    assertThat(risks).noneMatch(c -> "Header trusted".equals(c.title()));
  }

  @Test
  void aConnectionTheInvestigatorFoundIsDrawnMarkedAndSoIsTheElementOnlyItFound() {
    for (ViewDefinition view :
        List.of(
            ViewDefinition.perRepo("shop", "shop", TEMPLATE),
            ViewDefinition.executiveBrief("shop.brief", "shop", TEMPLATE),
            ViewDefinition.c4Report("shop.c4", "shop", TEMPLATE))) {
      Document document = compose(view);

      EntityId rates = EntityId.parse("external-system:rates.example.com");
      List<Diagram.Edge> edges =
          diagrams(document.sections())
              .filter(d -> d.diagramKind() == Diagram.Kind.C4_CONTAINER)
              .flatMap(d -> d.edges().stream())
              .filter(e -> e.to().equals(rates))
              .toList();
      assertThat(edges)
          .as(view.id())
          .isNotEmpty()
          .allSatisfy(
              e -> {
                assertThat(e.label()).isEqualTo("Found by the LLM: Fetches rates from");
                assertThat(e.technology()).isEqualTo("HTTPS");
              });
      DocumentEntity element =
          document.entities().stream().filter(e -> e.id().equals(rates)).findFirst().orElseThrow();
      markedAsFoundByTheLlm(element.description());
      assertThat(text(element.description()))
          .isEqualTo("Found by the LLM: shop-app: Fetches rates from rates.example.com.");
    }
  }

  @Test
  void anElementStaticAnalysisFoundIsNotMarkedWhenTheInvestigatorAlsoConnectsToIt() {
    EntityId pay = EntityId.parse("external-system:pay.example.com");
    facts.add(
        investigated(
            "service:com.example:worker",
            InvestigatedFindings.CONNECTION,
            FactObject.entity(pay),
            "{\"interaction\": \"http\", \"description\": \"Refunds through\","
                + " \"confidence\": \"low\", \"names\": [\"pay.example.com\"], \"cites\":"
                + " [\"base:src/a.yml:1-1\"], \"scope\": \"system\", \"link\":"
                + " \"fedcba9876543210\"}",
            0.3));

    Document system = compose(ViewDefinition.c4System("system:c4", "System", TEMPLATE));

    system.entities().stream()
        .filter(e -> e.id().equals(pay))
        .forEach(e -> assertThat(text(e.description())).doesNotContain("Found by the LLM"));
  }

  @Test
  void withoutRisksTheSectionSaysTheInvestigatorFoundNone() {
    facts.removeIf(f -> f.origin().equals(INVESTIGATOR));

    Document explorer = compose(ViewDefinition.perRepo("shop", "shop", TEMPLATE));

    assertThat(blocks(section(explorer, "findings"), Callout.class))
        .singleElement()
        .satisfies(c -> assertThat(c.title()).isEqualTo("The LLM investigator found no risks"));
  }
}

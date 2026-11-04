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
import io.lantern.core.types.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/** What Investigate found, in every template, marked as found by the LLM (ADR 0110). */
class InvestigatedFindingsTest {

  private static final TemplateRef TEMPLATE = new TemplateRef("architecture-explorer", "1");
  private static final Snapshot SNAPSHOT =
      new Snapshot(
          2, Map.of("shop", TestFacts.COMMIT, "base", TestFacts.COMMIT), 5, "0.1.0", Instant.EPOCH);
  private static final String APP = "service:com.example:app";
  private static final Origin INVESTIGATE = new Origin(Origin.Kind.LLM, "investigate", "1");
  private static final String CONTROLLER = "src/main/java/com/example/web/Controller.java";

  private final List<Fact> facts = new ArrayList<>(TestFacts.runtime().list());

  private Fact risk(String predicate, String subject, String title) {
    ObjectNode attributes = FactJson.newObject();
    attributes.put("title", title);
    attributes.put("finding", "list trusts the X-User-Id header as sent.");
    attributes.put("consequence", "Any caller can read any user's orders.");
    attributes.put("impact", "high");
    attributes.put("confidence", "high");
    attributes.putArray("units").add(APP);
    attributes.putArray("names").add("X-User-Id");
    attributes.putArray("cites").add("shop:" + CONTROLLER + ":3-5");
    attributes.put("mode", "leads");
    attributes.put("model", "fake/m");
    Fact fact =
        Fact.create(
            EntityId.parse(subject),
            predicate,
            FactObject.string("key-" + title.length()),
            attributes,
            INVESTIGATE,
            Evidence.of("shop", CONTROLLER, new Evidence.LineRange(3, 5), null),
            0.6,
            Validity.from(1));
    facts.add(fact);
    return fact;
  }

  private Fact connection() {
    ObjectNode attributes = FactJson.newObject();
    attributes.put("interaction", "http");
    attributes.put("description", "Stores order exports");
    attributes.putArray("cites").add("shop:" + CONTROLLER + ":3-5");
    Fact fact =
        Fact.create(
            EntityId.parse(APP),
            Types.INVESTIGATED_CONNECTION,
            FactObject.entity(EntityId.parse("external-system:s3")),
            attributes,
            INVESTIGATE,
            Evidence.of("shop", CONTROLLER, new Evidence.LineRange(3, 5), null),
            0.45,
            Validity.from(1));
    facts.add(fact);
    return fact;
  }

  private Document compose(ViewDefinition view) {
    return new Composer("0.2.0").compose(view, SNAPSHOT, facts);
  }

  private static Section section(Document document, String id) {
    return document.sections().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
  }

  private static String text(List<Inline> inlines) {
    StringBuilder text = new StringBuilder();
    inlines.forEach(i -> text.append(i instanceof TextRun run ? run.text() : ""));
    return text.toString();
  }

  private static Stream<Block> blocks(Section section) {
    return Stream.concat(
        section.blocks().stream(), section.sections().stream().flatMap(s -> blocks(s)));
  }

  @Test
  void theBriefShowsARiskItFoundAsARiskCalloutMarkedFoundByTheLlm() {
    risk(Types.INVESTIGATED_RISK, APP, "Orders trust the caller's user header");

    Document brief = compose(ViewDefinition.executiveBrief("shop.brief", "shop", TEMPLATE));

    Callout callout =
        section(brief, "risks").blocks().stream()
            .filter(Callout.class::isInstance)
            .map(Callout.class::cast)
            .filter(c -> "Orders trust the caller's user header".equals(c.title()))
            .findFirst()
            .orElseThrow();
    assertThat(callout.severity()).isEqualTo(Callout.Severity.HIGH);
    assertThat(callout.content().getFirst())
        .isEqualTo(new TextRun(InvestigatedFindings.FOUND_BY_LLM, TextRun.Style.EMPHASIS));
    assertThat(text(callout.content())).contains("shop:" + CONTROLLER + ":3-5");
    assertThat(callout.consequence()).isEqualTo("Any caller can read any user's orders.");
  }

  @Test
  void theExplorerAndTheSystemReportListItInTheirRisksAndFindings() {
    Fact risk = risk(Types.INVESTIGATED_RISK, APP, "Orders trust the caller's user header");

    for (Document document :
        List.of(
            compose(ViewDefinition.perRepo("shop", "shop", TEMPLATE)),
            compose(ViewDefinition.c4System("system:c4", "System", TEMPLATE)))) {
      Table table =
          (Table)
              section(document, "findings").blocks().stream()
                  .filter(Table.class::isInstance)
                  .findFirst()
                  .orElseThrow();
      Table.Row row = table.rows().getFirst();
      assertThat(text(row.cells().get(0))).isEqualTo("High");
      assertThat(text(row.cells().get(1)))
          .startsWith(
              InvestigatedFindings.FOUND_BY_LLM + " Orders trust the caller's user header.");
      assertThat(text(row.cells().get(4))).isEqualTo("shop:" + CONTROLLER + ":3-5");
      assertThat(row.facts()).containsExactly(risk.id());
    }
  }

  @Test
  void aFindingItWordedSaysAFixedCheckFoundIt() {
    ObjectNode raised = FactJson.newObject();
    raised.put("about", APP);
    Fact finding =
        Fact.create(
            EntityId.parse("finding:shared-jwt-secret/x"),
            "finding-raised",
            FactObject.string("shared-jwt-secret"),
            raised,
            new Origin(Origin.Kind.STATIC, "findings", "1"),
            Evidence.of("shop", CONTROLLER, new Evidence.LineRange(3, 5), null),
            1.0,
            Validity.from(1));
    facts.add(finding);
    risk(Types.FINDING_WORDING, finding.subject().toString(), "Every unit signs with one key");

    Document explorer = compose(ViewDefinition.perRepo("shop", "shop", TEMPLATE));

    Table table =
        (Table)
            section(explorer, "findings").blocks().stream()
                .filter(Table.class::isInstance)
                .findFirst()
                .orElseThrow();
    assertThat(text(table.rows().getFirst().cells().get(1)))
        .startsWith(InvestigatedFindings.WORDED_BY_LLM);
    assertThat(table.rows().getFirst().facts()).contains(finding.id());
  }

  @Test
  void aUnitItDidNotLookAtIsNamed() {
    facts.add(
        Fact.create(
            EntityId.parse(APP),
            Types.NOT_INVESTIGATED,
            FactObject.string("investigate"),
            FactJson.newObject(),
            INVESTIGATE,
            Evidence.of("shop", "pom.xml", null, null),
            0.6,
            Validity.from(1)));

    Document explorer = compose(ViewDefinition.perRepo("shop", "shop", TEMPLATE));

    assertThat(
            section(explorer, "decisions").blocks().stream()
                .filter(Callout.class::isInstance)
                .map(Callout.class::cast)
                .map(c -> text(c.content())))
        .anyMatch(t -> t.startsWith("The LLM investigator did not look at shop-app"));
  }

  @Test
  void aConnectionItFoundIsALineMarkedFoundByTheLlmAndItsNewElementIsDescribedByIt() {
    Fact found = connection();

    Document explorer = compose(ViewDefinition.perRepo("shop", "shop", TEMPLATE));

    Diagram.Edge edge =
        blocks(section(explorer, "containers"))
            .filter(Diagram.class::isInstance)
            .map(Diagram.class::cast)
            .flatMap(d -> d.edges().stream())
            .filter(e -> e.to().toString().equals("external-system:s3"))
            .findFirst()
            .orElseThrow();
    assertThat(edge.label()).isEqualTo(InvestigatedFindings.FOUND_BY_LLM + " Stores order exports");
    assertThat(edge.facts()).containsExactly(found.id());
    DocumentEntity s3 =
        explorer.entities().stream()
            .filter(e -> e.id().toString().equals("external-system:s3"))
            .findFirst()
            .orElseThrow();
    assertThat(text(s3.description())).startsWith(InvestigatedFindings.FOUND_BY_LLM);
  }
}

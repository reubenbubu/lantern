package io.lantern.compose;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.document.Callout;
import io.lantern.core.document.Document;
import io.lantern.core.document.Inline;
import io.lantern.core.document.QualityReport;
import io.lantern.core.document.Section;
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
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * The quality checks asked of the model and the unknowns page (knowledge-build epic, section 12;
 * #700).
 */
class UnknownsTest {

  private static final Snapshot SNAPSHOT =
      new Snapshot(
          2, Map.of("shop", TestFacts.COMMIT, "base", TestFacts.COMMIT), 5, "0.1.0", Instant.EPOCH);
  private static final ViewDefinition SYSTEM =
      ViewDefinition.c4System(
          "system:c4", "System", new TemplateRef("c4-model-sequence-diagrams", "1"));
  private static final String APP = "service:com.example:app";
  private static final Origin INVESTIGATE = new Origin(Origin.Kind.LLM, "investigate", "1");

  private final List<Fact> facts = new ArrayList<>(TestFacts.runtime().list());

  @Test
  void aContainerWhoseTechnologyFactIsRemovedFailsTheCheckFromTheModel() {
    assertThat(failed(compose(), QualityBar.TECHNOLOGY)).isEmpty();

    facts.removeIf(
        f -> f.subject().equals(EntityId.parse(APP)) && f.predicate().equals(Types.TECHNOLOGY));

    // The diagrams draw shop-app as before; the check asks the model what it is built with.
    assertThat(failed(compose(), QualityBar.TECHNOLOGY))
        .containsExactly("No technology for shop-app.");
  }

  @Test
  void elementsWithoutAResponsibilityAreNamedOnTheUnknownsPageAndFailTheCheck() {
    Document document = compose();

    assertThat(callout(document, "decisions.no-responsibility"))
        .startsWith("Lantern holds no responsibility sentence for ")
        .contains("shop-app")
        .endsWith("so what they do is not known.");
    // Named once, on the unknowns page; the failure counts them.
    assertThat(failed(document, QualityBar.RESPONSIBILITY))
        .singleElement()
        .asString()
        .matches(
            "\\d+ elements have no responsibility sentence; the decisions and unknowns page"
                + " names them\\.");
  }

  @Test
  void aLeadInvestigateLeftUnclearIsOnTheUnknownsPageWithItsReason() {
    unclearLead("missing-auth/list", "missing-auth");

    assertThat(callout(compose(), "decisions.unclear-leads"))
        .isEqualTo(
            "The LLM investigator could not say whether this lead points to a risk:"
                + " shop-app: missing auth (not answered).");
  }

  @Test
  void leadsOfOneKindAboutTheSameUnitReadAsOneLineWithTheirCount() {
    unclearLead("missing-auth/list", "missing-auth");
    unclearLead("missing-auth/get", "missing-auth");
    unclearLead("plaintext-secret/db", "plaintext-secret");

    assertThat(callout(compose(), "decisions.unclear-leads"))
        .isEqualTo(
            "The LLM investigator could not say whether these leads point to a risk:"
                + " shop-app: missing auth, 2 leads (not answered)."
                + " shop-app: plaintext secret (not answered).");
  }

  @Test
  void leadsAboutNoUnitAreNamedByWhatTheyPointAtAndANotAskedLeadByThatAlone() {
    unclearLead(
        "single-instance/db",
        "single-instance",
        List.of(),
        List.of("database:db:5432/shop"),
        "not asked: java.io.IOException: connection reset");

    assertThat(callout(compose(), "decisions.unclear-leads"))
        .isEqualTo(
            "The LLM investigator could not say whether this lead points to a risk:"
                + " shop on db: single instance (not asked).");
  }

  @Test
  void leadsComeInTheOrderOfWhatTheyAreAboutWhicheverModelWasAsked() {
    String worker = "service:com.example:worker";
    unclearLead("contract-mismatch/b", "contract-mismatch", List.of(APP, worker), List.of(), "x");
    unclearLead("contract-mismatch/a", "contract-mismatch", List.of(APP), List.of(), "x");

    assertThat(callout(compose(), "decisions.unclear-leads"))
        .isEqualTo(
            "The LLM investigator could not say whether these leads point to a risk:"
                + " shop-app: contract mismatch (x). shop-app and worker: contract mismatch (x).");
  }

  @Test
  void aLineBetweenContainersWhoseProtocolIsRemovedFailsTheCheckFromTheModel() {
    assertThat(failed(compose(), QualityBar.PROTOCOL)).isEmpty();

    Fact link =
        facts.stream()
            .filter(
                f ->
                    f.subject().equals(EntityId.parse(APP))
                        && f.predicate().equals(Types.USES_DATABASE))
            .findFirst()
            .orElseThrow();
    ObjectNode attributes = link.attributes().deepCopy();
    attributes.remove("protocol");
    facts.remove(link);
    facts.add(
        Fact.create(
            link.subject(),
            link.predicate(),
            link.object(),
            attributes,
            link.origin(),
            link.evidence(),
            link.confidence(),
            link.validity()));

    assertThat(failed(compose(), QualityBar.PROTOCOL))
        .containsExactly("No protocol on shop-app to shop on db.");
  }

  /** A lead about shop-app that Investigate did not answer. */
  private void unclearLead(String key, String kind) {
    unclearLead(key, kind, List.of(APP), List.of(), "not answered");
  }

  /** A lead about the units, or else about what it points at, that Investigate left unclear. */
  private void unclearLead(
      String key, String kind, List<String> units, List<String> code, String reason) {
    EntityId lead = new EntityId(Types.LEAD, key);
    ObjectNode trigger = FactJson.newObject();
    trigger.put("trigger", "what the lead rule saw");
    if (!units.isEmpty()) {
      units.forEach(trigger.putArray("units")::add);
    }
    code.forEach(trigger.putArray("code")::add);
    facts.add(
        Fact.create(
            lead,
            Types.LEAD_TRIGGER,
            FactObject.string(kind),
            trigger,
            new Origin(Origin.Kind.STATIC, "leads", "1"),
            Evidence.of("shop", "pom.xml", null, null),
            1.0,
            Validity.from(1)));
    ObjectNode verdict = FactJson.newObject();
    verdict.put("reason", reason);
    facts.add(
        Fact.create(
            lead,
            Types.LEAD_VERDICT,
            FactObject.string("unclear"),
            verdict,
            INVESTIGATE,
            Evidence.of("shop", "pom.xml", null, null),
            0.6,
            Validity.from(1)));
  }

  private Document compose() {
    return new Composer("0.2.0").compose(SYSTEM, SNAPSHOT, facts);
  }

  private static List<String> failed(Document document, String check) {
    List<String> result = new ArrayList<>();
    Section quality = document.sections().getLast();
    QualityReport report = (QualityReport) quality.blocks().getFirst();
    for (QualityReport.Failure failure : report.failures()) {
      if (failure.check().equals(check)) {
        result.add(text(failure.detail()));
      }
    }
    return result;
  }

  private static String callout(Document document, String id) {
    return document.sections().stream()
        .flatMap(s -> s.blocks().stream())
        .filter(b -> b.id().equals(id))
        .map(Callout.class::cast)
        .map(c -> text(c.content()))
        .findFirst()
        .orElseThrow();
  }

  private static String text(List<Inline> inlines) {
    StringBuilder text = new StringBuilder();
    inlines.forEach(i -> text.append(i instanceof TextRun run ? run.text() : ""));
    return text.toString();
  }
}

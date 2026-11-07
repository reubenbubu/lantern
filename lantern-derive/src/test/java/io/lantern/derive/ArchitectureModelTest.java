package io.lantern.derive;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.types.Types;
import io.lantern.harness.LlmFacts;
import io.lantern.harness.checks.SupportJudge;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class ArchitectureModelTest {

  private static final EntityId UNIT = EntityId.parse("service:g:shop");
  private static final EntityId TYPE = EntityId.parse("type:g.OrderController");
  private static final EntityId COMPONENT = Components.id(UNIT, TYPE);
  private static final EntityId FLOW = EntityId.parse("flow:service:g:shop/abc");

  private final TestModel model = new TestModel();
  private final Fact unit = model.add(UNIT, "deployable-unit", FactObject.string("spring-boot"));

  @Test
  void theTextsAreReadApartFromTheFactsTheyAreAbout() {
    Fact sentence = text(UNIT, Types.RESPONSIBILITY, LlmFacts.C4, "Takes orders.", 0.6);

    ArchitectureModel read = new ArchitectureModel(with(sentence));

    assertThat(read.fact(sentence.id())).isNull();
    assertThat(read.fact(unit.id())).isNotNull();
    assertThat(read.responsibility(UNIT).orElseThrow().marked())
        .isEqualTo("Takes orders. [fact:" + unit.id() + "]");
  }

  @Test
  void aTextTheJudgeRejectedIsLeftOutAndNotedSoThePlainWordingTakesItsPlace() {
    Fact rejected =
        LlmFacts.withSupport(
            text(UNIT, Types.RESPONSIBILITY, LlmFacts.C4, "Takes orders.", 0.6),
            SupportJudge.Support.UNSUPPORTED);
    Fact partly =
        LlmFacts.withSupport(
            text(UNIT, Types.RESPONSIBILITY, LlmFacts.C4, "Sells things.", 0.3),
            SupportJudge.Support.PARTLY);
    EntityId group = EntityId.parse("group:system/domain/orders");
    Fact name =
        LlmFacts.withSupport(
            text(group, Types.DISPLAY_NAME, LlmFacts.C4, "Ordering", 0.6),
            SupportJudge.Support.UNSUPPORTED);
    Fact purpose =
        LlmFacts.withSupport(
            text(ArchitectureModel.SYSTEM, Types.PURPOSE, LlmFacts.C4, "Sells shoes.", 0.6),
            SupportJudge.Support.UNSUPPORTED);

    ArchitectureModel read = new ArchitectureModel(with(rejected, partly, name, purpose));

    // A partly supported text is read; the rejected ones are not, though more confident (#751).
    assertThat(read.responsibility(UNIT).orElseThrow().text()).isEqualTo("Sells things.");
    assertThat(new ArchitectureModel(with(rejected)).responsibility(UNIT)).isEmpty();
    assertThat(read.displayName(group)).isEmpty();
    assertThat(read.purpose()).isEmpty();
    assertThat(read.fact(rejected.id())).isNull();
    assertThat(read.factOrText(rejected.id())).isNull();
    assertThat(read.rejected(ArchitectureModel.SYSTEM, Types.PURPOSE)).isTrue();
    assertThat(read.rejected(group, Types.DISPLAY_NAME)).isTrue();
    assertThat(read.rejected(UNIT, Types.SUMMARY)).isFalse();
  }

  @Test
  void aComponentsSentenceAlsoDescribesTheTypeItIsAnchoredOn() {
    model.add(COMPONENT, Types.ANCHORED_ON, TYPE);
    Fact sentence = text(COMPONENT, Types.RESPONSIBILITY, LlmFacts.C4, "Lists orders.", 0.6);

    ArchitectureModel read = new ArchitectureModel(with(sentence));

    assertThat(read.responsibility(TYPE).orElseThrow().text()).isEqualTo("Lists orders.");
    assertThat(read.responsibility(COMPONENT).orElseThrow().text()).isEqualTo("Lists orders.");
  }

  @Test
  void anIntentIsFoundByTheClaimOfItsInteraction() {
    Fact call = model.add(UNIT, "CALLS_HTTP", EntityId.parse("service:g:pricing"));
    Fact phrase = text(UNIT, Types.INTENT, call.claimId(), "Looks up prices", 0.6);

    assertThat(new ArchitectureModel(with(phrase)).intent(call).orElseThrow().text())
        .isEqualTo("Looks up prices");
  }

  @Test
  void aNarrativeHoldsOnlyForTheStepsItWasWrittenFor() {
    ObjectNode attributes = FactJson.newObject();
    attributes.put("title", "Shop takes an order.");
    attributes.put("walkthrough", "The shop takes it.");
    attributes.putArray("cites").add(unit.id());
    attributes.putObject("fieldCites").putArray("walkthrough").add(unit.id());
    attributes.put("basis", "inferred");
    attributes.put("content", "c1");
    Fact narrative = llm(FLOW, Types.FLOW_NARRATIVE, LlmFacts.C4, attributes, 0.6);

    ArchitectureModel read = new ArchitectureModel(with(narrative));

    ArchitectureModel.Narrative told = read.narrative(FLOW, "c1").orElseThrow();
    assertThat(told.title()).isEqualTo("Shop takes an order");
    assertThat(told.walkthrough().marked())
        .isEqualTo("The shop takes it. [fact:" + unit.id() + "]");
    assertThat(told.preconditions()).isNull();
    assertThat(read.narrative(FLOW, "c2")).isEmpty();
  }

  @Test
  void aNarrativeForTheStepsAsTheyAreIsReadThoughAStaleOneIsMoreConfident() {
    Fact stale = narrative("Old steps.", "c0", 0.6);
    Fact current = narrative("New steps.", "c1", 0.3);

    ArchitectureModel read = new ArchitectureModel(with(stale, current));

    assertThat(read.narrative(FLOW, "c1").orElseThrow().walkthrough().text())
        .isEqualTo("New steps.");
  }

  @Test
  void twoGroupsWithALineToTheSameEndKeepTheirOwnIntents() {
    EntityId orders = EntityId.parse("group:system/domain/orders");
    EntityId billing = EntityId.parse("group:system/domain/billing");
    String end = "database:postgres:main JDBC";

    ArchitectureModel read =
        new ArchitectureModel(
            with(
                text(orders, Types.INTENT, end, "Stores orders", 0.6),
                text(billing, Types.INTENT, end, "Stores invoices", 0.6)));

    assertThat(read.groupIntent(orders, end).orElseThrow().text()).isEqualTo("Stores orders");
    assertThat(read.groupIntent(billing, end).orElseThrow().text()).isEqualTo("Stores invoices");
  }

  private Fact narrative(String walkthrough, String content, double confidence) {
    ObjectNode attributes = FactJson.newObject();
    attributes.put("walkthrough", walkthrough);
    attributes.putArray("cites").add(unit.id());
    attributes.put("basis", "inferred");
    attributes.put("content", content);
    return llm(FLOW, Types.FLOW_NARRATIVE, LlmFacts.C4, attributes, confidence);
  }

  @Test
  void aScopesSignificantFlowsAreReadInRankOrder() {
    EntityId second = EntityId.parse("flow:service:g:shop/def");
    EntityId notShown = EntityId.parse("flow:service:g:shop/ghi");

    ArchitectureModel read =
        new ArchitectureModel(
            with(
                significance(second, "system", true, 2),
                significance(FLOW, "system", true, 1),
                significance(notShown, "system", false, null)));

    assertThat(read.significantFlows("system"))
        .contains(List.of(FLOW.toString(), second.toString()));
    assertThat(read.significantFlows("shop")).isEmpty();
  }

  @Test
  void aSignificanceJudgedOnOtherStepsIsNotRead() {
    EntityId second = EntityId.parse("flow:service:g:shop/def");
    ObjectNode shape = FactJson.newObject();
    shape.put("content", "c1");
    Fact steps =
        Fact.create(
            FLOW,
            Types.FLOW_SHAPE,
            FactObject.string("request"),
            shape,
            new Origin(Origin.Kind.RULE, "flows", "1"),
            LlmFacts.evidence(List.of(unit)),
            1.0,
            Validity.from(1));
    Fact stale = significance(FLOW, "system", true, 1, "c0");
    Fact fresh = significance(FLOW, "repo", true, 1, "c1");

    ArchitectureModel read =
        new ArchitectureModel(with(steps, stale, significance(second, "system", true, 2), fresh));

    // One judgement on the flow's earlier steps makes the scope unjudged, so the rank decides,
    // whatever its other judgements say; one on today's steps, or naming no steps, is read.
    assertThat(read.significantFlows("system")).isEmpty();
    assertThat(read.significantFlows("repo")).contains(List.of(FLOW.toString()));
    assertThat(
            new ArchitectureModel(with(steps, significance(second, "system", true, 1)))
                .significantFlows("system"))
        .contains(List.of(second.toString()));
  }

  private List<Fact> with(Fact... texts) {
    List<Fact> all = new ArrayList<>(model.facts);
    all.addAll(List.of(texts));
    return all;
  }

  private Fact text(EntityId subject, String predicate, String object, String text, double c) {
    ObjectNode attributes = FactJson.newObject();
    attributes.put("text", text);
    attributes.putArray("cites").add(unit.id());
    attributes.put("basis", "inferred");
    return llm(subject, predicate, object, attributes, c);
  }

  private Fact significance(EntityId flow, String scope, boolean significant, Integer rank) {
    return significance(flow, scope, significant, rank, null);
  }

  private Fact significance(
      EntityId flow, String scope, boolean significant, Integer rank, String content) {
    ObjectNode attributes = FactJson.newObject();
    if (content != null) {
      attributes.put("content", content);
    }
    attributes.put("significant", significant);
    attributes.put("category", "other");
    if (rank != null) {
      attributes.put("rank", rank);
    }
    attributes.putArray("cites").add(unit.id());
    attributes.put("basis", "inferred");
    return llm(flow, Types.FLOW_SIGNIFICANCE, scope, attributes, 0.6);
  }

  private Fact llm(
      EntityId subject, String predicate, String object, ObjectNode attributes, double confidence) {
    return Fact.create(
        subject,
        predicate,
        FactObject.string(object),
        attributes,
        new Origin(Origin.Kind.LLM, "describe-units", "1"),
        LlmFacts.evidence(List.of(unit)),
        confidence,
        Validity.from(1));
  }

  @Test
  void aNounPhraseIsMadePluralAsEnglishDoes() {
    // #944's real run: never "diagnostic visit entitys".
    org.assertj.core.api.Assertions.assertThat(ArchitectureModel.plural("work order"))
        .isEqualTo("work orders");
    org.assertj.core.api.Assertions.assertThat(ArchitectureModel.plural("labor entry"))
        .isEqualTo("labor entries");
    org.assertj.core.api.Assertions.assertThat(ArchitectureModel.plural("inbox"))
        .isEqualTo("inboxes");
    org.assertj.core.api.Assertions.assertThat(ArchitectureModel.plural("payments"))
        .isEqualTo("payments");
  }
}

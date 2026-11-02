package io.lantern.derive;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
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
    Fact sentence = text(UNIT, LlmFacts.RESPONSIBILITY, LlmFacts.C4, "Takes orders.", 0.6);

    ArchitectureModel read = new ArchitectureModel(with(sentence));

    assertThat(read.knowledge().fact(sentence.id())).isNull();
    assertThat(read.knowledge().fact(unit.id())).isNotNull();
    assertThat(read.responsibility(UNIT).orElseThrow().marked())
        .isEqualTo("Takes orders. [fact:" + unit.id() + "]");
  }

  @Test
  void theMostConfidentTextOfAThingIsReadAndAVerdictIsKept() {
    Fact judged =
        LlmFacts.withSupport(
            text(UNIT, LlmFacts.RESPONSIBILITY, LlmFacts.C4, "Takes orders.", 0.6),
            SupportJudge.Support.UNSUPPORTED);
    Fact other = text(UNIT, LlmFacts.RESPONSIBILITY, LlmFacts.C4, "Sells things.", 0.6);

    ArchitectureModel read = new ArchitectureModel(with(judged, other));

    assertThat(read.responsibility(UNIT).orElseThrow().text()).isEqualTo("Sells things.");
    assertThat(new ArchitectureModel(with(judged)).responsibility(UNIT).orElseThrow().unsupported())
        .isTrue();
  }

  @Test
  void aComponentsSentenceAlsoDescribesTheTypeItIsAnchoredOn() {
    model.add(COMPONENT, Components.ANCHORED_ON, TYPE);
    Fact sentence = text(COMPONENT, LlmFacts.RESPONSIBILITY, LlmFacts.C4, "Lists orders.", 0.6);

    ArchitectureModel read = new ArchitectureModel(with(sentence));

    assertThat(read.responsibility(TYPE).orElseThrow().text()).isEqualTo("Lists orders.");
    assertThat(read.responsibility(COMPONENT).orElseThrow().text()).isEqualTo("Lists orders.");
  }

  @Test
  void anIntentIsFoundByTheClaimOfItsInteraction() {
    Fact call = model.add(UNIT, "CALLS_HTTP", EntityId.parse("service:g:pricing"));
    Fact phrase = text(UNIT, LlmFacts.INTENT, call.claimId(), "Looks up prices", 0.6);

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
    Fact narrative = llm(FLOW, LlmFacts.FLOW_NARRATIVE, LlmFacts.C4, attributes, 0.6);

    ArchitectureModel read = new ArchitectureModel(with(narrative));

    ArchitectureModel.Narrative told = read.narrative(FLOW, "c1").orElseThrow();
    assertThat(told.title()).isEqualTo("Shop takes an order");
    assertThat(told.walkthrough().marked())
        .isEqualTo("The shop takes it. [fact:" + unit.id() + "]");
    assertThat(told.preconditions()).isNull();
    assertThat(read.narrative(FLOW, "c2")).isEmpty();
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
    ObjectNode attributes = FactJson.newObject();
    attributes.put("significant", significant);
    attributes.put("category", "other");
    if (rank != null) {
      attributes.put("rank", rank);
    }
    attributes.putArray("cites").add(unit.id());
    attributes.put("basis", "inferred");
    return llm(flow, LlmFacts.FLOW_SIGNIFICANCE, scope, attributes, 0.6);
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
}

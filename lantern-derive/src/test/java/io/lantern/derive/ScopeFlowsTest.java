package io.lantern.derive;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.vocabulary.BuiltInTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/** The flows a scope may draw, as queries on the stored flows (#718). */
class ScopeFlowsTest {

  private static final EntityId SHOP = EntityId.parse("service:com.example:shop");
  private static final EntityId BILLING = EntityId.parse("service:com.example:billing");
  private static final EntityId SHOP_MODULE = EntityId.parse("module:com.example:shop");
  private static final EntityId BILLING_MODULE = EntityId.parse("module:com.example:billing");
  private static final EntityId ORDERS = EntityId.parse("type:com.example.OrdersController");
  private static final EntityId PAYMENTS = EntityId.parse("type:com.example.billing.Payments");
  private static final EntityId PLACE =
      EntityId.parse("method:com.example.OrdersController#placeOrder()");
  private static final EntityId CHARGE =
      EntityId.parse("method:com.example.billing.Payments#charge()");
  private static final EntityId TOPIC = EntityId.parse("topic:kafka:charged");

  private final TestModel model = new TestModel();

  @Test
  void aFlowsStepsAreNeutralMessagesAndNotesWithTheLinesTheRolesAreShown() {
    Fact endpoint = system();
    Fact call = link(PLACE, BILLING, CHARGE);
    send(CHARGE, TOPIC);

    ScopeFlows flows = flows(Set.of(SHOP), new ArrayList<>());
    ScopeFlows.Trace trace = place(flows);

    assertThat(trace.entry()).isEqualTo(new ScopeFlows.Entry(SHOP, PLACE, endpoint));
    assertThat(trace.participants()).containsExactly(SHOP, BILLING, TOPIC);
    ScopeFlows.Message http = (ScopeFlows.Message) trace.steps().getFirst();
    assertThat(http.from()).isEqualTo(SHOP);
    assertThat(http.to()).isEqualTo(BILLING);
    assertThat(http.plain()).isEqualTo("Calls Payments.charge");
    assertThat(http.async()).isFalse();
    // The link whose intent phrase the composer may label the call with.
    assertThat(http.phrased()).isEqualTo(call);
    assertThat(trace.steps().get(1)).isInstanceOf(ScopeFlows.Message.class);
    assertThat(((ScopeFlows.Message) trace.steps().get(1)).async()).isTrue();
    assertThat(trace.steps().get(2)).isInstanceOf(ScopeFlows.Note.class);
    assertThat(trace.outline())
        .extracting(ScopeFlows.StepLine::text)
        .first()
        .isEqualTo("shop to billing (a deployable unit), sync: Calls Payments.charge");
    assertThat(trace.heading().title()).isEqualTo("POST /orders on shop");
    assertThat(trace.heading().starts()).isEqualTo("shop receives POST /orders");
  }

  @Test
  void theScopesCandidatesAndChoiceAreTheFlowsThatReachIt() {
    system();
    link(PLACE, BILLING, CHARGE);
    send(CHARGE, TOPIC);
    ScopeFlows flows = flows(Set.of(SHOP), new ArrayList<>());
    String place = place(flows).flow().id().toString();

    // The flow billing's charge starts never reaches shop, so shop's scope does not offer it.
    assertThat(flows.candidates())
        .extracting(c -> c.flow().toString() + " " + c.starts() + " " + c.crossesUnits())
        .containsExactly(place + " shop receives POST /orders (OrdersController.placeOrder) true");
    assertThat(flows.chosen(null)).extracting(t -> t.flow().id().toString()).containsExactly(place);
    assertThat(flows.chosen(List.of(place, place)))
        .extracting(t -> t.flow().id().toString())
        .containsExactly(place);
    assertThat(flows.chosen(List.of())).isEmpty();
    assertThat(flows.byRank(flows.candidates())).hasSize(1);
  }

  @Test
  void everyElementAStepNamesGoesThroughTheNamingHook() {
    system();
    link(PLACE, BILLING, CHARGE);
    send(CHARGE, TOPIC);
    List<EntityId> named = new ArrayList<>();

    flows(Set.of(SHOP), named).traces();

    assertThat(named).contains(SHOP, BILLING, TOPIC);
  }

  @Test
  void aScenarioIsTheEntryPointsMethodInWords() {
    assertThat(ScopeFlows.scenario(new ScopeFlows.Entry(SHOP, PLACE, null)))
        .isEqualTo("Place order");
    assertThat(ScopeFlows.methodName(CHARGE)).isEqualTo("Payments.charge");
    assertThat(ScopeFlows.list(List.of("a", "b", "c"))).isEqualTo("a, b and c");
  }

  private ScopeFlows flows(Set<EntityId> scope, List<EntityId> named) {
    Knowledge knowledge = new Knowledge(Derivation.withModel(model.facts, 1));
    ElementNames names = new ElementNames(knowledge, "Shop system");
    Elements elements = new Elements(knowledge, Vocabularies.standard(), names, Set.of());
    return new ScopeFlows(
        knowledge,
        elements,
        scope,
        e -> {
          named.add(e);
          return names.name(e);
        });
  }

  private static ScopeFlows.Trace place(ScopeFlows flows) {
    return flows.traces().stream()
        .filter(t -> t.entry().method().equals(PLACE))
        .findFirst()
        .orElseThrow();
  }

  private Fact system() {
    for (EntityId[] unit :
        List.of(new EntityId[] {SHOP, SHOP_MODULE}, new EntityId[] {BILLING, BILLING_MODULE})) {
      model.add(unit[0], "deployable-unit", FactObject.string("spring-boot"));
      model.add(unit[0], BuiltInTypes.CONTAINS, unit[1]);
    }
    model.add(SHOP_MODULE, BuiltInTypes.CONTAINS, ORDERS);
    model.add(BILLING_MODULE, BuiltInTypes.CONTAINS, PAYMENTS);
    model.add(ORDERS, BuiltInTypes.CONTAINS, PLACE);
    model.add(PAYMENTS, BuiltInTypes.CONTAINS, CHARGE);
    model.add(CHARGE, "endpoint", FactObject.string("POST /charge"));
    return model.add(PLACE, "endpoint", FactObject.string("POST /orders"));
  }

  /** A unit's HTTP call from the method's code to another unit, reaching the handler. */
  private Fact link(EntityId from, EntityId unit, EntityId handler) {
    ObjectNode at = FactJson.newObject();
    at.put("source", from.toString());
    at.put("target", handler.toString());
    return model.add(SHOP, "CALLS_HTTP", FactObject.entity(unit), at);
  }

  private void send(EntityId from, EntityId destination) {
    ObjectNode at = FactJson.newObject();
    at.put("source", from.toString());
    at.put("protocol", "Kafka");
    model.add(BILLING, "PUBLISHES", FactObject.entity(destination), at);
  }
}

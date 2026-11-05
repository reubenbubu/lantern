package io.lantern.derive;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.types.Types;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/** Every candidate flow and its steps, stored with lineage (#618). */
class FlowsTest {

  private static final EntityId SHOP = EntityId.parse("service:com.example:shop");
  private static final EntityId BILLING = EntityId.parse("service:com.example:billing");
  private static final EntityId SHOP_MODULE = EntityId.parse("module:com.example:shop");
  private static final EntityId BILLING_MODULE = EntityId.parse("module:com.example:billing");
  private static final EntityId ORDERS = EntityId.parse("type:com.example.OrdersController");
  private static final EntityId HELPER = EntityId.parse("type:com.example.Helper");
  private static final EntityId PAYMENTS = EntityId.parse("type:com.example.billing.Payments");
  private static final EntityId PLACE =
      EntityId.parse("method:com.example.OrdersController#place()");
  private static final EntityId CANCEL =
      EntityId.parse("method:com.example.OrdersController#cancel()");
  private static final EntityId NOTIFY = EntityId.parse("method:com.example.Helper#notify()");
  private static final EntityId CHARGE =
      EntityId.parse("method:com.example.billing.Payments#charge()");
  private static final EntityId TOPIC = EntityId.parse("topic:kafka:charged");

  private final TestModel model = new TestModel();

  @Test
  void aFlowIsStoredWithItsStepsInOrderEachCitingWhatItFollows() {
    Fact endpoint = system();
    Fact call = link(PLACE, BILLING, CHARGE);
    Fact send = send(CHARGE, TOPIC);

    List<Fact> derived = model.derived(Flows.NAME);

    EntityId flow = Flows.id(SHOP, endpoint);
    Fact triggered = only(TestModel.about(derived, flow, Types.TRIGGERED_BY));
    assertThat(triggered.object().entityId()).isEqualTo(PLACE);
    assertThat(triggered.lineage()).containsExactly(endpoint.claimId());
    Fact shape = only(TestModel.about(derived, flow, Types.FLOW_SHAPE));
    assertThat(shape.attributes().get("truncated").asBoolean()).isFalse();
    assertThat(shape.attributes().get("content").asString()).startsWith("sha256:");

    List<Fact> steps = steps(derived, flow);
    assertThat(steps)
        .extracting(s -> s.attributes().get("action").asString())
        .containsExactly(Types.HTTP_CALL, FlowModel.SEND, FlowModel.NO_LISTENER);
    Fact first = steps.getFirst();
    assertThat(first.attributes().get("from").asString()).isEqualTo(SHOP.toString());
    assertThat(first.attributes().get("to").asString()).isEqualTo(BILLING.toString());
    assertThat(first.attributes().get("kind").asString()).isEqualTo(FlowModel.SYNC);
    assertThat(first.lineage().getFirst()).isEqualTo(call.claimId());
    assertThat(first.subject().key()).isEqualTo(flow.key() + "/" + call.claimId() + "/0");
    assertThat(steps.get(1).attributes().get("kind").asString()).isEqualTo(FlowModel.ASYNC);
    assertThat(steps.get(1).lineage().getFirst()).isEqualTo(send.claimId());
    // The note follows the same send: the second step of the flow to follow it.
    assertThat(steps.get(2).subject().key()).isEqualTo(flow.key() + "/" + send.claimId() + "/1");

    // The composer reads it back as stored.
    FlowModel.Flow read =
        FlowModel.read(new Knowledge(Derivation.withModel(model.facts, 1))).stream()
            .filter(f -> f.method().equals(PLACE))
            .findFirst()
            .orElseThrow();
    assertThat(read.steps()).extracting(FlowModel.Step::action).hasSize(3);
    assertThat(read.containers()).containsExactly(SHOP, BILLING, TOPIC);
  }

  @Test
  void aStepInsertedBeforeOthersRenamesNone() {
    system();
    link(PLACE, BILLING, CHARGE);
    send(CHARGE, TOPIC);
    Set<EntityId> before = stepIds(model.derived(Flows.NAME));

    // A call made earlier in the same method.
    model.add(ORDERS, Types.CONTAINS, CANCEL);
    ObjectNode at = FactJson.newObject();
    at.put("source", PLACE.toString());
    model.add(
        SHOP, "CALLS_HTTP", FactObject.entity(EntityId.parse("external-system:tax.example")), at);
    Set<EntityId> after = stepIds(model.derived(Flows.NAME));

    assertThat(after).containsAll(before).hasSize(before.size() + 1);
  }

  @Test
  void everyFlowIsStoredAndATraceStoppedAtItsBoundIsTruncated() {
    system();
    // Ten entry points, more than a document shows (seven), each calling out.
    for (int i = 0; i < 10; i++) {
      EntityId method = EntityId.parse("method:com.example.OrdersController#m" + i + "()");
      model.add(ORDERS, Types.CONTAINS, method);
      model.add(method, "endpoint", FactObject.string("GET /m" + i));
      link(method, BILLING, null);
    }
    // One of them calls out more often than a flow holds.
    EntityId busy = EntityId.parse("method:com.example.OrdersController#m0()");
    for (int i = 0; i < Flows.MAX_MESSAGES + 5; i++) {
      ObjectNode at = FactJson.newObject();
      at.put("source", busy.toString());
      model.add(
          SHOP,
          "CALLS_HTTP",
          FactObject.entity(EntityId.parse("external-system:host" + i + ".example")),
          at);
    }

    List<Fact> derived = model.derived(Flows.NAME);

    List<Fact> flows =
        derived.stream().filter(f -> f.predicate().equals(Types.TRIGGERED_BY)).toList();
    assertThat(flows).hasSize(10);
    Fact busyFlow =
        flows.stream().filter(f -> f.object().entityId().equals(busy)).findFirst().orElseThrow();
    Fact shape = only(TestModel.about(derived, busyFlow.subject(), Types.FLOW_SHAPE));
    assertThat(shape.attributes().get("truncated").asBoolean()).isTrue();
    assertThat(shape.attributes().get("messages").asInt()).isEqualTo(Flows.MAX_MESSAGES);
    assertThat(steps(derived, busyFlow.subject()).getLast().attributes().get("action").asString())
        .isEqualTo(FlowModel.TRUNCATED);
  }

  @Test
  void aFlowOfAnotherFlowsShapeIsLinkedToIt() {
    Fact place = system();
    model.add(ORDERS, Types.CONTAINS, CANCEL);
    Fact cancel = model.add(CANCEL, "endpoint", FactObject.string("DELETE /orders"));
    model.add(PLACE, Types.CALLS, NOTIFY);
    model.add(CANCEL, Types.CALLS, NOTIFY);
    link(NOTIFY, BILLING, null);

    List<Fact> derived = model.derived(Flows.NAME);

    EntityId first = Flows.id(SHOP, place);
    EntityId second = Flows.id(SHOP, cancel);
    EntityId later = first.key().compareTo(second.key()) < 0 ? second : first;
    EntityId representative = later == first ? second : first;
    assertThat(TestModel.about(derived, later, Types.SAME_SHAPE_AS))
        .extracting(f -> f.object().entityId())
        .containsExactly(representative);
    assertThat(TestModel.about(derived, representative, Types.SAME_SHAPE_AS)).isEmpty();
    assertThat(only(TestModel.about(derived, first, Types.FLOW_SHAPE)).object())
        .isEqualTo(only(TestModel.about(derived, second, Types.FLOW_SHAPE)).object());
  }

  @Test
  void stepsOnOneLineToOneTargetKeepTheirOrderWhenTheirFactIdsChange() {
    // Two calls from one line of place() to billing, as one call site resolved two ways; the
    // facts' IDs rest on what they cite, which an edit of their file changes (#881).
    assertThat(pathsInOrder("a".repeat(64))).isEqualTo(pathsInOrder("b".repeat(64)));
    assertThat(pathsInOrder("a".repeat(64))).containsExactly("/fallback", "/public");
  }

  private static List<String> pathsInOrder(String cites) {
    FlowsTest test = new FlowsTest();
    Fact endpoint = test.system();
    for (String path : List.of("/public", "/fallback")) {
      ObjectNode at = FactJson.newObject();
      at.put("source", PLACE.toString());
      at.put("path", path);
      at.putArray("facts").add(cites + path);
      test.model.facts.add(
          Fact.create(
                  SHOP,
                  "CALLS_HTTP",
                  FactObject.entity(BILLING),
                  at,
                  new Origin(Origin.Kind.STATIC, "java", "1"),
                  Evidence.of("shop", "src/Orders.java", new Evidence.LineRange(7, 7), null),
                  1.0,
                  Validity.from(1))
              .stored(TestModel.claim("shop:CALLS_HTTP:" + path), Validity.from(1)));
    }
    List<Fact> derived = test.model.derived(Flows.NAME);
    EntityId flow = Flows.id(SHOP, endpoint);
    Map<String, String> pathOfClaim =
        test.model.facts.stream()
            .filter(f -> f.predicate().equals("CALLS_HTTP"))
            .collect(Collectors.toMap(Fact::claimId, f -> f.attributes().get("path").asString()));
    return steps(derived, flow).stream()
        .map(s -> pathOfClaim.get(s.lineage().getFirst()))
        .filter(java.util.Objects::nonNull)
        .toList();
  }

  /** shop's OrdersController#place is POST /orders; billing's Payments#charge is POST /charge. */
  private Fact system() {
    for (EntityId[] unit :
        List.of(new EntityId[] {SHOP, SHOP_MODULE}, new EntityId[] {BILLING, BILLING_MODULE})) {
      model.add(unit[0], "deployable-unit", FactObject.string("spring-boot"));
      model.add(unit[0], Types.CONTAINS, unit[1]);
    }
    model.add(SHOP_MODULE, Types.CONTAINS, ORDERS);
    model.add(SHOP_MODULE, Types.CONTAINS, HELPER);
    model.add(BILLING_MODULE, Types.CONTAINS, PAYMENTS);
    model.add(ORDERS, Types.CONTAINS, PLACE);
    model.add(HELPER, Types.CONTAINS, NOTIFY);
    model.add(PAYMENTS, Types.CONTAINS, CHARGE);
    model.add(CHARGE, "endpoint", FactObject.string("POST /charge"));
    return model.add(PLACE, "endpoint", FactObject.string("POST /orders"));
  }

  /** A unit's HTTP call from the method's code to another unit, reaching the handler, if any. */
  private Fact link(EntityId from, EntityId unit, EntityId handler) {
    ObjectNode at = FactJson.newObject();
    at.put("source", from.toString());
    if (handler != null) {
      at.put("target", handler.toString());
    }
    EntityId owner = from.equals(CHARGE) ? BILLING : SHOP;
    return model.add(owner, "CALLS_HTTP", FactObject.entity(unit), at);
  }

  private Fact send(EntityId from, EntityId destination) {
    ObjectNode at = FactJson.newObject();
    at.put("source", from.toString());
    at.put("protocol", "Kafka");
    return model.add(BILLING, "PUBLISHES", FactObject.entity(destination), at);
  }

  private static List<Fact> steps(List<Fact> derived, EntityId flow) {
    return derived.stream()
        .filter(f -> f.predicate().equals(Types.STEP_OF) && f.object().entityId().equals(flow))
        .sorted(Comparator.comparingInt(f -> f.attributes().get("order").asInt()))
        .toList();
  }

  private static Set<EntityId> stepIds(List<Fact> derived) {
    return derived.stream()
        .filter(f -> f.predicate().equals(Types.STEP_OF))
        .map(Fact::subject)
        .collect(Collectors.toSet());
  }

  private static <T> T only(List<T> list) {
    assertThat(list).hasSize(1);
    return list.getFirst();
  }
}

package io.lantern.derive;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.types.Types;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/** The Unknown caller: a line to each unit with entry points nothing reaches (#766). */
class UnknownCallersTest {

  private static final EntityId SHOP = EntityId.parse("service:com.example:shop");
  private static final EntityId BILLING = EntityId.parse("service:com.example:billing");
  private static final EntityId EDGE = EntityId.parse("service:com.example:edge");
  private static final EntityId UI = EntityId.parse("frontend:shop-ui");
  private static final EntityId ORDERS = EntityId.parse("type:com.example.OrdersController");
  private static final EntityId PAYMENTS = EntityId.parse("type:com.example.billing.Payments");
  private static final EntityId PLACE =
      EntityId.parse("method:com.example.OrdersController#place()");
  private static final EntityId NIGHTLY =
      EntityId.parse("method:com.example.OrdersController#nightly()");
  private static final EntityId CHARGE =
      EntityId.parse("method:com.example.billing.Payments#charge()");
  private static final EntityId REFUNDED =
      EntityId.parse("method:com.example.billing.Payments#refunded()");
  private static final EntityId LIST = EntityId.parse("method:com.example.OrdersController#list()");
  private static final EntityId TOPIC = EntityId.parse("topic:kafka:refunds");
  private static final EntityId PERSON = EntityId.parse("person:customer");

  private final TestModel model = new TestModel();

  @Test
  void aUnitWhoseEndpointNothingReachesIsCalledByTheUnknownCallerAndAReachedOneIsNot() {
    Fact place = system();
    call(SHOP, PLACE, BILLING, CHARGE);
    model.add(NIGHTLY, Types.SCHEDULED, FactObject.string("cron 0 0 2 * * *"));

    List<Fact> lines = lines();

    assertThat(lines).extracting(f -> f.object().entityId()).containsExactly(SHOP);
    Fact line = lines.getFirst();
    assertThat(line.subject()).isEqualTo(UnknownCallers.CALLER);
    // The scheduled job is no entry point meant to be called.
    assertThat(Screens.strings(line, "facts")).containsExactly(place.id());
    assertThat(violations()).isEmpty();
  }

  @Test
  void anEndpointAFrontendReachesOrAPersonUsesGetsNoLine() {
    system();
    call(SHOP, PLACE, BILLING, CHARGE);
    ObjectNode at = FactJson.newObject();
    at.put("reachesUnit", SHOP.toString());
    at.put("reaches", PLACE.toString());
    model.add(UI, Types.CALLS_HTTP, FactObject.entity(SHOP), at);

    assertThat(lines()).isEmpty();

    TestModel other = new TestModel();
    system(other);
    call(other, SHOP, PLACE, BILLING, CHARGE);
    Fact account = other.add(PLACE, Types.PERSON_EVIDENCE, FactObject.string("account"));
    ObjectNode basis = FactJson.newObject();
    basis.putArray("cites").add(account.id());
    other.add(PERSON, Types.PERSON_BASIS, FactObject.string("account"), basis);

    assertThat(lines(other)).isEmpty();
  }

  @Test
  void aCallThatReachesAUnitWithoutNamingItsEndpointLeavesNoneOfItsEndpointsUnreached() {
    system();
    call(SHOP, PLACE, BILLING, CHARGE);
    // A call to shop matched to the unit but to none of its endpoints.
    ObjectNode at = FactJson.newObject();
    at.put("source", CHARGE.toString());
    model.add(BILLING, Types.CALLS_HTTP, FactObject.entity(SHOP), at);

    assertThat(lines()).isEmpty();
    assertThat(violations()).isEmpty();
  }

  @Test
  void anEndpointAGatewayRoutesToIsReachedThroughItAndTheGatewayNothingCallsGetsTheLine() {
    system();
    call(SHOP, PLACE, BILLING, CHARGE);
    model.add(EDGE, "deployable-unit", FactObject.string("spring-boot"));
    ObjectNode at = FactJson.newObject();
    at.put("target", SHOP.toString());
    at.put("endpoint", PLACE.toString());
    Fact route = model.add(EDGE, Types.ROUTED_ENDPOINT, FactObject.string("POST /orders"), at);

    List<Fact> lines = lines();

    assertThat(lines).extracting(f -> f.object().entityId()).containsExactly(EDGE);
    assertThat(Screens.strings(lines.getFirst(), "facts")).containsExactly(route.id());
    assertThat(violations()).isEmpty();

    model.add(UI, Types.CALLS_HTTP, FactObject.entity(EDGE), FactJson.newObject());
    assertThat(lines()).isEmpty();
  }

  @Test
  void anEndpointAGatewaysFollowedRoutesDoNotReachGetsALineThoughTheGatewayRoutesToItsUnit() {
    system();
    call(SHOP, PLACE, BILLING, CHARGE);
    Fact list = gatewayToShop();
    routed(PLACE);
    unrouted(LIST, "GET /orders/all");

    List<Fact> lines = lines();

    assertThat(lines).extracting(f -> f.object().entityId()).containsExactly(SHOP);
    assertThat(Screens.strings(lines.getFirst(), "facts")).containsExactly(list.id());
    assertThat(violations()).isEmpty();
  }

  @Test
  void aRouteTheLinkerDidNotFollowLeavesNoEndpointOfItsUnitUnreached() {
    system();
    call(SHOP, PLACE, BILLING, CHARGE);
    gatewayToShop();

    assertThat(lines()).isEmpty();
  }

  @Test
  void theCheckFindsALineCitingAnEndpointAGatewayRoutesTo() {
    system();
    call(SHOP, PLACE, BILLING, CHARGE);
    Fact list = gatewayToShop();
    routed(PLACE);
    unrouted(LIST, "GET /orders/all");
    Fact place =
        model.facts.stream()
            .filter(f -> f.subject().equals(PLACE) && f.predicate().equals(Types.ENDPOINT))
            .findFirst()
            .orElseThrow();
    List<Fact> facts = new java.util.ArrayList<>(Derivation.withModel(model.facts, 1));
    ObjectNode at = FactJson.newObject();
    at.putArray("facts").add(place.id()).add(list.id());
    facts.add(
        Fact.create(
            UnknownCallers.CALLER,
            Types.CALLS_UNREACHED,
            FactObject.entity(SHOP),
            at,
            place.origin(),
            place.evidence(),
            1.0,
            place.validity()));

    assertThat(UnknownCallers.violations(new Knowledge(facts)))
        .singleElement()
        .asString()
        .contains(PLACE + " ")
        .contains("is reached by " + EDGE + " " + Types.ROUTED_ENDPOINT);
  }

  @Test
  void aListenerOnATopicNothingPublishesToIsCalledByTheUnknownCaller() {
    system();
    call(SHOP, PLACE, BILLING, CHARGE);
    call(BILLING, CHARGE, SHOP, PLACE);
    model.add(PAYMENTS, Types.CONTAINS, REFUNDED);
    Fact listener = model.add(REFUNDED, Types.MESSAGE_LISTENER, FactObject.string("refunds"));
    ObjectNode consumes = FactJson.newObject();
    consumes.put("source", REFUNDED.toString());
    model.add(BILLING, Types.CONSUMES, FactObject.entity(TOPIC), consumes);

    List<Fact> lines = lines();
    assertThat(lines).extracting(f -> f.object().entityId()).containsExactly(BILLING);
    assertThat(Screens.strings(lines.getFirst(), "facts")).containsExactly(listener.id());

    ObjectNode publishes = FactJson.newObject();
    publishes.put("source", PLACE.toString());
    model.add(SHOP, Types.PUBLISHES, FactObject.entity(TOPIC), publishes);
    assertThat(lines()).isEmpty();
  }

  @Test
  void theCheckFindsALineCitingAnEntryPointSomethingReaches() {
    system();
    Fact charge =
        model.facts.stream()
            .filter(f -> f.subject().equals(CHARGE) && f.predicate().equals(Types.ENDPOINT))
            .findFirst()
            .orElseThrow();
    call(SHOP, PLACE, BILLING, CHARGE);
    List<Fact> facts = Derivation.withModel(model.facts, 1);
    ObjectNode at = FactJson.newObject();
    at.putArray("facts").add(charge.id());
    Fact wrong =
        Fact.create(
            UnknownCallers.CALLER,
            Types.CALLS_UNREACHED,
            FactObject.entity(BILLING),
            at,
            charge.origin(),
            charge.evidence(),
            1.0,
            charge.validity());
    List<Fact> broken = new java.util.ArrayList<>(facts);
    broken.add(wrong);

    assertThat(UnknownCallers.violations(new Knowledge(facts))).isEmpty();
    // A line citing another unit's endpoint is wrong too.
    ObjectNode elsewhere = FactJson.newObject();
    elsewhere.putArray("facts").add(charge.id());
    List<Fact> misplaced = new java.util.ArrayList<>(facts);
    misplaced.add(
        Fact.create(
            UnknownCallers.CALLER,
            Types.CALLS_UNREACHED,
            FactObject.entity(SHOP),
            elsewhere,
            charge.origin(),
            charge.evidence(),
            1.0,
            charge.validity()));
    assertThat(UnknownCallers.violations(new Knowledge(misplaced)))
        .anyMatch(v -> v.contains("is not in the code of " + SHOP));
    assertThat(UnknownCallers.violations(new Knowledge(broken)))
        .singleElement()
        .asString()
        .contains("is reached by " + SHOP);
  }

  private Fact system() {
    return system(model);
  }

  /**
   * A gateway the frontend calls, routing to shop, which gets a second endpoint, list; returns
   * list's endpoint fact.
   */
  private Fact gatewayToShop() {
    model.add(EDGE, "deployable-unit", FactObject.string("spring-boot"));
    model.add(UI, Types.CALLS_HTTP, FactObject.entity(EDGE), FactJson.newObject());
    ObjectNode route = FactJson.newObject();
    route.put("route", "orders");
    model.add(EDGE, Types.CALLS_HTTP, FactObject.entity(SHOP), route);
    model.add(ORDERS, Types.CONTAINS, LIST);
    return model.add(LIST, Types.ENDPOINT, FactObject.string("GET /orders/all"));
  }

  private void routed(EntityId endpoint) {
    ObjectNode at = FactJson.newObject();
    at.put("target", SHOP.toString());
    at.put("endpoint", endpoint.toString());
    model.add(EDGE, Types.ROUTED_ENDPOINT, FactObject.string("POST /orders"), at);
  }

  private void unrouted(EntityId endpoint, String text) {
    ObjectNode at = FactJson.newObject();
    at.put("target", SHOP.toString());
    at.put("endpoint", endpoint.toString());
    model.add(EDGE, Types.UNROUTED_ENDPOINT, FactObject.string(text), at);
  }

  /** Two units, shop and billing, each with one endpoint; returns shop's. */
  private static Fact system(TestModel model) {
    EntityId shopModule = EntityId.parse("module:com.example:shop");
    EntityId billingModule = EntityId.parse("module:com.example:billing");
    model.add(SHOP, "deployable-unit", FactObject.string("spring-boot"));
    model.add(SHOP, Types.CONTAINS, shopModule);
    model.add(BILLING, "deployable-unit", FactObject.string("spring-boot"));
    model.add(BILLING, Types.CONTAINS, billingModule);
    model.add(shopModule, Types.CONTAINS, ORDERS);
    model.add(billingModule, Types.CONTAINS, PAYMENTS);
    model.add(ORDERS, Types.CONTAINS, PLACE);
    model.add(ORDERS, Types.CONTAINS, NIGHTLY);
    model.add(PAYMENTS, Types.CONTAINS, CHARGE);
    model.add(CHARGE, Types.ENDPOINT, FactObject.string("POST /charge"));
    return model.add(PLACE, Types.ENDPOINT, FactObject.string("POST /orders"));
  }

  private void call(EntityId unit, EntityId from, EntityId to, EntityId handler) {
    call(model, unit, from, to, handler);
  }

  private static void call(
      TestModel model, EntityId unit, EntityId from, EntityId to, EntityId handler) {
    ObjectNode at = FactJson.newObject();
    at.put("source", from.toString());
    at.put("target", handler.toString());
    model.add(unit, Types.CALLS_HTTP, FactObject.entity(to), at);
  }

  private List<Fact> lines() {
    return lines(model);
  }

  private static List<Fact> lines(TestModel model) {
    return model.derived(UnknownCallers.NAME).stream()
        .filter(f -> f.predicate().equals(Types.CALLS_UNREACHED))
        .toList();
  }

  private List<String> violations() {
    return UnknownCallers.violations(new Knowledge(Derivation.withModel(model.facts, 1)));
  }
}

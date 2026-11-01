package io.lantern.derive;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.vocabulary.BuiltInTypes;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** The rules of the Investigate lead catalog (#524). */
class LeadRulesTest {

  private static final EntityId UNIT = EntityId.parse("service:com.acme:shop");
  private static final EntityId OTHER = EntityId.parse("service:com.acme:billing");
  private static final EntityId MODULE = EntityId.parse("module:com.acme:shop");
  private static final EntityId BILLING_MODULE = EntityId.parse("module:com.acme:billing");
  private static final EntityId CONTROLLER = EntityId.parse("type:com.acme.OrderController");
  private static final EntityId SERVICE = EntityId.parse("type:com.acme.OrderService");
  private static final EntityId DELETE = method(CONTROLLER, "delete(long)");
  private static final EntityId CREATE = method(CONTROLLER, "create()");
  private static final EntityId GET = method(CONTROLLER, "get(long)");
  private static final EntityId PLACE = method(SERVICE, "place()");
  private static final EntityId PUBLISH = method(SERVICE, "publish()");

  private final TestModel model = new TestModel();

  @Test
  void theCatalogNamesEveryRuleOnce() throws IOException {
    List<String> kinds = new ArrayList<>();
    try (InputStream in = LeadRule.class.getResourceAsStream("/leads.json")) {
      for (JsonNode entry : tools.jackson.databind.json.JsonMapper.builder().build().readTree(in)) {
        kinds.add(entry.get("kind").asString());
      }
    }
    assertThat(kinds).isEqualTo(LeadRules.all().stream().map(LeadRule::kind).toList());
  }

  @Test
  void anIdentityHeaderReadByAUnitIsOneLeadCitingEveryRead() {
    shop();
    Fact first = model.add(GET, "reads-request-value", FactObject.string("X-User-Id"), header());
    Fact second =
        model.add(DELETE, "reads-request-value", FactObject.string("X-User-Id"), header());
    model.add(GET, "reads-request-value", FactObject.string("Accept-Language"), header());

    List<Fact> leads = leads("identity-from-request");

    assertThat(keys(leads))
        .containsExactly("identity-from-request/service:com.acme:shop/header:X-User-Id");
    assertThat(leads.getFirst().lineage())
        .containsExactlyInAnyOrder(first.claimId(), second.claimId());
    assertThat(strings(leads.getFirst(), "code"))
        .containsExactly(DELETE.toString(), GET.toString());
    assertThat(leads.getFirst().attributes().get("content").asString()).startsWith("sha256:");
  }

  @Test
  void aSendATransactionReachesIsALeadUnlessAnOutboxStandsBetween() {
    shop();
    model.add(PLACE, "transactional", FactObject.string("REQUIRED"), kind("transaction"));
    model.add(PLACE, BuiltInTypes.CALLS, PUBLISH);
    Fact send = model.add(PUBLISH, "message-send", FactObject.string("orders"));

    List<Fact> leads = leads("send-before-commit");

    assertThat(keys(leads)).containsExactly("send-before-commit/service:com.acme:shop/" + PUBLISH);
    assertThat(leads.getFirst().lineage()).contains(send.claimId());
  }

  @Test
  void aSendThroughAnOutboxIsNotALead() {
    shop();
    EntityId outbox = EntityId.parse("type:com.acme.OrderOutbox");
    model.add(MODULE, BuiltInTypes.CONTAINS, outbox);
    EntityId store = method(outbox, "store()");
    model.add(outbox, BuiltInTypes.CONTAINS, store);
    model.add(PLACE, "transactional", FactObject.string("REQUIRED"), kind("transaction"));
    model.add(PLACE, BuiltInTypes.CALLS, store);
    model.add(store, "message-send", FactObject.string("orders"));

    assertThat(leads("send-before-commit")).isEmpty();
  }

  @Test
  void aReadOnlyMethodInATransactionalClassStartsNoSearch() {
    shop();
    model.add(SERVICE, "transactional", FactObject.string("REQUIRED"), kind("transaction"));
    ObjectNode readOnly = kind("transaction");
    readOnly.put("readOnly", true);
    model.add(PLACE, "transactional", FactObject.string("REQUIRED"), readOnly);
    model.add(PLACE, BuiltInTypes.CALLS, PUBLISH);
    model.add(PUBLISH, "message-send", FactObject.string("orders"));

    // publish() itself runs in the class's transaction and sends, so it is the only lead's start.
    assertThat(leads("send-before-commit")).hasSize(1);
    assertThat(strings(leads("send-before-commit").getFirst(), "code"))
        .containsExactly(PUBLISH.toString());
  }

  @Test
  void aCallMatchingNoEndpointOfItsUnitIsAMismatch() {
    shop();
    billing();
    ObjectNode call = FactJson.newObject();
    call.put("path", "/api/invoces");
    call.put("httpMethod", "GET");
    call.put("source", PLACE.toString());
    model.add(UNIT, "CALLS_HTTP", FactObject.entity(OTHER), call);
    ObjectNode matched = call.deepCopy();
    matched.put("path", "/api/invoices");
    matched.put(
        "target", method(EntityId.parse("type:com.acme.InvoiceController"), "list()").toString());
    model.add(UNIT, "CALLS_HTTP", FactObject.entity(OTHER), matched);

    assertThat(keys(leads("contract-mismatch")))
        .containsExactly("contract-mismatch/service:com.acme:shop/GET%20/api/invoces");
  }

  @Test
  void aDeleteGuardedByAReadPermissionOrUnguardedAmongGuardedEndpointsIsWeak() {
    shop();
    model.add(DELETE, "endpoint", FactObject.string("DELETE /orders/{id}"), verb("DELETE"));
    model.add(CREATE, "endpoint", FactObject.string("POST /orders"), verb("POST"));
    model.add(GET, "endpoint", FactObject.string("GET /orders/{id}"), verb("GET"));
    model.add(DELETE, "custom-access-check", FactObject.string("resource=ORDERS, action=READ"));
    model.add(GET, "custom-access-check", FactObject.string("resource=ORDERS, action=READ"));

    assertThat(keys(leads("weak-access-check")))
        .containsExactly(
            "weak-access-check/service:com.acme:shop/" + CREATE,
            "weak-access-check/service:com.acme:shop/" + DELETE);
  }

  @Test
  void anUnguardedWriteInAUnitThatChecksNothingIsNoLead() {
    shop();
    model.add(CREATE, "endpoint", FactObject.string("POST /orders"), verb("POST"));

    assertThat(leads("weak-access-check")).isEmpty();
  }

  @Test
  void aPayloadOfALibraryBundledAtTwoVersionsIsSkewed() {
    shop();
    billing();
    EntityId library = EntityId.parse("module:com.acme:events");
    EntityId event = EntityId.parse("type:com.acme.events.OrderPlaced");
    model.add(library, BuiltInTypes.CONTAINS, event);
    model.add(UNIT, "BUNDLES", EntityId.parse("library:com.acme:events"));
    ObjectNode pins = FactJson.newObject();
    pins.putObject("pins").put(UNIT.toString(), "1.0").put(OTHER.toString(), "2.0");
    model.add(
        EntityId.parse("library:com.acme:events"), "version-skew", FactObject.string("2.0"), pins);
    model.add(PUBLISH, "payload-type", FactObject.string(event.key()), role("send"));
    EntityId listener = method(EntityId.parse("type:com.acme.BillingListener"), "on()");
    model.add(
        BILLING_MODULE, BuiltInTypes.CONTAINS, EntityId.parse("type:com.acme.BillingListener"));
    model.add(EntityId.parse("type:com.acme.BillingListener"), BuiltInTypes.CONTAINS, listener);
    model.add(listener, "payload-type", FactObject.string(event.key()), role("receive"));

    assertThat(keys(leads("shared-type-skew")))
        .containsExactly("shared-type-skew/library:com.acme:events/" + event.key());
  }

  @Test
  void aSimpleStompBrokerWithNoRelayRunsInOneInstance() {
    shop();
    model.add(PLACE, "stomp-prefix", FactObject.string("/topic"), kind("simple-broker"));

    assertThat(keys(leads("single-instance")))
        .containsExactly("single-instance/service:com.acme:shop/stomp-simple-broker");
  }

  @Test
  void secretsWrittenInPlainText() {
    shop();
    ObjectNode masked = FactJson.newObject();
    masked.put("key", "spring.datasource.password");
    masked.put("masked", true);
    model.add(MODULE, "config-value", FactObject.string(""), masked);
    ObjectNode placeholder = FactJson.newObject();
    placeholder.put("key", "spring.mail.password");
    model.add(MODULE, "config-value", FactObject.string("${MAIL_PASSWORD}"), placeholder);
    ObjectNode cipher = FactJson.newObject();
    cipher.put("key", "spring.rabbitmq.password");
    cipher.put("masked", true);
    cipher.put("written", "{cipher}AQB");
    model.add(MODULE, "config-value", FactObject.string("x"), cipher);

    assertThat(keys(leads("plaintext-secret")))
        .containsExactly("plaintext-secret/module:com.acme:shop/spring.datasource.password");
  }

  @Test
  void aDefaultBuildCallingAPrivateAddressIsBaked() {
    EntityId frontend = EntityId.parse("frontend:shop-web");
    ObjectNode built = FactJson.newObject();
    built.put("name", "apiUrl");
    built.put("source", "environment");
    built.put("defaultBuild", true);
    model.add(frontend, "frontend-url", FactObject.string("http://192.168.1.10:8080"), built);
    ObjectNode local = built.deepCopy();
    local.remove("defaultBuild");
    model.add(frontend, "frontend-url", FactObject.string("http://localhost:8080"), local);

    assertThat(keys(leads("environment-baked")))
        .containsExactly("environment-baked/frontend:shop-web/apiUrl");
  }

  @Test
  void corsAllowingEveryOriginIsOpen() {
    shop();
    ObjectNode open = FactJson.newObject();
    open.put("path", "/**");
    open.putArray("origins").add("${cors.origins:*}");
    model.add(PLACE, "cors-policy", FactObject.string("${cors.origins:*}"), open);
    ObjectNode closed = FactJson.newObject();
    closed.put("path", "/api/**");
    closed.putArray("origins").add("https://shop.example");
    model.add(PLACE, "cors-policy", FactObject.string("https://shop.example"), closed);

    assertThat(keys(leads("open-cors"))).containsExactly("open-cors/" + PLACE + "//**");
  }

  @Test
  void hibernateUpdatingASchemaWithoutMigrationsIsUnmanaged() {
    shop();
    billing();
    for (EntityId module : List.of(MODULE, BILLING_MODULE)) {
      ObjectNode ddl = FactJson.newObject();
      ddl.put("key", "spring.jpa.hibernate.ddl-auto");
      model.add(module, "config-value", FactObject.string("update"), ddl);
    }
    model.add(new EntityId("schema", BILLING_MODULE.key()), "kind", FactObject.string("flyway"));

    assertThat(keys(leads("schema-unmanaged")))
        .containsExactly("schema-unmanaged/module:com.acme:shop/default");
  }

  // --- Fixtures --------------------------------------------------------------------------------

  private void shop() {
    model.add(UNIT, "deployable-unit", FactObject.string("spring-boot"));
    model.add(UNIT, BuiltInTypes.CONTAINS, MODULE);
    for (EntityId type : List.of(CONTROLLER, SERVICE)) {
      model.add(MODULE, BuiltInTypes.CONTAINS, type);
    }
    for (EntityId m : List.of(DELETE, CREATE, GET)) {
      model.add(CONTROLLER, BuiltInTypes.CONTAINS, m);
    }
    for (EntityId m : List.of(PLACE, PUBLISH)) {
      model.add(SERVICE, BuiltInTypes.CONTAINS, m);
    }
  }

  private void billing() {
    model.add(OTHER, "deployable-unit", FactObject.string("spring-boot"));
    model.add(OTHER, BuiltInTypes.CONTAINS, BILLING_MODULE);
    EntityId invoices = EntityId.parse("type:com.acme.InvoiceController");
    model.add(BILLING_MODULE, BuiltInTypes.CONTAINS, invoices);
    EntityId list = method(invoices, "list()");
    model.add(invoices, BuiltInTypes.CONTAINS, list);
    model.add(list, "endpoint", FactObject.string("GET /api/invoices"), verb("GET"));
  }

  private List<Fact> leads(String kind) {
    return model.derived("lead-" + kind).stream()
        .sorted(java.util.Comparator.comparing(f -> f.subject().toString()))
        .toList();
  }

  private static List<String> keys(List<Fact> leads) {
    return leads.stream().map(f -> f.subject().key()).toList();
  }

  private static List<String> strings(Fact fact, String name) {
    List<String> values = new ArrayList<>();
    fact.attributes().get(name).forEach(v -> values.add(v.asString()));
    return values;
  }

  private static EntityId method(EntityId type, String signature) {
    return new EntityId("method", type.key() + "#" + signature);
  }

  private static ObjectNode header() {
    ObjectNode attributes = FactJson.newObject();
    attributes.put("source", "header");
    attributes.put("via", "binding");
    return attributes;
  }

  private static ObjectNode kind(String kind) {
    ObjectNode attributes = FactJson.newObject();
    attributes.put("kind", kind);
    return attributes;
  }

  private static ObjectNode verb(String verb) {
    ObjectNode attributes = FactJson.newObject();
    attributes.put("httpMethod", verb);
    return attributes;
  }

  private static ObjectNode role(String role) {
    ObjectNode attributes = FactJson.newObject();
    attributes.put("role", role);
    return attributes;
  }
}

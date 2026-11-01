package io.lantern.derive;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.vocabulary.BuiltInTypes;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/** Groups derived from what the code shows: feature areas and domains (#535). */
class GroupsTest {

  private static final EntityId BILLING = EntityId.parse("service:io.acme:billing");
  private static final EntityId BILLING_MODULE = EntityId.parse("module:io.acme:billing");

  private final TestModel model = new TestModel();

  @Test
  void featureFoldersAreTheAreasOfAUnitLaidOutByFeature() {
    unit(BILLING, BILLING_MODULE, "billing");
    controller("io.acme.billing.invoice", "InvoiceController", "GET /invoices");
    controller("io.acme.billing.invoice", "InvoiceEvents", "GET /invoices/events");
    controller("io.acme.billing.payment", "PaymentController", "POST /payments");
    controller("io.acme.billing.payment", "RefundController", "POST /refunds");
    controller("io.acme.billing.collections", "DunningController", "GET /dunning");
    controller("io.acme.billing.collections", "CollectionsController", "GET /collections");

    Map<String, Set<String>> areas = areas(BILLING);

    assertThat(areas)
        .containsOnlyKeys(area("pkg-collections"), area("pkg-invoice"), area("pkg-payment"))
        .containsEntry(
            area("pkg-invoice"),
            Set.of(
                component("io.acme.billing.invoice.InvoiceController"),
                component("io.acme.billing.invoice.InvoiceEvents")));
  }

  @Test
  void aLayeredUnitIsGroupedByTheResourceSegmentOfItsPaths() {
    unit(BILLING, BILLING_MODULE, "billing");
    controller("io.acme.billing.web", "OrderController", "GET /api/v1/orders/{id}");
    controller("io.acme.billing.web", "OrderItemController", "GET /api/v1/orders/{id}/items");
    controller("io.acme.billing.web", "Invoice1", "GET /api/v1/invoices");
    controller("io.acme.billing.web", "Invoice2", "POST /api/v1/invoices/{id}/send");
    controller("io.acme.billing.service", "Unused", null);

    Map<String, Set<String>> areas = areas(BILLING);

    // All paths share /api/v1: orders/{id}/items is an order resource, its first literal segment.
    assertThat(areas)
        .containsOnlyKeys(area("path-invoices"), area("path-orders"))
        .containsEntry(
            area("path-orders"),
            Set.of(
                component("io.acme.billing.web.OrderController"),
                component("io.acme.billing.web.OrderItemController")));
  }

  @Test
  void aComponentNoSignalPlacesJoinsTheAreaItCallsMost() {
    unit(BILLING, BILLING_MODULE, "billing");
    controller("io.acme.billing.web", "OrderController", "GET /orders");
    controller("io.acme.billing.web", "OrderAdmin", "GET /orders/admin");
    controller("io.acme.billing.web", "InvoiceController", "GET /invoices");
    controller("io.acme.billing.web", "InvoiceAdmin", "GET /invoices/admin");
    EntityId listener = controller("io.acme.billing.messaging", "OrderListener", null);
    link(listener, component("io.acme.billing.web.OrderController"));

    Map<String, Set<String>> areas = areas(BILLING);

    assertThat(areas.get(area("path-orders"))).contains(listener.toString());
    assertThat(areas.get(area("path-invoices"))).doesNotContain(listener.toString());
  }

  @Test
  void addingAnEndpointToAnAreaKeepsEveryGroupKey() {
    unit(BILLING, BILLING_MODULE, "billing");
    controller("io.acme.billing.invoice", "InvoiceController", "GET /invoices");
    controller("io.acme.billing.invoice", "InvoiceEvents", "GET /invoices/events");
    controller("io.acme.billing.payment", "PaymentController", "POST /payments");
    controller("io.acme.billing.payment", "RefundController", "POST /refunds");
    controller("io.acme.billing.collections", "DunningController", "GET /dunning");
    controller("io.acme.billing.collections", "CollectionsController", "GET /collections");
    Set<String> before = areas(BILLING).keySet();

    controller("io.acme.billing.payment", "ChargebackController", "POST /chargebacks");

    Map<String, Set<String>> after = areas(BILLING);
    assertThat(after.keySet()).isEqualTo(before);
    assertThat(after.get(area("pkg-payment")))
        .contains(component("io.acme.billing.payment.ChargebackController"));
  }

  @Test
  void unitsSharingARelationalDatabaseAreADomainAndRedisDoesNotCount() {
    EntityId orders = EntityId.parse("service:io.acme:orders");
    EntityId stock = EntityId.parse("service:io.acme:stock");
    EntityId mail = EntityId.parse("service:io.acme.mail:mailer");
    EntityId audit = EntityId.parse("service:io.acme.mail:audit");
    for (EntityId unit : List.of(BILLING, orders, stock, mail, audit)) {
      model.add(unit, "deployable-unit", FactObject.string("spring-boot"));
    }
    uses(BILLING, "database:postgresql:shop", "JDBC");
    uses(orders, "database:postgresql:shop", "JDBC");
    uses(stock, "database:redis:cache", "RESP");
    uses(mail, "database:redis:cache", "RESP");

    Map<String, Set<String>> domains = domains();

    // The rest share the Maven group segment after io.acme: mail; stock is left alone.
    assertThat(domains)
        .containsOnlyKeys(domain("db-postgresql:shop"), domain("maven-mail"))
        .containsEntry(domain("db-postgresql:shop"), Set.of(BILLING.toString(), orders.toString()))
        .containsEntry(domain("maven-mail"), Set.of(mail.toString(), audit.toString()));
  }

  @Test
  void aScopeYieldingOneGroupStoresNone() {
    EntityId orders = EntityId.parse("service:io.acme:orders");
    for (EntityId unit : List.of(BILLING, orders)) {
      model.add(unit, "deployable-unit", FactObject.string("spring-boot"));
      uses(unit, "database:postgresql:shop", "JDBC");
    }

    assertThat(domains()).isEmpty();
  }

  @Test
  void linesBetweenGroupsAreOnePerOrderedPairAndProtocol() {
    EntityId a = EntityId.parse("group:system/domain/db-a");
    EntityId b = EntityId.parse("group:system/domain/db-b");
    EntityId a1 = EntityId.parse("service:g:a1");
    EntityId a2 = EntityId.parse("service:g:a2");
    EntityId b1 = EntityId.parse("service:g:b1");
    EntityId lone = EntityId.parse("service:g:lone");
    Fact f1 = model.add(a1, "CALLS_HTTP", b1);
    Fact f2 = model.add(a2, "CALLS_HTTP", b1);
    Fact f3 = model.add(a1, "CALLS_HTTP", a2);
    Fact f4 = model.add(lone, "CALLS_HTTP", a1);
    List<Groups.Group> groups =
        List.of(
            new Groups.Group(a, Groups.DOMAIN, Groups.SYSTEM, "database", set(a1, a2)),
            new Groups.Group(b, Groups.DOMAIN, Groups.SYSTEM, "database", set(b1)));

    List<Groups.Line> lines =
        Groups.lines(
            groups,
            List.of(
                new Groups.Line(a1, b1, "HTTP", List.of(f1)),
                new Groups.Line(a2, b1, "HTTP", List.of(f2)),
                new Groups.Line(a1, a2, "HTTP", List.of(f3)),
                new Groups.Line(lone, a1, "HTTP", List.of(f4))));

    assertThat(lines)
        .extracting(l -> l.from() + " " + l.to() + " " + l.facts().size())
        .containsExactly(a + " " + b + " 2", lone + " " + a + " 1");
  }

  // The model.

  private void unit(EntityId unit, EntityId module, String repo) {
    model.add(unit, "deployable-unit", FactObject.string("spring-boot"));
    model.add(unit, BuiltInTypes.CONTAINS, module);
  }

  /** A controller (an entry point) with an endpoint, or a listener when the endpoint is null. */
  private EntityId controller(String pkg, String name, String endpoint) {
    EntityId pkgId = EntityId.parse("package:io.acme:billing:" + pkg);
    EntityId type = EntityId.parse("type:" + pkg + "." + name);
    EntityId method = EntityId.parse("method:" + pkg + "." + name + "#handle()");
    model.add(BILLING_MODULE, BuiltInTypes.CONTAINS, pkgId);
    model.add(pkgId, BuiltInTypes.CONTAINS, type);
    model.add(type, BuiltInTypes.CONTAINS, method);
    model.add(type, "stereotype", FactObject.string("controller"));
    if (endpoint != null) {
      model.add(method, "endpoint", FactObject.string(endpoint));
    } else {
      model.add(method, "message-listener", FactObject.string("orders"));
    }
    return EntityId.parse(component(pkg + "." + name));
  }

  private void link(EntityId from, String to) {
    model.add(from, ComponentInteractions.COMPONENT_LINK, EntityId.parse(to));
  }

  private void uses(EntityId unit, String database, String protocol) {
    ObjectNode attributes = FactJson.newObject();
    attributes.put("protocol", protocol);
    model.add(unit, "USES_DATABASE", FactObject.entity(EntityId.parse(database)), attributes);
  }

  private static String component(String type) {
    return Components.id(BILLING, EntityId.parse("type:" + type)).toString();
  }

  private static String area(String signal) {
    return Groups.id(BILLING.toString(), Groups.FEATURE_AREA, signal).toString();
  }

  private static String domain(String signal) {
    return Groups.id(Groups.SYSTEM, Groups.DOMAIN, signal).toString();
  }

  private Map<String, Set<String>> areas(EntityId unit) {
    return groups(Groups.FEATURE_AREA);
  }

  private Map<String, Set<String>> domains() {
    return groups(Groups.DOMAIN);
  }

  private Map<String, Set<String>> groups(String kind) {
    Knowledge knowledge = new Knowledge(Derivation.withModel(model.facts, 1));
    Map<String, Set<String>> groups = new TreeMap<>();
    for (Groups.Group group : Groups.of(knowledge, kind)) {
      Set<String> members = new java.util.TreeSet<>();
      group.members().forEach(m -> members.add(m.toString()));
      groups.put(group.id().toString(), members);
    }
    return groups;
  }

  private static java.util.SortedSet<EntityId> set(EntityId... ids) {
    java.util.SortedSet<EntityId> set = new java.util.TreeSet<>(Knowledge.BY_ID);
    set.addAll(List.of(ids));
    return set;
  }
}

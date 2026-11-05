package io.lantern.derive;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.types.Types;
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
    // A bean in another package tree does not move the base the entry points are below.
    shared("com.other.directory.client", "DirectoryClient");

    Map<String, Set<String>> areas = areas(BILLING);

    assertThat(areas)
        .containsOnlyKeys(area("pkg-collections"), area("pkg-invoice"), area("pkg-payment"));
    // The client both invoice and payment call joins the one whose key sorts first.
    assertThat(areas.get(area("pkg-invoice")))
        .containsExactlyInAnyOrder(
            component("io.acme.billing.invoice.InvoiceController"),
            component("io.acme.billing.invoice.InvoiceEvents"),
            component("com.other.directory.client.DirectoryClient"));
  }

  @Test
  void aspectsAndLibraryClassesJoinNoArea() {
    unit(BILLING, BILLING_MODULE, "billing");
    EntityId invoice = controller("io.acme.billing.invoice", "InvoiceController", "GET /invoices");
    controller("io.acme.billing.payment", "PaymentController", "POST /payments");
    controller("io.acme.billing.invoice", "InvoiceEvents", "GET /invoices/events");
    controller("io.acme.billing.payment", "RefundController", "POST /refunds");
    controller("io.acme.billing.collections", "DunningController", "GET /dunning");
    controller("io.acme.billing.collections", "CollectionsController", "GET /collections");
    // An aspect of the unit's own code advising the invoice controller, and calling into it.
    EntityId aspect = EntityId.parse("type:io.acme.billing.security.AuditAspect");
    EntityId security = EntityId.parse("package:io.acme:billing:io.acme.billing.security");
    model.add(BILLING_MODULE, Types.CONTAINS, security);
    model.add(security, Types.CONTAINS, aspect);
    model.add(
        aspect,
        "ADVISES",
        EntityId.parse("method:io.acme.billing.invoice.InvoiceController#handle()"));
    // A class of a library the unit bundles, which both controllers call.
    EntityId library = EntityId.parse("library:io.acme:commons");
    EntityId libraryModule = EntityId.parse("module:io.acme:commons");
    model.add(BILLING, "BUNDLES", library);
    model.add(library, Types.CONTAINS, libraryModule);
    EntityId messaging = EntityId.parse("package:io.acme:commons:io.acme.commons.messaging");
    EntityId sender = EntityId.parse("type:io.acme.commons.messaging.Sender");
    model.add(libraryModule, Types.CONTAINS, messaging);
    model.add(messaging, Types.CONTAINS, sender);
    model.add(sender, "stereotype", FactObject.string("service"));
    EntityId send = EntityId.parse("method:io.acme.commons.messaging.Sender#send()");
    model.add(sender, Types.CONTAINS, send);
    for (String from : List.of("invoice.InvoiceController", "payment.PaymentController")) {
      model.add(EntityId.parse("method:io.acme.billing." + from + "#handle()"), Types.CALLS, send);
    }
    // An aspect in a feature's own package belongs to that feature, by rule 1.
    EntityId local = EntityId.parse("type:io.acme.billing.invoice.InvoiceAspect");
    model.add(
        EntityId.parse("package:io.acme:billing:io.acme.billing.invoice"), Types.CONTAINS, local);
    model.add(
        local, "ADVISES", EntityId.parse("method:io.acme.billing.invoice.InvoiceEvents#handle()"));
    String aspectComponent = component("io.acme.billing.security.AuditAspect");
    String senderComponent = component("io.acme.commons.messaging.Sender");
    link(EntityId.parse(aspectComponent), invoice.toString());
    link(invoice, senderComponent);

    Map<String, Set<String>> areas = areas(BILLING);

    // Both are components, called from one area only, yet neither joins it.
    Knowledge derived = new Knowledge(Derivation.withModel(model.facts, 1));
    assertThat(derived.about(EntityId.parse(aspectComponent))).isNotEmpty();
    assertThat(derived.about(EntityId.parse(senderComponent))).isNotEmpty();
    assertThat(areas)
        .containsOnlyKeys(area("pkg-collections"), area("pkg-invoice"), area("pkg-payment"));
    assertThat(areas.values())
        .allSatisfy(
            members -> assertThat(members).doesNotContain(aspectComponent, senderComponent));
    assertThat(areas.get(area("pkg-invoice")))
        .contains(component("io.acme.billing.invoice.InvoiceAspect"));
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
  void addingAnEndpointToAComponentKeepsEveryGroupKey() {
    unit(BILLING, BILLING_MODULE, "billing");
    controller("io.acme.billing.web", "OrderController", "GET /api/orders/{id}");
    controller("io.acme.billing.web", "OrderAdmin", "GET /api/orders/admin");
    controller("io.acme.billing.web", "InvoiceController", "GET /api/invoices");
    controller("io.acme.billing.web", "InvoiceAdmin", "GET /api/invoices/admin");
    Map<String, Set<String>> before = areas(BILLING);

    // Another endpoint of an existing controller, under a path that still shares /api.
    model.add(
        EntityId.parse("method:io.acme.billing.web.OrderController#handle()"),
        "endpoint",
        FactObject.string("POST /api/orders"));

    assertThat(areas(BILLING)).isEqualTo(before);
  }

  @Test
  void aLayerPackageWithAnEntryPointDoesNotCancelFeatureFolders() {
    unit(BILLING, BILLING_MODULE, "billing");
    controller("io.acme.billing.invoice", "InvoiceController", "GET /invoices");
    controller("io.acme.billing.invoice", "InvoiceEvents", "GET /invoices/events");
    controller("io.acme.billing.payment", "PaymentController", "POST /payments");
    controller("io.acme.billing.payment", "RefundController", "POST /refunds");
    controller("io.acme.billing.collections", "DunningController", "GET /dunning");
    controller("io.acme.billing.collections", "CollectionsController", "GET /collections");
    EntityId listener = controller("io.acme.billing.messaging", "PaymentListener", null);
    link(listener, component("io.acme.billing.payment.PaymentController"));

    Map<String, Set<String>> areas = areas(BILLING);

    assertThat(areas)
        .containsOnlyKeys(area("pkg-collections"), area("pkg-invoice"), area("pkg-payment"));
    assertThat(areas.get(area("pkg-payment"))).contains(listener.toString());
  }

  @Test
  void aComponentLinkedOnlyToOneJoinedByCallsJoinsInALaterRound() {
    unit(BILLING, BILLING_MODULE, "billing");
    controller("io.acme.billing.web", "OrderController", "GET /orders");
    controller("io.acme.billing.web", "OrderAdmin", "GET /orders/admin");
    controller("io.acme.billing.web", "InvoiceController", "GET /invoices");
    controller("io.acme.billing.web", "InvoiceAdmin", "GET /invoices/admin");
    EntityId poller = controller("io.acme.billing.messaging", "OutboxPoller", null);
    EntityId listener = controller("io.acme.billing.messaging", "OrderListener", null);
    link(listener, component("io.acme.billing.web.OrderController"));
    link(poller, listener.toString());

    Map<String, Set<String>> areas = areas(BILLING);

    assertThat(areas.get(area("path-orders"))).contains(listener.toString(), poller.toString());
    // No element is in two groups of one kind.
    assertThat(areas.get(area("path-invoices")))
        .doesNotContain(listener.toString(), poller.toString());
  }

  @Test
  void aComponentNoCallPlacesJoinsTheAreaWhoseTablesItSharesCitingItsDataAccess() {
    unit(BILLING, BILLING_MODULE, "billing");
    controller("io.acme.billing.web", "OrderController", "GET /orders");
    controller("io.acme.billing.web", "OrderAdmin", "GET /orders/admin");
    controller("io.acme.billing.web", "InvoiceController", "GET /invoices");
    controller("io.acme.billing.web", "InvoiceAdmin", "GET /invoices/admin");
    EntityId listener = controller("io.acme.billing.messaging", "OrderListener", null);
    EntityId orders = EntityId.parse("table:io.acme:billing:orders");
    model.add(
        EntityId.parse("method:io.acme.billing.web.OrderController#handle()"),
        "READS_TABLE",
        orders);
    model.add(
        EntityId.parse("method:io.acme.billing.messaging.OrderListener#handle()"),
        "WRITES_TABLE",
        orders);

    Knowledge knowledge = new Knowledge(Derivation.withModel(model.facts, 1));

    Fact joined =
        knowledge.relations(EntityId.parse(area("path-orders")), Types.GROUPS).stream()
            .filter(f -> Knowledge.object(f).equals(listener))
            .findFirst()
            .orElseThrow();
    assertThat(joined.attributes().get("basis").asString()).isEqualTo(Groups.TABLES);
    Fact access =
        knowledge.attributes(listener).stream()
            .filter(f -> f.predicate().equals(Types.DATA_ACCESS))
            .findFirst()
            .orElseThrow();
    assertThat(joined.attributes().get("facts").get(0).asString()).isEqualTo(access.id());
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
  void unitsNoSignalGroupsFormPocketsByHowCloselyTheyWorkAndAGatewayJoinsNone() {
    // #764: the fifth signal, after the four explicit ones.
    EntityId orders = unitNamed("orders");
    EntityId stock = unitNamed("stock");
    EntityId mailer = unitNamed("mailer");
    EntityId audit = unitNamed("audit");
    EntityId gateway = unitNamed("gateway");
    calls(orders, stock);
    calls(stock, orders);
    model.add(mailer, Types.PUBLISHES, EntityId.parse("topic:kafka:mails"));
    model.add(audit, Types.CONSUMES, EntityId.parse("topic:kafka:mails"));
    for (EntityId unit : List.of(orders, stock, mailer, audit)) {
      ObjectNode route = FactJson.newObject();
      route.put("protocol", "HTTP");
      route.put("route", "r-" + unit.key());
      model.add(gateway, Types.CALLS_HTTP, FactObject.entity(unit), route);
    }

    Map<String, Set<String>> domains = domains();

    // Each named after its central unit; ties go to the first by ID.
    assertThat(domains)
        .containsOnlyKeys(domain("pocket-orders"), domain("pocket-audit"))
        .containsEntry(domain("pocket-orders"), Set.of(orders.toString(), stock.toString()))
        .containsEntry(domain("pocket-audit"), Set.of(mailer.toString(), audit.toString()));
    Knowledge knowledge = new Knowledge(Derivation.withModel(model.facts, 1));
    assertThat(Groups.of(knowledge, Groups.DOMAIN))
        .allSatisfy(g -> assertThat(g.basis()).isEqualTo(Groups.COUPLING));
  }

  @Test
  void thePocketSizeSettingCapsAPocketAndTheOrderOfTheFactsChangesNothing() {
    EntityId a = unitNamed("a");
    EntityId b = unitNamed("b");
    EntityId c = unitNamed("c");
    EntityId d = unitNamed("d");
    EntityId e = unitNamed("e");
    for (EntityId[] pair :
        List.of(
            new EntityId[] {a, b},
            new EntityId[] {b, c},
            new EntityId[] {c, a},
            new EntityId[] {d, e})) {
      calls(pair[0], pair[1]);
      calls(pair[1], pair[0]);
    }

    assertThat(pockets(Groups.POCKET_SIZE, model.facts))
        .containsExactlyInAnyOrder(
            Set.of(a.toString(), b.toString(), c.toString()), Set.of(d.toString(), e.toString()));
    // At most 2 a pocket: the triangle loses one unit, which stands alone.
    assertThat(pockets(2, model.facts))
        .hasSize(2)
        .allSatisfy(p -> assertThat(p).hasSize(2))
        .contains(Set.of(d.toString(), e.toString()));
    List<Fact> reversed = new java.util.ArrayList<>(model.facts);
    java.util.Collections.reverse(reversed);
    assertThat(pockets(Groups.POCKET_SIZE, reversed))
        .isEqualTo(pockets(Groups.POCKET_SIZE, model.facts));
    assertThat(pockets(2, reversed)).isEqualTo(pockets(2, model.facts));
  }

  @Test
  void aCallWeighsLessTheMoreUnitsCallTheSameOne() {
    // Dedication (SArF): a call into a unit four units call weighs a quarter of one into a unit
    // only one calls, so a unit everyone uses glues no pockets together; each kind of connection
    // between two units counts once, however many there are.
    EntityId a = EntityId.parse("service:io.acme:a");
    EntityId b = EntityId.parse("service:io.acme:b");
    EntityId notify = EntityId.parse("service:io.acme:notify");
    List<EntityId> units = new java.util.ArrayList<>(List.of(a, b, notify));
    List<Pockets.Edge> edges = new java.util.ArrayList<>();
    edges.add(new Pockets.Edge(a, b, Types.CALLS_HTTP, Pockets.CALL, List.of()));
    edges.add(new Pockets.Edge(a, b, Types.CALLS_HTTP, Pockets.CALL, List.of()));
    for (String name : List.of("a", "p1", "p2", "p3")) {
      EntityId caller = EntityId.parse("service:io.acme:" + name);
      if (!units.contains(caller)) {
        units.add(caller);
      }
      edges.add(new Pockets.Edge(caller, notify, Types.CALLS_HTTP, Pockets.CALL, List.of()));
    }

    Map<EntityId, Integer> index = new java.util.HashMap<>();
    units.forEach(u -> index.put(u, index.size()));
    double[][] w = Pockets.weights(index, edges);

    assertThat(w[index.get(a)][index.get(b)]).isEqualTo(Pockets.CALL);
    assertThat(w[index.get(a)][index.get(notify)]).isEqualTo(Pockets.CALL / 4);
    assertThat(w[index.get(notify)][index.get(a)]).isEqualTo(Pockets.CALL / 4);
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
    model.add(unit, Types.CONTAINS, module);
  }

  /** A controller (an entry point) with an endpoint, or a listener when the endpoint is null. */
  private EntityId controller(String pkg, String name, String endpoint) {
    EntityId pkgId = EntityId.parse("package:io.acme:billing:" + pkg);
    EntityId type = EntityId.parse("type:" + pkg + "." + name);
    EntityId method = EntityId.parse("method:" + pkg + "." + name + "#handle()");
    model.add(BILLING_MODULE, Types.CONTAINS, pkgId);
    model.add(pkgId, Types.CONTAINS, type);
    model.add(type, Types.CONTAINS, method);
    model.add(type, "stereotype", FactObject.string("controller"));
    if (endpoint != null) {
      model.add(method, "endpoint", FactObject.string(endpoint));
    } else {
      model.add(method, "message-listener", FactObject.string("orders"));
    }
    return EntityId.parse(component(pkg + "." + name));
  }

  /** A bean shared by every entry point: a component of its own, not an entry point. */
  private void shared(String pkg, String name) {
    EntityId pkgId = EntityId.parse("package:io.acme:billing:" + pkg);
    EntityId type = EntityId.parse("type:" + pkg + "." + name);
    model.add(BILLING_MODULE, Types.CONTAINS, pkgId);
    model.add(pkgId, Types.CONTAINS, type);
    model.add(type, "stereotype", FactObject.string("service"));
    for (String controller :
        List.of(
            "io.acme.billing.invoice.InvoiceController#handle()",
            "io.acme.billing.payment.PaymentController#handle()")) {
      model.add(
          EntityId.parse("method:" + controller),
          Types.CALLS,
          EntityId.parse("method:" + pkg + "." + name + "#find()"));
    }
    model.add(type, Types.CONTAINS, EntityId.parse("method:" + pkg + "." + name + "#find()"));
  }

  private void link(EntityId from, String to) {
    model.add(from, Types.COMPONENT_LINK, EntityId.parse(to));
  }

  private EntityId unitNamed(String name) {
    EntityId unit = EntityId.parse("service:io.acme:" + name);
    model.add(unit, "deployable-unit", FactObject.string("spring-boot"));
    return unit;
  }

  private void calls(EntityId from, EntityId to) {
    ObjectNode attributes = FactJson.newObject();
    attributes.put("protocol", "HTTP");
    model.add(from, Types.CALLS_HTTP, FactObject.entity(to), attributes);
  }

  /** The members of each domain a Groups rule of this pocket size derives from the facts. */
  private static List<Set<String>> pockets(int pocketSize, List<Fact> facts) {
    List<Fact> all = new java.util.ArrayList<>(facts);
    all.addAll(new Groups(pocketSize).derive(facts, 1));
    List<Set<String>> result = new java.util.ArrayList<>();
    for (Groups.Group group : Groups.of(new Knowledge(all), Groups.DOMAIN)) {
      Set<String> members = new java.util.TreeSet<>();
      group.members().forEach(m -> members.add(m.toString()));
      result.add(members);
    }
    return result;
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

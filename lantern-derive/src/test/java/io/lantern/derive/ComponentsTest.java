package io.lantern.derive;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.vocabulary.BuiltInTypes;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/** The components of a unit, stored as part of the architecture model (#523). */
class ComponentsTest {

  private static final EntityId UNIT = EntityId.parse("service:com.example:shop");
  private static final EntityId MODULE = EntityId.parse("module:com.example:shop");
  private static final EntityId ORDERS = EntityId.parse("type:com.example.OrdersController");
  private static final EntityId ADMIN = EntityId.parse("type:com.example.AdminController");
  private static final EntityId PRICING = EntityId.parse("type:com.example.Pricing");
  private static final EntityId AUDIT = EntityId.parse("type:com.example.Audit");

  private final List<Fact> facts = new ArrayList<>();

  @Test
  void eachEntryPointIsAComponentWithTheBeansOnlyItReachesAndASharedBeanIsOneOfItsOwn() {
    unitWithTwoControllers();

    List<Fact> derived = derived();

    EntityId orders = Components.id(UNIT, ORDERS);
    EntityId admin = Components.id(UNIT, ADMIN);
    EntityId audit = Components.id(UNIT, AUDIT);
    assertThat(orders.toString())
        .isEqualTo("component:service:com.example:shop/type:com.example.OrdersController");
    assertThat(objects(derived, orders, Components.HAS_MEMBER)).containsExactly(ORDERS, PRICING);
    assertThat(objects(derived, admin, Components.HAS_MEMBER)).containsExactly(ADMIN);
    assertThat(objects(derived, audit, Components.HAS_MEMBER)).containsExactly(AUDIT);
    assertThat(objects(derived, orders, Components.ANCHORED_ON)).containsExactly(ORDERS);

    Fact part = only(derived, orders, Components.PART_OF);
    assertThat(part.object().entityId()).isEqualTo(UNIT);
    assertThat(part.attributes().get("role").asString()).isEqualTo(Components.ENTRY_POINT);
    assertThat(part.attributes().get("technology").asString())
        .isEqualTo("Spring MVC REST controller");
    assertThat(only(derived, audit, Components.PART_OF).attributes().get("role").asString())
        .isEqualTo(Components.SHARED);
    // Its lineage is the fact that makes the controller an entry point, one level deep.
    assertThat(part.lineage()).containsExactly(claim("stereotype:" + ORDERS));
  }

  @Test
  void aNewEndpointOnAControllerKeepsItsComponent() {
    unitWithTwoControllers();
    List<EntityId> before = components(derived());

    add(
        EntityId.parse("method:com.example.OrdersController#cancel()"),
        "endpoint",
        FactObject.string("POST /orders/{id}/cancel"));
    add(
        ORDERS,
        BuiltInTypes.CONTAINS,
        EntityId.parse("method:com.example.OrdersController#cancel()"));

    assertThat(components(derived())).isEqualTo(before);
  }

  @Test
  void aBeanAConstructorTakesBelongsToItsComponentWithoutResolvedCalls() {
    add(UNIT, "deployable-unit", FactObject.string("spring-boot"));
    add(UNIT, BuiltInTypes.CONTAINS, MODULE);
    for (EntityId type : List.of(ORDERS, PRICING)) {
      add(MODULE, BuiltInTypes.CONTAINS, type);
    }
    ObjectNode rest = FactJson.newObject();
    rest.put("rest", true);
    add(ORDERS, "stereotype", FactObject.string("controller"), rest);
    add(PRICING, "stereotype", FactObject.string("service"));
    // No calls were resolved (no build), but the constructor names what it takes.
    add(
        ORDERS,
        BuiltInTypes.CONTAINS,
        EntityId.parse(
            "method:com.example.OrdersController#<init>(com.example.Pricing,java.util.List,java.lang.String[])"));

    List<Fact> derived = derived();

    assertThat(objects(derived, Components.id(UNIT, ORDERS), Components.HAS_MEMBER))
        .containsExactly(ORDERS, PRICING);
    assertThat(components(derived)).containsExactly(Components.id(UNIT, ORDERS));
  }

  @Test
  void mappersSettingsAndRepositoriesNoEntryPointReachesAreNotComponents() {
    unitWithTwoControllers();
    EntityId mapper = EntityId.parse("type:com.example.OrderMapperImpl");
    EntityId settings = EntityId.parse("type:com.example.ShopSettings");
    EntityId unused = EntityId.parse("type:com.example.ArchiveRepository");
    EntityId client = EntityId.parse("type:com.example.BillingClient");
    for (EntityId type : List.of(mapper, settings, unused, client)) {
      add(MODULE, BuiltInTypes.CONTAINS, type);
    }
    add(mapper, "stereotype", FactObject.string("component"));
    add(mapper, "generated", FactObject.string("org.mapstruct.ap.MappingProcessor"));
    add(settings, "stereotype", FactObject.string("component"));
    add(settings, "configuration-properties", FactObject.string("shop"));
    // Both controllers use the mapper and the settings, which would make them shared.
    for (EntityId controller : List.of(ORDERS, ADMIN)) {
      add(controller, "INJECTS", mapper);
      add(controller, "INJECTS", settings);
    }
    add(unused, "REPOSITORY_FOR", EntityId.parse("type:com.example.Archive"));
    // Pricing, inside OrdersController's component, already reaches the database.
    add(PRICING, "WRITES_TABLE", EntityId.parse("table:shop:prices"));
    add(client, "http-client", FactObject.string("feign"));

    List<EntityId> components = components(derived());

    assertThat(components)
        .doesNotContain(
            Components.id(UNIT, mapper), Components.id(UNIT, settings), Components.id(UNIT, unused))
        // An HTTP client nothing reaches still shows where calls to another container start.
        .contains(Components.id(UNIT, client));
  }

  @Test
  void aRepositoryNothingReachesStaysWhenNoOtherComponentReachesTheDatabase() {
    unitWithTwoControllers();
    EntityId users = EntityId.parse("type:com.example.UserRepository");
    add(MODULE, BuiltInTypes.CONTAINS, users);
    // Spring Security calls the service that uses it, not an entry point.
    add(users, "REPOSITORY_FOR", EntityId.parse("type:com.example.User"));

    assertThat(components(derived())).contains(Components.id(UNIT, users));
  }

  private void unitWithTwoControllers() {
    add(UNIT, "deployable-unit", FactObject.string("spring-boot"));
    add(UNIT, BuiltInTypes.CONTAINS, MODULE);
    for (EntityId type : List.of(ORDERS, ADMIN, PRICING, AUDIT)) {
      add(MODULE, BuiltInTypes.CONTAINS, type);
    }
    ObjectNode rest = FactJson.newObject();
    rest.put("rest", true);
    add(ORDERS, "stereotype", FactObject.string("controller"), rest);
    add(ADMIN, "stereotype", FactObject.string("controller"), rest.deepCopy());
    add(PRICING, "stereotype", FactObject.string("service"));
    add(AUDIT, "stereotype", FactObject.string("service"));
    // OrdersController uses Pricing and Audit; AdminController uses Audit.
    add(ORDERS, "INJECTS", PRICING);
    add(ORDERS, "INJECTS", AUDIT);
    add(ADMIN, "INJECTS", AUDIT);
  }

  private List<Fact> derived() {
    return Derivation.withModel(facts, 1).stream()
        .filter(f -> f.origin().name().equals(Components.NAME))
        .toList();
  }

  private static List<EntityId> components(List<Fact> derived) {
    return derived.stream()
        .filter(f -> f.predicate().equals(Components.PART_OF))
        .map(Fact::subject)
        .sorted(java.util.Comparator.comparing(EntityId::toString))
        .toList();
  }

  private static List<EntityId> objects(List<Fact> derived, EntityId subject, String predicate) {
    return derived.stream()
        .filter(f -> f.subject().equals(subject) && f.predicate().equals(predicate))
        .map(f -> f.object().entityId())
        .sorted(java.util.Comparator.comparing(EntityId::toString))
        .toList();
  }

  private static Fact only(List<Fact> derived, EntityId subject, String predicate) {
    List<Fact> found =
        derived.stream()
            .filter(f -> f.subject().equals(subject) && f.predicate().equals(predicate))
            .toList();
    assertThat(found).hasSize(1);
    return found.getFirst();
  }

  private void add(EntityId subject, String predicate, EntityId object) {
    add(subject, predicate, FactObject.entity(object), FactJson.newObject());
  }

  private void add(EntityId subject, String predicate, FactObject object) {
    add(subject, predicate, object, FactJson.newObject());
  }

  private void add(EntityId subject, String predicate, FactObject object, ObjectNode attributes) {
    Fact fact =
        Fact.create(
            subject,
            predicate,
            object,
            attributes,
            new Origin(Origin.Kind.STATIC, "java", "1"),
            Evidence.of("shop", "src/Shop.java", new Evidence.LineRange(1, 1), null),
            1.0,
            Validity.from(1));
    facts.add(fact.stored(claim(predicate + ":" + subject), Validity.from(1)));
  }

  /** A claim ID standing for the claim a text names. */
  private static String claim(String text) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}

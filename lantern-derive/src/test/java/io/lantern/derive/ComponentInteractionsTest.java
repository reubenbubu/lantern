package io.lantern.derive;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.vocabulary.BuiltInTypes;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/** The lines between a unit's components and where its links' code ends are (#617). */
class ComponentInteractionsTest {

  private static final EntityId UNIT = EntityId.parse("service:com.example:shop");
  private static final EntityId BILLING = EntityId.parse("service:com.example:billing");
  private static final EntityId MODULE = EntityId.parse("module:com.example:shop");
  private static final EntityId ORDERS = EntityId.parse("type:com.example.OrdersController");
  private static final EntityId ADMIN = EntityId.parse("type:com.example.AdminController");
  private static final EntityId AUDIT = EntityId.parse("type:com.example.Audit");
  private static final EntityId PLACE =
      EntityId.parse("method:com.example.OrdersController#place()");
  private static final EntityId RECORD = EntityId.parse("method:com.example.Audit#record()");

  private final TestModel model = new TestModel();

  @Test
  void aCallIntoAnotherComponentIsALineCitingTheCall() {
    Fact call = unit();

    List<Fact> derived = model.derived(ComponentInteractions.NAME);

    List<Fact> lines = calls(derived, Components.id(UNIT, ORDERS));
    assertThat(lines).hasSize(1);
    Fact line = lines.getFirst();
    assertThat(line.object().entityId()).isEqualTo(Components.id(UNIT, AUDIT));
    assertThat(line.attributes().get("relationship").asString()).isEqualTo(BuiltInTypes.CALLS);
    assertThat(line.attributes().get("at").asString()).isEqualTo(PLACE.toString());
    assertThat(line.attributes().get("detail").asString()).isEqualTo("record");
    assertThat(line.lineage()).containsExactly(call.claimId());
  }

  @Test
  void aLinkToAnotherUnitIsPlacedAtTheComponentHoldingItsCode() {
    unit();
    ObjectNode at = FactJson.newObject();
    at.put("source", PLACE.toString());
    Fact link = model.add(UNIT, "CALLS_HTTP", FactObject.entity(BILLING), at);

    List<Fact> ends =
        TestModel.about(
                model.derived(ComponentInteractions.NAME),
                Components.id(UNIT, ORDERS),
                ComponentInteractions.COMPONENT_LINK)
            .stream()
            .filter(
                f -> f.attributes().get("kind").asString().equals(ComponentInteractions.CODE_END))
            .toList();

    assertThat(ends).hasSize(1);
    Fact end = ends.getFirst();
    assertThat(end.object().entityId()).isEqualTo(BILLING);
    assertThat(end.attributes().get("target").asString()).isEqualTo("source " + BILLING);
    // The link is named only by the line's lineage, never by a key attribute.
    assertThat(end.lineage()).containsExactly(link.claimId());
  }

  @Test
  void aComponentWritingTablesHasItsDataAccess() {
    unit();
    Fact writes = model.add(RECORD, "WRITES_TABLE", EntityId.parse("table:shop:audit_log"));

    List<Fact> access =
        TestModel.about(
            model.derived(ComponentInteractions.NAME),
            Components.id(UNIT, AUDIT),
            ComponentInteractions.DATA_ACCESS);

    assertThat(access).hasSize(1);
    assertThat(access.getFirst().object().value()).isEqualTo("Writes");
    assertThat(access.getFirst().lineage()).containsExactly(writes.claimId());
  }

  /** A unit with two controllers both using Audit, OrdersController#place calling it. */
  private Fact unit() {
    model.add(UNIT, "deployable-unit", FactObject.string("spring-boot"));
    model.add(UNIT, BuiltInTypes.CONTAINS, MODULE);
    for (EntityId type : List.of(ORDERS, ADMIN, AUDIT)) {
      model.add(MODULE, BuiltInTypes.CONTAINS, type);
    }
    ObjectNode rest = FactJson.newObject();
    rest.put("rest", true);
    model.add(ORDERS, "stereotype", FactObject.string("controller"), rest);
    model.add(ADMIN, "stereotype", FactObject.string("controller"), rest.deepCopy());
    model.add(AUDIT, "stereotype", FactObject.string("service"));
    model.add(ORDERS, "INJECTS", AUDIT);
    model.add(ADMIN, "INJECTS", AUDIT);
    model.add(ORDERS, BuiltInTypes.CONTAINS, PLACE);
    model.add(AUDIT, BuiltInTypes.CONTAINS, RECORD);
    return model.add(PLACE, BuiltInTypes.CALLS, RECORD);
  }

  private static List<Fact> calls(List<Fact> derived, EntityId component) {
    return TestModel.about(derived, component, ComponentInteractions.COMPONENT_LINK).stream()
        .filter(f -> f.attributes().get("kind").asString().equals(ComponentInteractions.CALLS))
        .toList();
  }
}

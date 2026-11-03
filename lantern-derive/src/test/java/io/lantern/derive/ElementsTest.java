package io.lantern.derive;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.derive.attributes.Types;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class ElementsTest {

  private static final EntityId SHOP = EntityId.parse("service:g:shop");
  private static final EntityId PRICING = EntityId.parse("service:g:pricing");
  private static final EntityId DB = EntityId.parse("database:db:5432/shop");
  private static final EntityId CONFIG = EntityId.parse("infrastructure:config:8888");

  private final TestModel model = new TestModel();

  private Elements elements() {
    Knowledge knowledge = new Knowledge(model.facts);
    return new Elements(
        knowledge, Vocabularies.standard(), new ElementNames(knowledge, "Shop system"), Set.of());
  }

  @Test
  void aUnitsLinksAreLinesWithTheirWordsDetailAndProtocol() {
    model.add(SHOP, Types.DEPLOYABLE_UNIT, FactObject.string("spring-boot"));
    model.add(PRICING, Types.DEPLOYABLE_UNIT, FactObject.string("spring-boot"));
    ObjectNode call = FactJson.newObject();
    call.put("protocol", "HTTP");
    call.put("endpoint", "GET /prices");
    model.add(SHOP, Types.CALLS_HTTP, FactObject.entity(PRICING), call);
    ObjectNode jdbc = FactJson.newObject();
    jdbc.put("protocol", "JDBC");
    model.add(SHOP, Types.USES_DATABASE, FactObject.entity(DB), jdbc);
    model.add(SHOP, Types.USES_INFRASTRUCTURE, FactObject.entity(CONFIG), FactJson.newObject());

    Elements elements = elements();

    assertThat(elements.lines())
        .extracting(
            l ->
                l.from() + " " + l.plain() + " " + l.to() + " " + l.detail() + " " + l.technology())
        .containsExactly(
            SHOP + " Calls " + PRICING + " GET /prices HTTP",
            SHOP + " Connects to " + DB + " null JDBC");
    assertThat(elements.destinations()).containsExactly(DB);
    assertThat(elements.neighbours(DB))
        .extracting(n -> n.other() + " " + n.incoming())
        .containsExactly(SHOP + " true");
    assertThat(Elements.units(new Knowledge(model.facts))).containsExactly(PRICING, SHOP);
  }

  @Test
  void elementsAreNamedFromTheirFacts() {
    model.add(SHOP, Types.APPLICATION_NAME, FactObject.string("shop-app"));
    ElementNames names = new ElementNames(new Knowledge(model.facts), "Shop system");

    assertThat(names.name(SHOP)).isEqualTo("shop-app");
    assertThat(names.name(PRICING)).isEqualTo("pricing");
    assertThat(names.name(DB)).isEqualTo("shop on db");
    assertThat(names.name(EntityId.parse("system:snapshot"))).isEqualTo("Shop system");
    assertThat(names.name(EntityId.parse("topic:kafka:orders"))).isEqualTo("orders");
    assertThat(List.of(names.name(EntityId.parse("type:g.web.OrderController"))))
        .containsExactly("OrderController");
  }
}

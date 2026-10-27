package io.lantern.core.fact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EntityIdTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "service:orders",
        "java:com.acme.Foo",
        "topic:kafka:order-created",
        "batch-job:nightly/export",
        "table:public.orders"
      })
  void roundTripsThroughItsStringForm(String value) {
    EntityId id = EntityId.parse(value);

    assertThat(id.toString()).isEqualTo(value);
    assertThat(EntityId.parse(id.toString())).isEqualTo(id);
  }

  @ParameterizedTest
  @ValueSource(strings = {"service:orders", "topic:kafka:order-created"})
  void splitsAtTheFirstColon(String value) {
    EntityId id = EntityId.parse(value);

    assertThat(id.type() + ":" + id.key()).isEqualTo(value);
    assertThat(id.type()).doesNotContain(":");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "orders",
        ":orders",
        "service:",
        "Service:orders",
        "1service:orders",
        "my_type:orders",
        "service:order s",
        "service:orders\n",
        "service:\u0085orders",
        "service: orders"
      })
  void rejectsInvalidIds(String value) {
    assertThatThrownBy(() -> EntityId.parse(value)).isInstanceOf(IllegalArgumentException.class);
  }
}

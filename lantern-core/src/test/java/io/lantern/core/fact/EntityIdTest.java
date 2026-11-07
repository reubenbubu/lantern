package io.lantern.core.fact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
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

  /** The key check accepts and rejects every character as the regular expression it replaced. */
  @Test
  void theKeyCheckAgreesWithTheExpressionItReplacedOnEveryCharacter() {
    Pattern replaced =
        Pattern.compile(
            "[^\\u0000-\\u0020\\u007f-\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]+");
    for (int c = 0; c <= Character.MAX_VALUE; c++) {
      String key = "a" + (char) c + "b";
      assertThat(EntityId.isValidKey(key))
          .as("U+%04X", c)
          .isEqualTo(replaced.matcher(key).matches());
    }
    assertThat(EntityId.isValidKey("")).isFalse();
    assertThat(EntityId.isValidKey("x\uD83D\uDE00")).isTrue();
  }
}

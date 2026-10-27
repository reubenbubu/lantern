package io.lantern.core.fact;

import static io.lantern.core.fact.FactTest.COMMIT;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Checks that the JSON Schema and the Java types describe the same envelope. */
class FactSchemaTest {

  static JsonNode schema() throws IOException {
    try (InputStream in =
        FactSchemaTest.class.getResourceAsStream("/schema/fact-envelope.schema.json")) {
      assertThat(in).as("schema resource").isNotNull();
      return FactJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  /** A fact with every optional field set, so its JSON has every property. */
  static ObjectNode fullFactJson() {
    return FactJson.toJson(FactTest.sample());
  }

  @Test
  void topLevelPropertiesMatchTheJavaForm() throws IOException {
    JsonNode schema = schema();
    ObjectNode json = fullFactJson();

    assertThat(names(schema.get("properties"))).isEqualTo(names(json));
    assertThat(strings(schema.get("required"))).isEqualTo(names(json));
    assertThat(schema.get("additionalProperties").booleanValue()).isFalse();
  }

  @Test
  void nestedPropertiesMatchTheJavaForm() throws IOException {
    JsonNode defs = schema().get("$defs");
    ObjectNode json = fullFactJson();

    assertThat(names(defs.get("origin").get("properties"))).isEqualTo(names(json.get("origin")));
    assertThat(names(defs.get("evidence").get("properties")))
        .isEqualTo(names(json.get("evidence")));
    assertThat(names(defs.get("evidence").get("properties").get("lines").get("properties")))
        .isEqualTo(names(json.get("evidence").get("lines")));
    assertThat(names(defs.get("object").get("properties"))).isEqualTo(names(json.get("object")));
    assertThat(names(defs.get("validity").get("properties")))
        .isEqualTo(Set.of("from", "to"))
        .containsAll(names(json.get("validity")));
  }

  @Test
  void requiredFieldsMatchWhatTheJavaFormAlwaysWrites() throws IOException {
    JsonNode defs = schema().get("$defs");
    ObjectNode minimal =
        FactJson.toJson(
            Fact.create(
                FactTest.SUBJECT,
                "owner",
                FactObject.string("team"),
                FactJson.newObject(),
                new Origin(Origin.Kind.HUMAN, "user-1", null),
                new Evidence("orders", COMMIT, null, null, null),
                1.0,
                Validity.from(0)));

    assertThat(strings(defs.get("origin").get("required"))).isEqualTo(names(minimal.get("origin")));
    assertThat(strings(defs.get("evidence").get("required")))
        .isEqualTo(names(minimal.get("evidence")));
    assertThat(strings(defs.get("validity").get("required")))
        .isEqualTo(names(minimal.get("validity")));
  }

  @Test
  void enumsMatchTheJavaEnums() throws IOException {
    JsonNode defs = schema().get("$defs");

    List<String> originKinds = new ArrayList<>();
    for (Origin.Kind kind : Origin.Kind.values()) {
      originKinds.add(kind.jsonName());
    }
    List<String> objectKinds = new ArrayList<>();
    for (FactObject.Kind kind : FactObject.Kind.values()) {
      objectKinds.add(kind.jsonName());
    }

    assertThat(strings(defs.get("origin").get("properties").get("kind").get("enum")))
        .isEqualTo(Set.copyOf(originKinds));
    assertThat(strings(defs.get("object").get("properties").get("kind").get("enum")))
        .isEqualTo(Set.copyOf(objectKinds));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "service:orders",
        "topic:kafka:order-created",
        "java:com.acme.Foo",
        "orders",
        "Service:orders",
        "service:order s",
        "service:a\u0007b",
        "service:a\u00a0b",
        "service:a\ufeffb",
        "service:caf\u00e9"
      })
  void entityIdPatternAgreesWithTheParser(String value) throws IOException {
    Pattern pattern =
        Pattern.compile(schema().get("$defs").get("entityId").get("pattern").stringValue());

    assertThat(pattern.matcher(value).find()).isEqualTo(parses(value));
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "1.5", "-0.25", "10", "1.50", "01", "-0", "1.", ".5"})
  void decimalPatternAgreesWithTheCanonicalForm(String value) throws IOException {
    JsonNode decimal =
        schema().get("$defs").get("object").get("oneOf").get(3).get("properties").get("value");
    Pattern pattern = Pattern.compile(decimal.get("pattern").stringValue());

    boolean canonical;
    try {
      new FactObject(FactObject.Kind.DECIMAL, value);
      canonical = true;
    } catch (IllegalArgumentException e) {
      canonical = false;
    }
    assertThat(pattern.matcher(value).find()).isEqualTo(canonical);
  }

  private static boolean parses(String value) {
    try {
      EntityId.parse(value);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static Set<String> names(JsonNode node) {
    return new HashSet<>(node.propertyNames());
  }

  private static Set<String> strings(JsonNode array) {
    Set<String> result = new HashSet<>();
    for (JsonNode item : array) {
      result.add(item.stringValue());
    }
    return result;
  }
}

package io.lantern.core.vocabulary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lantern.core.fact.FactJson;
import io.lantern.core.vocabulary.AttributeSchema.Property;
import io.lantern.core.vocabulary.AttributeSchema.ValueType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;

class AttributeSchemaTest {

  static final AttributeSchema CONSUMES =
      schema(
          """
          {"properties": {
             "group": {"type": "string", "description": "Consumer group"},
             "mode": {"type": "string", "enum": ["push", "pull"]},
             "partitions": {"type": "integer"},
             "ratio": {"type": "number"},
             "durable": {"type": "boolean"},
             "tags": {"type": "array", "items": {"type": "string"}},
             "settings": {"type": "object"}},
           "required": ["group"],
           "additionalProperties": false}
          """);

  static AttributeSchema schema(String json) {
    return AttributeSchema.fromJson(FactJson.parse(json));
  }

  static ObjectNode attributes(String json) {
    return FactJson.normalize((ObjectNode) FactJson.parse(json));
  }

  @Test
  void acceptsAttributesThatMatch() {
    assertThatCode(
            () ->
                CONSUMES.validate(
                    attributes(
                        """
                        {"group": "g", "mode": "pull", "partitions": 3, "ratio": 0.5,
                         "durable": true, "tags": ["a", "b"], "settings": {"x": 1}}
                        """)))
        .doesNotThrowAnyException();
    assertThatCode(() -> CONSUMES.validate(attributes("{\"group\": \"g\", \"ratio\": 2}")))
        .doesNotThrowAnyException();
  }

  @Test
  void wholeNumbersWrittenWithAFractionCountAsIntegers() {
    assertThatCode(() -> CONSUMES.validate(attributes("{\"group\": \"g\", \"partitions\": 3.0}")))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsAttributesThatDoNotMatch() {
    assertThatThrownBy(() -> CONSUMES.validate(attributes("{}")))
        .hasMessageContaining("'group' is required");
    assertThatThrownBy(() -> CONSUMES.validate(attributes("{\"group\": null}")))
        .hasMessageContaining("'group' is required");
    assertThatThrownBy(() -> CONSUMES.validate(attributes("{\"group\": 1}")))
        .hasMessageContaining("'group' must be a string");
    assertThatThrownBy(() -> CONSUMES.validate(attributes("{\"group\": \"g\", \"mode\": \"x\"}")))
        .hasMessageContaining("'mode' must be one of [push, pull]");
    assertThatThrownBy(
            () -> CONSUMES.validate(attributes("{\"group\": \"g\", \"partitions\": 1.5}")))
        .hasMessageContaining("'partitions' must be an integer");
    assertThatThrownBy(() -> CONSUMES.validate(attributes("{\"group\": \"g\", \"tags\": [1]}")))
        .hasMessageContaining("'tags[0]' must be a string");
    assertThatThrownBy(() -> CONSUMES.validate(attributes("{\"group\": \"g\", \"other\": 1}")))
        .hasMessageContaining("'other' is not a declared attribute");
    assertThatThrownBy(() -> CONSUMES.validate(attributes("{\"durable\": \"yes\"}")))
        .hasMessageContaining("'group' is required")
        .hasMessageContaining("'durable' must be a boolean");
  }

  @Test
  void undeclaredAttributesAreAllowedByDefaultAsInJsonSchema() {
    AttributeSchema open = schema("{\"properties\": {\"a\": {\"type\": \"string\"}}}");

    assertThat(open.additionalProperties()).isTrue();
    assertThatCode(() -> open.validate(attributes("{\"b\": 1}"))).doesNotThrowAnyException();
    assertThatThrownBy(() -> open.validate(attributes("{\"a\": 1}")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void jsonFormRoundTrips() {
    assertThat(AttributeSchema.fromJson(CONSUMES.toJson())).isEqualTo(CONSUMES);
    assertThat(AttributeSchema.fromJson(AttributeSchema.NONE.toJson()))
        .isEqualTo(AttributeSchema.NONE);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "[]",
        "{\"type\": \"array\"}",
        "{\"minProperties\": 1}",
        "{\"properties\": []}",
        "{\"properties\": {\"a\": {}}}",
        "{\"properties\": {\"a\": {\"type\": \"date\"}}}",
        "{\"properties\": {\"a\": {\"type\": \"string\", \"pattern\": \"x\"}}}",
        "{\"properties\": {\"a\": {\"type\": \"integer\", \"enum\": [\"1\"]}}}",
        "{\"properties\": {\"a\": {\"type\": \"string\", \"enum\": []}}}",
        "{\"properties\": {\"a\": {\"type\": \"string\", \"enum\": [\"x\", \"x\"]}}}",
        "{\"properties\": {\"a\": {\"type\": \"string\", \"items\": {\"type\": \"string\"}}}}",
        "{\"properties\": {\"1a\": {\"type\": \"string\"}}}",
        "{\"required\": \"a\"}",
        "{\"required\": [\"a\", \"a\"]}",
        "{\"additionalProperties\": {}}"
      })
  void rejectsUnsupportedSchemas(String json) {
    assertThatThrownBy(() -> schema(json)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void mergeCombinesAncestorsAndClosesIfAnyIsClosed() {
    AttributeSchema parent =
        new AttributeSchema(Map.of("a", Property.of(ValueType.STRING)), Set.of("a"), true);
    AttributeSchema child =
        new AttributeSchema(Map.of("b", Property.of(ValueType.INTEGER)), Set.of(), false);

    AttributeSchema merged = AttributeSchema.merge(List.of(parent, child));

    assertThat(merged.properties()).containsOnlyKeys("a", "b");
    assertThat(merged.required()).containsExactly("a");
    assertThat(merged.additionalProperties()).isFalse();
  }

  @Test
  void mergeAllowsRepeatingAnAttributeWithTheSameConstraints() {
    AttributeSchema parent =
        new AttributeSchema(
            Map.of("a", new Property(ValueType.STRING, "parent", List.of(), null)), Set.of(), true);
    AttributeSchema child =
        new AttributeSchema(
            Map.of("a", new Property(ValueType.STRING, "child", List.of(), null)),
            Set.of("a"),
            true);

    assertThat(AttributeSchema.merge(List.of(parent, child)).required()).containsExactly("a");
  }

  @Test
  void mergeRejectsConflictsAndUndeclaredRequiredAttributes() {
    AttributeSchema parent =
        new AttributeSchema(Map.of("a", Property.of(ValueType.STRING)), Set.of(), true);
    AttributeSchema conflicting =
        new AttributeSchema(Map.of("a", Property.of(ValueType.INTEGER)), Set.of(), true);
    AttributeSchema requiresUnknown = new AttributeSchema(Map.of(), Set.of("b"), true);

    assertThatThrownBy(() -> AttributeSchema.merge(List.of(parent, conflicting)))
        .hasMessageContaining("'a' is declared differently");
    assertThatThrownBy(() -> AttributeSchema.merge(List.of(parent, requiresUnknown)))
        .hasMessageContaining("'b' is not declared");
  }

  @Test
  void additiveChangesAreRecognized() {
    AttributeSchema before = schema("{\"properties\": {\"a\": {\"type\": \"string\"}}}");

    assertThat(
            before.nonAdditiveChanges(
                schema(
                    """
                    {"properties": {"a": {"type": "string", "description": "now documented"},
                                    "b": {"type": "integer"}}}
                    """)))
        .isEmpty();
    assertThat(before.nonAdditiveChanges(schema("{}"))).containsExactly("removes attribute 'a'");
    assertThat(
            before.nonAdditiveChanges(schema("{\"properties\": {\"a\": {\"type\": \"integer\"}}}")))
        .containsExactly("changes attribute 'a'");
    assertThat(
            before.nonAdditiveChanges(
                schema("{\"properties\": {\"a\": {\"type\": \"string\"}}, \"required\": [\"a\"]}")))
        .containsExactly("makes attribute 'a' required");
    assertThat(
            before.nonAdditiveChanges(
                schema(
                    """
                    {"properties": {"a": {"type": "string"}}, "additionalProperties": false}
                    """)))
        .containsExactly("stops allowing undeclared attributes");
    AttributeSchema closed =
        schema(
            """
            {"properties": {"a": {"type": "string"}}, "required": ["a"],
             "additionalProperties": false}
            """);
    assertThat(closed.nonAdditiveChanges(before)).isEmpty();
    assertThat(
            schema("{\"properties\": {\"m\": {\"type\": \"string\", \"enum\": [\"x\", \"y\"]}}}")
                .nonAdditiveChanges(
                    schema(
                        "{\"properties\": {\"m\": {\"type\": \"string\", \"enum\": [\"y\", \"x\"]}}}")))
        .isEmpty();
  }
}

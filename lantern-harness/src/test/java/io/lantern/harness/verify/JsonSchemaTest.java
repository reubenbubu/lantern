package io.lantern.harness.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The schema of one item's entry, from a role's answer schema. */
class JsonSchemaTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void anItemsSchemaIsTheEntrySchemaWithItsReferencesResolved() {
    var answer =
        JSON.readTree(
            """
            {"$defs": {"text": {"type": "object", "required": ["text", "cites"]}},
             "type": "object", "properties": {"items": {"type": "object", "additionalProperties": {
               "type": "object", "properties": {
                 "unit": {"$ref": "#/$defs/text"},
                 "components": {"type": "object", "additionalProperties": {"$ref": "#/$defs/text"}}}}}}}
            """);

    assertThat(JsonSchema.itemSchema(answer))
        .isEqualTo(
            JSON.readTree(
                """
                {"type": "object", "properties": {
                  "unit": {"type": "object", "required": ["text", "cites"]},
                  "components": {"type": "object", "additionalProperties":
                    {"type": "object", "required": ["text", "cites"]}}}}
                """));
  }

  @Test
  void aSchemaWithoutItemsOrWithAnUnknownReferenceIsRefused() {
    assertThatThrownBy(() -> JsonSchema.itemSchema(JSON.readTree("{\"type\": \"object\"}")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                JsonSchema.itemSchema(
                    JSON.readTree(
                        """
                        {"properties": {"items": {"additionalProperties": {"$ref": "#/$defs/x"}}}}
                        """)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

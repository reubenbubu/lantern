package io.lantern.core.vocabulary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.vocabulary.AttributeSchema.Property;
import io.lantern.core.vocabulary.AttributeSchema.ValueType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Checks that the JSON Schema and the Java types describe the same vocabulary type. */
class VocabularySchemaTest {

  static JsonNode schema() throws IOException {
    try (InputStream in =
        VocabularySchemaTest.class.getResourceAsStream("/schema/vocabulary-type.schema.json")) {
      assertThat(in).as("schema resource").isNotNull();
      return FactJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  static VocabularyType full() {
    Property tags = new Property(ValueType.ARRAY, "Tags", List.of(), Property.of(ValueType.STRING));
    Property mode = new Property(ValueType.STRING, null, List.of("a", "b"), null);
    return new VocabularyType(
        "batch-job",
        TypeKind.ENTITY,
        "component",
        C4Level.COMPONENT,
        "box",
        "clock",
        "A job.",
        new AttributeSchema(Map.of("tags", tags, "mode", mode), Set.of("mode"), false));
  }

  /** An attribute type with every field an attribute type can have that {@link #full} lacks. */
  static VocabularyType attribute() {
    return new VocabularyType(
        "owner",
        TypeKind.ATTRIBUTE,
        null,
        null,
        null,
        null,
        null,
        AttributeSchema.NONE,
        FactObject.Kind.STRING);
  }

  @Test
  void propertiesMatchTheJavaForm() throws IOException {
    JsonNode schema = schema();
    ObjectNode json = full().toJson();

    Set<String> javaFields = new HashSet<>(names(json));
    javaFields.addAll(names(attribute().withKeyAttributes(Set.of("since")).toJson()));
    assertThat(names(schema.get("properties"))).isEqualTo(javaFields);
    assertThat(schema.get("additionalProperties").booleanValue()).isFalse();
    JsonNode defs = schema.get("$defs");
    assertThat(names(defs.get("attributeSchema").get("properties")))
        .isEqualTo(names(json.get("attributeSchema")));
    Set<String> propertyFields = new HashSet<>();
    json.get("attributeSchema").get("properties").forEach(p -> propertyFields.addAll(names(p)));
    assertThat(names(defs.get("property").get("properties"))).isEqualTo(propertyFields);
  }

  @Test
  void requiredFieldsMatchWhatTheJavaFormAlwaysWrites() throws IOException {
    ObjectNode minimal =
        new VocabularyType(
                "owner", TypeKind.ATTRIBUTE, null, null, null, null, null, AttributeSchema.NONE)
            .toJson();

    assertThat(strings(schema().get("required"))).isEqualTo(names(minimal));
    assertThat(strings(schema().get("$defs").get("property").get("required")))
        .isEqualTo(Set.of("type"));
  }

  @Test
  void enumsMatchTheJavaEnums() throws IOException {
    JsonNode schema = schema();
    List<String> kinds = new ArrayList<>();
    for (TypeKind kind : TypeKind.values()) {
      kinds.add(kind.jsonName());
    }
    List<String> levels = new ArrayList<>();
    for (C4Level level : C4Level.values()) {
      levels.add(level.jsonName());
    }
    List<String> valueTypes = new ArrayList<>();
    for (ValueType type : ValueType.values()) {
      valueTypes.add(type.jsonName());
    }

    assertThat(strings(schema.get("properties").get("kind").get("enum")))
        .isEqualTo(Set.copyOf(kinds));
    assertThat(strings(schema.get("properties").get("c4Level").get("enum")))
        .isEqualTo(Set.copyOf(levels));
    assertThat(
            strings(schema.get("$defs").get("property").get("properties").get("type").get("enum")))
        .isEqualTo(Set.copyOf(valueTypes));
    Set<String> objectKinds = new HashSet<>();
    for (FactObject.Kind kind : FactObject.Kind.values()) {
      if (kind != FactObject.Kind.ENTITY) {
        objectKinds.add(kind.jsonName());
      }
    }
    assertThat(strings(schema.get("properties").get("objectKind").get("enum")))
        .isEqualTo(objectKinds);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"service", "batch-job", "b2", "Service", "batch_job", "-a", "CALLS", "owner"})
  void entityTypeNamePatternAgreesWithTheJavaType(String name) throws IOException {
    assertThat(matches("entityTypeName", name)).isEqualTo(accepts(name, TypeKind.ENTITY));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"CALLS", "READS_TABLE", "owner", "line.count", "a-b", "1A", "_A", "A B", "é"})
  void predicateNamePatternAgreesWithTheJavaType(String name) throws IOException {
    assertThat(matches("predicateName", name)).isEqualTo(accepts(name, TypeKind.RELATIONSHIP));
  }

  @ParameterizedTest
  @ValueSource(strings = {"box", "dashed-arrow", "a1", "Box", "a b", "-a"})
  void tokenPatternAgreesWithTheJavaType(String token) throws IOException {
    boolean accepted;
    try {
      new VocabularyType(
          "a", TypeKind.ENTITY, null, null, token, token, null, AttributeSchema.NONE);
      accepted = true;
    } catch (IllegalArgumentException e) {
      accepted = false;
    }
    assertThat(matches("token", token)).isEqualTo(accepted);
  }

  @ParameterizedTest
  @ValueSource(strings = {"group", "a-last", "x.y", "a_b", "1a", "", "a b"})
  void attributeNamePatternAgreesWithTheJavaType(String name) throws IOException {
    boolean accepted;
    try {
      new AttributeSchema(Map.of(name, Property.of(ValueType.STRING)), Set.of(), true);
      accepted = true;
    } catch (IllegalArgumentException e) {
      accepted = false;
    }
    assertThat(matches("attributeName", name)).isEqualTo(accepted);
  }

  @Test
  void namePatternsDependOnTheKindAsInJava() throws IOException {
    JsonNode rule = schema().get("allOf").get(0);

    assertThat(rule.get("if").get("properties").get("kind").get("const").stringValue())
        .isEqualTo(TypeKind.ENTITY.jsonName());
    for (String field : List.of("name", "parent")) {
      assertThat(rule.get("then").get("properties").get(field).get("$ref").stringValue())
          .isEqualTo("#/$defs/entityTypeName");
      assertThat(rule.get("else").get("properties").get(field).get("$ref").stringValue())
          .isEqualTo("#/$defs/predicateName");
    }
  }

  @Test
  void allowedValuesAndItemsAreTiedToTheirTypesAsInJava() throws IOException {
    JsonNode dependent = schema().get("$defs").get("property").get("dependentSchemas");

    assertThat(dependent.propertyNames()).containsExactlyInAnyOrder("enum", "items");
    assertThat(dependent.get("enum").get("properties").get("type").get("const").stringValue())
        .isEqualTo(ValueType.STRING.jsonName());
    assertThat(dependent.get("items").get("properties").get("type").get("const").stringValue())
        .isEqualTo(ValueType.ARRAY.jsonName());
    assertThatThrownBy(() -> new Property(ValueType.INTEGER, null, List.of("1"), null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new Property(ValueType.STRING, null, List.of(), Property.of(ValueType.STRING)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void builtInTypesUseOnlyKnownFields() throws IOException {
    Set<String> allowed = names(schema().get("properties"));
    for (VocabularyType type : BuiltInTypes.definitions()) {
      assertThat(allowed).containsAll(names(type.toJson()));
    }
  }

  private static boolean matches(String def, String value) throws IOException {
    Pattern pattern = Pattern.compile(schema().get("$defs").get(def).get("pattern").stringValue());
    return pattern.matcher(value).find();
  }

  private static boolean accepts(String name, TypeKind kind) {
    try {
      new VocabularyType(name, kind, null, null, null, null, null, AttributeSchema.NONE);
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

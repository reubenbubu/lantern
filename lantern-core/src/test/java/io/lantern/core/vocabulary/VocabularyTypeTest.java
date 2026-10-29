package io.lantern.core.vocabulary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VocabularyTypeTest {

  @Test
  void jsonFormRoundTripsAndLeavesOutAbsentFields() {
    VocabularyType full =
        new VocabularyType(
            "batch-job",
            TypeKind.ENTITY,
            "component",
            C4Level.COMPONENT,
            "box",
            "clock",
            "A scheduled job.",
            AttributeSchema.NONE);
    VocabularyType minimal =
        new VocabularyType(
            "owner", TypeKind.ATTRIBUTE, null, null, null, null, null, AttributeSchema.NONE);

    assertThat(VocabularyType.fromJson(full.toJson())).isEqualTo(full);
    assertThat(VocabularyType.fromJson(minimal.toJson())).isEqualTo(minimal);
    assertThat(minimal.toJson().propertyNames()).containsExactly("name", "kind", "attributeSchema");
    VocabularyType owner =
        new VocabularyType(
            "owner",
            TypeKind.ATTRIBUTE,
            null,
            null,
            null,
            null,
            null,
            AttributeSchema.NONE,
            FactObject.Kind.STRING);
    assertThat(owner.toJson().get("objectKind").stringValue()).isEqualTo("string");
    assertThat(VocabularyType.fromJson(owner.toJson())).isEqualTo(owner);
  }

  @Test
  void onlyAttributeTypesHaveAValueObjectKind() {
    assertThatThrownBy(
            () ->
                new VocabularyType(
                    "CALLS",
                    TypeKind.RELATIONSHIP,
                    null,
                    null,
                    null,
                    null,
                    null,
                    AttributeSchema.NONE,
                    FactObject.Kind.STRING))
        .hasMessageContaining("Only attribute types have an object kind");
    assertThatThrownBy(
            () ->
                new VocabularyType(
                    "owner",
                    TypeKind.ATTRIBUTE,
                    null,
                    null,
                    null,
                    null,
                    null,
                    AttributeSchema.NONE,
                    FactObject.Kind.ENTITY))
        .hasMessageContaining("must be a value kind");
  }

  @Test
  void namesFollowTheEntityIdAndPredicateForms() {
    assertThatThrownBy(() -> type("Service", TypeKind.ENTITY, null))
        .hasMessageContaining("Invalid entity type name");
    assertThatThrownBy(() -> type("READS TABLE", TypeKind.RELATIONSHIP, null))
        .hasMessageContaining("Invalid relationship type name");
    assertThatThrownBy(() -> type("CALLS", TypeKind.RELATIONSHIP, "Calls!"))
        .hasMessageContaining("Invalid relationship type name");
    assertThatThrownBy(() -> type("a", TypeKind.ENTITY, "a")).hasMessageContaining("own parent");
    assertThat(type("READS_TABLE", TypeKind.RELATIONSHIP, null).name()).isEqualTo("READS_TABLE");
    assertThat(type("owner", TypeKind.ATTRIBUTE, null).name()).isEqualTo("owner");
  }

  @Test
  void presentationNamesAndDescriptionsAreChecked() {
    assertThatThrownBy(
            () ->
                new VocabularyType(
                    "a", TypeKind.ENTITY, null, null, "Box", null, null, AttributeSchema.NONE))
        .hasMessageContaining("Invalid shape");
    assertThatThrownBy(
            () ->
                new VocabularyType(
                    "a", TypeKind.ENTITY, null, null, null, "an icon", null, AttributeSchema.NONE))
        .hasMessageContaining("Invalid icon");
    assertThatThrownBy(
            () ->
                new VocabularyType(
                    "a", TypeKind.ENTITY, null, null, null, null, " ", AttributeSchema.NONE))
        .hasMessageContaining("must not be blank");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"name\": \"a\", \"kind\": \"entity\"}",
        "{\"kind\": \"entity\", \"attributeSchema\": {}}",
        "{\"name\": \"a\", \"kind\": \"thing\", \"attributeSchema\": {}}",
        "{\"name\": \"a\", \"kind\": \"entity\", \"c4Level\": \"deployment\", \"attributeSchema\": {}}",
        "{\"name\": \"a\", \"kind\": \"entity\", \"colour\": \"red\", \"attributeSchema\": {}}",
        "{\"name\": 1, \"kind\": \"entity\", \"attributeSchema\": {}}",
        "{\"name\": \"a\", \"kind\": \"entity\", \"parent\": null, \"attributeSchema\": {}}"
      })
  void rejectsInvalidJson(String json) {
    assertThatThrownBy(() -> VocabularyType.fromJson(FactJson.parse(json)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static VocabularyType type(String name, TypeKind kind, String parent) {
    return new VocabularyType(name, kind, parent, null, null, null, null, AttributeSchema.NONE);
  }
}

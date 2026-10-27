package io.lantern.core.vocabulary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.vocabulary.AttributeSchema.Property;
import io.lantern.core.vocabulary.AttributeSchema.ValueType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class VocabularyTest {

  static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

  static VocabularyType entity(String name, String parent) {
    return new VocabularyType(
        name, TypeKind.ENTITY, parent, null, null, null, null, AttributeSchema.NONE);
  }

  static VocabularyType predicate(
      String name, TypeKind kind, String parent, AttributeSchema schema) {
    return new VocabularyType(name, kind, parent, null, null, null, null, schema);
  }

  static AttributeSchema schema(Map<String, Property> properties, Set<String> required) {
    return new AttributeSchema(properties, required, false);
  }

  /** The built-in types plus a runtime-added {@code batch-job} that extends component. */
  static Vocabulary withBatchJob() {
    List<VocabularyType> types = new ArrayList<>(BuiltInTypes.definitions());
    types.add(
        new VocabularyType(
            "batch-job",
            TypeKind.ENTITY,
            BuiltInTypes.COMPONENT,
            null,
            null,
            "clock",
            null,
            AttributeSchema.NONE));
    return new Vocabulary(2, types);
  }

  static Fact fact(String predicate, ObjectNode attributes) {
    return Fact.create(
        EntityId.parse("service:orders"),
        predicate,
        FactObject.entity(EntityId.parse("service:billing")),
        attributes,
        new Origin(Origin.Kind.STATIC, "test", null),
        new Evidence("orders", COMMIT, null, null, null),
        1.0,
        Validity.from(1));
  }

  @Test
  void looksUpParentTypes() {
    Vocabulary vocabulary = withBatchJob();

    assertThat(vocabulary.lineage("batch-job"))
        .extracting(VocabularyType::name)
        .containsExactly("batch-job", BuiltInTypes.COMPONENT);
    assertThat(vocabulary.lineage(BuiltInTypes.CALLS))
        .extracting(VocabularyType::name)
        .containsExactly(BuiltInTypes.CALLS, BuiltInTypes.DEPENDS_ON);
    assertThat(vocabulary.lineage(BuiltInTypes.SYSTEM))
        .extracting(VocabularyType::name)
        .containsExactly(BuiltInTypes.SYSTEM);
    assertThat(vocabulary.isA("batch-job", BuiltInTypes.COMPONENT)).isTrue();
    assertThat(vocabulary.isA("batch-job", "batch-job")).isTrue();
    assertThat(vocabulary.isA(BuiltInTypes.SERVICE, BuiltInTypes.COMPONENT)).isFalse();
    assertThat(vocabulary.isA("unknown", BuiltInTypes.COMPONENT)).isFalse();
    assertThatThrownBy(() -> vocabulary.lineage("unknown"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aNewTypeInheritsHowItsParentIsShown() {
    Vocabulary vocabulary = withBatchJob();

    assertThat(vocabulary.c4Level("batch-job")).contains(C4Level.COMPONENT);
    assertThat(vocabulary.shape("batch-job")).contains("box");
    assertThat(vocabulary.icon("batch-job")).contains("clock");
    assertThat(vocabulary.c4Level(BuiltInTypes.SERVICE)).contains(C4Level.CONTAINER);
    assertThat(vocabulary.shape(BuiltInTypes.PACKAGE)).contains("folder");
    assertThat(vocabulary.icon(BuiltInTypes.SERVICE)).isEmpty();
    assertThat(vocabulary.c4Level(BuiltInTypes.CALLS)).isEmpty();
  }

  @Test
  void attributeSchemasAreInherited() {
    Vocabulary vocabulary =
        new Vocabulary(
            1,
            List.of(
                predicate(
                    "USES",
                    TypeKind.RELATIONSHIP,
                    null,
                    schema(Map.of("via", Property.of(ValueType.STRING)), Set.of())),
                predicate(
                    "PUBLISHES_TO",
                    TypeKind.RELATIONSHIP,
                    "USES",
                    schema(Map.of("key", Property.of(ValueType.STRING)), Set.of("via")))));

    AttributeSchema schema = vocabulary.attributeSchema("PUBLISHES_TO");

    assertThat(schema.properties()).containsOnlyKeys("via", "key");
    assertThat(schema.required()).containsExactly("via");
    assertThat(vocabulary.attributeSchema("USES").required()).isEmpty();
  }

  @Test
  void validatesFactAttributesAgainstThePredicateType() {
    Vocabulary vocabulary =
        new Vocabulary(
            1,
            List.of(
                entity("service", null),
                predicate(
                    "owner",
                    TypeKind.ATTRIBUTE,
                    null,
                    schema(Map.of("since", Property.of(ValueType.INTEGER)), Set.of())),
                predicate("CALLS", TypeKind.RELATIONSHIP, null, AttributeSchema.NONE)));
    ObjectNode since = FactJson.newObject().put("since", 2024);
    ObjectNode badSince = FactJson.newObject().put("since", "2024");

    assertThatCode(() -> vocabulary.validate(fact("owner", since))).doesNotThrowAnyException();
    assertThatCode(() -> vocabulary.validate(fact("CALLS", FactJson.newObject())))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> vocabulary.validate(fact("owner", badSince)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'since' must be an integer");
    assertThatThrownBy(() -> vocabulary.validate(fact("CALLS", since)))
        .hasMessageContaining("'since' is not a declared attribute");
    assertThatThrownBy(() -> vocabulary.validate(fact("CONSUMES", FactJson.newObject())))
        .hasMessageContaining("not in the vocabulary");
    assertThatThrownBy(() -> vocabulary.validate(fact("service", FactJson.newObject())))
        .hasMessageContaining("is an entity type");
  }

  @Test
  void rejectsInconsistentVocabularies() {
    assertThatThrownBy(() -> new Vocabulary(1, List.of(entity("a", null), entity("a", null))))
        .hasMessageContaining("defined twice");
    assertThatThrownBy(() -> new Vocabulary(1, List.of(entity("a", "missing"))))
        .hasMessageContaining("does not exist");
    assertThatThrownBy(
            () ->
                new Vocabulary(
                    1,
                    List.of(
                        entity("a", null),
                        predicate("B", TypeKind.RELATIONSHIP, null, AttributeSchema.NONE),
                        predicate("C", TypeKind.RELATIONSHIP, "B", AttributeSchema.NONE),
                        predicate("D", TypeKind.ATTRIBUTE, "C", AttributeSchema.NONE))))
        .hasMessageContaining("differ in kind");
    assertThatThrownBy(() -> new Vocabulary(1, List.of(entity("a", "b"), entity("b", "a"))))
        .hasMessageContaining("cycle");
    assertThatThrownBy(
            () ->
                new Vocabulary(
                    1,
                    List.of(
                        predicate(
                            "P",
                            TypeKind.RELATIONSHIP,
                            null,
                            schema(Map.of("a", Property.of(ValueType.STRING)), Set.of())),
                        predicate(
                            "Q",
                            TypeKind.RELATIONSHIP,
                            "P",
                            schema(Map.of("a", Property.of(ValueType.BOOLEAN)), Set.of())))))
        .hasMessageContaining("Attribute schema of 'Q'");
    assertThatThrownBy(() -> new Vocabulary(-1, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void changesLeaveOutUnchangedDefinitions() {
    Vocabulary vocabulary = new Vocabulary(1, List.of(entity("a", null)));
    VocabularyType documented =
        new VocabularyType(
            "a", TypeKind.ENTITY, null, C4Level.CODE, "box", "x", "doc", AttributeSchema.NONE);

    assertThat(vocabulary.changes(List.of(entity("a", null)))).isEmpty();
    assertThat(vocabulary.changes(List.of(entity("a", null), entity("b", "a"))))
        .containsExactly(entity("b", "a"));
    assertThat(vocabulary.changes(List.of(documented))).containsExactly(documented);
    assertThatThrownBy(() -> vocabulary.changes(List.of(entity("b", null), entity("b", null))))
        .hasMessageContaining("listed twice");
  }

  @Test
  void changesToExistingTypesMustBeAdditive() {
    AttributeSchema withA = schema(Map.of("a", Property.of(ValueType.STRING)), Set.of());
    Vocabulary vocabulary =
        new Vocabulary(
            1,
            List.of(
                entity("a", null),
                entity("b", null),
                predicate("P", TypeKind.RELATIONSHIP, null, withA)));

    assertThatThrownBy(() -> vocabulary.changes(List.of(entity("b", "a"))))
        .hasMessageContaining("changes the parent type");
    assertThatThrownBy(
            () -> vocabulary.changes(List.of(predicate("P", TypeKind.ATTRIBUTE, null, withA))))
        .hasMessageContaining("changes the kind");
    assertThatThrownBy(
            () ->
                vocabulary.changes(
                    List.of(predicate("P", TypeKind.RELATIONSHIP, null, AttributeSchema.NONE))))
        .hasMessageContaining("removes attribute 'a'");
    AttributeSchema withAB =
        schema(
            Map.of("a", Property.of(ValueType.STRING), "b", Property.of(ValueType.NUMBER)),
            Set.of());
    assertThat(vocabulary.changes(List.of(predicate("P", TypeKind.RELATIONSHIP, null, withAB))))
        .hasSize(1);
  }
}

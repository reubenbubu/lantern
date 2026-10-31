package io.lantern.core.vocabulary;

import static org.assertj.core.api.Assertions.assertThat;
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

/** Key attributes of vocabulary types and the claim IDs they give (ADR 0090). */
class KeyAttributesTest {

  private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
  private static final String OTHER_COMMIT = "89abcdef0123456789abcdef0123456789abcdef";

  /** {@code entry} declares {@code profile}; {@code route} adds the key {@code path}. */
  private static Vocabulary vocabulary() {
    List<VocabularyType> types = new ArrayList<>(BuiltInTypes.definitions());
    types.add(
        new VocabularyType(
                "entry",
                TypeKind.ATTRIBUTE,
                null,
                null,
                null,
                null,
                null,
                new AttributeSchema(
                    Map.of(
                        "profile", Property.of(ValueType.STRING),
                        "kind", Property.of(ValueType.STRING)),
                    Set.of(),
                    false))
            .withKeyAttributes(Set.of("kind")));
    types.add(
        new VocabularyType(
                "route",
                TypeKind.ATTRIBUTE,
                "entry",
                null,
                null,
                null,
                null,
                new AttributeSchema(
                    Map.of(
                        "path", Property.of(ValueType.STRING),
                        "controller", Property.of(ValueType.STRING)),
                    Set.of(),
                    false))
            .withKeyAttributes(Set.of("path")));
    return new Vocabulary(1, types);
  }

  private static Fact fact(ObjectNode attributes, String commit, int line, String version) {
    return Fact.create(
        new EntityId("type", "com.acme.Orders"),
        "route",
        FactObject.string("GET /orders"),
        attributes,
        new Origin(Origin.Kind.STATIC, "spring", version),
        Evidence.of("orders", "src/Orders.java", new Evidence.LineRange(line, line), null),
        1.0,
        Validity.from(1));
  }

  private static ObjectNode attributes(
      String kind, String path, String profile, String controller) {
    ObjectNode node = FactJson.newObject();
    node.put("kind", kind);
    node.put("path", path);
    if (profile != null) {
      node.put("profile", profile);
    }
    node.put("controller", controller);
    return node;
  }

  @Test
  void aTypeHasItsOwnKeyAttributesAndItsParents() {
    Vocabulary vocabulary = vocabulary();

    assertThat(vocabulary.keyAttributes("route")).containsExactly("kind", "path");
    assertThat(vocabulary.keyAttributes("entry")).containsExactly("kind");
    assertThat(vocabulary.keyAttributes(BuiltInTypes.CALLS)).isEmpty();
    assertThatThrownBy(() -> vocabulary.keyAttributes("nothing"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aKeyAttributeMustBeDeclared() {
    List<VocabularyType> types = new ArrayList<>(BuiltInTypes.definitions());
    types.add(
        new VocabularyType(
                "loose", TypeKind.ATTRIBUTE, null, null, null, null, null, AttributeSchema.NONE)
            .withKeyAttributes(Set.of("path")));

    assertThatThrownBy(() -> new Vocabulary(1, types))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Key attribute 'path' of 'loose' is not declared");
  }

  @Test
  void twoSightingsOfOneThingAreOneClaim() {
    Vocabulary vocabulary = vocabulary();
    Fact seen = fact(attributes("http", "/orders", null, "OrdersController"), COMMIT, 10, "1");
    Fact again = fact(attributes("http", "/orders", "docker", "OrderApi"), OTHER_COMMIT, 42, "2");

    assertThat(again.id()).isNotEqualTo(seen.id());
    assertThat(vocabulary.claimId(again)).isEqualTo(vocabulary.claimId(seen)).hasSize(64);
  }

  @Test
  void twoThingsAreTwoClaims() {
    Vocabulary vocabulary = vocabulary();
    Fact orders = fact(attributes("http", "/orders", null, "C"), COMMIT, 10, "1");
    Fact otherPath = fact(attributes("http", "/invoices", null, "C"), COMMIT, 10, "1");
    Fact otherInheritedKey = fact(attributes("grpc", "/orders", null, "C"), COMMIT, 10, "1");

    assertThat(vocabulary.claimId(otherPath)).isNotEqualTo(vocabulary.claimId(orders));
    assertThat(vocabulary.claimId(otherInheritedKey)).isNotEqualTo(vocabulary.claimId(orders));
  }

  @Test
  void anAbsentKeyAttributeIsLikeANullOne() {
    Vocabulary vocabulary = vocabulary();
    ObjectNode absent = attributes("http", "/orders", null, "C");
    absent.remove("path");
    ObjectNode isNull = attributes("http", "/orders", null, "C");
    isNull.putNull("path");

    assertThat(vocabulary.claimId(fact(isNull, COMMIT, 1, "1")))
        .isEqualTo(vocabulary.claimId(fact(absent, COMMIT, 1, "1")));
  }

  @Test
  void keyAttributesRoundTripThroughJsonAndOnlyPredicatesHaveThem() {
    VocabularyType route = vocabulary().find("route").orElseThrow();

    assertThat(VocabularyType.fromJson(route.toJson())).isEqualTo(route);
    assertThat(route.toJson().get("keyAttributes").toString()).isEqualTo("[\"path\"]");
    assertThat(vocabulary().find("entry").orElseThrow().toJson().has("keyAttributes")).isTrue();
    assertThat(VocabularyTest.entity("thing", null).toJson().has("keyAttributes")).isFalse();
    assertThatThrownBy(() -> VocabularyTest.entity("thing", null).withKeyAttributes(Set.of("a")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                VocabularyType.fromJson(
                    FactJson.parse(
                        "{\"name\": \"a\", \"kind\": \"attribute\", \"attributeSchema\": {},"
                            + " \"keyAttributes\": [\"x\", \"x\"]}")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                VocabularyType.fromJson(
                    FactJson.parse(
                        "{\"name\": \"a\", \"kind\": \"attribute\", \"attributeSchema\": {},"
                            + " \"keyAttributes\": \"x\"}")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void changingKeyAttributesIsANewRevision() {
    Vocabulary vocabulary = vocabulary();
    VocabularyType route = vocabulary.find("route").orElseThrow();

    assertThat(vocabulary.changes(List.of(route.withKeyAttributes(Set.of("controller")))))
        .extracting(VocabularyType::name)
        .containsExactly("route");
  }
}

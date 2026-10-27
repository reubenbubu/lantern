package io.lantern.core.fact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class FactTest {

  static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
  static final String CONTENT_HASH =
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

  static final EntityId SUBJECT = EntityId.parse("service:orders");
  static final FactObject OBJECT = FactObject.entity(EntityId.parse("topic:kafka:order-created"));
  static final Origin ORIGIN = new Origin(Origin.Kind.STATIC, "messaging", "1.0.0");
  static final Evidence EVIDENCE =
      new Evidence(
          "orders",
          COMMIT,
          "src/main/java/com/acme/OrderListener.java",
          new Evidence.LineRange(12, 18),
          CONTENT_HASH);

  static Fact sample() {
    ObjectNode attributes = FactJson.newObject();
    attributes.put("group", "orders-service");
    return Fact.create(
        SUBJECT, "CONSUMES", OBJECT, attributes, ORIGIN, EVIDENCE, 0.9, Validity.from(1));
  }

  @Test
  void createDerivesTheIdFromTheContent() {
    Fact fact = sample();

    assertThat(fact.id())
        .isEqualTo(
            FactJson.factId(
                fact.subject(),
                fact.predicate(),
                fact.object(),
                fact.attributes(),
                fact.origin(),
                fact.evidence()));
  }

  @Test
  void cannotBeCreatedWithoutOrigin() {
    assertThatThrownBy(
            () ->
                Fact.create(
                    SUBJECT,
                    "CONSUMES",
                    OBJECT,
                    FactJson.newObject(),
                    null,
                    EVIDENCE,
                    1.0,
                    Validity.from(1)))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("origin");
    assertThatThrownBy(
            () ->
                new Fact(
                    sample().id(),
                    SUBJECT,
                    "CONSUMES",
                    OBJECT,
                    FactJson.newObject(),
                    null,
                    EVIDENCE,
                    1.0,
                    Validity.from(1)))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("origin");
  }

  @Test
  void cannotBeCreatedWithoutEvidence() {
    assertThatThrownBy(
            () ->
                Fact.create(
                    SUBJECT,
                    "CONSUMES",
                    OBJECT,
                    FactJson.newObject(),
                    ORIGIN,
                    null,
                    1.0,
                    Validity.from(1)))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("evidence");
    assertThatThrownBy(
            () ->
                new Fact(
                    sample().id(),
                    SUBJECT,
                    "CONSUMES",
                    OBJECT,
                    FactJson.newObject(),
                    ORIGIN,
                    null,
                    1.0,
                    Validity.from(1)))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("evidence");
  }

  @Test
  void rejectsAnIdThatDoesNotMatchTheContent() {
    Fact fact = sample();

    assertThatThrownBy(
            () ->
                new Fact(
                    fact.id(),
                    EntityId.parse("service:billing"),
                    fact.predicate(),
                    fact.object(),
                    fact.attributes(),
                    fact.origin(),
                    fact.evidence(),
                    fact.confidence(),
                    fact.validity()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not match");
  }

  @Test
  void holdsAttributesInCanonicalForm() {
    ObjectNode attributes = FactJson.newObject();
    attributes.put("b", 1.0).put("a", 2.50);

    Fact fact =
        Fact.create(
            SUBJECT, "CONSUMES", OBJECT, attributes, ORIGIN, EVIDENCE, 1.0, Validity.from(1));

    assertThat(fact.attributes()).isEqualTo(FactJson.parse("{\"a\":2.5,\"b\":1}"));
    assertThat(fact.attributes().propertyNames()).containsExactly("a", "b");
  }

  @Test
  void rejectsAttributesJsonCannotCarry() {
    ObjectNode attributes = FactJson.newObject().put("x", Double.NaN);

    assertThatThrownBy(
            () ->
                Fact.create(
                    SUBJECT,
                    "CONSUMES",
                    OBJECT,
                    attributes,
                    ORIGIN,
                    EVIDENCE,
                    1.0,
                    Validity.from(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsConfidenceOutsideZeroToOne() {
    for (double confidence : new double[] {-0.1, 1.1, Double.NaN}) {
      assertThatThrownBy(
              () ->
                  Fact.create(
                      SUBJECT,
                      "CONSUMES",
                      OBJECT,
                      FactJson.newObject(),
                      ORIGIN,
                      EVIDENCE,
                      confidence,
                      Validity.from(1)))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void rejectsInvalidEvidence() {
    assertThatThrownBy(() -> new Evidence("orders", "abc123", null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new Evidence("orders", COMMIT, null, new Evidence.LineRange(1, 2), null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Evidence.LineRange(5, 4))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void attributesCannotBeChangedFromOutside() {
    ObjectNode attributes = FactJson.newObject();
    attributes.put("group", "a");
    Fact fact =
        Fact.create(
            SUBJECT, "CONSUMES", OBJECT, attributes, ORIGIN, EVIDENCE, 1.0, Validity.from(1));

    attributes.put("group", "b");
    fact.attributes().put("group", "c");

    assertThat(fact.attributes().get("group").stringValue()).isEqualTo("a");
  }

  @Test
  void validityContainsItsStartButNotItsEnd() {
    Validity validity = new Validity(3, 5L);

    assertThat(validity.contains(2)).isFalse();
    assertThat(validity.contains(3)).isTrue();
    assertThat(validity.contains(4)).isTrue();
    assertThat(validity.contains(5)).isFalse();
    assertThat(Validity.from(3).contains(Long.MAX_VALUE)).isTrue();
    assertThatThrownBy(() -> new Validity(5, 5L)).isInstanceOf(IllegalArgumentException.class);
  }
}

package io.lantern.core.fact;

import static io.lantern.core.fact.FactTest.COMMIT;
import static io.lantern.core.fact.FactTest.EVIDENCE;
import static io.lantern.core.fact.FactTest.OBJECT;
import static io.lantern.core.fact.FactTest.ORIGIN;
import static io.lantern.core.fact.FactTest.SUBJECT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.node.ObjectNode;

class FactJsonTest {

  static Stream<Fact> facts() {
    ObjectNode nested = FactJson.newObject();
    nested.put("b", 2).put("a", "x");
    nested.putArray("list").add(1).add("two").add(true);
    nested.putObject("inner").put("z", 1.5);
    return Stream.of(
        FactTest.sample(),
        Fact.create(
            SUBJECT,
            "owner",
            FactObject.string("team-payments"),
            FactJson.newObject(),
            new Origin(Origin.Kind.HUMAN, "user-42", null),
            new Evidence("orders", COMMIT, null, null, null),
            1.0,
            new Validity(0, 7L)),
        Fact.create(
            EntityId.parse("java:com.acme.Foo"),
            "lineCount",
            FactObject.integer(-120),
            nested,
            new Origin(Origin.Kind.LLM, "summarizer-model", "prompt-3"),
            new Evidence("orders", COMMIT, "Foo.java", null, null),
            0.0,
            Validity.from(2)),
        Fact.create(
            SUBJECT,
            "coverage",
            FactObject.decimal(new BigDecimal("0.750")),
            FactJson.newObject(),
            new Origin(Origin.Kind.RULE, "rule-7", "2"),
            EVIDENCE,
            0.5,
            Validity.from(1)),
        Fact.create(
            SUBJECT,
            "deployable",
            FactObject.bool(true),
            FactJson.newObject(),
            new Origin(Origin.Kind.README, "readme", "1"),
            new Evidence("orders", COMMIT.repeat(2).substring(0, 64), "README.md", null, null),
            0.3,
            Validity.from(1)));
  }

  @ParameterizedTest
  @MethodSource("facts")
  void envelopeRoundTripsThroughJson(Fact fact) {
    String json = FactJson.write(fact);
    Fact read = FactJson.read(json);

    assertThat(read).isEqualTo(fact);
    assertThat(FactJson.write(read)).isEqualTo(json);
  }

  @Test
  void writesAbsentOptionalFieldsAsMissingNotNull() {
    String humanFact = FactJson.write(facts().toList().get(1));
    String openEnded = FactJson.write(facts().toList().get(2));

    assertThat(humanFact).doesNotContain("null").doesNotContain("\"version\"");
    assertThat(humanFact).doesNotContain("\"path\"").doesNotContain("\"lines\"");
    assertThat(openEnded).doesNotContain("null").doesNotContain("\"to\"");
  }

  @Test
  void sameInputAlwaysGivesTheSameFactId() {
    ObjectNode first = FactJson.newObject();
    first.put("a", 1).put("b", "two");
    first.putObject("c").put("y", 1).put("x", 2);
    ObjectNode second = FactJson.newObject();
    second.putObject("c").put("x", 2).put("y", 1);
    second.put("b", "two").put("a", 1);

    String id1 = FactJson.factId(SUBJECT, "CONSUMES", OBJECT, first, ORIGIN, EVIDENCE);
    String id2 =
        FactJson.factId(
            EntityId.parse("service:orders"),
            "CONSUMES",
            FactObject.entity(EntityId.parse("topic:kafka:order-created")),
            second,
            new Origin(Origin.Kind.STATIC, "messaging", "1.0.0"),
            new Evidence(
                EVIDENCE.repo(),
                EVIDENCE.commit(),
                EVIDENCE.path(),
                new Evidence.LineRange(12, 18),
                EVIDENCE.contentHash()));

    assertThat(id1).isEqualTo(id2).matches("[0-9a-f]{64}");
  }

  @Test
  void factIdIsPinned() {
    // Guards the canonical form: changing it changes every stored fact ID.
    assertThat(FactTest.sample().id())
        .isEqualTo("24ad7b33ea63503bf6f2605eab7c3497283f449b956c8e801df0a1bf04fbe4f2");
  }

  @Test
  void numericallyEqualAttributesGiveTheSameId() {
    ObjectNode a = FactJson.newObject().put("n", 1);
    ObjectNode b = (ObjectNode) FactJson.parse("{\"n\":1.0}");

    assertThat(FactJson.factId(SUBJECT, "p", OBJECT, a, ORIGIN, EVIDENCE))
        .isEqualTo(FactJson.factId(SUBJECT, "p", OBJECT, b, ORIGIN, EVIDENCE));
  }

  @Test
  void anyChangeInClaimOrProvenanceChangesTheId() {
    Fact base = FactTest.sample();
    String id = base.id();
    List<String> others =
        List.of(
            FactJson.factId(
                EntityId.parse("service:billing"),
                base.predicate(),
                base.object(),
                base.attributes(),
                base.origin(),
                base.evidence()),
            FactJson.factId(
                base.subject(),
                "PRODUCES",
                base.object(),
                base.attributes(),
                base.origin(),
                base.evidence()),
            FactJson.factId(
                base.subject(),
                base.predicate(),
                FactObject.string("topic:kafka:order-created"),
                base.attributes(),
                base.origin(),
                base.evidence()),
            FactJson.factId(
                base.subject(),
                base.predicate(),
                base.object(),
                FactJson.newObject(),
                base.origin(),
                base.evidence()),
            FactJson.factId(
                base.subject(),
                base.predicate(),
                base.object(),
                base.attributes(),
                new Origin(Origin.Kind.STATIC, "messaging", "1.0.1"),
                base.evidence()),
            FactJson.factId(
                base.subject(),
                base.predicate(),
                base.object(),
                base.attributes(),
                base.origin(),
                new Evidence(
                    base.evidence().repo(), base.evidence().commit(), "Other.java", null, null)));

    assertThat(others).doesNotContain(id).doesNotHaveDuplicates();
  }

  @Test
  void confidenceAndValidityDoNotChangeTheId() {
    Fact base = FactTest.sample();
    Fact other =
        Fact.create(
            base.subject(),
            base.predicate(),
            base.object(),
            base.attributes(),
            base.origin(),
            base.evidence(),
            0.1,
            new Validity(5, 9L));

    assertThat(other.id()).isEqualTo(base.id());
  }

  @Test
  void readingAFactWithoutProvenanceFails() {
    ObjectNode json = FactJson.toJson(FactTest.sample());
    ObjectNode noOrigin = json.deepCopy();
    noOrigin.remove("origin");
    ObjectNode noEvidence = json.deepCopy();
    noEvidence.remove("evidence");
    ObjectNode nullOrigin = json.deepCopy();
    nullOrigin.putNull("origin");

    for (ObjectNode node : List.of(noOrigin, noEvidence, nullOrigin)) {
      assertThatThrownBy(() -> FactJson.fromJson(node))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void readingRejectsUnknownFieldsAndWrongTypes() {
    ObjectNode extra = FactJson.toJson(FactTest.sample());
    extra.put("extra", 1);
    ObjectNode wrongValueType = FactJson.toJson(FactTest.sample());
    ((ObjectNode) wrongValueType.get("object")).put("kind", "integer");
    ObjectNode nonCanonicalDecimal = FactJson.toJson(FactTest.sample());
    ((ObjectNode) nonCanonicalDecimal.get("object")).put("kind", "decimal").put("value", "1.50");
    ObjectNode hugeDecimal = FactJson.toJson(FactTest.sample());
    ((ObjectNode) hugeDecimal.get("object")).put("kind", "decimal").put("value", "1E+999999999");
    ObjectNode tampered = FactJson.toJson(FactTest.sample());
    tampered.put("subject", "service:billing");

    for (ObjectNode node :
        List.of(extra, wrongValueType, nonCanonicalDecimal, hugeDecimal, tampered)) {
      assertThatThrownBy(() -> FactJson.fromJson(node))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}

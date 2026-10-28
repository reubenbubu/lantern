package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.FactRepository;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.vocabulary.AttributeSchema;
import io.lantern.core.vocabulary.TypeKind;
import io.lantern.core.vocabulary.VocabularyRegistry;
import io.lantern.core.vocabulary.VocabularyType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.node.ObjectNode;

/** Runs the fact repository against the real schema in PostgreSQL. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FactRepositoryIT {

  static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
  static final String CONTENT_HASH =
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
  static final EntityId ORDERS = EntityId.parse("service:orders");
  static final EntityId PAYMENTS = EntityId.parse("service:payments");
  static final EntityId TOPIC = EntityId.parse("topic:kafka:order-created");
  static final Origin ORIGIN = new Origin(Origin.Kind.STATIC, "messaging", "1.0.0");
  static final Evidence EVIDENCE =
      new Evidence("orders", COMMIT, "src/Foo.java", new Evidence.LineRange(3, 9), CONTENT_HASH);

  /** The predicates these tests use. CONSUMES declares one attribute and allows others. */
  static final List<VocabularyType> TEST_TYPES =
      List.of(
          new VocabularyType(
              "CONSUMES",
              TypeKind.RELATIONSHIP,
              null,
              null,
              null,
              null,
              null,
              new AttributeSchema(
                  Map.of("group", AttributeSchema.Property.of(AttributeSchema.ValueType.STRING)),
                  Set.of(),
                  true)),
          attribute("owner"),
          attribute("coverage"),
          attribute("lineCount"),
          attribute("deployable"));

  @Autowired FactRepository facts;
  @Autowired VocabularyRegistry vocabulary;
  @Autowired JdbcClient jdbc;

  static VocabularyType attribute(String name) {
    return new VocabularyType(
        name, TypeKind.ATTRIBUTE, null, null, null, null, null, AttributeSchema.NONE);
  }

  @BeforeEach
  void clean() {
    jdbc.sql("DELETE FROM facts").update();
    vocabulary.apply("test", TEST_TYPES);
  }

  static Fact consumes(EntityId subject, Validity validity) {
    return consumes(subject, 0.9, validity);
  }

  static Fact consumes(EntityId subject, double confidence, Validity validity) {
    ObjectNode attributes = FactJson.newObject();
    attributes.put("group", "orders-service");
    ObjectNode nested = attributes.putObject("nested");
    nested.put("ratio", 1.5).put("whole", 1.0).put("big", 1e20);
    nested.putArray("tags").add("a").add(2);
    attributes.put("a-last-but-sorts-first", true);
    return Fact.create(
        subject,
        "CONSUMES",
        FactObject.entity(TOPIC),
        attributes,
        ORIGIN,
        EVIDENCE,
        confidence,
        validity);
  }

  @Test
  void savedFactsReadBackUnchanged() {
    List<Fact> saved =
        List.of(
            consumes(ORDERS, Validity.from(1)),
            Fact.create(
                ORDERS,
                "owner",
                FactObject.string("team-payments"),
                FactJson.newObject(),
                new Origin(Origin.Kind.HUMAN, "user-42", null),
                new Evidence("orders", COMMIT, null, null, null),
                1.0,
                new Validity(0, 4L)),
            Fact.create(
                ORDERS,
                "coverage",
                FactObject.decimal(new BigDecimal("0.75")),
                FactJson.newObject(),
                ORIGIN,
                EVIDENCE,
                0.0,
                Validity.from(2)),
            Fact.create(
                ORDERS,
                "lineCount",
                FactObject.integer(Long.MAX_VALUE),
                FactJson.newObject(),
                ORIGIN,
                EVIDENCE,
                0.5,
                Validity.from(2)),
            Fact.create(
                ORDERS,
                "deployable",
                FactObject.bool(false),
                FactJson.newObject(),
                ORIGIN,
                EVIDENCE,
                0.5,
                Validity.from(2)));

    for (Fact fact : saved) {
      assertThat(facts.save(fact)).isTrue();
    }
    for (Fact fact : saved) {
      assertThat(facts.findById(fact.id())).contains(fact);
    }
  }

  @Test
  void savingTheSameFactTwiceKeepsOneRow() {
    Fact fact = consumes(ORDERS, Validity.from(1));

    assertThat(facts.save(fact)).isTrue();
    assertThat(facts.save(consumes(ORDERS, Validity.from(1)))).isFalse();
    assertThat(jdbc.sql("SELECT count(*) FROM facts").query(Long.class).single()).isEqualTo(1L);
  }

  @Test
  void savingTheSameClaimWithDifferentValidityIsRefused() {
    facts.save(consumes(ORDERS, Validity.from(1)));

    assertThatThrownBy(() -> facts.save(consumes(ORDERS, new Validity(1, 2L))))
        .isInstanceOf(IllegalStateException.class);
    // A stored fact that holds from a later snapshot does not cover an earlier one.
    facts.save(consumes(PAYMENTS, Validity.from(3)));
    assertThatThrownBy(() -> facts.save(consumes(PAYMENTS, Validity.from(2))))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aFactObservedAgainWhileItStillHoldsChangesNothing() {
    Fact first = consumes(ORDERS, Validity.from(1));
    facts.save(first);

    assertThat(facts.save(consumes(ORDERS, Validity.from(2)))).isFalse();
    assertThat(facts.findById(first.id())).contains(first);
    assertThat(facts.findValidAt(2)).containsExactly(first);
  }

  static Fact human(Validity validity) {
    return Fact.create(
        ORDERS,
        "owner",
        FactObject.string("team-payments"),
        FactJson.newObject(),
        new Origin(Origin.Kind.HUMAN, "user-42", null),
        new Evidence("orders", COMMIT, null, null, null),
        1.0,
        validity);
  }

  FactRepository.SnapshotUpdate record(long snapshot, Fact... found) {
    return facts.recordSnapshot(snapshot, Origin.Kind.STATIC, List.of(found));
  }

  @Test
  void aFactFoundAgainKeepsHoldingFromWhereItStarted() {
    Fact orders = consumes(ORDERS, Validity.from(1));
    record(1, orders);

    FactRepository.SnapshotUpdate update = record(2, consumes(ORDERS, Validity.from(2)));

    assertThat(update.facts()).containsExactly(orders);
    assertThat(update.added()).isZero();
    assertThat(update.changed()).isZero();
    assertThat(update.closed()).isZero();
    assertThat(facts.findValidAt(1)).containsExactly(orders);
    assertThat(facts.findValidAt(2)).containsExactly(orders);
    assertThat(facts.findValidAt(7)).containsExactly(orders);
  }

  @Test
  void aFactNoLongerFoundStopsHolding() {
    Fact orders = consumes(ORDERS, Validity.from(1));
    Fact payments = consumes(PAYMENTS, Validity.from(1));
    record(1, orders, payments);

    FactRepository.SnapshotUpdate update = record(2, consumes(ORDERS, Validity.from(2)));

    assertThat(update.closed()).isEqualTo(1);
    Fact closed = consumes(PAYMENTS, new Validity(1, 2L));
    assertThat(facts.findById(payments.id())).contains(closed);
    assertThat(facts.findValidAt(1)).containsExactlyInAnyOrder(orders, closed);
    assertThat(facts.findValidAt(2)).containsExactly(orders);
  }

  @Test
  void aFactFoundAgainAfterAGapHoldsAgainAsANewVersion() {
    record(1, consumes(ORDERS, Validity.from(1)));
    record(2);

    FactRepository.SnapshotUpdate update = record(3, consumes(ORDERS, Validity.from(3)));

    Fact before = consumes(ORDERS, new Validity(1, 2L));
    Fact again = consumes(ORDERS, Validity.from(3));
    assertThat(update.added()).isEqualTo(1);
    assertThat(update.facts()).containsExactly(again);
    assertThat(facts.findById(again.id())).contains(again);
    assertThat(facts.findBySubject(ORDERS)).containsExactly(before, again);
    assertThat(facts.findValidAt(1)).containsExactly(before);
    assertThat(facts.findValidAt(2)).isEmpty();
    assertThat(facts.findValidAt(3)).containsExactly(again);
  }

  @Test
  void aConfidenceChangeStartsANewVersion() {
    record(1, consumes(ORDERS, 0.5, Validity.from(1)));

    FactRepository.SnapshotUpdate update = record(3, consumes(ORDERS, 0.9, Validity.from(3)));

    Fact lower = consumes(ORDERS, 0.5, new Validity(1, 3L));
    Fact higher = consumes(ORDERS, 0.9, Validity.from(3));
    assertThat(update.changed()).isEqualTo(1);
    assertThat(update.added()).isZero();
    assertThat(update.facts()).containsExactly(higher);
    assertThat(facts.findValidAt(2)).containsExactly(lower);
    assertThat(facts.findValidAt(3)).containsExactly(higher);
    assertThat(facts.findById(higher.id())).contains(higher);
  }

  @Test
  void recordingTheLatestSnapshotAgainReplacesIt() {
    Fact orders = consumes(ORDERS, Validity.from(1));
    Fact payments = consumes(PAYMENTS, Validity.from(2));
    record(1, orders);
    record(2, payments);

    record(2, consumes(ORDERS, Validity.from(2)), consumes(PAYMENTS, 0.5, Validity.from(2)));
    assertThat(facts.findValidAt(1)).containsExactly(orders);
    assertThat(facts.findValidAt(2))
        .containsExactlyInAnyOrder(orders, consumes(PAYMENTS, 0.5, Validity.from(2)));

    record(2, payments);
    assertThat(facts.findValidAt(1)).containsExactly(consumes(ORDERS, new Validity(1, 2L)));
    assertThat(facts.findValidAt(2)).containsExactly(payments);
    assertThat(jdbc.sql("SELECT count(*) FROM facts").query(Long.class).single()).isEqualTo(2L);
  }

  @Test
  void factsOfOtherOriginKindsAreNotTouched() {
    Fact owner = human(Validity.from(1));
    facts.save(owner);
    record(1, consumes(ORDERS, Validity.from(1)));

    record(2);

    assertThat(facts.findValidAt(2)).containsExactly(owner);
  }

  @Test
  void snapshotsMustBeRecordedInOrder() {
    record(3, consumes(ORDERS, Validity.from(3)));
    assertThatThrownBy(() -> record(2, consumes(ORDERS, Validity.from(2))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("in order");

    record(4);
    assertThatThrownBy(() -> record(3)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void recordingRefusesFactsThatDoNotFitTheSnapshot() {
    assertThatThrownBy(() -> record(2, consumes(ORDERS, Validity.from(1))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> record(2, consumes(ORDERS, new Validity(2, 3L))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                record(
                    2, consumes(ORDERS, 0.5, Validity.from(2)), consumes(ORDERS, Validity.from(2))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> record(2, human(Validity.from(2))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(jdbc.sql("SELECT count(*) FROM facts").query(Long.class).single()).isZero();
  }

  @Test
  void databaseRejectsOverlappingVersions() {
    Fact orders = consumes(ORDERS, Validity.from(1));
    facts.save(orders);

    assertThatThrownBy(
            () ->
                jdbc.sql(
                        """
                        INSERT INTO facts (id, subject, predicate, object_kind, object, origin,
                                           evidence, confidence, validity)
                        SELECT id, subject, predicate, object_kind, object, origin, evidence,
                               confidence, int8range(5, 9)
                        FROM facts
                        """)
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void findsBySubjectPredicateObjectAndSnapshot() {
    Fact current = consumes(ORDERS, Validity.from(3));
    Fact billing = consumes(EntityId.parse("service:billing"), new Validity(1, 3L));
    Fact owner =
        Fact.create(
            ORDERS,
            "owner",
            FactObject.string("topic:kafka:order-created"),
            FactJson.newObject(),
            ORIGIN,
            EVIDENCE,
            1.0,
            Validity.from(1));
    facts.save(current);
    facts.save(billing);
    facts.save(owner);

    assertThat(facts.findBySubject(ORDERS)).containsExactlyInAnyOrder(current, owner);
    assertThat(facts.findByPredicate("CONSUMES")).containsExactlyInAnyOrder(current, billing);
    assertThat(facts.findByObject(FactObject.entity(TOPIC)))
        .containsExactlyInAnyOrder(current, billing);
    assertThat(facts.findByObject(FactObject.string(TOPIC.toString()))).containsExactly(owner);
    assertThat(facts.findValidAt(2)).containsExactlyInAnyOrder(billing, owner);
    assertThat(facts.findValidAt(3)).containsExactlyInAnyOrder(current, owner);
    assertThat(facts.findValidAt(0)).isEmpty();
  }

  @Test
  void factsThatDoNotMatchTheVocabularyAreNotSaved() {
    ObjectNode badGroup = FactJson.newObject().put("group", 42);
    Fact wrongAttributes =
        Fact.create(
            ORDERS,
            "CONSUMES",
            FactObject.entity(TOPIC),
            badGroup,
            ORIGIN,
            EVIDENCE,
            0.9,
            Validity.from(1));
    Fact unknownPredicate =
        Fact.create(
            ORDERS,
            "NOT_IN_VOCABULARY",
            FactObject.entity(TOPIC),
            FactJson.newObject(),
            ORIGIN,
            EVIDENCE,
            0.9,
            Validity.from(1));

    assertThatThrownBy(() -> facts.save(wrongAttributes))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'group' must be a string");
    assertThatThrownBy(() -> facts.save(unknownPredicate))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not in the vocabulary");
    assertThat(jdbc.sql("SELECT count(*) FROM facts").query(Long.class).single()).isZero();
  }

  @Test
  void databaseRejectsRowsWithoutProvenance() {
    Fact fact = consumes(ORDERS, Validity.from(1));
    String insert =
        """
        INSERT INTO facts (id, subject, predicate, object_kind, object, origin, evidence,
                           confidence, validity)
        VALUES (:id, 'service:orders', 'CONSUMES', 'string', 'x', CAST(:origin AS jsonb),
                CAST(:evidence AS jsonb), 1, int8range(1, NULL))
        """;
    String origin = "{\"kind\":\"static\",\"name\":\"messaging\"}";
    String evidence = "{\"repo\":\"orders\",\"commit\":\"" + COMMIT + "\"}";

    assertThatThrownBy(
            () ->
                jdbc.sql(insert)
                    .param("id", fact.id())
                    .param("origin", null)
                    .param("evidence", evidence)
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.sql(insert)
                    .param("id", fact.id())
                    .param("origin", origin)
                    .param("evidence", null)
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.sql(insert)
                    .param("id", fact.id())
                    .param("origin", origin)
                    .param("evidence", "{}")
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void queriedColumnsAreIndexed() {
    List<String> indexes =
        jdbc.sql("SELECT indexdef FROM pg_indexes WHERE tablename = 'facts'")
            .query(String.class)
            .list();

    assertThat(indexes)
        .anyMatch(d -> d.contains("(subject)"))
        .anyMatch(d -> d.contains("(predicate)"))
        .anyMatch(d -> d.contains("(object, object_kind)"))
        .anyMatch(d -> d.contains("gist (validity)"));
  }
}

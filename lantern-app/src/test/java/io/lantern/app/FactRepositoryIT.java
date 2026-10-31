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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
  static final Evidence BILLING_EVIDENCE = Evidence.of("billing", null, null, null);
  static final EntityId ORDERS = EntityId.parse("service:orders");
  static final EntityId PAYMENTS = EntityId.parse("service:payments");
  static final EntityId TOPIC = EntityId.parse("topic:kafka:order-created");
  static final Origin ORIGIN = new Origin(Origin.Kind.STATIC, "messaging", "1.0.0");
  static final Evidence EVIDENCE =
      Evidence.of("orders", "src/Foo.java", new Evidence.LineRange(3, 9), CONTENT_HASH);

  /**
   * The types these tests use besides the built-in and the app's ones (topic is the linker's).
   * LISTENS_ON declares one attribute, its key, and allows others; owner's objects must be strings.
   */
  static final List<VocabularyType> TEST_TYPES =
      List.of(
          new VocabularyType(
                  "LISTENS_ON",
                  TypeKind.RELATIONSHIP,
                  null,
                  null,
                  null,
                  null,
                  null,
                  new AttributeSchema(
                      Map.of(
                          "group", AttributeSchema.Property.of(AttributeSchema.ValueType.STRING)),
                      Set.of(),
                      true))
              .withKeyAttributes(Set.of("group")),
          new VocabularyType(
              "owner",
              TypeKind.ATTRIBUTE,
              null,
              null,
              null,
              null,
              null,
              AttributeSchema.NONE,
              FactObject.Kind.STRING),
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
    jdbc.sql("DELETE FROM observations").update();
    jdbc.sql("DELETE FROM claims").update();
    vocabulary.apply("test", TEST_TYPES);
  }

  static Fact consumes(EntityId subject, Validity validity) {
    return consumes(subject, 0.9, validity);
  }

  static Fact consumes(EntityId subject, double confidence, Validity validity) {
    return consumes(subject, confidence, EVIDENCE, validity);
  }

  static Fact consumes(EntityId subject, double confidence, Evidence evidence, Validity validity) {
    ObjectNode attributes = FactJson.newObject();
    attributes.put("group", "orders-service");
    ObjectNode nested = attributes.putObject("nested");
    nested.put("ratio", 1.5).put("whole", 1.0).put("big", 1e20);
    nested.putArray("tags").add("a").add(2);
    attributes.put("a-last-but-sorts-first", true);
    return Fact.create(
        subject,
        "LISTENS_ON",
        FactObject.entity(TOPIC),
        attributes,
        ORIGIN,
        evidence,
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
                Evidence.of("orders", null, null, null),
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
      assertThat(findById(fact.id())).contains(fact);
    }
  }

  @Test
  void savingTheSameFactTwiceKeepsOneRow() {
    Fact fact = consumes(ORDERS, Validity.from(1));

    assertThat(facts.save(fact)).isTrue();
    assertThat(facts.save(consumes(ORDERS, Validity.from(1)))).isFalse();
    assertThat(jdbc.sql("SELECT count(*) FROM observations").query(Long.class).single())
        .isEqualTo(1L);
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
    assertThat(findById(first.id())).contains(first);
    assertThat(findValidAt(2)).containsExactly(first);
  }

  static Fact human(Validity validity) {
    return Fact.create(
        ORDERS,
        "owner",
        FactObject.string("team-payments"),
        FactJson.newObject(),
        new Origin(Origin.Kind.HUMAN, "user-42", null),
        Evidence.of("orders", null, null, null),
        1.0,
        validity);
  }

  /**
   * A fact as read back, checked to carry its claim ID, without it, so tests compare it with the
   * fact they stored.
   */
  static Fact bare(Fact fact) {
    assertThat(fact.claimId()).matches("[0-9a-f]{64}");
    return fact.stored(null, fact.validity());
  }

  static List<Fact> bare(List<Fact> found) {
    return found.stream().map(FactRepositoryIT::bare).toList();
  }

  Optional<Fact> findById(String id) {
    return facts.findById(id).map(FactRepositoryIT::bare);
  }

  List<Fact> findValidAt(long snapshot) {
    return bare(facts.findValidAt(snapshot));
  }

  List<Fact> findValidAt(long snapshot, Collection<String> repos) {
    return bare(facts.findValidAt(snapshot, repos));
  }

  List<Fact> findBySubject(EntityId subject) {
    return bare(facts.findBySubject(subject));
  }

  List<Fact> findByPredicate(String predicate) {
    return bare(facts.findByPredicate(predicate));
  }

  List<Fact> findByObject(FactObject object) {
    return bare(facts.findByObject(object));
  }

  FactRepository.SnapshotUpdate record(long snapshot, Fact... found) {
    return facts.recordSnapshot(snapshot, Origin.Kind.STATIC, Set.of("orders"), List.of(found));
  }

  @Test
  void aFactFoundAgainKeepsHoldingFromWhereItStarted() {
    Fact orders = consumes(ORDERS, Validity.from(1));
    record(1, orders);

    FactRepository.SnapshotUpdate update = record(2, consumes(ORDERS, Validity.from(2)));

    assertThat(bare(update.facts())).containsExactly(orders);
    assertThat(update.added()).isZero();
    assertThat(update.changed()).isZero();
    assertThat(update.closed()).isZero();
    assertThat(findValidAt(1)).containsExactly(orders);
    assertThat(findValidAt(2)).containsExactly(orders);
    assertThat(findValidAt(7)).containsExactly(orders);
  }

  @Test
  void aFactNoLongerFoundStopsHolding() {
    Fact orders = consumes(ORDERS, Validity.from(1));
    Fact payments = consumes(PAYMENTS, Validity.from(1));
    record(1, orders, payments);

    FactRepository.SnapshotUpdate update = record(2, consumes(ORDERS, Validity.from(2)));

    assertThat(update.closed()).isEqualTo(1);
    Fact closed = consumes(PAYMENTS, new Validity(1, 2L));
    assertThat(findById(payments.id())).contains(closed);
    assertThat(findValidAt(1)).containsExactlyInAnyOrder(orders, closed);
    assertThat(findValidAt(2)).containsExactly(orders);
  }

  @Test
  void aFactFoundAgainAfterAGapHoldsAgainAsANewVersion() {
    record(1, consumes(ORDERS, Validity.from(1)));
    record(2);

    FactRepository.SnapshotUpdate update = record(3, consumes(ORDERS, Validity.from(3)));

    Fact before = consumes(ORDERS, new Validity(1, 2L));
    Fact again = consumes(ORDERS, Validity.from(3));
    assertThat(update.added()).isEqualTo(1);
    assertThat(bare(update.facts())).containsExactly(again);
    assertThat(findById(again.id())).contains(again);
    assertThat(findBySubject(ORDERS)).containsExactly(before, again);
    assertThat(findValidAt(1)).containsExactly(before);
    assertThat(findValidAt(2)).isEmpty();
    assertThat(findValidAt(3)).containsExactly(again);
  }

  @Test
  void aConfidenceChangeStartsANewVersion() {
    record(1, consumes(ORDERS, 0.5, Validity.from(1)));

    FactRepository.SnapshotUpdate update = record(3, consumes(ORDERS, 0.9, Validity.from(3)));

    Fact lower = consumes(ORDERS, 0.5, new Validity(1, 3L));
    Fact higher = consumes(ORDERS, 0.9, Validity.from(3));
    assertThat(update.changed()).isEqualTo(1);
    assertThat(update.added()).isZero();
    assertThat(bare(update.facts())).containsExactly(higher);
    assertThat(findValidAt(2)).containsExactly(lower);
    assertThat(findValidAt(3)).containsExactly(higher);
    assertThat(findById(higher.id())).contains(higher);
  }

  @Test
  void recordingTheLatestSnapshotAgainReplacesIt() {
    Fact orders = consumes(ORDERS, Validity.from(1));
    Fact payments = consumes(PAYMENTS, Validity.from(2));
    record(1, orders);
    record(2, payments);

    record(2, consumes(ORDERS, Validity.from(2)), consumes(PAYMENTS, 0.5, Validity.from(2)));
    assertThat(findValidAt(1)).containsExactly(orders);
    assertThat(findValidAt(2))
        .containsExactlyInAnyOrder(orders, consumes(PAYMENTS, 0.5, Validity.from(2)));

    record(2, payments);
    assertThat(findValidAt(1)).containsExactly(consumes(ORDERS, new Validity(1, 2L)));
    assertThat(findValidAt(2)).containsExactly(payments);
    assertThat(jdbc.sql("SELECT count(*) FROM observations").query(Long.class).single())
        .isEqualTo(2L);
  }

  @Test
  void recordingTheLatestSnapshotAgainWithTheEarlierConfidenceRejoinsTheVersions() {
    Fact lower = consumes(ORDERS, 0.5, Validity.from(1));
    record(1, lower);
    record(2, consumes(ORDERS, 0.9, Validity.from(2)));

    FactRepository.SnapshotUpdate update = record(2, consumes(ORDERS, 0.5, Validity.from(2)));

    assertThat(bare(update.facts())).containsExactly(lower);
    assertThat(update.changed()).isEqualTo(1);
    assertThat(update.added()).isZero();
    assertThat(findBySubject(ORDERS)).containsExactly(lower);
  }

  @Test
  void factsOfOtherOriginKindsAreNotTouched() {
    Fact owner = human(Validity.from(1));
    facts.save(owner);
    record(1, consumes(ORDERS, Validity.from(1)));

    record(2);

    assertThat(findValidAt(2)).containsExactly(owner);
  }

  @Test
  void factsOfRepositoriesNotAnalyzedKeepHolding() {
    Fact orders = consumes(ORDERS, Validity.from(1));
    record(1, orders);

    Fact billing = consumes(PAYMENTS, 0.9, BILLING_EVIDENCE, Validity.from(2));
    FactRepository.SnapshotUpdate update =
        facts.recordSnapshot(2, Origin.Kind.STATIC, Set.of("billing"), List.of(billing));

    assertThat(update.closed()).isZero();
    assertThat(findValidAt(2)).containsExactlyInAnyOrder(orders, billing);

    // Analyzing orders again closes its facts that are gone, and leaves billing's alone.
    update = record(3);
    assertThat(update.closed()).isEqualTo(1);
    assertThat(findValidAt(3)).containsExactly(billing);
  }

  @Test
  void derivedFactsOfAnotherSystemKeepHolding() {
    // Two systems share snapshot numbers: orders' in snapshot 1, billing's in snapshot 2 (ADR
    // 0071).
    Fact orders = consumes(ORDERS, Validity.from(1));
    facts.recordDerived(1, ORIGIN, Set.of("orders"), List.of(orders));
    Fact billing = consumes(PAYMENTS, 0.9, BILLING_EVIDENCE, Validity.from(2));

    FactRepository.SnapshotUpdate update =
        facts.recordDerived(2, ORIGIN, Set.of("billing"), List.of(billing));

    assertThat(update.closed()).isZero();
    assertThat(findValidAt(2)).containsExactlyInAnyOrder(orders, billing);
    assertThat(findValidAt(2, List.of("billing"))).containsExactly(billing);
    assertThat(findValidAt(2, List.of())).isEmpty();
    assertThatThrownBy(
            () ->
                facts.recordDerived(
                    3, ORIGIN, Set.of("billing"), List.of(consumes(ORDERS, Validity.from(3)))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("orders");
    // Unscoped, a derivation step ends its facts in every repository.
    assertThat(facts.recordDerived(3, ORIGIN, List.of()).closed()).isEqualTo(2);
  }

  @Test
  void recordingRefusesFactsOfRepositoriesNotAnalyzed() {
    assertThatThrownBy(
            () ->
                facts.recordSnapshot(
                    1,
                    Origin.Kind.STATIC,
                    Set.of("billing"),
                    List.of(consumes(ORDERS, Validity.from(1)))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("orders");
    assertThat(jdbc.sql("SELECT count(*) FROM observations").query(Long.class).single()).isZero();
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
    assertThat(jdbc.sql("SELECT count(*) FROM observations").query(Long.class).single()).isZero();
  }

  @Test
  void aChangedLineRangeKeepsTheClaimAndStartsANewObservation() {
    Fact before = consumes(ORDERS, Validity.from(1));
    Fact moved =
        consumes(
            ORDERS,
            0.9,
            Evidence.of("orders", "src/Foo.java", new Evidence.LineRange(5, 11), CONTENT_HASH),
            Validity.from(2));
    record(1, before);

    FactRepository.SnapshotUpdate update = record(2, moved);

    // Another sighting of the same claim (ADR 0091): the claim keeps its ID, the first
    // observation ends where the second starts.
    assertThat(moved.id()).isNotEqualTo(before.id());
    assertThat(update.facts()).singleElement().extracting(Fact::claimId).isEqualTo(claim(before));
    assertThat(update.added()).isOne();
    assertThat(update.closed()).isOne();
    assertThat(findById(before.id()).orElseThrow().validity()).isEqualTo(new Validity(1, 2L));
    assertThat(findValidAt(2)).containsExactly(moved);
    assertThat(jdbc.sql("SELECT count(*) FROM claims").query(Long.class).single()).isOne();
  }

  @Test
  void severalSightingsOfOneClaimHoldAtOnce() {
    Fact first = consumes(ORDERS, Validity.from(1));
    Fact second =
        consumes(
            ORDERS,
            0.9,
            Evidence.of("orders", "src/Bar.java", new Evidence.LineRange(1, 2), CONTENT_HASH),
            Validity.from(1));

    record(1, first, second);

    assertThat(findValidAt(1)).containsExactlyInAnyOrder(first, second);
    assertThat(facts.findValidAt(1)).extracting(Fact::claimId).containsOnly(claim(first));
  }

  @Test
  void theClaimHoldsTheKeyAttributesAndTheObservationTheOthers() {
    Fact fact = consumes(ORDERS, Validity.from(1));
    record(1, fact);

    assertThat(jdbc.sql("SELECT key_attributes::text FROM claims").query(String.class).single())
        .isEqualTo("{\"group\": \"orders-service\"}");
    assertThat(jdbc.sql("SELECT attributes::text FROM observations").query(String.class).single())
        .contains("nested")
        .doesNotContain("group");
    assertThat(findById(fact.id())).contains(fact);
  }

  @Test
  void lineageIsStoredWithTheObservation() {
    Fact source = consumes(ORDERS, Validity.from(1));
    record(1, source);
    Fact derived =
        Fact.create(
                ORDERS,
                "owner",
                FactObject.string("team"),
                FactJson.newObject(),
                ORIGIN,
                EVIDENCE,
                1.0,
                Validity.from(1))
            .withLineage(List.of(claim(source)));

    record(1, source, derived);

    assertThat(findById(derived.id()).orElseThrow().lineage()).containsExactly(claim(source));
  }

  @Test
  void recordingNamesEachFileByItsBlobAtTheSnapshotsCommit() {
    String blob = "b".repeat(40);
    FactRepository withBlobs =
        new FactRepository(
            jdbc,
            vocabulary,
            (snapshot, repo, path) ->
                repo.equals("orders") && path.equals("src/Foo.java")
                    ? Optional.of(blob)
                    : Optional.empty());
    Fact found = consumes(ORDERS, Validity.from(1));

    FactRepository.SnapshotUpdate first =
        withBlobs.recordSnapshot(1, Origin.Kind.STATIC, Set.of("orders"), List.of(found));
    FactRepository.SnapshotUpdate second =
        withBlobs.recordSnapshot(
            2, Origin.Kind.STATIC, Set.of("orders"), List.of(consumes(ORDERS, Validity.from(2))));

    Fact stored = first.facts().getFirst();
    assertThat(stored.evidence().blob()).isEqualTo(blob);
    assertThat(stored.evidence().contentHash()).isNull();
    assertThat(stored.claimId()).isEqualTo(claim(found));
    assertThat(second.added()).isZero();
    assertThat(second.facts()).containsExactly(stored);
  }

  /** The claim ID the app's vocabulary gives the fact. */
  private String claim(Fact fact) {
    return vocabulary.current().claimId(fact);
  }

  @Test
  void databaseRejectsOverlappingVersions() {
    Fact orders = consumes(ORDERS, Validity.from(1));
    facts.save(orders);

    assertThatThrownBy(
            () ->
                jdbc.sql(
                        """
                        INSERT INTO observations (id, claim_id, origin, evidence, confidence,
                                                  validity)
                        SELECT id, claim_id, origin, evidence, confidence, int8range(5, 9)
                        FROM observations
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

    assertThat(findBySubject(ORDERS)).containsExactlyInAnyOrder(current, owner);
    assertThat(findByPredicate("LISTENS_ON")).containsExactlyInAnyOrder(current, billing);
    assertThat(findByObject(FactObject.entity(TOPIC))).containsExactlyInAnyOrder(current, billing);
    assertThat(findByObject(FactObject.string(TOPIC.toString()))).containsExactly(owner);
    assertThat(findValidAt(2)).containsExactlyInAnyOrder(billing, owner);
    assertThat(findValidAt(3)).containsExactlyInAnyOrder(current, owner);
    assertThat(findValidAt(0)).isEmpty();
  }

  @Test
  void factsThatDoNotMatchTheVocabularyAreNotSaved() {
    ObjectNode badGroup = FactJson.newObject().put("group", 42);
    Fact wrongAttributes =
        Fact.create(
            ORDERS,
            "LISTENS_ON",
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
    Fact unknownEntityType =
        Fact.create(
            EntityId.parse("mailbox:orders"),
            "LISTENS_ON",
            FactObject.entity(TOPIC),
            FactJson.newObject(),
            ORIGIN,
            EVIDENCE,
            0.9,
            Validity.from(1));
    Fact wrongObjectKind =
        Fact.create(
            ORDERS,
            "owner",
            FactObject.integer(7),
            FactJson.newObject(),
            ORIGIN,
            EVIDENCE,
            0.9,
            Validity.from(1));
    assertThatThrownBy(() -> facts.save(unknownEntityType))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Subject 'mailbox:orders' has type 'mailbox'");
    assertThatThrownBy(() -> facts.save(wrongObjectKind))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be a string, not an integer");
    assertThatThrownBy(
            () ->
                facts.recordSnapshot(
                    1, Origin.Kind.STATIC, Set.of("orders"), List.of(wrongObjectKind)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be a string, not an integer");
    assertThat(jdbc.sql("SELECT count(*) FROM observations").query(Long.class).single()).isZero();
  }

  @Test
  void databaseRejectsRowsWithoutProvenance() {
    Fact fact = consumes(ORDERS, Validity.from(1));
    String insert =
        """
        INSERT INTO observations (id, claim_id, origin, evidence, confidence, validity)
        VALUES (:id, :claim, CAST(:origin AS jsonb), CAST(:evidence AS jsonb), 1,
                int8range(1, NULL))
        """;
    facts.save(fact);
    String claim = facts.findById(fact.id()).orElseThrow().claimId();
    String origin = "{\"kind\":\"static\",\"name\":\"messaging\"}";
    String evidence = "[{\"repo\":\"orders\",\"role\":\"declared\"}]";

    assertThatThrownBy(
            () ->
                jdbc.sql(insert)
                    .param("id", fact.id())
                    .param("claim", claim)
                    .param("origin", null)
                    .param("evidence", evidence)
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.sql(insert)
                    .param("id", fact.id())
                    .param("claim", claim)
                    .param("origin", origin)
                    .param("evidence", null)
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.sql(insert)
                    .param("id", fact.id())
                    .param("claim", claim)
                    .param("origin", origin)
                    .param("evidence", "[]")
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void queriedColumnsAreIndexed() {
    List<String> indexes =
        jdbc.sql("SELECT indexdef FROM pg_indexes WHERE tablename IN ('claims', 'observations')")
            .query(String.class)
            .list();

    assertThat(indexes)
        .anyMatch(d -> d.contains("(subject, predicate)"))
        .anyMatch(d -> d.contains("(predicate)"))
        .anyMatch(d -> d.contains("(object, object_kind)"))
        .anyMatch(d -> d.contains("(claim_id)"))
        .anyMatch(d -> d.contains("(repo)"))
        .anyMatch(d -> d.contains("gist (validity)"));
  }
}

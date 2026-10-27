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
import java.math.BigDecimal;
import java.util.List;
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
  static final EntityId TOPIC = EntityId.parse("topic:kafka:order-created");
  static final Origin ORIGIN = new Origin(Origin.Kind.STATIC, "messaging", "1.0.0");
  static final Evidence EVIDENCE =
      new Evidence("orders", COMMIT, "src/Foo.java", new Evidence.LineRange(3, 9), CONTENT_HASH);

  @Autowired FactRepository facts;
  @Autowired JdbcClient jdbc;

  @BeforeEach
  void clean() {
    jdbc.sql("DELETE FROM facts").update();
  }

  static Fact consumes(EntityId subject, Validity validity) {
    ObjectNode attributes = FactJson.newObject();
    attributes.put("group", "orders-service");
    attributes.putObject("nested").put("ratio", 1.5).putArray("tags").add("a").add(2);
    return Fact.create(
        subject, "CONSUMES", FactObject.entity(TOPIC), attributes, ORIGIN, EVIDENCE, 0.9, validity);
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
        .anyMatch(d -> d.contains("(object)"))
        .anyMatch(d -> d.contains("gist (validity)"));
  }
}

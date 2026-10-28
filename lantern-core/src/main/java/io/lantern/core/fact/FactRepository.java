package io.lantern.core.fact;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.node.ObjectNode;

/**
 * Stores facts in the {@code facts} table. Every fact is checked by the {@link FactValidator}
 * before it is written; in the app that is the vocabulary registry, which validates the fact's
 * attributes against its predicate's type.
 */
public class FactRepository {

  private static final String COLUMNS =
      """
      id, subject, predicate, object_kind, object, attributes::text AS attributes,
      origin::text AS origin, evidence::text AS evidence, confidence,
      lower(validity) AS valid_from, upper(validity) AS valid_to
      """;

  private final JdbcClient jdbc;
  private final FactValidator validator;

  public FactRepository(JdbcClient jdbc, FactValidator validator) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.validator = Objects.requireNonNull(validator, "validator");
  }

  /**
   * Saves a fact. Facts are keyed by their content-derived ID, so saving a fact that is already
   * stored changes nothing. The ID leaves out confidence and validity, so a stored fact with the
   * same ID can differ in those. A fact observed again in a later snapshot, while the stored one
   * still holds (its validity is open and starts no later), is already covered and changes nothing.
   * Any other difference is refused rather than silently ignored, because changing a stored fact's
   * validity or confidence is not supported yet (#23).
   *
   * @return whether the fact was new
   * @throws IllegalArgumentException if the validator rejects the fact
   * @throws IllegalStateException if a fact with the same ID but a different confidence, or a
   *     validity the stored one does not cover, is already stored
   */
  public boolean save(Fact fact) {
    Objects.requireNonNull(fact, "fact");
    validator.validate(fact);
    int rows =
        jdbc.sql(
                """
                INSERT INTO facts (id, subject, predicate, object_kind, object, attributes,
                                   origin, evidence, confidence, validity)
                VALUES (:id, :subject, :predicate, :objectKind, :object, CAST(:attributes AS jsonb),
                        CAST(:origin AS jsonb), CAST(:evidence AS jsonb), :confidence,
                        int8range(:validFrom, CAST(:validTo AS bigint), '[)'))
                ON CONFLICT (id) DO NOTHING
                """)
            .param("id", fact.id())
            .param("subject", fact.subject().toString())
            .param("predicate", fact.predicate())
            .param("objectKind", fact.object().kind().jsonName())
            .param("object", fact.object().value())
            .param("attributes", FactJson.canonical(fact.attributes()))
            .param("origin", FactJson.canonical(FactJson.toJson(fact.origin())))
            .param("evidence", FactJson.canonical(FactJson.toJson(fact.evidence())))
            .param("confidence", fact.confidence())
            .param("validFrom", fact.validity().from())
            .param("validTo", fact.validity().to())
            .update();
    if (rows == 1) {
      return true;
    }
    Fact stored =
        findById(fact.id())
            .orElseThrow(() -> new IllegalStateException("Fact " + fact.id() + " vanished"));
    if (!stored.equals(fact) && !stillHolds(stored, fact)) {
      throw new IllegalStateException(
          "Fact "
              + fact.id()
              + " is already stored with a different confidence or validity: stored "
              + stored.confidence()
              + " "
              + stored.validity()
              + ", new "
              + fact.confidence()
              + " "
              + fact.validity());
    }
    return false;
  }

  /**
   * Whether the stored fact is the same claim with the same confidence, still holding from an
   * earlier (or the same) snapshot onwards, so it covers the fact observed again.
   */
  private static boolean stillHolds(Fact stored, Fact observed) {
    return stored.confidence() == observed.confidence()
        && observed.validity().to() == null
        && stored.validity().to() == null
        && stored.validity().from() <= observed.validity().from();
  }

  public Optional<Fact> findById(String id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM facts WHERE id = :id")
        .param("id", id)
        .query(FactRepository::mapRow)
        .optional();
  }

  public List<Fact> findBySubject(EntityId subject) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM facts WHERE subject = :subject ORDER BY id")
        .param("subject", subject.toString())
        .query(FactRepository::mapRow)
        .list();
  }

  public List<Fact> findByPredicate(String predicate) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM facts WHERE predicate = :predicate ORDER BY id")
        .param("predicate", predicate)
        .query(FactRepository::mapRow)
        .list();
  }

  public List<Fact> findByObject(FactObject object) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM facts WHERE object = :object AND object_kind = :objectKind ORDER BY id")
        .param("object", object.value())
        .param("objectKind", object.kind().jsonName())
        .query(FactRepository::mapRow)
        .list();
  }

  /** The facts that hold in the given snapshot. */
  public List<Fact> findValidAt(long snapshot) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM facts WHERE validity @> CAST(:snapshot AS bigint) ORDER BY id")
        .param("snapshot", snapshot)
        .query(FactRepository::mapRow)
        .list();
  }

  private static Fact mapRow(ResultSet rs, int rowNum) throws SQLException {
    long validTo = rs.getLong("valid_to");
    Long to = rs.wasNull() ? null : validTo;
    return new Fact(
        rs.getString("id"),
        EntityId.parse(rs.getString("subject")),
        rs.getString("predicate"),
        new FactObject(
            FactObject.Kind.fromJsonName(rs.getString("object_kind")), rs.getString("object")),
        (ObjectNode) FactJson.parse(rs.getString("attributes")),
        FactJson.originFromJson(FactJson.parse(rs.getString("origin"))),
        FactJson.evidenceFromJson(FactJson.parse(rs.getString("evidence"))),
        rs.getDouble("confidence"),
        new Validity(rs.getLong("valid_from"), to));
  }
}

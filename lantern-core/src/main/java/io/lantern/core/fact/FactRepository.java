package io.lantern.core.fact;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.node.ObjectNode;

/**
 * Stores facts in the {@code facts} table. Every fact is checked by the {@link FactValidator}
 * before it is written; in the app that is the vocabulary registry, which validates the fact's
 * attributes against its predicate's type.
 *
 * <p>A fact's ID leaves out its validity and confidence, so one fact can be stored as several
 * versions, each a contiguous range of snapshots with one confidence; the versions of a fact never
 * overlap (ADR 0017). Queries by subject, predicate or object return every version.
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
   * Any other difference is refused rather than silently ignored: a fact's validity and confidence
   * change only through {@link #recordSnapshot}.
   *
   * @return whether the fact was new
   * @throws IllegalArgumentException if the validator rejects the fact
   * @throws IllegalStateException if a fact with the same ID but a different confidence, or a
   *     validity its latest stored version does not cover, is already stored
   */
  public boolean save(Fact fact) {
    Objects.requireNonNull(fact, "fact");
    validator.validate(fact);
    if (insert(fact, true)) {
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
        && stored.validity().from() <= observed.validity().from()
        && stored.validity().to() == null;
  }

  /**
   * What {@link #recordSnapshot} changed.
   *
   * @param facts the facts found in the snapshot as now stored, each once, in the order first
   *     found; a fact that already held keeps the start of its validity
   * @param added how many found facts did not hold in the previous snapshot
   * @param changed how many found facts held with a different confidence, which now starts a new
   *     version
   * @param closed how many facts that held were not found, so no longer hold
   */
  public record SnapshotUpdate(List<Fact> facts, int added, int changed, int closed) {

    public SnapshotUpdate {
      facts = List.copyOf(facts);
    }
  }

  /**
   * Records the facts of one origin kind found in a snapshot, so that afterwards exactly these
   * facts of that kind hold in it (ADR 0017):
   *
   * <ul>
   *   <li>a found fact that already holds with the same confidence keeps holding, its validity
   *       still open from where it started;
   *   <li>one that holds with a different confidence stops holding at this snapshot, and a new
   *       version with the new confidence holds from it;
   *   <li>one that does not hold (new, or seen before but closed since) holds from this snapshot;
   *   <li>a fact of that kind that holds but was not found stops holding at this snapshot.
   * </ul>
   *
   * Snapshots must be recorded in order, which is checked as far as the stored facts of that kind
   * show. Recording the latest snapshot again replaces what was recorded for it. Run this in a
   * transaction, so a failure leaves the store unchanged.
   *
   * @param snapshot the snapshot number
   * @param kind the origin kind of the found facts; facts of other kinds are not touched
   * @param found the facts found, each valid from the snapshot onwards; the same fact may appear
   *     more than once, with the same confidence
   * @throws IllegalArgumentException if a fact has another validity or origin kind, the same fact
   *     appears with different confidences, or the validator rejects a fact
   * @throws IllegalStateException if facts of that kind were recorded for a later snapshot
   */
  public SnapshotUpdate recordSnapshot(long snapshot, Origin.Kind kind, Collection<Fact> found) {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(found, "found");
    Validity validity = Validity.from(snapshot);
    Map<String, Fact> byId = new LinkedHashMap<>();
    for (Fact fact : found) {
      if (!fact.validity().equals(validity)) {
        throw new IllegalArgumentException(
            "Fact "
                + fact.id()
                + " must be valid from snapshot "
                + snapshot
                + ": "
                + fact.validity());
      }
      if (fact.origin().kind() != kind) {
        throw new IllegalArgumentException(
            "Fact " + fact.id() + " has origin kind " + fact.origin().kind() + ", not " + kind);
      }
      Fact previous = byId.putIfAbsent(fact.id(), fact);
      if (previous != null && previous.confidence() != fact.confidence()) {
        throw new IllegalArgumentException(
            "Fact " + fact.id() + " was found with different confidences");
      }
      validator.validate(fact);
    }
    long later =
        jdbc.sql(
                """
                SELECT count(*) FROM facts
                WHERE (lower(validity) > :snapshot OR upper(validity) > :snapshot)
                  AND origin->>'kind' = :kind
                """)
            .param("snapshot", snapshot)
            .param("kind", kind.jsonName())
            .query(Long.class)
            .single();
    if (later > 0) {
      throw new IllegalStateException(
          later
              + " "
              + kind.jsonName()
              + " facts were recorded for a snapshot after "
              + snapshot
              + "; snapshots must be recorded in order");
    }

    Map<String, Fact> stored = new HashMap<>();
    int added = 0;
    int changed = 0;
    int closed = 0;
    List<OpenVersion> open =
        jdbc.sql(
                """
                SELECT id, confidence, lower(validity) AS valid_from FROM facts
                WHERE upper_inf(validity) AND origin->>'kind' = :kind
                """)
            .param("kind", kind.jsonName())
            .query(
                (rs, rowNum) ->
                    new OpenVersion(
                        rs.getString("id"), rs.getDouble("confidence"), rs.getLong("valid_from")))
            .list();
    for (OpenVersion version : open) {
      Fact fact = byId.get(version.id());
      if (fact != null && fact.confidence() == version.confidence()) {
        stored.put(fact.id(), withValidity(fact, Validity.from(version.from())));
        continue;
      }
      if (version.from() == snapshot) {
        // Recorded for this snapshot before: nothing held before it, so nothing to keep.
        jdbc.sql("DELETE FROM facts WHERE id = :id AND upper_inf(validity)")
            .param("id", version.id())
            .update();
      } else {
        jdbc.sql(
                """
                UPDATE facts SET validity = int8range(lower(validity), :to, '[)')
                WHERE id = :id AND upper_inf(validity)
                """)
            .param("id", version.id())
            .param("to", snapshot)
            .update();
      }
      if (fact == null) {
        closed++;
      } else {
        insert(fact, false);
        stored.put(fact.id(), fact);
        changed++;
      }
    }
    for (Fact fact : byId.values()) {
      if (stored.containsKey(fact.id())) {
        continue;
      }
      // Closed at this very snapshot by an earlier recording of it: it holds on, unbroken.
      Optional<Long> reopened =
          jdbc.sql(
                  """
                  UPDATE facts SET validity = int8range(lower(validity), NULL, '[)')
                  WHERE id = :id AND upper(validity) = :snapshot AND confidence = :confidence
                  RETURNING lower(validity)
                  """)
              .param("id", fact.id())
              .param("snapshot", snapshot)
              .param("confidence", fact.confidence())
              .query(Long.class)
              .optional();
      if (reopened.isPresent()) {
        stored.put(fact.id(), withValidity(fact, Validity.from(reopened.get())));
      } else {
        insert(fact, false);
        stored.put(fact.id(), fact);
      }
      added++;
    }
    return new SnapshotUpdate(
        byId.keySet().stream().map(stored::get).toList(), added, changed, closed);
  }

  /** The open version of a stored fact. */
  private record OpenVersion(String id, double confidence, long from) {}

  private static Fact withValidity(Fact fact, Validity validity) {
    return new Fact(
        fact.id(),
        fact.subject(),
        fact.predicate(),
        fact.object(),
        fact.attributes(),
        fact.origin(),
        fact.evidence(),
        fact.confidence(),
        validity);
  }

  /**
   * Inserts the fact as a new version.
   *
   * @param onlyIfNew insert nothing if any version of the fact is stored
   * @return whether it was inserted
   */
  private boolean insert(Fact fact, boolean onlyIfNew) {
    int rows =
        jdbc.sql(
                """
                INSERT INTO facts (id, subject, predicate, object_kind, object, attributes,
                                   origin, evidence, confidence, validity)
                SELECT :id, :subject, :predicate, :objectKind, :object, CAST(:attributes AS jsonb),
                       CAST(:origin AS jsonb), CAST(:evidence AS jsonb), :confidence,
                       int8range(:validFrom, CAST(:validTo AS bigint), '[)')
                WHERE NOT :onlyIfNew OR NOT EXISTS (SELECT 1 FROM facts WHERE id = :id)
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
            .param("onlyIfNew", onlyIfNew)
            .update();
    return rows == 1;
  }

  /** The fact's latest version: the one that holds from the latest snapshot. */
  public Optional<Fact> findById(String id) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM facts WHERE id = :id ORDER BY lower(validity) DESC LIMIT 1")
        .param("id", id)
        .query(FactRepository::mapRow)
        .optional();
  }

  public List<Fact> findBySubject(EntityId subject) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM facts WHERE subject = :subject ORDER BY id, lower(validity)")
        .param("subject", subject.toString())
        .query(FactRepository::mapRow)
        .list();
  }

  public List<Fact> findByPredicate(String predicate) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM facts WHERE predicate = :predicate ORDER BY id, lower(validity)")
        .param("predicate", predicate)
        .query(FactRepository::mapRow)
        .list();
  }

  public List<Fact> findByObject(FactObject object) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM facts WHERE object = :object AND object_kind = :objectKind ORDER BY id, lower(validity)")
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

package io.lantern.core.fact;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.node.ObjectNode;

/**
 * Stores facts in the {@code facts} table. Every fact is checked by the {@link FactValidator}
 * before it is written; in the app that is the vocabulary registry, which checks the fact's entity
 * types, predicate, object and attributes against the vocabulary.
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
        && stored.validity().to() == null
        && stored.validity().from() <= observed.validity().from();
  }

  /**
   * What {@link #recordSnapshot} changed.
   *
   * @param facts the facts found in the snapshot as now stored, each once, in the order first
   *     found; a fact that already held keeps the start of its validity
   * @param added how many found facts did not hold before this recording
   * @param changed how many found facts held before this recording with a different confidence
   * @param closed how many facts that held before this recording were not found, so no longer hold
   */
  public record SnapshotUpdate(List<Fact> facts, int added, int changed, int closed) {

    public SnapshotUpdate {
      facts = List.copyOf(facts);
    }
  }

  /**
   * Records the facts of one origin kind found in a snapshot by analyzing some repositories, so
   * that afterwards exactly these facts of that kind hold in it for those repositories (ADR 0017,
   * 0018):
   *
   * <ul>
   *   <li>a found fact that already holds with the same confidence keeps holding, its validity
   *       still open from where it started;
   *   <li>one that holds with a different confidence stops holding at this snapshot, and a new
   *       version with the new confidence holds from it;
   *   <li>one that does not hold (new, or seen before but closed since) holds from this snapshot;
   *   <li>a fact of that kind with evidence in one of the repositories that holds but was not found
   *       stops holding at this snapshot.
   * </ul>
   *
   * Facts of other repositories are not touched: they keep holding as they did, so a snapshot that
   * carries a repository forward without analyzing it again keeps that repository's facts.
   *
   * <p>Snapshots must be recorded in order, which is checked as far as the stored facts of that
   * kind show. Recording the latest snapshot again replaces what was recorded for it. The store is
   * expected to have one writer at a time (the analysis lock); concurrent writers may fail on the
   * constraint that keeps a fact's versions from overlapping. Run this in a transaction, so a
   * failure leaves the store unchanged.
   *
   * @param snapshot the snapshot number
   * @param kind the origin kind of the found facts; facts of other kinds are not touched
   * @param repos the names of the repositories that were analyzed, by which a fact's evidence names
   *     it; facts of other repositories are not touched
   * @param found the facts found, each valid from the snapshot onwards and with evidence in one of
   *     the repositories; the same fact may appear more than once, with the same confidence
   * @throws IllegalArgumentException if a fact has another validity or origin kind, evidence in
   *     another repository, the same fact appears with different confidences, or the validator
   *     rejects a fact
   * @throws IllegalStateException if facts of that kind were recorded for a later snapshot
   */
  public SnapshotUpdate recordSnapshot(
      long snapshot, Origin.Kind kind, Set<String> repos, Collection<Fact> found) {
    return recordSnapshot(snapshot, kind, repos, Set.of(), found);
  }

  /**
   * Records facts as {@link #recordSnapshot(long, Origin.Kind, Set, Collection)} does, leaving
   * alone the facts of the given origin names, which {@link #recordDerived} records.
   *
   * @param derived the origin names (extractor names) of the facts a derivation step records for
   *     the whole snapshot; found facts must have none of them
   * @throws IllegalArgumentException also if a found fact has one of the derived origin names
   */
  public SnapshotUpdate recordSnapshot(
      long snapshot,
      Origin.Kind kind,
      Set<String> repos,
      Set<String> derived,
      Collection<Fact> found) {
    Objects.requireNonNull(kind, "kind");
    repos = Set.copyOf(Objects.requireNonNull(repos, "repos"));
    Set<String> excluded = Set.copyOf(Objects.requireNonNull(derived, "derived"));
    for (Fact fact : Objects.requireNonNull(found, "found")) {
      if (excluded.contains(fact.origin().name())) {
        throw new IllegalArgumentException(
            "Fact " + fact.id() + " comes from " + fact.origin().name() + ", a derivation step");
      }
    }
    return record(snapshot, new Scope(kind, repos, null, excluded), found);
  }

  /**
   * Records the facts a derivation step (such as the linker's) found from the facts of a whole
   * snapshot, so that afterwards exactly these facts of that origin hold in it, whichever
   * repositories their evidence names. Found facts are kept, changed or added as {@link
   * #recordSnapshot(long, Origin.Kind, Set, Collection)} does; every fact of the same origin kind
   * and name that holds but was not found stops holding at this snapshot, in any repository. The
   * step must therefore derive from all facts that hold in the snapshot, not only those of the
   * repositories just analyzed. Run this in a transaction, after the snapshot's other facts are
   * recorded.
   *
   * @param snapshot the snapshot number
   * @param origin the step's origin; its kind and name select the facts it owns (its version may
   *     differ from that of stored facts, which a new version of the step replaces)
   * @param found the facts found, each valid from the snapshot onwards and of that origin kind and
   *     name
   * @throws IllegalArgumentException if a fact has another validity, origin kind or name, the same
   *     fact appears with different confidences, or the validator rejects a fact
   * @throws IllegalStateException if facts of that origin were recorded for a later snapshot
   */
  public SnapshotUpdate recordDerived(long snapshot, Origin origin, Collection<Fact> found) {
    return recordDerived(snapshot, origin, null, found);
  }

  /**
   * Records a derivation step's facts as {@link #recordDerived(long, Origin, Collection)} does, for
   * the snapshot of one system: only facts of that origin whose evidence names one of the
   * snapshot's repositories stop holding when not found, so a snapshot of one system never ends
   * another system's derived facts (ADR 0071).
   *
   * @param repos the snapshot's repositories, or {@code null} for any repository
   * @throws IllegalArgumentException also if a found fact's evidence names another repository
   */
  public SnapshotUpdate recordDerived(
      long snapshot, Origin origin, Set<String> repos, Collection<Fact> found) {
    Objects.requireNonNull(origin, "origin");
    Set<String> scope = repos == null ? null : Set.copyOf(repos);
    for (Fact fact : Objects.requireNonNull(found, "found")) {
      if (!fact.origin().name().equals(origin.name())) {
        throw new IllegalArgumentException(
            "Fact " + fact.id() + " comes from " + fact.origin().name() + ", not " + origin.name());
      }
    }
    return record(snapshot, new Scope(origin.kind(), scope, origin.name(), Set.of()), found);
  }

  /**
   * Which stored facts a recording owns: those of one origin kind, with evidence in the given
   * repositories (any if null), of the given origin name (any if null) and of none of the excluded
   * names.
   */
  private record Scope(Origin.Kind kind, Set<String> repos, String name, Set<String> excluded) {

    /** The SQL conditions selecting the scope's facts, besides the kind and repositories. */
    String originConditions() {
      return (name == null ? "" : " AND origin->>'name' = :name")
          + (excluded.isEmpty() ? "" : " AND origin->>'name' NOT IN (:excluded)");
    }

    JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec) {
      spec = spec.param("kind", kind.jsonName());
      if (repos != null) {
        // No repository is named "", so an empty set matches no fact.
        spec = spec.param("repos", repos.isEmpty() ? List.of("") : List.copyOf(repos));
      }
      if (name != null) {
        spec = spec.param("name", name);
      }
      if (!excluded.isEmpty()) {
        spec = spec.param("excluded", List.copyOf(excluded));
      }
      return spec;
    }
  }

  private SnapshotUpdate record(long snapshot, Scope scope, Collection<Fact> found) {
    Origin.Kind kind = scope.kind();
    Set<String> repos = scope.repos();
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
      if (repos != null && !repos.contains(fact.evidence().repo())) {
        throw new IllegalArgumentException(
            "Fact "
                + fact.id()
                + " has evidence in "
                + fact.evidence().repo()
                + ", which is not one of the analyzed repositories "
                + new TreeSet<>(repos));
      }
      Fact previous = byId.putIfAbsent(fact.id(), fact);
      if (previous != null && previous.confidence() != fact.confidence()) {
        throw new IllegalArgumentException(
            "Fact " + fact.id() + " was found with different confidences");
      }
    }
    validator.validateAll(byId.values());
    long later =
        scope
            .bind(
                jdbc.sql(
                        """
                        SELECT count(*) FROM facts
                        WHERE (lower(validity) > :snapshot OR upper(validity) > :snapshot)
                          AND origin->>'kind' = :kind
                        """
                            + scope.originConditions())
                    .param("snapshot", snapshot))
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
    Set<String> changedIds = new HashSet<>();
    List<OpenVersion> open =
        scope
            .bind(
                jdbc.sql(
                    """
                    SELECT id, confidence, lower(validity) AS valid_from FROM facts
                    WHERE upper_inf(validity) AND origin->>'kind' = :kind
                    """
                        + (repos == null ? "" : " AND evidence->>'repo' IN (:repos)")
                        + scope.originConditions()))
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
        changedIds.add(fact.id());
        changed++;
      }
    }
    for (Fact fact : byId.values()) {
      if (stored.containsKey(fact.id())) {
        continue;
      }
      if (!changedIds.contains(fact.id())) {
        added++;
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

  /**
   * The facts that hold in the given snapshot and whose evidence names one of the repositories:
   * those of the snapshot's own repositories, since snapshot numbers are shared by all systems and
   * another system's facts can hold at the same number (ADR 0071).
   */
  public List<Fact> findValidAt(long snapshot, Collection<String> repos) {
    List<String> names = List.copyOf(repos);
    if (names.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM facts WHERE validity @> CAST(:snapshot AS bigint)"
                + " AND evidence->>'repo' IN (:repos) ORDER BY id")
        .param("snapshot", snapshot)
        .param("repos", names)
        .query(FactRepository::mapRow)
        .list();
  }

  /**
   * The facts that hold in the given snapshot, whichever repositories their evidence names; with
   * several systems, those of other systems too (see {@link #findValidAt(long, Collection)}).
   */
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

package io.lantern.core.fact;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
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
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.node.ObjectNode;

/**
 * Stores facts as claims and their observations, in the {@code claims} and {@code observations}
 * tables (ADR 0091). Every fact is checked by the {@link FactValidator} before it is written; in
 * the app that is the vocabulary registry, which checks the fact's entity types, predicate, object
 * and attributes against the vocabulary and says which attributes are key. A fact's claim holds its
 * subject, predicate, object and key attributes; its observation the rest.
 *
 * <p>A fact's ID, its observation's, leaves out its validity and confidence, so one observation can
 * be stored as several versions, each a contiguous range of snapshots with one confidence; the
 * versions of an observation never overlap (ADR 0017). Several observations of one claim may hold
 * at once when they differ (ADR 0091). Queries by subject, predicate or object return every
 * version.
 *
 * <p>Recording a snapshot fills in the git blob of each evidence location from {@link Blobs}, so
 * evidence that names a file git holds keeps no content hash.
 */
public class FactRepository {

  private static final String COLUMNS =
      """
      o.id, o.claim_id, c.subject, c.predicate, c.object_kind, c.object,
      (c.key_attributes || o.attributes)::text AS attributes,
      array_to_json(o.lineage)::text AS lineage, o.origin::text AS origin,
      o.evidence::text AS evidence, o.confidence,
      lower(o.validity) AS valid_from, upper(o.validity) AS valid_to
      """;

  /** The observations joined with their claims. */
  private static final String FROM = " FROM observations o JOIN claims c ON c.id = o.claim_id";

  private final JdbcClient jdbc;
  private final FactValidator validator;
  private final Blobs blobs;

  public FactRepository(JdbcClient jdbc, FactValidator validator) {
    this(jdbc, validator, Blobs.NONE);
  }

  /**
   * @param blobs finds the blobs of evidence files at a snapshot's commits
   */
  public FactRepository(JdbcClient jdbc, FactValidator validator, Blobs blobs) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.validator = Objects.requireNonNull(validator, "validator");
    this.blobs = Objects.requireNonNull(blobs, "blobs");
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
  public boolean save(Fact given) {
    Objects.requireNonNull(given, "fact");
    Fact completed = withBlobs(given.validity().from(), given);
    validator.validate(completed);
    Function<Fact, Set<String>> keys = validator.keyAttributes();
    Fact fact = completed.stored(claimId(keys, completed), completed.validity());
    if (insert(fact, keys, true)) {
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
      return (name == null ? "" : " AND o.origin->>'name' = :name")
          + (excluded.isEmpty() ? "" : " AND o.origin->>'name' NOT IN (:excluded)");
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

  private SnapshotUpdate record(long snapshot, Scope scope, Collection<Fact> given) {
    Origin.Kind kind = scope.kind();
    Set<String> repos = scope.repos();
    Validity validity = Validity.from(snapshot);
    Map<String, Fact> byId = new LinkedHashMap<>();
    List<Fact> found = new ArrayList<>();
    for (Fact fact : given) {
      found.add(withBlobs(snapshot, fact));
    }
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
    Function<Fact, Set<String>> keys = validator.keyAttributes();
    byId.replaceAll((id, fact) -> fact.stored(claimId(keys, fact), fact.validity()));
    long later =
        scope
            .bind(
                jdbc.sql(
                        """
                        SELECT count(*) FROM observations o
                        WHERE (lower(o.validity) > :snapshot OR upper(o.validity) > :snapshot)
                          AND o.origin->>'kind' = :kind
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
                    SELECT o.id, o.claim_id, o.confidence, lower(o.validity) AS valid_from
                    FROM observations o
                    WHERE upper_inf(o.validity) AND o.origin->>'kind' = :kind
                    """
                        + (repos == null ? "" : " AND o.repo IN (:repos)")
                        + scope.originConditions()))
            .query(
                (rs, rowNum) ->
                    new OpenVersion(
                        rs.getString("id"),
                        rs.getString("claim_id"),
                        rs.getDouble("confidence"),
                        rs.getLong("valid_from")))
            .list();
    for (OpenVersion version : open) {
      Fact fact = byId.get(version.id());
      // A type whose key attributes changed puts the same sighting under another claim, which is
      // a change like a new confidence.
      if (fact != null
          && fact.claimId().equals(version.claim())
          && fact.confidence() == version.confidence()) {
        stored.put(fact.id(), withValidity(fact, Validity.from(version.from())));
        continue;
      }
      if (version.from() == snapshot) {
        // Recorded for this snapshot before: nothing held before it, so nothing to keep.
        jdbc.sql("DELETE FROM observations WHERE id = :id AND upper_inf(validity)")
            .param("id", version.id())
            .update();
      } else {
        jdbc.sql(
                """
                UPDATE observations SET validity = int8range(lower(validity), :to, '[)')
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
                  UPDATE observations SET validity = int8range(lower(validity), NULL, '[)')
                  WHERE id = :id AND upper(validity) = :snapshot AND confidence = :confidence
                    AND claim_id = :claim
                  RETURNING lower(validity)
                  """)
              .param("id", fact.id())
              .param("claim", fact.claimId())
              .param("snapshot", snapshot)
              .param("confidence", fact.confidence())
              .query(Long.class)
              .optional();
      if (reopened.isPresent()) {
        stored.put(fact.id(), withValidity(fact, Validity.from(reopened.get())));
      } else {
        insert(fact, keys, false);
        stored.put(fact.id(), fact);
      }
    }
    return new SnapshotUpdate(
        byId.keySet().stream().map(stored::get).toList(), added, changed, closed);
  }

  /** The open version of a stored observation. */
  private record OpenVersion(String id, String claim, double confidence, long from) {}

  private static Fact withValidity(Fact fact, Validity validity) {
    return fact.stored(fact.claimId(), validity);
  }

  /** The claim ID of a fact, by the given key attributes. */
  private static String claimId(Function<Fact, Set<String>> keys, Fact fact) {
    return ClaimId.of(
        fact.subject(), fact.predicate(), fact.object(), fact.attributes(), keys.apply(fact));
  }

  /**
   * The fact with the blob of each evidence file git holds at the snapshot's commit, in place of
   * its content hash; the fact itself when nothing changes.
   */
  private Fact withBlobs(long snapshot, Fact fact) {
    Evidence evidence = fact.evidence();
    Evidence completed =
        evidence.map(
            location ->
                location.path() == null || location.blob() != null
                    ? location
                    : blobs
                        .blob(snapshot, location.repo(), location.path())
                        .map(location::withBlob)
                        .orElse(location));
    return completed.equals(evidence) ? fact : fact.withEvidence(completed);
  }

  /**
   * Inserts the fact as a new version of its observation, and its claim if it is new. The fact has
   * its claim ID; its attributes are split into the claim's key ones and the observation's others.
   *
   * @param keys the key attributes of facts, by which its claim ID was computed
   * @param onlyIfNew insert nothing if any version of the observation is stored
   * @return whether it was inserted
   */
  private boolean insert(Fact fact, Function<Fact, Set<String>> keys, boolean onlyIfNew) {
    Objects.requireNonNull(fact.claimId(), "claimId");
    ObjectNode key = FactJson.newObject();
    ObjectNode descriptive = FactJson.newObject();
    Set<String> names = keys.apply(fact);
    fact.attributes()
        .properties()
        .forEach(
            e -> (names.contains(e.getKey()) ? key : descriptive).set(e.getKey(), e.getValue()));
    jdbc.sql(
            """
            INSERT INTO claims (id, subject, predicate, object_kind, object, key_attributes)
            VALUES (:id, :subject, :predicate, :objectKind, :object, CAST(:keys AS jsonb))
            ON CONFLICT (id) DO NOTHING
            """)
        .param("id", fact.claimId())
        .param("subject", fact.subject().toString())
        .param("predicate", fact.predicate())
        .param("objectKind", fact.object().kind().jsonName())
        .param("object", fact.object().value())
        .param("keys", FactJson.canonical(key))
        .update();
    int rows =
        jdbc.sql(
                """
                INSERT INTO observations (id, claim_id, origin, attributes, evidence, lineage,
                                          confidence, validity)
                SELECT :id, :claim, CAST(:origin AS jsonb), CAST(:attributes AS jsonb),
                       CAST(:evidence AS jsonb), CAST(:lineage AS text[]), :confidence,
                       int8range(:validFrom, CAST(:validTo AS bigint), '[)')
                WHERE NOT :onlyIfNew OR NOT EXISTS (SELECT 1 FROM observations WHERE id = :id)
                """)
            .param("id", fact.id())
            .param("claim", fact.claimId())
            .param("origin", FactJson.canonical(FactJson.toJson(fact.origin())))
            .param("attributes", FactJson.canonical(descriptive))
            .param("evidence", FactJson.canonical(FactJson.toJson(fact.evidence())))
            .param("lineage", "{" + String.join(",", fact.lineage()) + "}")
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
                + FROM
                + " WHERE o.id = :id ORDER BY lower(o.validity) DESC LIMIT 1")
        .param("id", id)
        .query(FactRepository::mapRow)
        .optional();
  }

  public List<Fact> findBySubject(EntityId subject) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + FROM
                + " WHERE c.subject = :subject ORDER BY o.id, lower(o.validity)")
        .param("subject", subject.toString())
        .query(FactRepository::mapRow)
        .list();
  }

  public List<Fact> findByPredicate(String predicate) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + FROM
                + " WHERE c.predicate = :predicate ORDER BY o.id, lower(o.validity)")
        .param("predicate", predicate)
        .query(FactRepository::mapRow)
        .list();
  }

  public List<Fact> findByObject(FactObject object) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + FROM
                + " WHERE c.object = :object AND c.object_kind = :objectKind"
                + " ORDER BY o.id, lower(o.validity)")
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
                + FROM
                + " WHERE o.validity @> CAST(:snapshot AS bigint)"
                + " AND o.repo IN (:repos) ORDER BY o.id")
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
                + FROM
                + " WHERE o.validity @> CAST(:snapshot AS bigint) ORDER BY o.id")
        .param("snapshot", snapshot)
        .query(FactRepository::mapRow)
        .list();
  }

  private static Fact mapRow(ResultSet rs, int rowNum) throws SQLException {
    long validTo = rs.getLong("valid_to");
    Long to = rs.wasNull() ? null : validTo;
    List<String> lineage = new ArrayList<>();
    FactJson.parse(rs.getString("lineage")).forEach(claim -> lineage.add(claim.stringValue()));
    return new Fact(
        rs.getString("id"),
        rs.getString("claim_id"),
        EntityId.parse(rs.getString("subject")),
        rs.getString("predicate"),
        new FactObject(
            FactObject.Kind.fromJsonName(rs.getString("object_kind")), rs.getString("object")),
        (ObjectNode) FactJson.parse(rs.getString("attributes")),
        lineage,
        FactJson.originFromJson(FactJson.parse(rs.getString("origin"))),
        FactJson.evidenceFromJson(FactJson.parse(rs.getString("evidence"))),
        rs.getDouble("confidence"),
        new Validity(rs.getLong("valid_from"), to));
  }
}

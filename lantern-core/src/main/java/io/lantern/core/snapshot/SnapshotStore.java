package io.lantern.core.snapshot;

import io.lantern.core.repo.RepoRegistry;
import io.lantern.core.vocabulary.VocabularyRegistry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Creates and reads snapshots in the {@code snapshots} and {@code snapshot_repositories} tables.
 * Snapshots are numbered 1, 2, 3 and so on without gaps, in creation order, across all systems:
 * fact validity is a range of these numbers (ADR 0017, ADR 0071).
 */
public class SnapshotStore {

  private final JdbcClient jdbc;
  private final TransactionOperations transactions;
  private final RepoRegistry repos;
  private final VocabularyRegistry vocabulary;
  private final String toolVersion;

  public SnapshotStore(
      JdbcClient jdbc,
      TransactionOperations transactions,
      RepoRegistry repos,
      VocabularyRegistry vocabulary,
      String toolVersion) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.repos = Objects.requireNonNull(repos, "repos");
    this.vocabulary = Objects.requireNonNull(vocabulary, "vocabulary");
    this.toolVersion = Objects.requireNonNull(toolVersion, "toolVersion");
    if (toolVersion.isBlank()) {
      throw new IllegalArgumentException("Tool version must not be blank");
    }
  }

  /**
   * Creates a snapshot. Each revision is resolved to a full commit hash first, and the snapshot
   * records the latest vocabulary revision and this tool version.
   *
   * @param revisions for each repository to include, by name, the revision to analyze: {@link
   *     RepoRegistry#HEAD}, a branch or tag name, or a commit hash
   * @return the new snapshot
   * @throws IllegalArgumentException if there are no repositories, a repository is not registered
   *     or a revision does not name a commit; nothing is stored then
   */
  public Snapshot create(Map<String, String> revisions) {
    return create(null, revisions);
  }

  /**
   * Creates a snapshot of a whole named system (ADR 0071): the named repositories at their
   * revisions, as {@link #create(Map)} resolves them, and every other repository that belongs to
   * the system and that an earlier snapshot of the system analyzed, at its commit in the latest
   * snapshot of the system that analyzed it (see {@link #markAnalyzed}), without resolving it again
   * (ADR 0018). A repository that no snapshot of the system analyzed, or that belongs to another
   * system or none, is not carried forward. The snapshot is tagged with the system.
   *
   * @param system the name of a defined system the named repositories belong to
   * @param revisions for each repository to analyze, by name, the revision to analyze
   * @return the new snapshot
   * @throws IllegalArgumentException as {@link #create(Map)} does; nothing is stored then
   */
  public Snapshot createCarryingForward(String system, Map<String, String> revisions) {
    return create(Objects.requireNonNull(system, "system"), revisions);
  }

  private Snapshot create(String system, Map<String, String> revisions) {
    Objects.requireNonNull(revisions, "revisions");
    if (revisions.isEmpty()) {
      throw new IllegalArgumentException("A snapshot must contain at least one repository");
    }
    Map<String, String> commits = new TreeMap<>();
    revisions.forEach((repo, revision) -> commits.put(repo, repos.resolve(repo, revision)));
    long vocabularyRevision = vocabulary.current().revision();
    Long number =
        transactions.execute(
            status -> {
              // One writer at a time, so snapshots are numbered without gaps or races.
              jdbc.sql("LOCK TABLE snapshots IN EXCLUSIVE MODE").update();
              long next =
                  jdbc.sql("SELECT coalesce(max(number), 0) + 1 FROM snapshots")
                      .query(Long.class)
                      .single();
              if (system != null) {
                jdbc.sql(
                        """
                        SELECT DISTINCT ON (s.repository) s.repository, s.commit
                        FROM snapshot_repositories s
                        JOIN repositories r ON r.name = s.repository
                        JOIN snapshots n ON n.number = s.snapshot
                        WHERE s.analyzed AND r.system = :system AND n.system = :system
                        ORDER BY s.repository, s.snapshot DESC
                        """)
                    .param("system", system)
                    .query()
                    .listOfRows()
                    .forEach(
                        row ->
                            commits.putIfAbsent(
                                (String) row.get("repository"), (String) row.get("commit")));
              }
              jdbc.sql(
                      """
                      INSERT INTO snapshots (number, vocabulary_revision, tool_version, system)
                      VALUES (:n, :vocabulary, :tool, :system)
                      """)
                  .param("n", next)
                  .param("system", system, Types.VARCHAR)
                  .param("vocabulary", vocabularyRevision)
                  .param("tool", toolVersion)
                  .update();
              commits.forEach(
                  (repo, commit) ->
                      jdbc.sql(
                              """
                              INSERT INTO snapshot_repositories (snapshot, repository, commit)
                              VALUES (:n, :repo, :commit)
                              """)
                          .param("n", next)
                          .param("repo", repo)
                          .param("commit", commit)
                          .update());
              return next;
            });
    return find(Objects.requireNonNull(number, "number")).orElseThrow();
  }

  /**
   * Marks repositories as analyzed in a snapshot: their facts for it are stored. Later snapshots
   * carry a repository forward only at a commit marked here, so a run that fails before storing its
   * facts leaves nothing behind to carry forward (ADR 0018). A repository carried forward into a
   * snapshot is not analyzed in it. Run this in the transaction that stores the facts; it joins
   * that transaction. Marking a repository again changes nothing.
   *
   * @param snapshot the snapshot number
   * @param repos the names of the repositories analyzed in it
   * @throws IllegalArgumentException if the snapshot does not have one of the repositories; nothing
   *     is marked then
   */
  public void markAnalyzed(long snapshot, Collection<String> repos) {
    Objects.requireNonNull(repos, "repos");
    transactions.executeWithoutResult(
        status -> {
          for (String repo : new TreeSet<>(repos)) {
            int updated =
                jdbc.sql(
                        """
                        UPDATE snapshot_repositories SET analyzed = true
                        WHERE snapshot = :n AND repository = :repo
                        """)
                    .param("n", snapshot)
                    .param("repo", repo)
                    .update();
            if (updated == 0) {
              throw new IllegalArgumentException(
                  "Snapshot " + snapshot + " does not have the repository " + repo);
            }
          }
        });
  }

  /** The system the snapshot was taken of, if it was taken of one (ADR 0071). */
  public Optional<String> system(long number) {
    return jdbc.sql("SELECT system FROM snapshots WHERE number = :n AND system IS NOT NULL")
        .param("n", number)
        .query(String.class)
        .optional();
  }

  /** The snapshot with the number, if any. */
  public Optional<Snapshot> find(long number) {
    return jdbc.sql(
            "SELECT number, vocabulary_revision, tool_version, created_at FROM snapshots"
                + " WHERE number = :n")
        .param("n", number)
        .query(SnapshotStore::mapHeader)
        .optional()
        .map(this::withCommits);
  }

  /** The most recently created snapshot, if any. */
  public Optional<Snapshot> latest() {
    return jdbc.sql(
            "SELECT number, vocabulary_revision, tool_version, created_at FROM snapshots"
                + " ORDER BY number DESC LIMIT 1")
        .query(SnapshotStore::mapHeader)
        .optional()
        .map(this::withCommits);
  }

  /** A snapshot row without its commits. */
  private record Header(
      long number, long vocabularyRevision, String toolVersion, Instant createdAt) {}

  private static Header mapHeader(ResultSet rs, int rowNum) throws SQLException {
    return new Header(
        rs.getLong("number"),
        rs.getLong("vocabulary_revision"),
        rs.getString("tool_version"),
        rs.getTimestamp("created_at").toInstant());
  }

  private Snapshot withCommits(Header header) {
    Map<String, String> commits = new TreeMap<>();
    jdbc.sql("SELECT repository, commit FROM snapshot_repositories WHERE snapshot = :n")
        .param("n", header.number())
        .query()
        .listOfRows()
        .forEach(row -> commits.put((String) row.get("repository"), (String) row.get("commit")));
    return new Snapshot(
        header.number(),
        commits,
        header.vocabularyRevision(),
        header.toolVersion(),
        header.createdAt());
  }
}

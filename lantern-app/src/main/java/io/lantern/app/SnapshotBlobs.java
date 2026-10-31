package io.lantern.app;

import io.lantern.core.fact.Blobs;
import io.lantern.core.repo.GitTree;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Finds evidence files' blobs at the commit a snapshot holds of their repository, in the
 * repository's registered folder (ADR 0091). Each repository and commit's tree is read once with
 * {@code git ls-tree} and kept while recent.
 */
final class SnapshotBlobs implements Blobs {

  private static final int TREES = 64;

  private final JdbcClient jdbc;
  private final Map<String, Optional<Map<String, String>>> trees =
      new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Optional<Map<String, String>>> e) {
          return size() > TREES;
        }
      };

  SnapshotBlobs(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public synchronized Optional<String> blob(long snapshot, String repo, String path) {
    String key = snapshot + "\0" + repo;
    Optional<Map<String, String>> tree = trees.get(key);
    if (tree == null) {
      tree =
          jdbc.sql(
                  """
                  SELECT s.commit, r.path FROM snapshot_repositories s
                  JOIN repositories r ON r.name = s.repository
                  WHERE s.snapshot = :snapshot AND s.repository = :repo
                  """)
              .param("snapshot", snapshot)
              .param("repo", repo)
              .query((rs, row) -> GitTree.blobs(Path.of(rs.getString(2)), rs.getString(1)))
              .optional()
              .flatMap(found -> found);
      trees.put(key, tree);
    }
    return tree.map(files -> files.get(path));
  }
}

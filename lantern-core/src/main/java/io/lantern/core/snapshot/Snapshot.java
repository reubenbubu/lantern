package io.lantern.core.snapshot;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * A set of {@code repo@commit} pairs that an analysis covers, with the vocabulary revision and tool
 * version it used.
 *
 * @param number the snapshot number: 1, 2, 3 and so on in creation order, which fact validity
 *     ranges refer to
 * @param commits the full commit hash of each repository in the snapshot, by repository name
 * @param vocabularyRevision the vocabulary revision current when the snapshot was created
 * @param toolVersion the Lantern version that created the snapshot
 * @param createdAt when the snapshot was created
 */
public record Snapshot(
    long number,
    Map<String, String> commits,
    long vocabularyRevision,
    String toolVersion,
    Instant createdAt) {

  public Snapshot {
    Objects.requireNonNull(commits, "commits");
    Objects.requireNonNull(toolVersion, "toolVersion");
    Objects.requireNonNull(createdAt, "createdAt");
    if (number < 1) {
      throw new IllegalArgumentException("Snapshot number must be at least 1: " + number);
    }
    if (commits.isEmpty()) {
      throw new IllegalArgumentException("A snapshot must contain at least one repository");
    }
    if (vocabularyRevision < 0) {
      throw new IllegalArgumentException("Invalid vocabulary revision: " + vocabularyRevision);
    }
    commits = Collections.unmodifiableMap(new TreeMap<>(commits));
  }
}

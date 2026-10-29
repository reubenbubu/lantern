package io.lantern.core.document;

import java.time.LocalDate;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * What a document was composed from, so it can be reproduced, and what pins it: the commits of the
 * repositories it covers and the date of their snapshot (ADR 0063).
 *
 * @param snapshot the snapshot number
 * @param vocabularyRevision the vocabulary revision the snapshot used
 * @param toolVersion the Lantern version that composed the document
 * @param commits the full commit hash of each repository the document covers, by repository name,
 *     at least one; held sorted by name
 * @param date the day the snapshot was taken, in UTC
 */
public record DocumentSource(
    long snapshot,
    long vocabularyRevision,
    String toolVersion,
    Map<String, String> commits,
    LocalDate date) {

  /** A full git commit hash: SHA-1 or SHA-256, in lower-case hex. */
  private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");

  public DocumentSource {
    if (snapshot < 1) {
      throw new IllegalArgumentException("Snapshot number must be at least 1: " + snapshot);
    }
    if (vocabularyRevision < 0) {
      throw new IllegalArgumentException("Invalid vocabulary revision: " + vocabularyRevision);
    }
    Checks.text(toolVersion, "tool version");
    Objects.requireNonNull(commits, "commits");
    if (commits.isEmpty()) {
      throw new IllegalArgumentException("A document must pin at least one commit");
    }
    for (Map.Entry<String, String> commit : commits.entrySet()) {
      Checks.text(commit.getKey(), "repository name");
      if (commit.getValue() == null || !COMMIT.matcher(commit.getValue()).matches()) {
        throw new IllegalArgumentException(
            "Invalid commit of repository " + commit.getKey() + ": " + commit.getValue());
      }
    }
    commits = Collections.unmodifiableMap(new TreeMap<>(commits));
    Objects.requireNonNull(date, "date");
  }
}

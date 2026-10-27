package io.lantern.core.fact;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * What a fact was observed in.
 *
 * @param repo the repository name
 * @param commit the full commit hash (40 or 64 lowercase hex characters)
 * @param path the file path relative to the repository root, or {@code null} for a repository-wide
 *     fact
 * @param lines the line range in the file, or {@code null}; requires a path
 * @param contentHash the SHA-256 of the file content as 64 lowercase hex characters, or {@code
 *     null}; requires a path
 */
public record Evidence(
    String repo, String commit, String path, LineRange lines, String contentHash) {

  private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

  public Evidence {
    Objects.requireNonNull(repo, "repo");
    Objects.requireNonNull(commit, "commit");
    if (repo.isBlank()) {
      throw new IllegalArgumentException("Evidence repo must not be blank");
    }
    if (!COMMIT.matcher(commit).matches()) {
      throw new IllegalArgumentException("Invalid commit hash: '" + commit + "'");
    }
    if (path != null && path.isBlank()) {
      throw new IllegalArgumentException("Evidence path must be null or not blank");
    }
    if (path == null && (lines != null || contentHash != null)) {
      throw new IllegalArgumentException("Evidence lines and content hash require a path");
    }
    if (contentHash != null && !SHA256.matcher(contentHash).matches()) {
      throw new IllegalArgumentException("Invalid content hash: '" + contentHash + "'");
    }
  }

  /** An inclusive range of 1-based line numbers. */
  public record LineRange(int start, int end) {

    public LineRange {
      if (start < 1 || end < start) {
        throw new IllegalArgumentException("Invalid line range: " + start + "-" + end);
      }
    }
  }
}

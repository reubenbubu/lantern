package io.lantern.core.fact;

import java.util.Optional;

/**
 * Finds the git blob of a file at the commit a snapshot holds of its repository, so recorded
 * evidence names the file's blob rather than a commit (ADR 0091).
 */
@FunctionalInterface
public interface Blobs {

  /** Finds no blob: evidence keeps the content hash the extractor gave. */
  Blobs NONE = (snapshot, repo, path) -> Optional.empty();

  /**
   * The blob SHA of the file, or empty if git does not hold it at the snapshot's commit of the
   * repository (a build output, say), or the repository cannot be read.
   *
   * @param snapshot the snapshot number
   * @param repo the repository name
   * @param path the file path relative to the repository root
   */
  Optional<String> blob(long snapshot, String repo, String path);
}

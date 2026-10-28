package io.lantern.summarize;

import java.util.Optional;

/**
 * Reads the files that facts were observed in, so prompts can show excerpts of the code. The
 * summarizer shows an excerpt only when the file's SHA-256 matches the content hash of the fact's
 * evidence, so a reader that returns another version of a file does no harm.
 */
@FunctionalInterface
public interface SourceFiles {

  /** Reads nothing: prompts carry facts and summaries only. */
  SourceFiles NONE = (repo, commit, path) -> Optional.empty();

  /**
   * The content of a file at a commit, or empty if it cannot be read.
   *
   * @param repo the repository name, as in the facts' evidence
   * @param commit the commit
   * @param path the file path relative to the repository root
   */
  Optional<byte[]> read(String repo, String commit, String path);
}

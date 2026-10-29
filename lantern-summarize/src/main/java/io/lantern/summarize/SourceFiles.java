package io.lantern.summarize;

import io.lantern.core.fact.Evidence;
import io.lantern.extract.config.SourceSecrets;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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

  /**
   * The text of the file of a fact's evidence as prompts show it (ADR 0024, ADR 0054): read at the
   * evidence's commit, only if its SHA-256 matches the evidence's content hash, with its secrets
   * masked or removed by {@link SourceSecrets}; empty if it has no path or hash, cannot be read or
   * is another version.
   *
   * @param evidence where a fact was observed
   */
  default Optional<String> shown(Evidence evidence) {
    if (evidence.path() == null || evidence.contentHash() == null) {
      return Optional.empty();
    }
    return read(evidence.repo(), evidence.commit(), evidence.path())
        .filter(content -> sha256(content).equals(evidence.contentHash()))
        .map(content -> SourceSecrets.scrub(new String(content, StandardCharsets.UTF_8)));
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}

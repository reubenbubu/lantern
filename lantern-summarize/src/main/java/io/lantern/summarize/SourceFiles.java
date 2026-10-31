package io.lantern.summarize;

import io.lantern.core.fact.Evidence;
import io.lantern.extract.config.SourceSecrets;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;

/**
 * Reads the files that facts were observed in, so prompts can show excerpts of the code. The
 * summarizer shows an excerpt only when the file's git blob SHA matches the blob of the fact's
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
   * The content of a file by its git blob, or empty if it cannot be read. Reads nothing unless
   * overridden.
   *
   * @param repo the repository name, as in the facts' evidence
   * @param blob the blob SHA
   * @param path the file's path, for messages
   */
  default Optional<byte[]> blob(String repo, String blob, String path) {
    return Optional.empty();
  }

  /**
   * The config keys known to hold a secret, such as {@link
   * io.lantern.extract.config.ConfigExtractor#jwtSecretKeys}, whose values {@link #shown} masks
   * whatever the key is called (#348).
   */
  default Set<String> secretKeys() {
    return Set.of();
  }

  /**
   * The text of the file of a fact's evidence as prompts show it (ADR 0024, ADR 0054): read by the
   * blob of its declared location (ADR 0091), only if the content has that blob SHA, with its
   * secrets masked or removed by {@link SourceSecrets}; empty if it has no blob, cannot be read or
   * is another version.
   *
   * @param evidence where a fact was observed
   */
  default Optional<String> shown(Evidence evidence) {
    if (evidence.path() == null || evidence.blob() == null) {
      return Optional.empty();
    }
    return blob(evidence.repo(), evidence.blob(), evidence.path())
        .filter(content -> blobSha(content, evidence.blob().length()).equals(evidence.blob()))
        .map(
            content ->
                SourceSecrets.scrub(new String(content, StandardCharsets.UTF_8), secretKeys()));
  }

  /**
   * The git blob SHA of a file's content: the SHA-1 (or, in a SHA-256 repository, SHA-256) of
   * {@code blob <length>\0} followed by the content.
   *
   * @param length the length of the blob SHA in hex characters, 40 or 64
   */
  public static String blobSha(byte[] content, int length) {
    try {
      MessageDigest digest = MessageDigest.getInstance(length == 64 ? "SHA-256" : "SHA-1");
      digest.update(("blob " + content.length + "\0").getBytes(StandardCharsets.US_ASCII));
      return HexFormat.of().formatHex(digest.digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-1 is not available", e);
    }
  }
}

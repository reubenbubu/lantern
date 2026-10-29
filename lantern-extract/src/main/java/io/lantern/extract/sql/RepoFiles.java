package io.lantern.extract.sql;

import io.lantern.extract.GitFiles;
import io.lantern.extract.RepoBuild;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Reads a repository's files at its commit with their hash, as evidence records it. */
final class RepoFiles {

  private RepoFiles() {}

  /** A file's content and its SHA-256. */
  record Content(byte[] bytes, String hash) {}

  /**
   * A file's content at the commit, or null if the commit has no such file or it is larger than
   * {@code maxBytes}.
   */
  static Content read(RepoBuild repo, String path, long maxBytes) {
    byte[] bytes = GitFiles.read(repo, path, maxBytes);
    return bytes == null ? null : new Content(bytes, sha256(bytes));
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}

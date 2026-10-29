package io.lantern.extract.sql;

import io.lantern.core.repo.Git;
import io.lantern.extract.RepoBuild;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Reads a repository's files at its commit from git, never from the working tree. */
final class RepoFiles {

  private static final long GIT_TIMEOUT_SECONDS = 60;

  private RepoFiles() {}

  /** A file's content and its SHA-256, as evidence records it. */
  record Content(byte[] bytes, String hash) {}

  /**
   * The regular files at the commit, ordered by path. Symbolic links and submodules are left out.
   *
   * @throws IllegalStateException if git fails
   */
  static List<String> list(RepoBuild repo) {
    Git.Result result =
        Git.run(repo.repo().path(), "ls-tree", "-r", "-z", "--full-tree", repo.commit());
    if (!result.ok()) {
      throw new IllegalStateException(
          "Cannot list the files of " + repo.repo().name() + ": " + result.err());
    }
    List<String> paths = new ArrayList<>();
    for (String entry : result.out().split("\0")) {
      // "<mode> <type> <object>\t<path>"
      int tab = entry.indexOf('\t');
      if (tab < 0 || !entry.startsWith("100")) {
        continue;
      }
      paths.add(entry.substring(tab + 1));
    }
    paths.sort(null);
    return paths;
  }

  /**
   * A file's content at the commit, or null if the commit has no such file or it is larger than
   * {@code maxBytes}.
   */
  static Content read(RepoBuild repo, String path, int maxBytes) {
    ProcessBuilder builder =
        Git.command(repo.repo().path(), "cat-file", "blob", repo.commit() + ":" + path);
    Process process;
    try {
      process = builder.start();
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot run git; is it installed?", e);
    }
    try {
      process.getOutputStream().close();
      CompletableFuture<byte[]> err =
          CompletableFuture.supplyAsync(
              () -> {
                try (InputStream in = process.getErrorStream()) {
                  return in.readAllBytes();
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              },
              Thread.ofVirtual()::start);
      byte[] out;
      try (InputStream in = process.getInputStream()) {
        out = in.readNBytes(maxBytes + 1);
      }
      if (out.length > maxBytes) {
        // Too large; git may still be writing, so stop it rather than wait.
        process.destroyForcibly();
        return null;
      }
      if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IllegalStateException("git timed out reading " + path);
      }
      err.join();
      return process.exitValue() == 0 ? new Content(out, sha256(out)) : null;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while running git", e);
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}

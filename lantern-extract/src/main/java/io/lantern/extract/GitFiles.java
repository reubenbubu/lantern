package io.lantern.extract;

import io.lantern.core.repo.Git;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Reads the files of a repository at its commit from git, never from the working tree. */
public final class GitFiles {

  private static final long GIT_TIMEOUT_SECONDS = 120;

  private GitFiles() {}

  /**
   * The regular files under a directory at the commit, ordered by path. Symbolic links and
   * submodules are left out.
   *
   * @param repo the repository and commit
   * @param directory the directory relative to the top of the repository, {@code ""} for all files
   * @return the paths relative to the top of the repository
   * @throws IllegalStateException if git fails
   */
  public static List<String> list(RepoBuild repo, String directory) {
    Objects.requireNonNull(repo, "repo");
    Objects.requireNonNull(directory, "directory");
    List<String> args =
        new ArrayList<>(List.of("ls-tree", "-r", "-z", "--full-tree", repo.commit()));
    if (!directory.isEmpty()) {
      args.add("--");
      args.add(directory);
    }
    Git.Result result = Git.run(repo.repo().path(), args.toArray(String[]::new));
    if (!result.ok()) {
      throw new IllegalStateException(
          "Cannot list the files of " + repo.repo().name() + ": " + result.err());
    }
    // Each entry is "<mode> <type> <object>\t<path>". Only regular files are kept: a symbolic
    // link's blob is its target, and a submodule is not in this repository.
    List<String> paths = new ArrayList<>();
    for (String entry : result.out().split("\0")) {
      int tab = entry.indexOf('\t');
      if (tab < 0 || !(entry.startsWith("100644 ") || entry.startsWith("100755 "))) {
        continue;
      }
      String path = entry.substring(tab + 1);
      if (!path.isEmpty() && path.indexOf('\n') < 0) {
        paths.add(path);
      }
    }
    return paths.stream().sorted().toList();
  }

  /**
   * A file's content at the commit.
   *
   * @param repo the repository and commit
   * @param path the path relative to the top of the repository
   * @param maxBytes the largest file to read
   * @return the content, or null if the commit has no such file or it is larger than {@code
   *     maxBytes}
   * @throws IllegalStateException if git times out or the thread is interrupted
   */
  public static byte[] read(RepoBuild repo, String path, long maxBytes) {
    Objects.requireNonNull(repo, "repo");
    Objects.requireNonNull(path, "path");
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
        out = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1));
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
      return process.exitValue() == 0 ? out : null;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while running git", e);
    }
  }
}

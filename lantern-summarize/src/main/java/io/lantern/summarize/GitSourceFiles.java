package io.lantern.summarize;

import io.lantern.core.repo.Git;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Reads files from git at the commit the facts were observed in (never the working tree), with
 * {@code git cat-file}. Files larger than 1 MiB are not read.
 */
public final class GitSourceFiles implements SourceFiles {

  private static final int MAX_BYTES = 1024 * 1024;
  private static final long TIMEOUT_SECONDS = 30;

  private final Function<String, Optional<Path>> directories;

  /**
   * @param directories the git working tree of each repository, by name, or empty for a repository
   *     whose files cannot be read
   */
  public GitSourceFiles(Function<String, Optional<Path>> directories) {
    this.directories = Objects.requireNonNull(directories, "directories");
  }

  @Override
  public Optional<byte[]> read(String repo, String commit, String path) {
    Optional<Path> dir = directories.apply(repo);
    if (dir.isEmpty() || path.startsWith("-") || path.contains(":")) {
      return Optional.empty();
    }
    ProcessBuilder builder =
        Git.command(dir.get(), "cat-file", "blob", commit + ":" + path)
            .redirectError(ProcessBuilder.Redirect.DISCARD);
    Process process;
    try {
      process = builder.start();
    } catch (IOException e) {
      return Optional.empty();
    }
    try {
      process.getOutputStream().close();
      InputStream in = process.getInputStream();
      CompletableFuture<byte[]> read =
          CompletableFuture.supplyAsync(
              () -> {
                try (in) {
                  return in.readNBytes(MAX_BYTES + 1);
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              },
              Thread.ofVirtual()::start);
      byte[] content = read.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      if (content.length > MAX_BYTES || !process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return Optional.empty();
      }
      return process.exitValue() == 0 ? Optional.of(content) : Optional.empty();
    } catch (IOException | ExecutionException | TimeoutException e) {
      process.destroyForcibly();
      return Optional.empty();
    } catch (InterruptedException e) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }
}

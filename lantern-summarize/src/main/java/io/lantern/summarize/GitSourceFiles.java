package io.lantern.summarize;

import io.lantern.core.repo.Git;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads files from git, at a commit or by their blob (never the working tree), with {@code git
 * cat-file}. Files larger than 1 MiB are not read. A file that is missing gives nothing; one that
 * cannot be read because git fails or times out gives nothing and logs a warning.
 */
public final class GitSourceFiles implements SourceFiles {

  private static final Logger LOG = LoggerFactory.getLogger(GitSourceFiles.class);
  private static final int MAX_BYTES = 1024 * 1024;
  private static final Pattern BLOB = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");
  private static final long TIMEOUT_SECONDS = 30;

  private final Function<String, Optional<Path>> directories;
  private final Set<String> secretKeys;

  /**
   * @param directories the git working tree of each repository, by name, or empty for a repository
   *     whose files cannot be read
   */
  public GitSourceFiles(Function<String, Optional<Path>> directories) {
    this(directories, Set.of());
  }

  private GitSourceFiles(Function<String, Optional<Path>> directories, Set<String> secretKeys) {
    this.directories = Objects.requireNonNull(directories, "directories");
    this.secretKeys = Set.copyOf(secretKeys);
  }

  /**
   * The same files, shown with the values under these config keys masked as well (#348).
   *
   * @param keys config keys known to hold a secret, such as {@code ConfigExtractor.jwtSecretKeys}
   */
  public GitSourceFiles withSecretKeys(Set<String> keys) {
    return new GitSourceFiles(directories, keys);
  }

  @Override
  public Set<String> secretKeys() {
    return secretKeys;
  }

  @Override
  public Optional<byte[]> read(String repo, String commit, String path) {
    if (path.startsWith("-") || path.contains(":")) {
      return Optional.empty();
    }
    return cat(repo, commit + ":" + path, path, commit);
  }

  @Override
  public Optional<byte[]> blob(String repo, String blob, String path) {
    if (!BLOB.matcher(blob).matches()) {
      return Optional.empty();
    }
    return cat(repo, blob, path, blob);
  }

  /** A line of a file at a commit that holds a text. */
  public record Match(String path, int line) {}

  /**
   * The lines of a repository's text files at a commit that hold a text as written, in the order of
   * their paths and lines, with {@code git grep}; none if git fails.
   *
   * @param max the most to give
   */
  public java.util.List<Match> grep(String repo, String commit, String text, int max) {
    Optional<Path> dir = directories.apply(repo);
    if (dir.isEmpty() || text.isEmpty() || commit.startsWith("-")) {
      return java.util.List.of();
    }
    Git.Result result;
    try {
      result =
          Git.run(dir.get(), "grep", "-n", "-I", "-F", "--null", "--no-color", "-e", text, commit);
    } catch (RuntimeException e) {
      LOG.warn("Cannot search {} at {} in {}", text, commit, repo, e);
      return java.util.List.of();
    }
    if (result.exitCode() != 0) {
      // 1: nothing holds it.
      return java.util.List.of();
    }
    java.util.List<Match> matches = new java.util.ArrayList<>();
    String prefix = commit + ":";
    for (String line : result.out().split("\n")) {
      // commit:path NUL line NUL text
      String[] parts = line.split("\u0000", 3);
      if (parts.length < 3 || !parts[0].startsWith(prefix)) {
        continue;
      }
      try {
        matches.add(new Match(parts[0].substring(prefix.length()), Integer.parseInt(parts[1])));
      } catch (NumberFormatException e) {
        continue;
      }
      if (matches.size() == max) {
        break;
      }
    }
    return matches;
  }

  private Optional<byte[]> cat(String repo, String object, String path, String commit) {
    Optional<Path> dir = directories.apply(repo);
    if (dir.isEmpty()) {
      return Optional.empty();
    }
    ProcessBuilder builder =
        Git.command(dir.get(), "cat-file", "blob", object)
            .redirectError(ProcessBuilder.Redirect.DISCARD);
    Process process;
    try {
      process = builder.start();
    } catch (IOException e) {
      LOG.warn("Cannot run git to read {} at {} in {}", path, commit, repo, e);
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
      LOG.warn("Cannot read {} at {} in {}", path, commit, repo, e);
      return Optional.empty();
    } catch (InterruptedException e) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }
}

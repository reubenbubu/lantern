package io.lantern.core.repo;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Runs the {@code git} command line tool on a local repository. */
public final class Git {

  private static final long TIMEOUT_SECONDS = 30;

  /** Environment variables that make git use a repository other than the one given with -C. */
  static final List<String> REPOSITORY_VARIABLES =
      List.of(
          "GIT_DIR",
          "GIT_WORK_TREE",
          "GIT_INDEX_FILE",
          "GIT_OBJECT_DIRECTORY",
          "GIT_ALTERNATE_OBJECT_DIRECTORIES",
          "GIT_COMMON_DIR",
          "GIT_NAMESPACE",
          "GIT_CEILING_DIRECTORIES",
          "GIT_DISCOVERY_ACROSS_FILESYSTEM");

  private Git() {}

  /** The result of a git command: its exit code and trimmed standard output and error. */
  public record Result(int exitCode, String out, String err) {

    public boolean ok() {
      return exitCode == 0;
    }
  }

  /**
   * A process builder for {@code git -C dir args...} that never prompts for credentials, for
   * callers that need to stream the output themselves.
   */
  public static ProcessBuilder command(Path dir, String... args) {
    List<String> command = new ArrayList<>(List.of("git", "-C", dir.toString()));
    command.addAll(List.of(args));
    ProcessBuilder builder = new ProcessBuilder(command);
    // Variables like GIT_DIR would override -C and point git at another repository.
    REPOSITORY_VARIABLES.forEach(builder.environment()::remove);
    builder.environment().put("GIT_TERMINAL_PROMPT", "0");
    builder.environment().put("LC_ALL", "C");
    return builder;
  }

  /** Runs {@code git -C dir args...}, without prompting for credentials. */
  public static Result run(Path dir, String... args) {
    ProcessBuilder builder = command(dir, args);
    List<String> command = builder.command();
    Process process;
    try {
      process = builder.start();
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot run git; is it installed?", e);
    }
    try {
      process.getOutputStream().close();
      CompletableFuture<String> out = read(process.getInputStream());
      CompletableFuture<String> err = read(process.getErrorStream());
      if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IllegalStateException("git timed out in " + dir + ": " + command);
      }
      return new Result(
          process.exitValue(),
          out.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).strip(),
          err.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).strip());
    } catch (ExecutionException | TimeoutException e) {
      throw new IllegalStateException("Cannot read git output in " + dir + ": " + command, e);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while running git in " + dir, e);
    }
  }

  private static CompletableFuture<String> read(InputStream stream) {
    return CompletableFuture.supplyAsync(
        () -> {
          try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        },
        Thread.ofVirtual()::start);
  }
}

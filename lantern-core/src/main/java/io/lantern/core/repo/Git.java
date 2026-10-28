package io.lantern.core.repo;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Runs the {@code git} command line tool on a local repository. */
final class Git {

  private static final long TIMEOUT_SECONDS = 30;

  private Git() {}

  /** The result of a git command: its exit code and trimmed standard output and error. */
  record Result(int exitCode, String out, String err) {

    boolean ok() {
      return exitCode == 0;
    }
  }

  /** Runs {@code git -C dir args...}, without prompting for credentials. */
  static Result run(Path dir, String... args) {
    List<String> command = new ArrayList<>(List.of("git", "-C", dir.toString()));
    command.addAll(List.of(args));
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.environment().put("GIT_TERMINAL_PROMPT", "0");
    builder.environment().put("LC_ALL", "C");
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
      return new Result(process.exitValue(), out.join().strip(), err.join().strip());
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

package io.lantern.sandbox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Runs the Docker (or Podman) command line tool. */
final class Docker {

  /** How much of a command's output is kept. */
  private static final int MAX_CAPTURE_BYTES = 1024 * 1024;

  private final String command;

  Docker(String command) {
    this.command = command;
  }

  /** The result of a command: its exit code and trimmed standard output and error. */
  record Result(int exitCode, String out, String err) {

    boolean ok() {
      return exitCode == 0;
    }
  }

  /** A process builder for {@code docker args...}. */
  ProcessBuilder builder(List<String> args) {
    List<String> full = new ArrayList<>();
    full.add(command);
    full.addAll(args);
    return new ProcessBuilder(full);
  }

  /** Runs {@code docker args...} and waits for it, killing it after the timeout. */
  Result run(Duration timeout, List<String> args) {
    Process process = start(builder(args));
    CompletableFuture<String> out = capture(process.getInputStream());
    CompletableFuture<String> err = capture(process.getErrorStream());
    try {
      process.getOutputStream().close();
      int exit = waitFor(process, timeout, args);
      return new Result(exit, out.get(30, TimeUnit.SECONDS), err.get(30, TimeUnit.SECONDS));
    } catch (ExecutionException | TimeoutException e) {
      throw new SandboxException("Cannot read the output of docker " + args, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SandboxException("Interrupted while running docker " + args, e);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Runs {@code docker args...} and fails unless it exits with 0. */
  Result check(Duration timeout, List<String> args) {
    Result result = run(timeout, args);
    if (!result.ok()) {
      throw new SandboxException(
          "docker "
              + String.join(" ", args)
              + " failed ("
              + result.exitCode()
              + "): "
              + result.err());
    }
    return result;
  }

  /** Runs {@code docker args...}, ignoring failures; for cleanup. */
  void quietly(Duration timeout, List<String> args) {
    try {
      run(timeout, args);
    } catch (RuntimeException e) {
      // Cleanup is best effort; leftovers carry the sandbox label.
    }
  }

  static Process start(ProcessBuilder builder) {
    try {
      return builder.start();
    } catch (IOException e) {
      throw new SandboxException(
          "Cannot run " + builder.command().getFirst() + "; is it installed?", e);
    }
  }

  /** Waits for the process, killing it and failing after the timeout. */
  static int waitFor(Process process, Duration timeout, Object what) {
    try {
      if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        throw new SandboxException("Timed out after " + timeout + ": " + what);
      }
      return process.exitValue();
    } catch (InterruptedException e) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new SandboxException("Interrupted while waiting for " + what, e);
    }
  }

  /** Reads a stream to its end on a virtual thread, keeping the first megabyte. */
  static CompletableFuture<String> capture(InputStream stream) {
    return CompletableFuture.supplyAsync(
        () -> {
          try (stream) {
            ByteArrayOutputStream kept = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
              int room = MAX_CAPTURE_BYTES - kept.size();
              if (room > 0) {
                kept.write(buffer, 0, Math.min(room, read));
              }
            }
            return kept.toString(StandardCharsets.UTF_8).strip();
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        },
        Thread.ofVirtual()::start);
  }
}

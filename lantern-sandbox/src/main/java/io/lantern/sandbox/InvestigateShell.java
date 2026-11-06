package io.lantern.sandbox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The sealed container Investigate's shell runs its commands in (#893, ADR 0127): a read-only copy
 * of the repositories at {@code /work}, no network, a read-only root, a user that is not root, no
 * capabilities, no new privileges, bounded processes, memory and {@code /tmp}, and only text tools
 * (its image, {@code investigate-shell/Dockerfile}, which Lantern builds once and tags by the
 * file's hash). It is started once an Investigate run and removed when the run ends, also on
 * failure; it stops by itself when Lantern's process ends, and carries the sandbox's labels, so a
 * leftover can be found. Each command is its own process, stopped after {@link #TIMEOUT}, its
 * output read only up to {@link #CEILING} bytes; conversations running at once share the container.
 * The container is the guard: commands are not parsed or refused.
 */
public final class InvestigateShell implements AutoCloseable {

  /** How long one command may run. */
  public static final Duration TIMEOUT = Duration.ofSeconds(20);

  /** How much of a command's standard output, and of its error, is read. */
  public static final int CEILING = 256 * 1024;

  /**
   * How many commands run at once. Conversations running at the same time share the container and
   * its process limit; each command takes a few processes, so more wait their turn here.
   */
  static final int AT_ONCE = 8;

  /** How many processes the container may hold: room for {@link #AT_ONCE} commands' pipelines. */
  static final int PROCESSES = 256;

  /** The image's repository; its tag is a hash of the Dockerfile. */
  static final String IMAGE = "lantern-investigate-shell";

  /** Where the container sees the repositories, read-only. */
  public static final String WORK = "/work";

  static final String USER = "65534:65534";

  /**
   * Runs the command ({@code $1}) in a process group of its own under {@code timeout}, then kills
   * what is left of that group, so nothing it started in the background outlives it. The group's id
   * is kept in {@code /tmp/lantern-$2} while it runs, so a command Lantern stops reading can be
   * killed at once ({@link #KILL}).
   */
  static final String WRAPPER =
      "setsid timeout -k 2 "
          + TIMEOUT.toSeconds()
          + " sh -c \"$1\" & p=$!; echo $p > /tmp/lantern-$2 2>/dev/null; wait $p; s=$?;"
          + " kill -9 -$p 2>/dev/null; rm -f /tmp/lantern-$2; exit $s";

  /** Kills the process group of the command whose id is {@code $1}. */
  static final String KILL =
      "p=$(cat /tmp/lantern-$1 2>/dev/null) && kill -9 -$p 2>/dev/null; rm -f /tmp/lantern-$1";

  private static final String DOCKERFILE = "investigate-shell/Dockerfile";
  private static final Duration DOCKER_TIMEOUT = Duration.ofMinutes(2);
  private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(10);

  /** A command's run: its output and error as read, its exit, and whether a limit stopped it. */
  public record Run(String out, String err, int exitCode, boolean timedOut, boolean overCeiling) {}

  private final Docker docker;
  private final String name;
  private final java.util.concurrent.Semaphore turns = new java.util.concurrent.Semaphore(AT_ONCE);
  private final Process lifeline;
  private boolean closed;

  private InvestigateShell(Docker docker, String name, Process lifeline) {
    this.docker = docker;
    this.name = name;
    this.lifeline = lifeline;
  }

  /**
   * Starts the container over a folder holding one folder per repository, building its image first
   * if it is not there.
   *
   * @param settings the sandbox's Docker command and labels
   * @param repositories the folder mounted read-only at {@link #WORK}
   */
  public static InvestigateShell start(SandboxSettings settings, Path repositories) {
    Docker docker = new Docker(settings.dockerCommand());
    String image = image(docker);
    String name = "lantern-investigate-" + UUID.randomUUID().toString().substring(0, 12);
    List<String> args = new ArrayList<>(List.of("create", "--name", name));
    args.addAll(List.of("--label", BuildSandbox.LABEL + "=" + name));
    if (settings.ownerLabel() != null) {
      args.addAll(List.of("--label", settings.ownerLabel()));
    }
    OwnerProcess.current()
        .ifPresent(
            o -> args.addAll(List.of("--label", BuildSandbox.OWNER_LABEL + "=" + o.label())));
    args.addAll(
        List.of(
            "--interactive",
            "--rm",
            "--network",
            "none",
            "--read-only",
            "--user",
            USER,
            "--cap-drop",
            "ALL",
            "--security-opt",
            "no-new-privileges",
            "--pids-limit",
            String.valueOf(PROCESSES),
            "--memory",
            "512m",
            "--memory-swap",
            "512m",
            "--cpus",
            "1",
            "--tmpfs",
            "/tmp:rw,size=16m,mode=1777,noexec,nosuid,nodev",
            "--log-driver",
            "none",
            "--mount",
            "type=bind,source=" + repositories.toAbsolutePath() + ",target=" + WORK + ",readonly",
            "--workdir",
            WORK,
            "--entrypoint",
            "sh",
            image,
            "-c",
            BuildSandbox.LIFELINE,
            "sh",
            "sleep",
            "infinity"));
    docker.create(DOCKER_TIMEOUT, args);
    Process lifeline;
    try {
      // Holds the container's standard input: when Lantern's process ends, so does the container.
      lifeline =
          Docker.start(
              docker
                  .builder(List.of("start", "--attach", "--interactive", name))
                  .redirectErrorStream(true)
                  .redirectOutput(ProcessBuilder.Redirect.DISCARD));
      long deadline = System.nanoTime() + DOCKER_TIMEOUT.toNanos();
      while (!docker
          .run(DOCKER_TIMEOUT, List.of("inspect", "--format", "{{.State.Running}}", name))
          .out()
          .equals("true")) {
        if (!lifeline.isAlive() || System.nanoTime() > deadline) {
          throw new SandboxException("The Investigate shell's container did not start: " + name);
        }
        Thread.sleep(100);
      }
    } catch (InterruptedException e) {
      docker.quietly(DOCKER_TIMEOUT, List.of("rm", "--force", name));
      Thread.currentThread().interrupt();
      throw new SandboxException("Interrupted while starting the Investigate shell", e);
    } catch (RuntimeException e) {
      docker.quietly(DOCKER_TIMEOUT, List.of("rm", "--force", name));
      throw e;
    }
    return new InvestigateShell(docker, name, lifeline);
  }

  /** The container's name. */
  public String name() {
    return name;
  }

  /**
   * Runs one command with {@code sh -c} in {@link #WORK}, stopped after {@link #TIMEOUT}; its
   * output and error read up to {@link #CEILING} bytes each.
   */
  public Run run(String command) {
    Objects.requireNonNull(command, "command");
    try {
      turns.acquire();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SandboxException("Interrupted while waiting to run a command in " + name, e);
    }
    try {
      return runNow(command);
    } finally {
      turns.release();
    }
  }

  private Run runNow(String command) {
    String id = UUID.randomUUID().toString().substring(0, 12);
    List<String> args = List.of("exec", name, "sh", "-c", WRAPPER, "sh", command, id);
    long started = System.nanoTime();
    Process process = Docker.start(docker.builder(args));
    CompletableFuture<Read> out = read(process.getInputStream());
    CompletableFuture<Read> err = read(process.getErrorStream());
    try {
      process.getOutputStream().close();
      // A command whose output passed the ceiling is not read further: stop it now.
      CompletableFuture.anyOf(
          out.thenAccept(r -> stopIf(r.over(), id)), err.thenAccept(r -> stopIf(r.over(), id)));
      boolean ended = process.waitFor(TIMEOUT.toSeconds() + 5, TimeUnit.SECONDS);
      if (!ended) {
        stop(id);
        process.destroyForcibly();
      }
      Read o = out.get(5, TimeUnit.SECONDS);
      Read e = err.get(5, TimeUnit.SECONDS);
      int exit = ended ? process.exitValue() : 124;
      boolean over = o.over() || e.over();
      // timeout says 124, or 137 when it had to kill; a kill once the time is up is the timeout's.
      boolean timedOut =
          !over
              && (exit == 124
                  || !ended
                  || exit == 137
                      && Duration.ofNanos(System.nanoTime() - started).compareTo(TIMEOUT) >= 0);
      return new Run(o.text(), e.text(), exit, timedOut, over);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SandboxException("Interrupted while running a command in " + name, e);
    } catch (ExecutionException | TimeoutException e) {
      // The output could not be read: say so rather than call it a timeout.
      stop(id);
      process.destroyForcibly();
      Read o = out.isDone() && !out.isCompletedExceptionally() ? out.join() : new Read("", false);
      return new Run(
          o.text(), "Lantern could not read this command's output.", -1, false, o.over());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } finally {
      process.destroyForcibly();
    }
  }

  private void stopIf(boolean over, String id) {
    if (over) {
      stop(id);
    }
  }

  /** Kills a command's process group in the container, so its turn frees its processes too. */
  private void stop(String id) {
    docker.quietly(DOCKER_TIMEOUT, List.of("exec", name, "sh", "-c", KILL, "sh", id));
  }

  private record Read(String text, boolean over) {}

  /** Reads a stream up to the ceiling, then stops reading; the process is stopped after. */
  private static CompletableFuture<Read> read(InputStream stream) {
    return CompletableFuture.supplyAsync(
        () -> {
          try (stream) {
            ByteArrayOutputStream kept = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = stream.read(buffer)) >= 0) {
              int room = CEILING - kept.size();
              if (n > room) {
                kept.write(buffer, 0, room);
                return new Read(kept.toString(StandardCharsets.UTF_8), true);
              }
              kept.write(buffer, 0, n);
            }
            return new Read(kept.toString(StandardCharsets.UTF_8), false);
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        },
        Thread.ofVirtual()::start);
  }

  /** Removes the container. */
  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    closed = true;
    docker.quietly(DOCKER_TIMEOUT, List.of("rm", "--force", name));
    try (OutputStream in = lifeline.getOutputStream()) {
      // Closing the lifeline's input ends it, if removing the container did not.
    } catch (IOException e) {
      // Best effort.
    }
    try {
      lifeline.waitFor(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      lifeline.destroyForcibly();
    }
  }

  /** The image, built from the Dockerfile the first time; its tag is the file's hash. */
  static synchronized String image(Docker docker) {
    byte[] dockerfile = dockerfile();
    String tag = IMAGE + ":" + hash(dockerfile).substring(0, 16);
    if (docker.run(DOCKER_TIMEOUT, List.of("image", "inspect", tag)).ok()) {
      return tag;
    }
    Process build =
        Docker.start(
            docker
                .builder(List.of("build", "--quiet", "--tag", tag, "-"))
                .redirectErrorStream(true));
    CompletableFuture<String> output = Docker.capture(build.getInputStream());
    try (OutputStream in = build.getOutputStream()) {
      in.write(dockerfile);
    } catch (IOException e) {
      build.destroyForcibly();
      throw new UncheckedIOException("Cannot send the Investigate shell's Dockerfile", e);
    }
    int exit = Docker.waitFor(build, BUILD_TIMEOUT, "docker build " + tag);
    String log = output.join();
    if (exit != 0) {
      throw new SandboxException("Cannot build the Investigate shell's image " + tag + ": " + log);
    }
    return tag;
  }

  private static byte[] dockerfile() {
    try (InputStream in = InvestigateShell.class.getResourceAsStream(DOCKERFILE)) {
      if (in == null) {
        throw new IllegalStateException("No " + DOCKERFILE + " beside " + InvestigateShell.class);
      }
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}

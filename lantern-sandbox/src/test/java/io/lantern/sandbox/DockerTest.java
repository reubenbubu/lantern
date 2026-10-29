package io.lantern.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests {@link Docker} with a script in place of the Docker command line tool. */
class DockerTest {

  @TempDir Path temp;

  /**
   * A command that writes its arguments to {@code started}, waits a second and then writes them to
   * {@code created}, like a Docker daemon that finishes creating a container after a while.
   */
  private Docker slowDocker() throws IOException {
    Path script = temp.resolve("docker");
    Files.writeString(
        script,
        "#!/bin/sh\n"
            + "echo \"$@\" > '"
            + temp.resolve("started")
            + "'\nsleep 1\necho \"$@\" > '"
            + temp.resolve("created")
            + "'\n");
    Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
    return new Docker(script.toString());
  }

  @Test
  void createWaitsForTheCommandWhenInterruptedAndThenFails() throws Exception {
    Docker docker = slowDocker();
    AtomicReference<Throwable> thrown = new AtomicReference<>();
    AtomicBoolean interruptedAfter = new AtomicBoolean();
    Thread call =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    docker.create(Duration.ofMinutes(1), List.of("create", "--name", "c"));
                  } catch (Throwable t) {
                    thrown.set(t);
                  }
                  interruptedAfter.set(Thread.currentThread().isInterrupted());
                });
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (!Files.exists(temp.resolve("started"))) {
      assertThat(System.nanoTime()).as("the command never started").isLessThan(deadline);
      Thread.sleep(20);
    }
    call.interrupt();
    call.join(Duration.ofSeconds(30));

    assertThat(call.isAlive()).isFalse();
    assertThat(temp.resolve("created")).as("the command ran to its end").exists();
    assertThat(thrown.get())
        .isInstanceOf(SandboxException.class)
        .hasMessageContaining("Interrupted");
    assertThat(interruptedAfter).isTrue();
  }

  @Test
  void createRunsWhenTheThreadIsAlreadyInterrupted() throws Exception {
    Docker docker = slowDocker();
    AtomicReference<Throwable> thrown = new AtomicReference<>();
    Thread call =
        Thread.ofVirtual()
            .start(
                () -> {
                  Thread.currentThread().interrupt();
                  try {
                    docker.create(Duration.ofMinutes(1), List.of("network", "create", "n"));
                  } catch (Throwable t) {
                    thrown.set(t);
                  }
                });
    call.join(Duration.ofSeconds(30));

    assertThat(call.isAlive()).isFalse();
    assertThat(temp.resolve("created")).as("the command ran to its end").exists();
    assertThat(thrown.get()).isInstanceOf(SandboxException.class);
  }

  @Test
  void createReturnsTheResultWhenNotInterrupted() throws IOException {
    Docker.Result result = slowDocker().create(Duration.ofMinutes(1), List.of("create", "x"));

    assertThat(result.ok()).isTrue();
    assertThat(temp.resolve("created")).hasContent("create x");
  }
}

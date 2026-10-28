package io.lantern.extract;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Runs the docker CLI from tests, for the Maven cache volumes the sandbox builds use. */
public final class TestDocker {

  private TestDocker() {}

  /** A new, empty volume with a random name. */
  public static String createVolume() {
    String volume =
        "lantern-test-m2-" + HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt());
    run("volume", "create", volume);
    return volume;
  }

  public static void removeVolume(String volume) {
    run("volume", "rm", "--force", volume);
  }

  private static void run(String... args) {
    List<String> command = new ArrayList<>(List.of("docker"));
    command.addAll(List.of(args));
    try {
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      process.getOutputStream().close();
      String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (process.waitFor() != 0) {
        throw new IllegalStateException(String.join(" ", command) + ": " + out);
      }
    } catch (IOException e) {
      throw new IllegalStateException("Cannot run docker", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while running docker", e);
    }
  }
}

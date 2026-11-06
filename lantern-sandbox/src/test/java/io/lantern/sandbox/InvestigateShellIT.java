package io.lantern.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Investigate's shell container keeps commands in (#893, ADR 0127), checked against the real
 * container: nothing written outside {@code /tmp}, no network, nothing of the host, no git; a fork
 * bomb and an endless loop stopped by the limits; a flood of output read only to the ceiling; and
 * the container gone once closed. One container serves the tests, as one serves a run.
 */
class InvestigateShellIT {

  private static final Docker DOCKER = new Docker("docker");
  private static final Duration TIMEOUT = Duration.ofSeconds(30);
  private static final String RUN_LABEL = "lantern.test.run";
  private static final String RUN =
      HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextLong());

  @TempDir static Path temp;

  private static Path secret;
  private static InvestigateShell shell;

  @BeforeAll
  static void start() throws IOException {
    Path work = Files.createDirectories(temp.resolve("work"));
    Path repo = Files.createDirectories(work.resolve("edge-gateway/src/main/resources"));
    Files.writeString(repo.resolve("application.yml"), "spring:\n  application:\n    name: edge\n");
    Files.createDirectories(work.resolve("billing"));
    Files.writeString(work.resolve("billing/notes.txt"), "one\ntwo\n");
    // A file of the host, beside the folder the container sees but not in it.
    secret = Files.writeString(temp.resolve("host-only-" + RUN + ".txt"), "host secret");
    shell =
        InvestigateShell.start(
            SandboxSettings.defaults(temp.resolve("builds")).withOwnerLabel(RUN_LABEL, RUN), work);
  }

  @AfterAll
  static void stop() {
    if (shell != null) {
      shell.close();
    }
  }

  @Test
  void commandsRunWithCdPipesLoopsAndSubstitution() {
    assertThat(shell.run("cd edge-gateway && grep -rn name src | cut -c1-80").out())
        .isEqualTo("src/main/resources/application.yml:3:    name: edge\n");
    assertThat(shell.run("for f in $(ls billing); do wc -l < billing/$f; done").out().strip())
        .isEqualTo("2");
    assertThat(shell.run("nl -ba billing/notes.txt | sed -n '2,2p'").out())
        .isEqualTo("     2\ttwo\n");
    assertThat(shell.run("yq -o=props edge-gateway/src/main/resources/application.yml").out())
        .contains("spring.application.name = edge");
    InvestigateShell.Run failing = shell.run("grep -q nothing billing/notes.txt");
    assertThat(failing.exitCode()).isEqualTo(1);
    assertThat(failing.timedOut()).isFalse();
  }

  @Test
  void nothingCanBeWrittenOutsideTmp() {
    assertThat(shell.run("touch edge-gateway/new.txt").exitCode()).isNotZero();
    assertThat(shell.run("echo x > billing/notes.txt").exitCode()).isNotZero();
    assertThat(shell.run("touch /new.txt").exitCode()).isNotZero();
    assertThat(shell.run("touch /etc/new.txt").exitCode()).isNotZero();
    assertThat(shell.run("cat billing/notes.txt").out()).isEqualTo("one\ntwo\n");
    // sort and the like may use /tmp, which is small and runs nothing.
    assertThat(shell.run("echo ok > /tmp/t && cat /tmp/t").out()).isEqualTo("ok\n");
    assertThat(shell.run("cp /bin/ls /tmp/ls && /tmp/ls /").exitCode()).isNotZero();
  }

  @Test
  void noNetworkAndNoNetworkTools() {
    for (String tool : List.of("wget", "curl", "nc", "git", "apk", "ssh", "python3", "perl")) {
      assertThat(shell.run("command -v " + tool).exitCode()).as(tool).isNotZero();
    }
    // gawk can open TCP connections itself; with no network, none goes anywhere.
    for (String address : List.of("1.1.1.1/80", "172.17.0.1/2375", "10.0.0.1/80")) {
      InvestigateShell.Run connect =
          shell.run(
              "gawk 'BEGIN { s = \"/inet/tcp/0/"
                  + address
                  + "\"; print \"GET /\" |& s; if ((s |& getline line) > 0) print \"connected\" }'");
      assertThat(connect.out()).as(address).doesNotContain("connected");
    }
    assertThat(shell.run("cat /sys/class/net/*/operstate 2>/dev/null; ls /sys/class/net").out())
        .doesNotContain("eth0");
  }

  @Test
  void theHostAndGitHistoryAreNotThere() {
    assertThat(shell.run("find / -name 'host-only-*' 2>/dev/null").out()).isEmpty();
    assertThat(shell.run("find /work -name .git").out()).isEmpty();
    assertThat(shell.run("ls /work").out()).isEqualTo("billing\nedge-gateway\n");
    assertThat(shell.run("id -u").out().strip()).isEqualTo("65534");
    assertThat(secret).exists();
  }

  @Test
  void anEndlessLoopIsStoppedAfterTheTimeout() {
    long started = System.nanoTime();
    InvestigateShell.Run run = shell.run("echo started; while :; do :; done");
    Duration took = Duration.ofNanos(System.nanoTime() - started);
    assertThat(run.timedOut()).isTrue();
    assertThat(run.out()).isEqualTo("started\n");
    assertThat(took).isLessThan(InvestigateShell.TIMEOUT.plusSeconds(8));
    assertThat(shell.run("echo still here").out()).isEqualTo("still here\n");
  }

  @Test
  void aForkBombIsHeldByTheProcessLimit() {
    InvestigateShell.Run run = shell.run("f() { f | f & }; f; sleep 2; echo done");
    assertThat(run.timedOut()).isFalse();
    // What the bomb started is killed with its command, and the container goes on serving.
    assertThat(shell.run("echo after").out()).isEqualTo("after\n");
    assertThat(Integer.parseInt(shell.run("ps -o pid= | wc -l").out().strip())).isLessThan(10);
  }

  @Test
  void aFloodOfOutputIsReadOnlyToTheCeiling() {
    InvestigateShell.Run run = shell.run("head -c 10000000 /dev/zero | tr '\\0' a");
    assertThat(run.overCeiling()).isTrue();
    assertThat(run.out()).hasSize(InvestigateShell.CEILING);
    assertThat(shell.run("echo next").out()).isEqualTo("next\n");
  }

  @Test
  void theContainerIsGoneOnceClosed() throws IOException {
    Path work = Files.createDirectories(temp.resolve("other"));
    InvestigateShell other =
        InvestigateShell.start(
            SandboxSettings.defaults(temp.resolve("builds")).withOwnerLabel(RUN_LABEL, RUN), work);
    String name = other.name();
    assertThat(running(name)).isTrue();
    other.close();
    assertThat(running(name)).isFalse();
    assertThat(
            DOCKER
                .check(TIMEOUT, List.of("ps", "--all", "--quiet", "--filter", "name=" + name))
                .out())
        .isEmpty();
  }

  private static boolean running(String name) {
    return DOCKER
        .run(TIMEOUT, List.of("inspect", "--format", "{{.State.Running}}", name))
        .out()
        .equals("true");
  }
}

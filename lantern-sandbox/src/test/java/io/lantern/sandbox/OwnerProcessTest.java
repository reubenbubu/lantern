package io.lantern.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Tests {@link OwnerProcess} against this machine's {@code /proc}. */
class OwnerProcessTest {

  private static OwnerProcess current() {
    Optional<OwnerProcess> current = OwnerProcess.current();
    assumeTrue(current.isPresent(), "needs Linux's /proc");
    return current.get();
  }

  @Test
  void namesThisProcess() {
    OwnerProcess current = current();

    assertThat(current.pid()).isEqualTo(ProcessHandle.current().pid());
    assertThat(current.startTicks()).isPositive();
    assertThat(OwnerProcess.parse(current.label())).contains(current);
  }

  @Test
  void parsesOnlyItsOwnLabels() {
    assertThat(OwnerProcess.parse("")).isEmpty();
    assertThat(OwnerProcess.parse("83a4059c-d72f:1:2:3")).isEmpty();
    assertThat(OwnerProcess.parse("83a4059c-d72f:1:2:3:x")).isEmpty();
    assertThat(OwnerProcess.parse("not a boot:1:2:3:4")).isEmpty();
    assertThat(OwnerProcess.parse("83a4059c-d72f:1:2:3:4"))
        .contains(new OwnerProcess("83a4059c-d72f", 1, 2, 3, 4));
  }

  @Test
  void aLiveProcessHasNotEnded() {
    OwnerProcess current = current();

    assertThat(current.endedAsSeenBy(current)).isFalse();
  }

  @Test
  void aKilledProcessHasEnded() throws Exception {
    OwnerProcess current = current();
    Process child = new ProcessBuilder("sleep", "60").start();
    OwnerProcess owner;
    try {
      owner = OwnerProcess.of(child.pid()).orElseThrow();
      assertThat(owner.endedAsSeenBy(current)).isFalse();
    } finally {
      child.destroyForcibly().waitFor();
    }

    assertThat(owner.endedAsSeenBy(current)).isTrue();
  }

  @Test
  void aReusedProcessIdIsNotTheOwner() {
    OwnerProcess current = current();
    OwnerProcess earlier =
        new OwnerProcess(
            current.boot(),
            current.pidNamespace(),
            current.uid(),
            current.pid(),
            current.startTicks() - 1);

    assertThat(earlier.endedAsSeenBy(current)).isTrue();
  }

  @Test
  void whatCannotBeToldHasNotEnded() {
    OwnerProcess current = current();
    // A process ID no process has, on another boot, in another PID namespace, of another user.
    long gone = Long.MAX_VALUE;

    assertThat(
            new OwnerProcess(
                    "00000000-0000-0000-0000-000000000000",
                    current.pidNamespace(),
                    current.uid(),
                    gone,
                    1)
                .endedAsSeenBy(current))
        .isFalse();
    assertThat(
            new OwnerProcess(current.boot(), current.pidNamespace() + 1, current.uid(), gone, 1)
                .endedAsSeenBy(current))
        .isFalse();
    assertThat(
            new OwnerProcess(current.boot(), current.pidNamespace(), current.uid() + 1, gone, 1)
                .endedAsSeenBy(current))
        .isFalse();
    assertThat(
            new OwnerProcess(current.boot(), current.pidNamespace(), current.uid(), gone, 1)
                .endedAsSeenBy(current))
        .isTrue();
  }
}

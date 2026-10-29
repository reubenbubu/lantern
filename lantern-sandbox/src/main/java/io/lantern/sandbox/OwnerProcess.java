package io.lantern.sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The process that created a sandbox container or network, as it is written in their {@link
 * BuildSandbox#OWNER_LABEL}, so that another build can tell whether that process has died and
 * remove what it left (#174). It names the machine's boot, the PID namespace, the user, the process
 * ID and the process's start time, read from Linux's {@code /proc}. Whether the process lives can
 * only be told from the same boot, PID namespace and user: another machine sharing the Docker
 * daemon, a process in another container or another user's process (which {@code /proc} may hide)
 * is never taken for dead.
 *
 * @param boot the boot ID of the machine
 * @param pidNamespace the inode of the PID namespace
 * @param uid the user ID
 * @param pid the process ID in that namespace
 * @param startTicks when the process started, in clock ticks after boot, so a reused process ID is
 *     not taken for the owner
 */
record OwnerProcess(String boot, long pidNamespace, long uid, long pid, long startTicks) {

  private static final Path PROC = Path.of("/proc");
  private static final Pattern BOOT = Pattern.compile("[0-9a-f-]{1,64}");
  private static final Pattern NAMESPACE = Pattern.compile("pid:\\[(\\d+)]");

  /** The process running Lantern, if {@code /proc} tells who it is. */
  static Optional<OwnerProcess> current() {
    return of(ProcessHandle.current().pid());
  }

  /**
   * The process with the ID in this process's PID namespace, as seen from here, if it is there and
   * {@code /proc} tells who it is.
   */
  static Optional<OwnerProcess> of(long pid) {
    try {
      String boot =
          Files.readString(PROC.resolve("sys/kernel/random/boot_id"), StandardCharsets.US_ASCII)
              .strip();
      var namespace =
          NAMESPACE.matcher(Files.readSymbolicLink(PROC.resolve("self/ns/pid")).toString());
      Path process = PROC.resolve(Long.toString(pid));
      String stat = Files.readString(process.resolve("stat"), StandardCharsets.UTF_8);
      long uid = ((Number) Files.getAttribute(process, "unix:uid")).longValue();
      if (!BOOT.matcher(boot).matches() || !namespace.matches()) {
        return Optional.empty();
      }
      // The command name, in parentheses, may hold spaces; the start time is the 22nd field.
      String[] fields = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
      return Optional.of(
          new OwnerProcess(
              boot, Long.parseLong(namespace.group(1)), uid, pid, Long.parseLong(fields[22 - 3])));
    } catch (IOException | RuntimeException e) {
      // Not Linux, no such process, or an unexpected format.
      return Optional.empty();
    }
  }

  /** The owner a label value names, if it is one. */
  static Optional<OwnerProcess> parse(String label) {
    String[] parts = label.split(":", -1);
    if (parts.length != 5 || !BOOT.matcher(parts[0]).matches()) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          new OwnerProcess(
              parts[0],
              Long.parseLong(parts[1]),
              Long.parseLong(parts[2]),
              Long.parseLong(parts[3]),
              Long.parseLong(parts[4])));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  /** The label value naming this process. */
  String label() {
    return boot + ":" + pidNamespace + ":" + uid + ":" + pid + ":" + startTicks;
  }

  /**
   * Whether this process is known to have ended, as seen from the given process: both run on the
   * same boot, in the same PID namespace and as the same user, and no process with this ID and
   * start time is there. False whenever it cannot be told.
   */
  boolean endedAsSeenBy(OwnerProcess observer) {
    if (!boot.equals(observer.boot)
        || pidNamespace != observer.pidNamespace
        || uid != observer.uid) {
      return false;
    }
    if (!Files.isDirectory(PROC.resolve("self"))) {
      return false;
    }
    if (!Files.exists(PROC.resolve(Long.toString(pid)))) {
      return true;
    }
    // A process that ends meanwhile, or cannot be read, counts as alive.
    return of(pid).map(now -> now.startTicks != startTicks || now.uid != uid).orElse(false);
  }
}

package io.lantern.sandbox;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The stored outcome of building one {@code repo@commit}.
 *
 * @param repo the repository name
 * @param commit the full commit hash that was built
 * @param status whether the build succeeded
 * @param failure why the build failed, or null if it succeeded
 * @param jdk the JDK version of the image the build ran in, or 0 if no image was chosen
 * @param jdkSource how the JDK was chosen: the repository setting, what the POM says, or the
 *     default
 * @param image the image the build ran in, or null if none was chosen
 * @param mavenExitCode Maven's exit code, or null if Maven did not finish
 * @param startedAt when the build started
 * @param duration how long the build took
 * @param effectivePom the effective POM of all modules, relative to {@link #directory}, or null
 * @param modules the modules that left outputs, ordered by path
 * @param directory the directory holding the build's outputs, log and this result
 * @param upstream the other repositories' successful builds whose artifacts the build was given, as
 *     {@code repo@commit} (ADR 0041)
 * @param shadowed the coordinates ({@code groupId:artifactId:version}) of those artifacts that the
 *     shared Maven cache already held when the build started, so Maven may have used the cache's
 *     copy instead (ADR 0041)
 */
public record BuildResult(
    String repo,
    String commit,
    BuildStatus status,
    String failure,
    int jdk,
    String jdkSource,
    String image,
    Integer mavenExitCode,
    Instant startedAt,
    Duration duration,
    String effectivePom,
    List<BuildModule> modules,
    Path directory,
    Set<String> upstream,
    List<String> shadowed) {

  /** A build that was given no other repository's artifacts. */
  public BuildResult(
      String repo,
      String commit,
      BuildStatus status,
      String failure,
      int jdk,
      String jdkSource,
      String image,
      Integer mavenExitCode,
      Instant startedAt,
      Duration duration,
      String effectivePom,
      List<BuildModule> modules,
      Path directory) {
    this(
        repo,
        commit,
        status,
        failure,
        jdk,
        jdkSource,
        image,
        mavenExitCode,
        startedAt,
        duration,
        effectivePom,
        modules,
        directory,
        Set.of(),
        List.of());
  }

  public BuildResult {
    Objects.requireNonNull(repo, "repo");
    Objects.requireNonNull(commit, "commit");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(jdkSource, "jdkSource");
    Objects.requireNonNull(startedAt, "startedAt");
    Objects.requireNonNull(duration, "duration");
    Objects.requireNonNull(directory, "directory");
    modules = List.copyOf(modules);
    upstream = Set.copyOf(upstream);
    shadowed = List.copyOf(shadowed);
    if ((status == BuildStatus.FAILED) != (failure != null)) {
      throw new IllegalArgumentException("A failed build, and only a failed one, has a failure");
    }
  }

  /** Whether the repository must be analyzed in degraded mode because its build failed. */
  public boolean degraded() {
    return status != BuildStatus.SUCCEEDED;
  }

  /** The build log: Lantern's notes, Maven's output and the egress proxy's log. */
  public Path log() {
    return directory.resolve(BuildSandbox.LOG_FILE);
  }

  /** A path from this result, such as a classpath entry, resolved against {@link #directory}. */
  public Path resolve(String relative) {
    return directory.resolve(relative);
  }
}

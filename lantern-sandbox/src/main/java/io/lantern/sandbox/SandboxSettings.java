package io.lantern.sandbox;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * How the build sandbox runs builds.
 *
 * @param dockerCommand the Docker (or Podman) command line tool
 * @param images the build image for each JDK version; each must have the JDK at {@code
 *     /opt/java/openjdk} and Maven as {@code mvn}
 * @param defaultJdk the JDK to build with when the POM does not say; must have an image
 * @param cpus the CPUs each build container may use
 * @param memoryBytes the memory each build container may use, swap included
 * @param pidsLimit the most processes and threads each build container may run
 * @param timeout how long each container of a build (the JDK detection, if needed, and the build)
 *     may run before it is stopped and the build recorded as failed
 * @param maxConcurrentBuilds the most builds that run at once on this host
 * @param mavenCacheVolume the Docker volume holding the local Maven repository that all builds
 *     share
 * @param mavenRepositories the Maven repositories builds download from, in this order: the only
 *     hosts they reach, through the egress proxy (ADR 0023); at least one, with distinct IDs
 * @param buildsDirectory the directory build outputs are stored in, one directory per {@code
 *     repo@commit}
 * @param maxOutputBytes the most bytes of output a build may hand back; a larger output fails the
 *     build
 * @param maxLogBytes the most bytes of build log kept; the rest is dropped
 * @param diskBytes the most bytes each build container may write outside the shared Maven cache,
 *     counting the repository's files and the outputs it hands back, classpath jars included; a
 *     build that writes more is stopped and recorded as failed
 * @param minFreeDiskBytes the free space Docker's storage must keep while a build runs; below it a
 *     build does not start, or is stopped, with a {@link SandboxException} and nothing stored,
 *     since which build used the space is unknown
 * @param ownerLabel an extra {@code key=value} label on every container and network the sandbox
 *     creates, besides {@link BuildSandbox#LABEL}, so whoever runs the sandbox can find its own
 *     resources among those of other sandboxes on the same Docker; none if {@code null}
 */
public record SandboxSettings(
    String dockerCommand,
    Map<Integer, String> images,
    int defaultJdk,
    double cpus,
    long memoryBytes,
    int pidsLimit,
    Duration timeout,
    int maxConcurrentBuilds,
    String mavenCacheVolume,
    List<MavenRepository> mavenRepositories,
    Path buildsDirectory,
    long maxOutputBytes,
    long maxLogBytes,
    long diskBytes,
    long minFreeDiskBytes,
    String ownerLabel) {

  /** The JDK images used unless configured otherwise: official Maven images on Eclipse Temurin. */
  public static final Map<Integer, String> DEFAULT_IMAGES =
      Map.of(
          17, "maven:3.9.16-eclipse-temurin-17",
          21, "maven:3.9.16-eclipse-temurin-21",
          25, "maven:3.9.16-eclipse-temurin-25");

  /** The egress proxy is compiled for this JDK, so it needs an image with this JDK or newer. */
  static final int PROXY_MIN_JDK = 21;

  private static final Pattern VOLUME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]*");
  private static final Pattern LABEL =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]*=[A-Za-z0-9_.:/-]+");

  public SandboxSettings {
    Objects.requireNonNull(dockerCommand, "dockerCommand");
    Objects.requireNonNull(timeout, "timeout");
    Objects.requireNonNull(buildsDirectory, "buildsDirectory");
    images = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(images, "images")));
    mavenRepositories = List.copyOf(Objects.requireNonNull(mavenRepositories, "mavenRepositories"));
    if (dockerCommand.isBlank()) {
      throw new IllegalArgumentException("Docker command must not be blank");
    }
    images.forEach(
        (jdk, image) -> {
          if (jdk < 1 || image == null || image.isBlank() || image.startsWith("-")) {
            throw new IllegalArgumentException("Invalid image for JDK " + jdk + ": " + image);
          }
        });
    if (!images.containsKey(defaultJdk)) {
      throw new IllegalArgumentException("No image for the default JDK " + defaultJdk);
    }
    if (images.keySet().stream().noneMatch(jdk -> jdk >= PROXY_MIN_JDK)) {
      throw new IllegalArgumentException(
          "The egress proxy needs an image with JDK " + PROXY_MIN_JDK + " or newer");
    }
    if (!(cpus > 0) || memoryBytes < 64L * 1024 * 1024 || pidsLimit < 64) {
      throw new IllegalArgumentException(
          "Limits too low: cpus=" + cpus + ", memory=" + memoryBytes + ", pids=" + pidsLimit);
    }
    if (timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("Timeout must be positive: " + timeout);
    }
    if (maxConcurrentBuilds < 1) {
      throw new IllegalArgumentException("At least one build must be allowed at a time");
    }
    if (mavenCacheVolume == null || !VOLUME.matcher(mavenCacheVolume).matches()) {
      throw new IllegalArgumentException("Invalid Maven cache volume name: " + mavenCacheVolume);
    }
    if (mavenRepositories.isEmpty()) {
      throw new IllegalArgumentException("At least one Maven repository is needed");
    }
    if (mavenRepositories.stream().map(MavenRepository::id).distinct().count()
        != mavenRepositories.size()) {
      throw new IllegalArgumentException(
          "Maven repository IDs must be distinct: " + mavenRepositories);
    }
    if (!buildsDirectory.isAbsolute()) {
      throw new IllegalArgumentException("Builds directory must be absolute: " + buildsDirectory);
    }
    if (maxOutputBytes < 1 || maxLogBytes < 1) {
      throw new IllegalArgumentException("Output and log limits must be positive");
    }
    if (diskBytes < 64L * 1024 * 1024 || minFreeDiskBytes < 0) {
      throw new IllegalArgumentException(
          "Disk limits out of range: disk=" + diskBytes + ", minFree=" + minFreeDiskBytes);
    }
    if (ownerLabel != null
        && (!LABEL.matcher(ownerLabel).matches()
            || ownerLabel.startsWith(BuildSandbox.LABEL + "="))) {
      throw new IllegalArgumentException("Invalid owner label: " + ownerLabel);
    }
  }

  /**
   * The default settings: JDK 17, 21 and 25 images with 21 as the default, 2 CPUs, 4 GiB of memory,
   * 8 GiB of disk and 30 minutes per build, 4 builds at once, at least 2 GiB left free on Docker's
   * storage, and Maven Central as the only Maven repository.
   *
   * @param buildsDirectory where build outputs are stored
   */
  public static SandboxSettings defaults(Path buildsDirectory) {
    return new SandboxSettings(
        "docker",
        DEFAULT_IMAGES,
        21,
        2,
        4L * 1024 * 1024 * 1024,
        4096,
        Duration.ofMinutes(30),
        4,
        "lantern-maven-cache",
        List.of(MavenRepository.CENTRAL),
        buildsDirectory,
        4L * 1024 * 1024 * 1024,
        16L * 1024 * 1024,
        8L * 1024 * 1024 * 1024,
        2L * 1024 * 1024 * 1024,
        null);
  }

  /** These settings with another build timeout. */
  public SandboxSettings withTimeout(Duration timeout) {
    return new SandboxSettings(
        dockerCommand,
        images,
        defaultJdk,
        cpus,
        memoryBytes,
        pidsLimit,
        timeout,
        maxConcurrentBuilds,
        mavenCacheVolume,
        mavenRepositories,
        buildsDirectory,
        maxOutputBytes,
        maxLogBytes,
        diskBytes,
        minFreeDiskBytes,
        ownerLabel);
  }

  /** These settings with another shared Maven cache volume. */
  public SandboxSettings withMavenCacheVolume(String mavenCacheVolume) {
    return new SandboxSettings(
        dockerCommand,
        images,
        defaultJdk,
        cpus,
        memoryBytes,
        pidsLimit,
        timeout,
        maxConcurrentBuilds,
        mavenCacheVolume,
        mavenRepositories,
        buildsDirectory,
        maxOutputBytes,
        maxLogBytes,
        diskBytes,
        minFreeDiskBytes,
        ownerLabel);
  }

  /** These settings with another output size limit. */
  public SandboxSettings withMaxOutputBytes(long maxOutputBytes) {
    return new SandboxSettings(
        dockerCommand,
        images,
        defaultJdk,
        cpus,
        memoryBytes,
        pidsLimit,
        timeout,
        maxConcurrentBuilds,
        mavenCacheVolume,
        mavenRepositories,
        buildsDirectory,
        maxOutputBytes,
        maxLogBytes,
        diskBytes,
        minFreeDiskBytes,
        ownerLabel);
  }

  /** These settings with other Maven repositories. */
  public SandboxSettings withMavenRepositories(List<MavenRepository> mavenRepositories) {
    return new SandboxSettings(
        dockerCommand,
        images,
        defaultJdk,
        cpus,
        memoryBytes,
        pidsLimit,
        timeout,
        maxConcurrentBuilds,
        mavenCacheVolume,
        mavenRepositories,
        buildsDirectory,
        maxOutputBytes,
        maxLogBytes,
        diskBytes,
        minFreeDiskBytes,
        ownerLabel);
  }

  /** These settings with another per-build disk limit. */
  public SandboxSettings withDiskBytes(long diskBytes) {
    return new SandboxSettings(
        dockerCommand,
        images,
        defaultJdk,
        cpus,
        memoryBytes,
        pidsLimit,
        timeout,
        maxConcurrentBuilds,
        mavenCacheVolume,
        mavenRepositories,
        buildsDirectory,
        maxOutputBytes,
        maxLogBytes,
        diskBytes,
        minFreeDiskBytes,
        ownerLabel);
  }

  /** These settings with another amount of free space to keep on Docker's storage. */
  public SandboxSettings withMinFreeDiskBytes(long minFreeDiskBytes) {
    return new SandboxSettings(
        dockerCommand,
        images,
        defaultJdk,
        cpus,
        memoryBytes,
        pidsLimit,
        timeout,
        maxConcurrentBuilds,
        mavenCacheVolume,
        mavenRepositories,
        buildsDirectory,
        maxOutputBytes,
        maxLogBytes,
        diskBytes,
        minFreeDiskBytes,
        ownerLabel);
  }

  /**
   * These settings with an extra label on every container and network the sandbox creates.
   *
   * @param key the label key, not {@link BuildSandbox#LABEL}
   * @param value the label value
   */
  public SandboxSettings withOwnerLabel(String key, String value) {
    return new SandboxSettings(
        dockerCommand,
        images,
        defaultJdk,
        cpus,
        memoryBytes,
        pidsLimit,
        timeout,
        maxConcurrentBuilds,
        mavenCacheVolume,
        mavenRepositories,
        buildsDirectory,
        maxOutputBytes,
        maxLogBytes,
        diskBytes,
        minFreeDiskBytes,
        key + "=" + value);
  }

  /**
   * The {@code host:port} targets the egress proxy lets builds reach: those of the Maven
   * repositories, each once.
   */
  public List<String> allowedHosts() {
    return mavenRepositories.stream().map(MavenRepository::hostAndPort).distinct().toList();
  }

  /** The image the egress proxy runs in: the one with the newest JDK. */
  String proxyImage() {
    return images.get(Collections.max(images.keySet()));
  }
}

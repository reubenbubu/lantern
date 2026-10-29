package io.lantern.sandbox;

import io.lantern.core.repo.Git;
import io.lantern.core.repo.Repo;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Builds a repository at a commit in an isolated container and stores what the analyzers need:
 * compiled classes, generated sources, the classpath jars, the effective POM, the dependency tree
 * and the module graph. Outputs are stored in {@code <buildsDirectory>/<repo>/<commit>/}, with the
 * result in {@code build.json} and the log in {@code build.log}.
 *
 * <p>Each build gets its own container and its own internal Docker network, which has no route out.
 * The only other container on that network is an egress proxy that reaches the hosts of the
 * configured Maven repositories, or only the configured Maven mirror's, and nothing else. The build
 * container gets the repository's files at the commit (no {@code .git}), no host directory but the
 * shared Maven cache, no capabilities, a user that is not root, and limited CPU, memory, disk,
 * processes and time. All builds share one host directory, bind-mounted, as their local Maven
 * repository.
 *
 * <p>A build that fails, times out or runs out of memory is stored as {@link BuildStatus#FAILED},
 * keeping whatever outputs it left, so the repository can be analyzed in degraded mode. Problems of
 * the sandbox itself, such as Docker being unavailable, throw {@link SandboxException} and store
 * nothing.
 */
public class BuildSandbox {

  /** The name of the build log in a build's directory. */
  public static final String LOG_FILE = "build.log";

  /** The name of the stored result in a build's directory. */
  public static final String RESULT_FILE = "build.json";

  /** The label on every container and network the sandbox creates, for finding leftovers. */
  public static final String LABEL = "io.lantern.sandbox";

  /**
   * The label naming the process that created a container or network (see {@link OwnerProcess}), so
   * that a later build can remove what a process that died left behind (#174).
   */
  public static final String OWNER_LABEL = "io.lantern.sandbox.owner";

  static final String HELP_PLUGIN = "org.apache.maven.plugins:maven-help-plugin:3.5.2";
  static final String DEPENDENCY_PLUGIN = "org.apache.maven.plugins:maven-dependency-plugin:3.10.0";

  private static final String PROXY_ALIAS = "lantern-proxy";

  /** The directory under {@code /lantern} that holds other repositories' artifacts. */
  private static final String UPSTREAM = "upstream";

  private static final int PROXY_PORT = 3128;
  private static final String PROXY_CLASS = EgressProxy.class.getName();
  private static final long PROXY_MEMORY_BYTES = 256L * 1024 * 1024;
  private static final String JDK_HOME = "/opt/java/openjdk";
  private static final Duration DOCKER_TIMEOUT = Duration.ofMinutes(5);
  private static final Duration COPY_TIMEOUT = Duration.ofMinutes(15);
  private static final Duration CLEANUP_TIMEOUT = Duration.ofMinutes(1);
  private static final Duration PROXY_START_TIMEOUT = Duration.ofSeconds(60);
  private static final Duration DISK_CHECK_INTERVAL = Duration.ofSeconds(5);
  private static final Duration HELPER_START_TIMEOUT = Duration.ofSeconds(60);
  private static final long MAX_OUTPUT_ENTRIES = 2_000_000;
  private static final long MAX_DETECT_BYTES = 64L * 1024 * 1024;
  private static final long MAX_TRAILING_BYTES = 1024 * 1024;
  private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");
  // The repository name rule of Repo, so a name cannot lead outside the builds directory.
  private static final Pattern REPO_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
  private static final SecureRandom RANDOM = new SecureRandom();

  /**
   * The user and group builds and the egress proxy run as: not root, and not a user the images
   * define. The shared Maven cache belongs to this user, in the group of the user running Lantern.
   */
  static final String BUILD_USER = "10001:10001";

  /** How long one measurement of a container's disk use may take. */
  private static final Duration MEASURE_TIMEOUT = Duration.ofSeconds(30);

  /** After this many disk checks in a row that could not measure, the container is stopped. */
  private static final int MAX_FAILED_DISK_CHECKS = 6;

  /** The free inodes Docker's storage must keep while a build runs, where it counts inodes. */
  private static final long MIN_FREE_INODES = 100_000;

  /**
   * The mask builds and JDK detection run under, so the directories they add to the shared Maven
   * cache are writable by their group, which the hand-over after the build sets to the group of the
   * user running Lantern, who can then delete them.
   */
  private static final String UMASK = "0002";

  /**
   * The shell command every long-running container, the build, the JDK detection and the helpers
   * (the egress proxy and the disk monitor), runs its command under, with the command as its
   * arguments. It ends when the command ends, or when the container's standard input ends: Lantern
   * holds that open through {@code docker start --attach --interactive} for as long as the
   * container should run, and it ends when that process or Lantern's own process goes away, even
   * when killed. The helpers are created with {@code --rm}, so a build whose cleanup never ran
   * leaves no helper running or behind (#171); the build and detection containers stop, and the
   * next build removes them (#174).
   */
  static final String LIFELINE =
      "exec 3<&0; \"$@\" </dev/null & p=$!; { cat <&3 >/dev/null; kill $p; } & wait $p";

  /** Where the shared Maven cache is mounted in every container that mounts it. */
  private static final String CACHE_MOUNT =
      BuildOutputs.CONTAINER_REPOSITORY.substring(
          0, BuildOutputs.CONTAINER_REPOSITORY.length() - 1);

  private final SandboxSettings settings;
  private final Docker docker;
  private final Semaphore slots;
  private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

  /** This process, as its containers and networks name it, or null if it cannot be told. */
  private final OwnerProcess owner = OwnerProcess.current().orElse(null);

  public BuildSandbox(SandboxSettings settings) {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.docker = new Docker(settings.dockerCommand());
    this.slots = new Semaphore(settings.maxConcurrentBuilds(), true);
  }

  /**
   * Builds the repository at the commit, replacing any stored result for that {@code repo@commit}.
   * Waits while the most builds allowed at once are running.
   *
   * @param repo a registered repository
   * @param commit a full commit hash in the repository, as a snapshot holds it
   * @return the stored result, succeeded or failed
   * @throws IllegalArgumentException if the commit is not a full hash of a commit in the repository
   * @throws SandboxException if the sandbox could not run the build; nothing is stored then
   */
  public BuildResult build(Repo repo, String commit) {
    return build(repo, commit, List.of());
  }

  /**
   * Builds the repository at the commit, as {@link #build(Repo, String)} does, giving the build the
   * artifacts of other repositories' successful builds as a read-only tail of its local Maven
   * repository, so it can use the libraries and parent POMs they build (ADR 0041). They are copied
   * into the build's container, never into the shared Maven cache, so builds of other snapshots
   * cannot pick them up.
   *
   * @param upstream the builds of the repositories this one depends on; when two build the same
   *     coordinates, the first one's are used; failed builds are left out
   */
  public BuildResult build(Repo repo, String commit, List<UpstreamBuild> upstream) {
    Objects.requireNonNull(repo, "repo");
    List<UpstreamBuild> given = List.copyOf(upstream);
    checkCommit(repo, commit);
    Path directory = directory(repo.name(), commit);
    // A lock, not a monitor: a virtual thread blocked in a monitor would pin its carrier.
    ReentrantLock lock =
        locks.computeIfAbsent(repo.name() + "@" + commit, key -> new ReentrantLock());
    try {
      lock.lockInterruptibly();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SandboxException("Interrupted while waiting for another build of the commit", e);
    }
    try {
      slots.acquire();
    } catch (InterruptedException e) {
      lock.unlock();
      Thread.currentThread().interrupt();
      throw new SandboxException("Interrupted while waiting for a build slot", e);
    }
    try {
      removeLeftovers();
      return buildInto(repo, commit, given, directory);
    } finally {
      slots.release();
      lock.unlock();
    }
  }

  /**
   * Removes the containers and networks that sandboxes of processes that have ended left behind,
   * such as a build container and network of a process that was killed, or a helper it had created
   * but not yet started. Only what names its owner (see {@link OwnerProcess}) is removed, and only
   * when that owner ran on this machine's current boot, in this process's PID namespace, as this
   * process's user, and is no longer there, so the builds of another live process are never
   * touched. Best effort: failures are ignored.
   */
  void removeLeftovers() {
    if (owner == null) {
      return;
    }
    try {
      List<String> containers = leftBehind(List.of("ps", "--all"));
      if (!containers.isEmpty()) {
        List<String> rm = new ArrayList<>(List.of("rm", "--force", "--volumes"));
        rm.addAll(containers);
        docker.quietly(CLEANUP_TIMEOUT, rm);
      }
      List<String> networks = leftBehind(List.of("network", "ls"));
      if (!networks.isEmpty()) {
        List<String> rm = new ArrayList<>(List.of("network", "rm"));
        rm.addAll(networks);
        docker.quietly(CLEANUP_TIMEOUT, rm);
      }
    } catch (RuntimeException e) {
      // Best effort; the next build tries again.
    }
  }

  /** The IDs of the containers or networks the listing finds whose owner process has ended. */
  private List<String> leftBehind(List<String> listing) {
    List<String> args = new ArrayList<>(listing);
    args.addAll(
        List.of(
            "--filter",
            "label=" + OWNER_LABEL,
            "--format",
            "{{.ID}} {{.Label \"" + OWNER_LABEL + "\"}}"));
    List<String> ended = new ArrayList<>();
    for (String line : docker.check(CLEANUP_TIMEOUT, args).out().lines().toList()) {
      String[] parts = line.strip().split(" ", 2);
      if (parts.length == 2
          && OwnerProcess.parse(parts[1]).filter(p -> p.endedAsSeenBy(owner)).isPresent()) {
        ended.add(parts[0]);
      }
    }
    return ended;
  }

  /**
   * Whether the repository has a Maven build at the commit: a {@code pom.xml} file at its top. A
   * repository without one, such as a frontend or a repository of deployment files, is not built;
   * the extractors that apply read its files, and that is not degraded mode (design, Repository
   * registry; ADR 0005). {@link #build} still records a failed build for one, if asked.
   *
   * @param repo a registered repository
   * @param commit a commit in the repository
   */
  public static boolean hasMavenBuild(Repo repo, String commit) {
    Objects.requireNonNull(repo, "repo");
    Objects.requireNonNull(commit, "commit");
    Git.Result type = Git.run(repo.path(), "cat-file", "-t", commit + ":pom.xml");
    return type.ok() && type.out().equals("blob");
  }

  /** The stored result of building the repository at the commit, if there is one. */
  public Optional<BuildResult> find(String repo, String commit) {
    Objects.requireNonNull(repo, "repo");
    Objects.requireNonNull(commit, "commit");
    if (!COMMIT.matcher(commit).matches() || !REPO_NAME.matcher(repo).matches()) {
      return Optional.empty();
    }
    Path directory = directory(repo, commit);
    Path file = directory.resolve(RESULT_FILE);
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    try {
      return Optional.of(BuildJson.read(Files.readString(file), directory));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The directory a build's outputs are stored in. */
  Path directory(String repo, String commit) {
    return settings.buildsDirectory().resolve(repo).resolve(commit);
  }

  private static void checkCommit(Repo repo, String commit) {
    Objects.requireNonNull(commit, "commit");
    if (!COMMIT.matcher(commit).matches()
        || !Git.run(repo.path(), "cat-file", "-e", commit + "^{commit}").ok()) {
      throw new IllegalArgumentException(
          "Not a full commit hash in repository " + repo.name() + ": " + commit);
    }
  }

  private BuildResult buildInto(
      Repo repo, String commit, List<UpstreamBuild> upstream, Path directory) {
    Instant started = Instant.now();
    Path partial = null;
    Path staging = null;
    try {
      Files.createDirectories(directory.getParent());
      partial = Files.createTempDirectory(directory.getParent(), "." + commit + ".partial-");
      // Next to the stored outputs, not in the system temporary directory: outputs are moved from
      // here, and a directory move fails across filesystems.
      staging = Files.createTempDirectory(directory.getParent(), "." + commit + ".staging-");
      Run run = new Run(repo, commit, upstream, partial, staging);
      run.execute();
      BuildResult result = run.result(started, directory);
      Files.writeString(partial.resolve(RESULT_FILE), BuildJson.write(result));
      replace(partial, directory);
      partial = null;
      return result;
    } catch (IOException e) {
      throw new SandboxException("Cannot store the build of " + repo.name() + "@" + commit, e);
    } finally {
      deleteQuietly(partial);
      deleteQuietly(staging);
    }
  }

  /** One build: its containers, network, log and outcome. */
  private final class Run {

    private final Repo repo;
    private final String commit;
    private final List<UpstreamBuild> upstream;
    private final Path outputs;
    private final Path staging;
    private final String id = newId();
    private final String network = "lantern-build-net-" + id;
    private final String proxy = "lantern-build-proxy-" + id;
    private final String container = "lantern-build-" + id;
    private final String detector = "lantern-build-jdk-" + id;
    private final String cacheOwner = "lantern-build-cache-" + id;
    private final String monitor = "lantern-build-disk-" + id;
    private final Log log;

    private int jdk;
    private String jdkSource = "not chosen";
    private String image;
    private Integer mavenExitCode;
    private boolean timedOut;
    private boolean outOfMemory;
    private int containerExitCode;
    private String outputProblem;
    private String diskProblem;
    private String failure;
    private boolean cacheHandedOver;
    private List<BuildModule> modules = List.of();

    /** The attached {@code docker start} process of each running helper, by container name. */
    private final Map<String, Process> lifelines = new LinkedHashMap<>();

    /** What the egress proxy writes, read through its attached process, or null before it runs. */
    private Captured proxyOutput;

    private boolean helpersStopped;
    private String effectivePom;
    private boolean hasUpstream;
    private final Set<String> given;
    private final List<String> shadowed = new ArrayList<>();

    Run(Repo repo, String commit, List<UpstreamBuild> upstream, Path outputs, Path staging) {
      this.repo = repo;
      this.commit = commit;
      this.upstream = upstream;
      this.given =
          upstream.stream()
              .map(UpstreamBuild::build)
              .filter(b -> !b.degraded())
              .map(b -> b.repo() + "@" + b.commit())
              .collect(Collectors.toSet());
      this.outputs = outputs;
      this.staging = staging;
      this.log = new Log(outputs.resolve(LOG_FILE), settings.maxLogBytes());
    }

    void execute() throws IOException {
      try (log) {
        log.note("building " + repo.name() + "@" + commit);
        if (!chooseJdk()) {
          log.note("build failed: " + failure);
          return;
        }
        try {
          prepareCache();
          cacheHandedOver = true;
          startMonitor();
          startProxy();
          Path lantern = writeLanternFiles();
          if (jdk == 0) {
            detectWithMaven(lantern);
          }
          if (!stopped()) {
            image = settings.images().get(jdk);
            log.note("JDK " + jdk + " (" + jdkSource + "), image " + image);
            Files.writeString(lantern.resolve("toolchains.xml"), toolchains(jdk));
            List<String> command = new ArrayList<>(List.of("/lantern/build.sh"));
            command.addAll(mavenArguments());
            createContainer(container, image, "sh", command, lantern);
            runBuild();
          }
          // The build container has ended; the proxy and the monitor are not needed to collect.
          stopHelpers();
          if (!stopped()) {
            collect();
          }
          failure = failure();
        } finally {
          // Clean up even when interrupted: clear the flag so the docker calls run, then restore
          // it.
          boolean interrupted = Thread.interrupted();
          stopHelpers();
          docker.quietly(
              CLEANUP_TIMEOUT,
              List.of("rm", "--force", "--volumes", container, detector, cacheOwner));
          docker.quietly(CLEANUP_TIMEOUT, List.of("network", "rm", network));
          if (!interrupted && cacheHandedOver) {
            // What the build wrote to the cache, in whatever modes, stays deletable by the user.
            handOverCache();
          }
          removeLabelled();
          if (interrupted) {
            Thread.currentThread().interrupt();
          }
        }
        if (failure != null) {
          log.note("build failed: " + failure);
        } else {
          log.note("build succeeded");
        }
      }
    }

    BuildResult result(Instant started, Path directory) {
      return new BuildResult(
          repo.name(),
          commit,
          failure == null ? BuildStatus.SUCCEEDED : BuildStatus.FAILED,
          failure,
          jdk,
          jdkSource,
          image,
          mavenExitCode,
          started,
          Duration.between(started, Instant.now()),
          effectivePom,
          modules,
          directory,
          given,
          shadowed);
    }

    /**
     * Chooses the JDK from the repository setting or the top pom.xml, leaving it 0 if neither says.
     * Records a failure and returns false if the build cannot run.
     */
    private boolean chooseJdk() {
      Map<Integer, String> images = settings.images();
      Integer override = repo.settings().jdk();
      if (override != null) {
        jdkSource = "repository setting";
        if (!images.containsKey(override)) {
          failure = "No build image for JDK " + override + "; images exist for " + images.keySet();
          return false;
        }
        jdk = override;
      } else {
        Git.Result pom = Git.run(repo.path(), "show", commit + ":pom.xml");
        if (!pom.ok()) {
          failure = "No pom.xml at the top of the repository";
          return false;
        }
        Optional<JdkDetector.Detection> detected =
            JdkDetector.detect(pom.out().getBytes(StandardCharsets.UTF_8));
        if (detected.isPresent()) {
          jdk = jdkFor(detected.get().version());
          jdkSource =
              "detected "
                  + detected.get().version()
                  + " from "
                  + detected.get().source()
                  + " in pom.xml";
        }
      }
      return true;
    }

    /** The oldest JDK with an image that is at least the wanted one, else the newest there is. */
    private int jdkFor(int wanted) {
      return settings.images().keySet().stream()
          .filter(v -> v >= wanted)
          .findFirst()
          .orElse(Collections.max(settings.images().keySet()));
    }

    /**
     * Asks Maven, in a sandboxed container with the default JDK, for the effective POM of the top
     * module, and detects the JDK from that. This finds versions set in parent POMs outside the
     * repository. Falls back to the default JDK.
     */
    private void detectWithMaven(Path lantern) throws IOException {
      log.note("pom.xml does not name a Java version; asking Maven for the effective POM");
      List<String> command = new ArrayList<>(commonMavenArguments());
      command.add("--non-recursive");
      command.add(HELP_PLUGIN + ":effective-pom");
      command.add("-Doutput=target/lantern/jdk-effective-pom.xml");
      command.addAll(0, List.of("-c", "umask " + UMASK + " && exec mvn \"$@\"", "sh"));
      createContainer(
          detector, settings.images().get(settings.defaultJdk()), "sh", command, lantern);
      ContainerExit exit = runContainer(detector);
      if (exit.timedOut()) {
        timedOut = true;
        return;
      }
      if (exit.diskProblem() != null) {
        diskProblem = exit.diskProblem();
        return;
      }
      Optional<JdkDetector.Detection> detected = Optional.empty();
      if (exit.code() == 0) {
        Path into = Files.createDirectories(staging.resolve("detect"));
        if (copyOut(detector + ":/work/target/lantern", into, MAX_DETECT_BYTES) == null) {
          Path pom = into.resolve("jdk-effective-pom.xml");
          if (Files.isRegularFile(pom, LinkOption.NOFOLLOW_LINKS)) {
            detected = JdkDetector.detect(Files.readAllBytes(pom));
          }
        }
      }
      if (detected.isPresent()) {
        jdk = jdkFor(detected.get().version());
        jdkSource =
            "detected "
                + detected.get().version()
                + " from "
                + detected.get().source()
                + " in the effective POM";
      } else {
        jdk = settings.defaultJdk();
        jdkSource =
            exit.code() == 0
                ? "default; the effective POM does not name a Java version"
                : "default; Maven could not compute the effective POM";
      }
    }

    /**
     * Removes whatever containers and networks with this build's sandbox label are still there,
     * such as one a create that was killed at its timeout made late. Best effort, like all cleanup.
     */
    private void removeLabelled() {
      String filter = "label=" + LABEL + "=" + id;
      try {
        List<String> containers =
            docker
                .check(CLEANUP_TIMEOUT, List.of("ps", "--all", "--quiet", "--filter", filter))
                .out()
                .lines()
                .toList();
        if (!containers.isEmpty()) {
          List<String> rm = new ArrayList<>(List.of("rm", "--force", "--volumes"));
          rm.addAll(containers);
          docker.quietly(CLEANUP_TIMEOUT, rm);
        }
        List<String> networks =
            docker
                .check(CLEANUP_TIMEOUT, List.of("network", "ls", "--quiet", "--filter", filter))
                .out()
                .lines()
                .toList();
        if (!networks.isEmpty()) {
          List<String> rm = new ArrayList<>(List.of("network", "rm"));
          rm.addAll(networks);
          docker.quietly(CLEANUP_TIMEOUT, rm);
        }
      } catch (RuntimeException e) {
        // Cleanup is best effort; leftovers carry the sandbox label.
      }
    }

    /** Whether the build was stopped before it finished: timed out or used too much disk. */
    private boolean stopped() {
      return timedOut || diskProblem != null;
    }

    /**
     * Creates the shared Maven cache directory if it does not exist and hands it to the build user.
     */
    private void prepareCache() {
      Path cache = settings.mavenCacheDirectory();
      try {
        Files.createDirectories(cache);
      } catch (IOException e) {
        throw new SandboxException("Cannot create the Maven cache directory " + cache, e);
      }
      handOverCache();
    }

    /**
     * Gives the build user whatever in the shared Maven cache belongs to someone else, in the group
     * of the user running Lantern, and makes every directory in it writable by that group, so that
     * user can delete what builds wrote (under rootless Docker the build user is not a user of the
     * host). The group is read from a directory Lantern created, as the container sees it. Runs
     * before each build and after each build that was not interrupted. A container runs only this
     * command, as root, with no network and no capabilities but changing owners and modes and
     * reading every directory, and is removed afterwards. If it fails, the build goes ahead and
     * fails on what it cannot write. It only adds to what the build user and the group may do, so
     * it is safe while other builds use the cache.
     */
    private void handOverCache() {
      String[] user = BUILD_USER.split(":");
      Path owner;
      try {
        owner = Files.createDirectories(staging.resolve("owner"));
      } catch (IOException e) {
        log.note("could not hand the Maven cache to the build user: " + e.getMessage());
        return;
      }
      List<String> handover =
          labelled(
              List.of("create", "--name", cacheOwner),
              List.of(
                  // Removed when it ends, also if Lantern is killed meanwhile.
                  "--rm",
                  "--network",
                  "none",
                  "--user",
                  "0:0",
                  "--cap-drop",
                  "ALL",
                  "--cap-add",
                  "CHOWN",
                  "--cap-add",
                  "DAC_READ_SEARCH",
                  "--cap-add",
                  "FOWNER",
                  "--security-opt",
                  "no-new-privileges",
                  "--memory",
                  Long.toString(PROXY_MEMORY_BYTES),
                  "--pids-limit",
                  "64",
                  "--mount",
                  cacheMount(false),
                  "--mount",
                  "type=bind,source=" + owner + ",target=/lantern-owner,readonly",
                  "--entrypoint",
                  "sh",
                  settings.proxyImage(),
                  "-c",
                  // The group of the user running Lantern; a group this user namespace does not
                  // map shows as the overflow group, and then the build user's group is used.
                  "g=$(stat -c %g /lantern-owner) || exit 1; [ \"$g\" != 65534 ] || g=$3;"
                      // Neither find nor chown -h follows symbolic links. chmod is only given
                      // directories, and gives no set-ID bits, so one a build swaps for a link
                      // gains nothing. Files that other builds remove meanwhile are no error.
                      + " { find \"$1\" \\( ! -user \"$2\" -o ! -group \"$g\" \\)"
                      + " -exec chown -h \"$2:$g\" {} +;"
                      + " find \"$1\" -type d ! -perm -070 -exec chmod g+rwx {} +; } 2>&1"
                      + " | grep -v 'No such file or directory' >&2; exit 0",
                  "sh",
                  CACHE_MOUNT,
                  user[0],
                  user[1]));
      try {
        docker.create(DOCKER_TIMEOUT, handover);
        Docker.Result result =
            docker.run(settings.timeout(), List.of("start", "--attach", cacheOwner));
        if (!result.ok() || !result.err().isBlank()) {
          log.note("could not hand the Maven cache to the build user: " + result.err());
        }
      } catch (SandboxException e) {
        if (Thread.currentThread().isInterrupted()) {
          throw e;
        }
        log.note("could not hand the Maven cache to the build user: " + e.getMessage());
      } finally {
        boolean interrupted = Thread.interrupted();
        docker.quietly(CLEANUP_TIMEOUT, List.of("rm", "--force", cacheOwner));
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }

    /** The {@code --mount} value of the shared Maven cache directory. */
    private String cacheMount(boolean readOnly) {
      return "type=bind,source="
          + settings.mavenCacheDirectory()
          + ",target="
          + CACHE_MOUNT
          + (readOnly ? ",readonly" : "");
    }

    /**
     * Starts the container the free space of Docker's storage and of the shared Maven cache is
     * measured in while the build runs: it mounts the cache read only and has no network, so the
     * build cannot reach it. Refuses to build when either is already short of space, which is a
     * problem of the host, not of the build.
     */
    private void startMonitor() {
      docker.create(
          DOCKER_TIMEOUT,
          labelled(
              List.of("create", "--name", monitor),
              List.of(
                  "--interactive",
                  "--rm",
                  "--network",
                  "none",
                  "--user",
                  BUILD_USER,
                  "--cap-drop",
                  "ALL",
                  "--security-opt",
                  "no-new-privileges",
                  "--memory",
                  Long.toString(64L * 1024 * 1024),
                  "--pids-limit",
                  "16",
                  "--log-driver",
                  "none",
                  "--mount",
                  cacheMount(true),
                  "--entrypoint",
                  "sh",
                  settings.proxyImage(),
                  "-c",
                  LIFELINE,
                  "sh",
                  "sleep",
                  "infinity")));
      startHelper(monitor, false);
      List<Storage> storage = storage();
      if (storage == null) {
        throw new SandboxException(
            "Cannot measure the free space of Docker's storage and the Maven cache");
      }
      String problem = storageProblem(storage);
      if (problem != null) {
        throw new SandboxException("Not building: " + problem);
      }
    }

    private void startProxy() throws IOException {
      Path proxyDir = Files.createDirectories(staging.resolve("proxy"));
      String classFile = PROXY_CLASS.replace('.', '/') + ".class";
      Path target = proxyDir.resolve(classFile);
      Files.createDirectories(target.getParent());
      try (InputStream in = EgressProxy.class.getResourceAsStream("/" + classFile)) {
        if (in == null) {
          throw new SandboxException("Cannot find " + classFile);
        }
        Files.copy(in, target);
      }
      permit(proxyDir, false);
      docker.create(
          DOCKER_TIMEOUT, labelled(List.of("network", "create", "--internal"), List.of(network)));
      docker.create(
          DOCKER_TIMEOUT,
          labelled(
              List.of("create", "--name", proxy),
              List.of(
                  "--interactive",
                  "--rm",
                  "--network",
                  "bridge",
                  "--cpus",
                  "1",
                  "--memory",
                  Long.toString(PROXY_MEMORY_BYTES),
                  "--memory-swap",
                  Long.toString(PROXY_MEMORY_BYTES),
                  "--pids-limit",
                  "256",
                  "--cap-drop",
                  "ALL",
                  "--security-opt",
                  "no-new-privileges",
                  "--user",
                  BUILD_USER,
                  // Its output is read through docker start --attach; Docker keeps no copy on disk.
                  "--log-driver",
                  "none",
                  "--entrypoint",
                  "sh",
                  settings.proxyImage(),
                  "-c",
                  LIFELINE,
                  "sh",
                  "java",
                  "-Xmx128m",
                  "-cp",
                  "/proxy",
                  PROXY_CLASS,
                  Integer.toString(PROXY_PORT),
                  String.join(",", settings.allowedHosts()))));
      docker.check(COPY_TIMEOUT, List.of("cp", proxyDir.toString(), proxy + ":/proxy"));
      docker.check(
          DOCKER_TIMEOUT, List.of("network", "connect", "--alias", PROXY_ALIAS, network, proxy));
      // The proxy is removed when it ends, so its output is read as it comes, not from Docker.
      proxyOutput = startHelper(proxy, true);
      Process process = lifelines.get(proxy);
      long deadline = System.nanoTime() + PROXY_START_TIMEOUT.toNanos();
      while (!proxyOutput.text().contains(EgressProxy.READY)) {
        if (System.nanoTime() > deadline || !process.isAlive()) {
          proxyOutput.await(Duration.ofSeconds(5));
          throw new SandboxException("The egress proxy did not start: " + proxyOutput.text());
        }
        sleep(Duration.ofMillis(200));
      }
    }

    /**
     * Writes the files every container gets in {@code /lantern}, and an empty directory the build
     * user may write to for {@code /out}.
     */
    private Path writeLanternFiles() throws IOException {
      Path lantern = Files.createDirectories(staging.resolve("lantern"));
      try (InputStream in = BuildSandbox.class.getResourceAsStream("build.sh")) {
        Files.copy(Objects.requireNonNull(in, "build.sh"), lantern.resolve("build.sh"));
      }
      Files.writeString(
          lantern.resolve("settings.xml"),
          MavenSettings.xml(
              CACHE_MOUNT,
              PROXY_ALIAS,
              PROXY_PORT,
              settings.mavenRepositories(),
              settings.mavenMirror()));
      permit(Files.createDirectories(staging.resolve("empty-out")), true);
      if (!upstream.isEmpty()) {
        // Copied in with the other Lantern files, owned by root, so the build can only read it.
        Path tail = Files.createDirectories(lantern.resolve(UPSTREAM));
        UpstreamRepository.Written written =
            UpstreamRepository.write(upstream, tail, Instant.now());
        written.notes().forEach(log::note);
        hasUpstream = true;
        // Maven looks in the shared cache before the tail, so where the cache already holds an
        // artifact, Maven uses the cache's copy, which may be another build's of the same version.
        Path cache = settings.mavenCacheDirectory();
        written
            .artifacts()
            .forEach(
                (gav, files) -> {
                  if (files.stream().anyMatch(f -> Files.exists(cache.resolve(f)))) {
                    shadowed.add(gav);
                    log.note(
                        "WARNING: the Maven cache already holds "
                            + gav
                            + ", so the build may use the cache's copy instead of this snapshot's");
                  }
                });
      }
      return lantern;
    }

    /**
     * Creates a limited container on the build's internal network with the shared Maven cache, and
     * copies the repository into {@code /work} and the Lantern files into {@code /lantern}. Its
     * command runs under {@link #LIFELINE}, so it stops when Lantern's process ends.
     */
    private void createContainer(
        String name, String image, String entrypoint, List<String> command, Path lantern)
        throws IOException {
      List<String> args =
          new ArrayList<>(
              labelled(
                  List.of("create", "--name", name),
                  List.of(
                      "--network",
                      network,
                      "--cpus",
                      java.math.BigDecimal.valueOf(settings.cpus()).toPlainString(),
                      "--memory",
                      Long.toString(settings.memoryBytes()),
                      "--memory-swap",
                      Long.toString(settings.memoryBytes()),
                      "--pids-limit",
                      Integer.toString(settings.pidsLimit()),
                      "--cap-drop",
                      "ALL",
                      "--security-opt",
                      "no-new-privileges",
                      "--user",
                      BUILD_USER,
                      // Output is read through docker start --attach; Docker keeps no copy on disk.
                      "--log-driver",
                      "none",
                      "--mount",
                      cacheMount(false),
                      // The build user has no home directory in the image.
                      "--env",
                      "HOME=/tmp",
                      "--env",
                      "MAVEN_OPTS=-XX:MaxRAMPercentage=50 -Duser.home=/tmp",
                      "--workdir",
                      "/work",
                      "--interactive",
                      "--entrypoint",
                      "sh",
                      image,
                      "-c",
                      LIFELINE,
                      "sh",
                      entrypoint)));
      args.addAll(command);
      docker.create(DOCKER_TIMEOUT, args);
      copyIn(name);
      permit(lantern, false);
      docker.check(COPY_TIMEOUT, List.of("cp", lantern.toString(), name + ":/lantern"));
      docker.check(
          COPY_TIMEOUT, List.of("cp", staging.resolve("empty-out").toString(), name + ":/out"));
    }

    /**
     * The Docker arguments up to the labels, the labels of this build's containers and networks
     * (the sandbox label, then the owner label if one is set, then the owner process's if it can be
     * told), then the other arguments.
     */
    private List<String> labelled(List<String> head, List<String> rest) {
      List<String> args = new ArrayList<>(head);
      args.addAll(List.of("--label", LABEL + "=" + id));
      if (settings.ownerLabel() != null) {
        args.addAll(List.of("--label", settings.ownerLabel()));
      }
      if (owner != null) {
        args.addAll(List.of("--label", OWNER_LABEL + "=" + owner.label()));
      }
      args.addAll(rest);
      return args;
    }

    /** Options for every Maven run in the sandbox. */
    private List<String> commonMavenArguments() {
      List<String> args =
          new ArrayList<>(
              List.of(
                  "--batch-mode",
                  "--no-transfer-progress",
                  "-Dstyle.color=never",
                  "--settings",
                  "/lantern/settings.xml",
                  "-Daether.syncContext.named.factory=file-lock",
                  "-Daether.syncContext.named.nameMapper=file-gav"));
      if (hasUpstream) {
        // Maven reads artifacts it does not find in the shared cache from here, and writes nothing
        // here or, for what it finds here, into the cache.
        args.add("-Dmaven.repo.local.tail=" + BuildOutputs.CONTAINER_UPSTREAM);
        args.add("-Dmaven.repo.local.tail.ignoreAvailability=true");
      }
      if (!repo.settings().mavenProfiles().isEmpty()) {
        args.add("--activate-profiles=" + String.join(",", repo.settings().mavenProfiles()));
      }
      return args;
    }

    /** The Maven arguments of the build. */
    private List<String> mavenArguments() {
      List<String> args = new ArrayList<>(commonMavenArguments());
      args.addAll(
          List.of(
              "--toolchains",
              "/lantern/toolchains.xml",
              "--fail-at-end",
              "-DskipTests",
              // The build gets no .git, and the build information this plugin records is not
              // needed.
              "-Dmaven.gitcommitid.skip=true"));
      if (!repo.settings().skipModules().isEmpty()) {
        args.add(
            "--projects="
                + repo.settings().skipModules().stream()
                    .map(this::skipSelector)
                    .collect(Collectors.joining(",")));
      }
      args.addAll(
          List.of(
              HELP_PLUGIN + ":effective-pom",
              "-Doutput=target/lantern/effective-pom.xml",
              DEPENDENCY_PLUGIN + ":tree",
              "-DoutputFile=target/lantern/dependency-tree.txt",
              DEPENDENCY_PLUGIN + ":build-classpath",
              "-Dmdep.outputFile=target/lantern/classpath.txt",
              "-DincludeScope=compile",
              "compile"));
      return args;
    }

    /** {@code !path} for a module directory at the commit, {@code !:artifactId} otherwise. */
    private String skipSelector(String module) {
      boolean isPath =
          Git.run(repo.path(), "cat-file", "-e", commit + ":" + module + "/pom.xml").ok();
      return isPath ? "!" + module : "!:" + module;
    }

    /**
     * Streams the repository's files at the commit into the container's {@code /work}, writable by
     * everyone so the build user can build there.
     */
    private void copyIn(String container) {
      ProcessBuilder archive =
          Git.command(
              repo.path(),
              "-c",
              "tar.umask=0",
              "archive",
              "--format=tar",
              "--prefix=work/",
              commit);
      ProcessBuilder copy = docker.builder(List.of("cp", "-", container + ":/"));
      List<Process> pipeline;
      try {
        pipeline = ProcessBuilder.startPipeline(List.of(archive, copy));
      } catch (IOException e) {
        throw new SandboxException("Cannot run git archive | docker cp", e);
      }
      Process git = pipeline.get(0);
      Process cp = pipeline.get(1);
      CompletableFuture<String> gitErr = Docker.capture(git.getErrorStream());
      CompletableFuture<String> cpOut = Docker.capture(cp.getInputStream());
      CompletableFuture<String> cpErr = Docker.capture(cp.getErrorStream());
      int gitExit;
      int cpExit;
      try {
        gitExit = Docker.waitFor(git, COPY_TIMEOUT, "git archive");
        cpExit = Docker.waitFor(cp, COPY_TIMEOUT, "docker cp");
      } finally {
        git.destroyForcibly();
        cp.destroyForcibly();
      }
      if (gitExit != 0 || cpExit != 0) {
        throw new SandboxException(
            "Cannot copy the repository into the build container: "
                + gitErr.join()
                + " "
                + cpOut.join()
                + " "
                + cpErr.join());
      }
    }

    /**
     * How a container run ended.
     *
     * @param diskProblem why the container was stopped for its disk use, or null
     */
    private record ContainerExit(
        boolean timedOut, int code, boolean outOfMemory, String diskProblem) {}

    /**
     * Starts the container, copies its output into the log and waits up to the build timeout,
     * checking its disk use every few seconds and stopping it when it uses too much. Its standard
     * input stays open while it runs, for {@link #LIFELINE}.
     */
    private ContainerExit runContainer(String name) throws IOException {
      ProcessBuilder start =
          docker
              .builder(List.of("start", "--attach", "--interactive", name))
              .redirectErrorStream(true);
      Process process = Docker.start(start);
      try {
        return awaitContainer(name, process);
      } finally {
        try {
          // Ends the container's input, should it still run.
          process.getOutputStream().close();
        } catch (IOException e) {
          // The process has gone.
        }
      }
    }

    private ContainerExit awaitContainer(String name, Process process) throws IOException {
      CompletableFuture<Void> pump =
          CompletableFuture.runAsync(
              () -> log.copy(process.getInputStream()), Thread.ofVirtual()::start);
      try {
        long deadline = System.nanoTime() + settings.timeout().toNanos();
        int failedChecks = 0;
        while (true) {
          long left = deadline - System.nanoTime();
          if (left <= 0) {
            stop(name, process, pump);
            return new ContainerExit(true, -1, false, null);
          }
          if (process.waitFor(
              Math.min(left, DISK_CHECK_INTERVAL.toNanos()), TimeUnit.NANOSECONDS)) {
            break;
          }
          String helper = helperProblem();
          if (helper != null) {
            // A problem of the sandbox, not of the build: store nothing.
            log.note("stopping the container: " + helper);
            stop(name, process, pump);
            throw new SandboxException("Build stopped: " + helper);
          }
          long written = writtenBytes(name);
          List<Storage> storage = storage();
          if (Thread.interrupted()) {
            throw new InterruptedException();
          }
          String low = storageProblem(storage);
          if (low != null) {
            // The host is short of space, and which build used it is unknown: store nothing.
            log.note("stopping the container: " + low);
            stop(name, process, pump);
            throw new SandboxException("Build stopped: " + low);
          }
          failedChecks = written < 0 || storage == null ? failedChecks + 1 : 0;
          String problem = sizeProblem(written);
          if (problem == null && failedChecks >= MAX_FAILED_DISK_CHECKS) {
            problem =
                "The build's disk use could not be measured "
                    + MAX_FAILED_DISK_CHECKS
                    + " times in a row";
          }
          if (problem != null) {
            log.note("stopping the container: " + problem);
            stop(name, process, pump);
            return new ContainerExit(false, -1, false, problem);
          }
        }
      } catch (InterruptedException e) {
        process.destroyForcibly();
        docker.quietly(CLEANUP_TIMEOUT, List.of("kill", name));
        Thread.currentThread().interrupt();
        throw new SandboxException("Interrupted while building " + repo.name(), e);
      }
      pump.join();
      // A helper that ended since the last check may be why the build failed.
      String helper = helperProblem();
      if (helper != null) {
        log.note("the container ended, but " + helper);
        throw new SandboxException("Build stopped: " + helper);
      }
      // A build may write a lot and end between two checks. Free space that cannot be measured
      // here passes: the next build refuses to start if it is short.
      String low = storageProblem(storage());
      if (low != null) {
        log.note("the container ended, but " + low);
        throw new SandboxException("Build stopped: " + low);
      }
      long written = writtenBytes(name);
      if (written < 0) {
        written = writtenBytes(name);
      }
      String problem =
          written < 0 ? "The build's disk use could not be measured" : sizeProblem(written);
      if (problem != null) {
        log.note("the container ended, but " + problem);
        return new ContainerExit(false, -1, false, problem);
      }
      Docker.Result state =
          docker.check(
              DOCKER_TIMEOUT,
              List.of("inspect", "--format", "{{.State.ExitCode}} {{.State.OOMKilled}}", name));
      String[] parts = state.out().split(" ");
      try {
        return new ContainerExit(
            false, Integer.parseInt(parts[0]), parts.length == 2 && parts[1].equals("true"), null);
      } catch (NumberFormatException e) {
        throw new SandboxException("Unexpected docker inspect output: " + state.out());
      }
    }

    /** Kills a running container and waits for its output to end. */
    private void stop(String name, Process process, CompletableFuture<Void> pump)
        throws InterruptedException {
      docker.quietly(CLEANUP_TIMEOUT, List.of("kill", name));
      if (!process.waitFor(CLEANUP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
      }
      pump.join();
    }

    /** Why the container must be stopped for what it wrote, or null; -1 is not measured. */
    private String sizeProblem(long written) {
      if (written > settings.diskBytes()) {
        return "The build used more than " + settings.diskBytes() + " bytes of disk";
      }
      return null;
    }

    /** How many bytes the container has written to its own filesystem, or -1 if unknown. */
    private long writtenBytes(String name) {
      try {
        Docker.Result size =
            docker.run(
                MEASURE_TIMEOUT,
                List.of("container", "inspect", "--size", "--format", "{{.SizeRw}}", name));
        return size.ok() ? Long.parseLong(size.out()) : -1;
      } catch (NumberFormatException | SandboxException e) {
        return -1;
      }
    }

    /**
     * The free space and inodes of Docker's storage (the monitor's own filesystem) and of the
     * filesystem holding the shared Maven cache, in that order, measured in the monitor container,
     * or null if they could not be measured.
     */
    private List<Storage> storage() {
      try {
        Docker.Result df =
            docker.run(
                MEASURE_TIMEOUT,
                List.of(
                    "exec",
                    monitor,
                    "sh",
                    "-c",
                    "df -P -k / \"$1\" && df -P -i / \"$1\"",
                    "sh",
                    CACHE_MOUNT));
        if (!df.ok()) {
          return null;
        }
        // POSIX output: a header and a line per path, for blocks of 1024 bytes and then for
        // inodes. Each line ends with total, used, available, capacity and mount point.
        List<String[]> lines =
            df.out()
                .lines()
                .filter(line -> !line.startsWith("Filesystem"))
                .map(line -> line.trim().split("\\s+"))
                .toList();
        if (lines.size() != 4 || lines.stream().anyMatch(line -> line.length < 6)) {
          return null;
        }
        List<Storage> storage = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
          String[] blocks = lines.get(i);
          String[] inodes = lines.get(i + 2);
          long freeBytes = Long.parseLong(blocks[blocks.length - 3]) * 1024;
          // Filesystems that do not limit inodes report none.
          long freeInodes =
              Long.parseLong(inodes[inodes.length - 5]) == 0
                  ? -1
                  : Long.parseLong(inodes[inodes.length - 3]);
          storage.add(
              new Storage(
                  i == 0 ? "Docker's storage" : "the Maven cache's filesystem",
                  freeBytes,
                  freeInodes));
        }
        return storage;
      } catch (NumberFormatException | SandboxException e) {
        return null;
      }
    }

    /**
     * The free space of a filesystem builds write to.
     *
     * @param name what the filesystem holds, for messages
     * @param freeInodes -1 if the filesystem does not limit inodes
     */
    private record Storage(String name, long freeBytes, long freeInodes) {}

    /** Why no build may run with this little free space, or null, also if it is unknown. */
    private String storageProblem(List<Storage> storage) {
      if (storage == null) {
        return null;
      }
      for (Storage one : storage) {
        if (one.freeBytes() < settings.minFreeDiskBytes()) {
          return one.name()
              + " has "
              + one.freeBytes()
              + " bytes free, less than the "
              + settings.minFreeDiskBytes()
              + " it must keep";
        }
        if (one.freeInodes() >= 0 && one.freeInodes() < MIN_FREE_INODES) {
          return one.name()
              + " has "
              + one.freeInodes()
              + " inodes free, less than the "
              + MIN_FREE_INODES
              + " it must keep";
        }
      }
      return null;
    }

    private void runBuild() throws IOException {
      ContainerExit exit = runContainer(container);
      timedOut = exit.timedOut();
      containerExitCode = exit.code();
      outOfMemory = exit.outOfMemory();
      diskProblem = exit.diskProblem();
    }

    /** Why the build failed, or null if it succeeded. */
    private String failure() {
      if (timedOut) {
        return "The build timed out after " + settings.timeout();
      }
      if (outOfMemory) {
        return "The build ran out of memory (limit " + settings.memoryBytes() + " bytes)";
      }
      if (diskProblem != null) {
        return diskProblem;
      }
      if (outputProblem != null) {
        return "The build output was rejected: " + outputProblem;
      }
      if (mavenExitCode == null) {
        return "Maven did not finish (container exit code " + containerExitCode + ")";
      }
      if (mavenExitCode != 0) {
        return "Maven exited with code " + mavenExitCode;
      }
      if (containerExitCode != 0) {
        return "The build container exited with code " + containerExitCode;
      }
      return null;
    }

    /** Copies {@code /out} out of the container and reads it. */
    private void collect() throws IOException {
      // Extract apart from Lantern's own files, so the build cannot overwrite the log or result.
      Path raw = Files.createDirectories(staging.resolve("out"));
      String problem = copyOut(container + ":/out", raw, settings.maxOutputBytes());
      if (problem != null) {
        if (!problem.isEmpty()) {
          outputProblem = problem;
        }
        return;
      }
      for (String part : List.of(BuildOutputs.WORK, BuildOutputs.LIB)) {
        Path from = raw.resolve(part);
        if (Files.isDirectory(from, LinkOption.NOFOLLOW_LINKS)) {
          Files.move(from, outputs.resolve(part));
        }
      }
      Path exitFile = raw.resolve("maven-exit-code");
      if (Files.isRegularFile(exitFile, LinkOption.NOFOLLOW_LINKS) && Files.size(exitFile) < 32) {
        try {
          mavenExitCode = Integer.parseInt(Files.readString(exitFile).strip());
        } catch (NumberFormatException e) {
          mavenExitCode = null;
        }
      }
      modules = BuildOutputs.modules(outputs);
      effectivePom = BuildOutputs.effectivePom(outputs);
    }

    /**
     * Copies a directory out of a container into a host directory, as a hostile tar stream.
     *
     * @return null if copied, "" if the container has no such directory, or why the content was
     *     rejected
     */
    private String copyOut(String source, Path into, long maxBytes) {
      Process process = Docker.start(docker.builder(List.of("cp", source, "-")));
      CompletableFuture<String> err = Docker.capture(process.getErrorStream());
      TarExtractor extractor = new TarExtractor(maxBytes, MAX_OUTPUT_ENTRIES);
      CompletableFuture<IOException> extraction =
          CompletableFuture.supplyAsync(
              () -> {
                try (InputStream in = process.getInputStream()) {
                  process.getOutputStream().close();
                  extractor.extract(in, into);
                  // Read what docker cp still writes after the end marker, so it exits cleanly.
                  byte[] rest = new byte[8192];
                  long drained = 0;
                  int read;
                  while ((read = in.read(rest)) >= 0) {
                    drained += read;
                    if (drained > MAX_TRAILING_BYTES) {
                      throw new TarExtractor.TarException("Data after the end of the archive");
                    }
                  }
                  return null;
                } catch (IOException e) {
                  return e;
                }
              },
              Thread.ofVirtual()::start);
      IOException problem;
      try {
        problem = extraction.get(COPY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        process.destroyForcibly();
        Thread.currentThread().interrupt();
        throw new SandboxException("Interrupted while copying build output", e);
      } catch (ExecutionException | TimeoutException e) {
        problem = new IOException("copying did not finish in " + COPY_TIMEOUT, e);
      }
      if (problem != null) {
        process.destroyForcibly();
      }
      int exit = Docker.waitFor(process, COPY_TIMEOUT, "docker cp");
      if (problem != null) {
        return problem.getMessage();
      }
      if (exit != 0) {
        log.note("nothing to copy from " + source + ": " + err.join());
        return "";
      }
      return null;
    }

    /**
     * Starts a helper container through {@code docker start --attach --interactive}, whose standard
     * input stays open until {@link #stopHelpers} or the end of Lantern's process (see {@link
     * #LIFELINE}), and waits until it runs.
     *
     * @param keepOutput whether to keep the container's output, standard error included
     * @return the output if kept, else null
     */
    private Captured startHelper(String name, boolean keepOutput) {
      Process process =
          Docker.start(
              docker
                  .builder(List.of("start", "--attach", "--interactive", name))
                  .redirectErrorStream(true)
                  .redirectOutput(
                      keepOutput ? ProcessBuilder.Redirect.PIPE : ProcessBuilder.Redirect.DISCARD));
      lifelines.put(name, process);
      Captured output = keepOutput ? new Captured(process.getInputStream()) : null;
      long deadline = System.nanoTime() + HELPER_START_TIMEOUT.toNanos();
      while (true) {
        Docker.Result state =
            docker.run(DOCKER_TIMEOUT, List.of("inspect", "--format", "{{.State.Running}}", name));
        if (state.out().equals("true")) {
          return output;
        }
        if (!process.isAlive() || System.nanoTime() > deadline) {
          String why = state.err();
          if (output != null) {
            output.await(Duration.ofSeconds(5));
            why = output.text() + " " + why;
          }
          throw new SandboxException("The container " + name + " did not start: " + why);
        }
        sleep(Duration.ofMillis(100));
      }
    }

    /**
     * Copies the egress proxy's log into the build log and removes the proxy and the disk monitor,
     * once: as soon as the build container has ended, or in cleanup. Best effort.
     */
    private void stopHelpers() {
      if (helpersStopped) {
        return;
      }
      helpersStopped = true;
      docker.quietly(CLEANUP_TIMEOUT, List.of("rm", "--force", "--volumes", proxy, monitor));
      for (Process process : lifelines.values()) {
        try {
          // Ends once the removed container's last output is read.
          process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        // Ending their input also ends the helpers, should removing them have failed.
        process.destroyForcibly();
      }
      lifelines.clear();
      if (proxyOutput != null) {
        proxyOutput.await(Duration.ofSeconds(5));
        log.note("egress proxy log:");
        log.write(proxyOutput.text() + "\n");
      }
    }

    /**
     * Why the build cannot go on, if a helper container has ended while it runs: without the proxy
     * the build cannot download, and without the monitor its disk use is not measured. Null if they
     * all run.
     */
    private String helperProblem() {
      for (Map.Entry<String, Process> helper : lifelines.entrySet()) {
        if (!helper.getValue().isAlive()) {
          return "The sandbox's helper container "
              + helper.getKey()
              + " stopped while the build ran";
        }
      }
      return null;
    }
  }

  /** A toolchains file offering the image's JDK, for builds that use the toolchains plugin. */
  static String toolchains(int jdk) {
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <toolchains>
          <toolchain>
            <type>jdk</type>
            <provides>
              <version>%d</version>
            </provides>
            <configuration>
              <jdkHome>%s</jdkHome>
            </configuration>
          </toolchain>
        </toolchains>
        """
        .formatted(jdk, JDK_HOME);
  }

  /** A process's output, read on a virtual thread as it comes; the first megabyte is kept. */
  private static final class Captured {

    private static final int MAX_BYTES = 1024 * 1024;

    private final ByteArrayOutputStream kept = new ByteArrayOutputStream();
    private final Thread reader;

    Captured(InputStream in) {
      reader = Thread.ofVirtual().start(() -> read(in));
    }

    private void read(InputStream in) {
      byte[] buffer = new byte[8192];
      try (in) {
        int read;
        while ((read = in.read(buffer)) >= 0) {
          keep(buffer, read);
        }
      } catch (IOException e) {
        // The process went away.
      }
    }

    private synchronized void keep(byte[] bytes, int length) {
      int room = MAX_BYTES - kept.size();
      if (room > 0) {
        kept.write(bytes, 0, Math.min(room, length));
      }
    }

    /** What was read so far. */
    synchronized String text() {
      return kept.toString(StandardCharsets.UTF_8).strip();
    }

    /** Waits up to the timeout for the output to end. */
    void await(Duration timeout) {
      try {
        reader.join(timeout);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /** The build log, with a size limit; output past the limit is dropped. */
  private static final class Log implements AutoCloseable {

    private final OutputStream out;
    private final long limit;
    private long written;
    private boolean truncated;

    Log(Path file, long limit) {
      try {
        this.out = Files.newOutputStream(file);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      this.limit = limit;
    }

    void note(String message) {
      write("[lantern] " + message + "\n");
    }

    synchronized void write(String text) {
      byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
      write(bytes, bytes.length);
    }

    /** Copies a stream into the log until it ends. */
    void copy(InputStream in) {
      byte[] buffer = new byte[8192];
      try (in) {
        int read;
        while ((read = in.read(buffer)) >= 0) {
          write(buffer, read);
        }
      } catch (IOException e) {
        // The process went away.
      }
    }

    private synchronized void write(byte[] bytes, int length) {
      try {
        long room = limit - written;
        if (room <= 0) {
          if (!truncated) {
            truncated = true;
            out.write("\n[lantern] log truncated\n".getBytes(StandardCharsets.UTF_8));
          }
          return;
        }
        int n = (int) Math.min(room, length);
        out.write(bytes, 0, n);
        written += n;
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @Override
    public synchronized void close() throws IOException {
      out.close();
    }
  }

  /**
   * Makes a directory tree on the host readable, or also writable, by everyone, so the build user
   * can use it once copied into a container, whatever the host's umask.
   */
  private static void permit(Path root, boolean writable) throws IOException {
    Set<PosixFilePermission> directory =
        PosixFilePermissions.fromString(writable ? "rwxrwxrwx" : "rwxr-xr-x");
    Set<PosixFilePermission> file = PosixFilePermissions.fromString("rw-r--r--");
    try (Stream<Path> paths = Files.walk(root)) {
      for (Path path : paths.toList()) {
        Files.setPosixFilePermissions(
            path, Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ? directory : file);
      }
    }
  }

  /** Moves the new outputs into place, replacing any older outputs of the same build. */
  private static void replace(Path partial, Path directory) throws IOException {
    Path old = null;
    if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
      old = directory.resolveSibling("." + directory.getFileName() + ".old-" + System.nanoTime());
      Files.move(directory, old, StandardCopyOption.ATOMIC_MOVE);
    }
    Files.move(partial, directory, StandardCopyOption.ATOMIC_MOVE);
    deleteQuietly(old);
  }

  private static String newId() {
    byte[] bytes = new byte[6];
    RANDOM.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SandboxException("Interrupted", e);
    }
  }

  /** Deletes a file or directory tree without following links; ignores failures. */
  static void deleteQuietly(Path path) {
    if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    try {
      Files.walkFileTree(
          path,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              Files.deleteIfExists(file);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
              try {
                Files.deleteIfExists(dir);
              } catch (DirectoryNotEmptyException | NoSuchFileException ignored) {
                // Left behind; harmless.
              }
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException e) {
      // Best effort.
    }
  }
}

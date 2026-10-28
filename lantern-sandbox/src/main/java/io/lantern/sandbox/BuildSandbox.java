package io.lantern.sandbox;

import io.lantern.core.repo.Git;
import io.lantern.core.repo.Repo;
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
 * The only other container on that network is an egress proxy that tunnels to the allowed hosts
 * (the artifact repository) and nothing else. The build container gets the repository's files at
 * the commit (no {@code .git}), no host directories, no capabilities, a user that is not root, and
 * limited CPU, memory, disk, processes and time. All builds share one Docker volume as their local
 * Maven repository.
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

  static final String HELP_PLUGIN = "org.apache.maven.plugins:maven-help-plugin:3.5.2";
  static final String DEPENDENCY_PLUGIN = "org.apache.maven.plugins:maven-dependency-plugin:3.10.0";

  private static final String PROXY_ALIAS = "lantern-proxy";
  private static final int PROXY_PORT = 3128;
  private static final String PROXY_CLASS = EgressProxy.class.getName();
  private static final long PROXY_MEMORY_BYTES = 256L * 1024 * 1024;
  private static final String JDK_HOME = "/opt/java/openjdk";
  private static final Duration DOCKER_TIMEOUT = Duration.ofMinutes(5);
  private static final Duration COPY_TIMEOUT = Duration.ofMinutes(15);
  private static final Duration CLEANUP_TIMEOUT = Duration.ofMinutes(1);
  private static final Duration PROXY_START_TIMEOUT = Duration.ofSeconds(60);
  private static final Duration DISK_CHECK_INTERVAL = Duration.ofSeconds(5);
  private static final long MAX_OUTPUT_ENTRIES = 2_000_000;
  private static final long MAX_DETECT_BYTES = 64L * 1024 * 1024;
  private static final long MAX_TRAILING_BYTES = 1024 * 1024;
  private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");
  // The repository name rule of Repo, so a name cannot lead outside the builds directory.
  private static final Pattern REPO_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
  private static final SecureRandom RANDOM = new SecureRandom();

  /**
   * The user and group builds and the egress proxy run as: not root, and not a user the images
   * define. The shared Maven cache belongs to this user.
   */
  static final String BUILD_USER = "10001:10001";

  /** The file in the shared Maven cache that says it has been handed to {@link #BUILD_USER}. */
  private static final String CACHE_OWNER_MARKER = ".lantern-owner-10001";

  /** Where the shared Maven cache is mounted in every container that mounts it. */
  private static final String CACHE_MOUNT =
      BuildOutputs.CONTAINER_REPOSITORY.substring(
          0, BuildOutputs.CONTAINER_REPOSITORY.length() - 1);

  private final SandboxSettings settings;
  private final Docker docker;
  private final Semaphore slots;
  private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

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
    Objects.requireNonNull(repo, "repo");
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
      return buildInto(repo, commit, directory);
    } finally {
      slots.release();
      lock.unlock();
    }
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

  private BuildResult buildInto(Repo repo, String commit, Path directory) {
    Instant started = Instant.now();
    Path partial = null;
    Path staging = null;
    try {
      Files.createDirectories(directory.getParent());
      partial = Files.createTempDirectory(directory.getParent(), "." + commit + ".partial-");
      // Next to the stored outputs, not in the system temporary directory: outputs are moved from
      // here, and a directory move fails across filesystems.
      staging = Files.createTempDirectory(directory.getParent(), "." + commit + ".staging-");
      Run run = new Run(repo, commit, partial, staging);
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
    private final Path outputs;
    private final Path staging;
    private final String id = newId();
    private final String network = "lantern-build-net-" + id;
    private final String proxy = "lantern-build-proxy-" + id;
    private final String container = "lantern-build-" + id;
    private final String detector = "lantern-build-jdk-" + id;
    private final String cacheOwner = "lantern-build-cache-" + id;
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
    private List<BuildModule> modules = List.of();
    private String effectivePom;

    Run(Repo repo, String commit, Path outputs, Path staging) {
      this.repo = repo;
      this.commit = commit;
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
          if (!stopped()) {
            collect();
          }
          failure = failure();
        } finally {
          // Clean up even when interrupted: clear the flag so the docker calls run, then restore
          // it.
          boolean interrupted = Thread.interrupted();
          appendProxyLog();
          docker.quietly(
              CLEANUP_TIMEOUT,
              List.of("rm", "--force", "--volumes", container, detector, proxy, cacheOwner));
          docker.quietly(CLEANUP_TIMEOUT, List.of("network", "rm", network));
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
          directory);
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
      createContainer(
          detector, settings.images().get(settings.defaultJdk()), "mvn", command, lantern);
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

    /** Whether the build was stopped before it finished: timed out or used too much disk. */
    private boolean stopped() {
      return timedOut || diskProblem != null;
    }

    /**
     * Creates the shared Maven cache if it does not exist and hands it to the build user. A cache
     * filled by builds that ran as root is handed over once, by a container that runs only this
     * command as root with no network and no capabilities but changing owners and writing the
     * marker. The container is removed with the build's other containers.
     */
    private void prepareCache() {
      docker.check(DOCKER_TIMEOUT, List.of("volume", "create", settings.mavenCacheVolume()));
      docker.check(
          DOCKER_TIMEOUT,
          List.of(
              "run",
              "--name",
              cacheOwner,
              "--label",
              LABEL + "=" + id,
              "--network",
              "none",
              "--user",
              "0:0",
              "--cap-drop",
              "ALL",
              "--cap-add",
              "CHOWN",
              // To write the marker once the cache belongs to the build user.
              "--cap-add",
              "DAC_OVERRIDE",
              "--security-opt",
              "no-new-privileges",
              "--memory",
              Long.toString(PROXY_MEMORY_BYTES),
              "--pids-limit",
              "64",
              "--mount",
              "type=volume,source=" + settings.mavenCacheVolume() + ",target=" + CACHE_MOUNT,
              "--entrypoint",
              "sh",
              settings.proxyImage(),
              "-c",
              "[ -e \"$1/$2\" ] || { chown -R \"$3\" \"$1\" && touch \"$1/$2\"; }",
              "sh",
              CACHE_MOUNT,
              CACHE_OWNER_MARKER,
              BUILD_USER));
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
      docker.check(
          DOCKER_TIMEOUT,
          List.of("network", "create", "--internal", "--label", LABEL + "=" + id, network));
      docker.check(
          DOCKER_TIMEOUT,
          List.of(
              "create",
              "--name",
              proxy,
              "--label",
              LABEL + "=" + id,
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
              // Read-only, to measure the free space of Docker's storage while a build runs.
              "--mount",
              "type=volume,source="
                  + settings.mavenCacheVolume()
                  + ",target="
                  + CACHE_MOUNT
                  + ",readonly",
              "--entrypoint",
              "java",
              settings.proxyImage(),
              "-Xmx128m",
              "-cp",
              "/proxy",
              PROXY_CLASS,
              Integer.toString(PROXY_PORT),
              String.join(",", settings.allowedHosts())));
      docker.check(COPY_TIMEOUT, List.of("cp", proxyDir.toString(), proxy + ":/proxy"));
      docker.check(
          DOCKER_TIMEOUT, List.of("network", "connect", "--alias", PROXY_ALIAS, network, proxy));
      docker.check(DOCKER_TIMEOUT, List.of("start", proxy));
      long deadline = System.nanoTime() + PROXY_START_TIMEOUT.toNanos();
      while (true) {
        Docker.Result logs = docker.run(DOCKER_TIMEOUT, List.of("logs", proxy));
        if (logs.out().contains(EgressProxy.READY)) {
          return;
        }
        Docker.Result state =
            docker.run(DOCKER_TIMEOUT, List.of("inspect", "--format", "{{.State.Running}}", proxy));
        if (System.nanoTime() > deadline || !state.out().equals("true")) {
          throw new SandboxException(
              "The egress proxy did not start: " + logs.out() + " " + logs.err());
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
      for (String resource : List.of("build.sh", "settings.xml")) {
        try (InputStream in = BuildSandbox.class.getResourceAsStream(resource)) {
          Files.copy(Objects.requireNonNull(in, resource), lantern.resolve(resource));
        }
      }
      permit(Files.createDirectories(staging.resolve("empty-out")), true);
      return lantern;
    }

    /**
     * Creates a limited container on the build's internal network with the shared Maven cache, and
     * copies the repository into {@code /work} and the Lantern files into {@code /lantern}.
     */
    private void createContainer(
        String name, String image, String entrypoint, List<String> command, Path lantern)
        throws IOException {
      List<String> args =
          new ArrayList<>(
              List.of(
                  "create",
                  "--name",
                  name,
                  "--label",
                  LABEL + "=" + id,
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
                  "--mount",
                  "type=volume,source=" + settings.mavenCacheVolume() + ",target=" + CACHE_MOUNT,
                  // The build user has no home directory in the image.
                  "--env",
                  "HOME=/tmp",
                  "--env",
                  "MAVEN_OPTS=-XX:MaxRAMPercentage=50 -Duser.home=/tmp",
                  "--workdir",
                  "/work",
                  "--entrypoint",
                  entrypoint,
                  image));
      args.addAll(command);
      docker.check(DOCKER_TIMEOUT, args);
      copyIn(name);
      permit(lantern, false);
      docker.check(COPY_TIMEOUT, List.of("cp", lantern.toString(), name + ":/lantern"));
      docker.check(
          COPY_TIMEOUT, List.of("cp", staging.resolve("empty-out").toString(), name + ":/out"));
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
     * checking its disk use every few seconds and stopping it when it uses too much.
     */
    private ContainerExit runContainer(String name) throws IOException {
      ProcessBuilder start =
          docker.builder(List.of("start", "--attach", name)).redirectErrorStream(true);
      Process process = Docker.start(start);
      process.getOutputStream().close();
      CompletableFuture<Void> pump =
          CompletableFuture.runAsync(
              () -> log.copy(process.getInputStream()), Thread.ofVirtual()::start);
      try {
        long deadline = System.nanoTime() + settings.timeout().toNanos();
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
          String problem = diskProblem(name);
          if (Thread.interrupted()) {
            throw new InterruptedException();
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

    /**
     * Why the container must be stopped for its disk use, or null: it wrote more than the disk
     * limit to its own filesystem, or Docker's storage, which also holds the shared Maven cache,
     * has less free space than it must keep. A measurement that fails is skipped.
     */
    private String diskProblem(String name) {
      try {
        Docker.Result size =
            docker.run(
                DOCKER_TIMEOUT,
                List.of("container", "inspect", "--size", "--format", "{{.SizeRw}}", name));
        if (size.ok() && Long.parseLong(size.out()) > settings.diskBytes()) {
          return "The build used more than " + settings.diskBytes() + " bytes of disk";
        }
        // POSIX output: the last line ends with available 1024-byte blocks, capacity, mount point.
        Docker.Result df =
            docker.run(DOCKER_TIMEOUT, List.of("exec", proxy, "df", "-P", "-k", CACHE_MOUNT));
        if (df.ok()) {
          String[] lines = df.out().split("\n");
          String[] fields = lines[lines.length - 1].trim().split("\\s+");
          long free =
              fields.length < 6 ? Long.MAX_VALUE : Long.parseLong(fields[fields.length - 3]) * 1024;
          if (free < settings.minFreeDiskBytes()) {
            return "Docker's storage has "
                + free
                + " bytes free, less than the "
                + settings.minFreeDiskBytes()
                + " it must keep";
          }
        }
      } catch (NumberFormatException | ArrayIndexOutOfBoundsException | SandboxException e) {
        // Skipped; an interrupt is handled by the caller.
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

    private void appendProxyLog() {
      try {
        Docker.Result logs = docker.run(CLEANUP_TIMEOUT, List.of("logs", proxy));
        if (logs.ok()) {
          log.note("egress proxy log:");
          log.write(logs.out() + "\n");
        }
      } catch (RuntimeException e) {
        // The log is a convenience.
      }
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

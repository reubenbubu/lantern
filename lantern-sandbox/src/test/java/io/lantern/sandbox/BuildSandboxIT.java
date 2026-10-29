package io.lantern.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.lantern.core.repo.Repo;
import io.lantern.core.repo.RepoSettings;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Builds the fixture projects in real build containers. Needs Docker and network access to Maven
 * Central, or to the Maven mirror of {@link TestMirror}, which the builds then download through,
 * except those of the tests of configured repositories, which use Maven Central. Builds use the
 * default shared Maven cache directory, which keeps downloads between runs. The tests that need a
 * cache of their own use one in their temporary directory, and delete it as the user running the
 * tests can; it starts seeded from the shared cache unless the test must see the downloads, so that
 * the tests download little.
 *
 * <p>The tests run at the same time, as many as the failsafe configuration of this module allows,
 * which is the number of builds a sandbox runs at once by default. Each has its own temporary
 * directory, sandbox and owner label.
 */
@Execution(ExecutionMode.CONCURRENT)
class BuildSandboxIT {

  private static final Docker DOCKER = new Docker("docker");
  private static final Duration TIMEOUT = Duration.ofMinutes(2);

  /**
   * A repository on a host the default settings do not allow: Maven Central's other host name, so
   * the tests that configure it download nothing from outside Maven Central.
   */
  private static final MavenRepository EXTRA =
      new MavenRepository("central-repo1", URI.create("https://repo1.maven.org/maven2"));

  /** The owner label key on everything these tests' sandboxes create. */
  private static final String RUN_LABEL = "lantern.test.run";

  /**
   * The owner label value of this test, so its leftover checks see only its own resources and not
   * those of the tests running at the same time or of other sandboxes on the same Docker. JUnit
   * creates an instance per test, so each test has its own.
   */
  private final String run = HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextLong());

  /** The Maven mirror the builds download through, or null for Maven Central. */
  private static final MavenMirror MIRROR = TestMirror.configured();

  private static Path fixtures;

  @TempDir Path temp;

  @BeforeAll
  static void findFixtures() {
    String path = System.getProperty("lantern.test.fixtures");
    if (path == null || path.isBlank()) {
      throw new IllegalStateException(
          "lantern.test.fixtures is not set; run this test through Maven");
    }
    fixtures = Path.of(path);
  }

  /**
   * Checks that the test's sandboxes left no container or network, the cache hand-over containers
   * included: everything a sandbox creates carries the owner label, and the sandbox waits for each
   * create to finish, even when interrupted or timed out, so nothing appears after the build ends.
   */
  @AfterEach
  void nothingLeftRunning() {
    assertThat(
            DOCKER.check(TIMEOUT, List.of("ps", "--all", "--quiet", "--filter", ownFilter())).out())
        .as("sandbox containers left behind")
        .isEmpty();
    assertThat(
            DOCKER
                .check(TIMEOUT, List.of("network", "ls", "--quiet", "--filter", ownFilter()))
                .out())
        .as("sandbox networks left behind")
        .isEmpty();
  }

  /** The Docker filter for the containers and networks of this test's sandboxes. */
  private String ownFilter() {
    return "label=" + RUN_LABEL + "=" + run;
  }

  /**
   * The default settings, with this test's owner label, no free space required on Docker's storage,
   * and the test mirror if there is one.
   */
  private SandboxSettings settings() {
    return SandboxSettings.defaults(temp.resolve("builds"))
        .withOwnerLabel(RUN_LABEL, run)
        .withMinFreeDiskBytes(0)
        .withMavenMirror(MIRROR);
  }

  private Repo repo(Path source, String name, RepoSettings settings) throws IOException {
    return TestRepos.create(source, temp.resolve("repos").resolve(name), name, settings);
  }

  /**
   * Deletes a cache a test created, if it is there, in a {@code finally} block: a failure to delete
   * is printed, not thrown, so it does not hide why the test failed.
   */
  private static void deleteIfExists(Path dir) {
    try {
      if (Files.exists(dir)) {
        deleteRecursively(dir);
      }
    } catch (IOException e) {
      System.err.println("Cannot delete " + dir + ": " + e);
    }
  }

  private static void deleteRecursively(Path dir) throws IOException {
    try (Stream<Path> paths = Files.walk(dir)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    }
  }

  private static Path sandboxFixture(String name) throws URISyntaxException {
    return Path.of(BuildSandboxIT.class.getResource("/sandbox/" + name).toURI());
  }

  @Test
  void buildsMultiModuleFixture() throws IOException {
    Repo repo = repo(fixtures.resolve("multi-module"), "multi-module", RepoSettings.DEFAULT);
    BuildSandbox sandbox = new BuildSandbox(settings());

    BuildResult result = sandbox.build(repo, TestRepos.head(repo));

    assertThat(result.status()).as(log(result)).isEqualTo(BuildStatus.SUCCEEDED);
    assertThat(result.degraded()).isFalse();
    assertThat(result.jdk()).isEqualTo(21);
    assertThat(result.jdkSource()).contains("maven.compiler.release");
    assertThat(result.mavenExitCode()).isZero();
    assertThat(result.modules()).extracting(BuildModule::path).containsExactly("", "api", "core");
    BuildModule core = result.modules().get(2);
    assertThat(core.artifactId()).isEqualTo("shop-core");
    assertThat(core.dependsOn()).containsExactly("api");
    assertThat(core.classpath()).containsExactly("work/api/target/classes");
    assertThat(
            result.resolve(core.classes()).resolve("com/example/shop/core/FixedPriceService.class"))
        .isRegularFile();
    assertThat(result.resolve(core.generatedSources())).isDirectory();
    assertThat(Files.readString(result.resolve(core.dependencyTree())))
        .contains("com.example.shop:shop-api:jar:1.0.0:compile");
    assertThat(Files.readString(result.resolve(result.effectivePom())))
        .contains("<artifactId>shop-core</artifactId>");
    assertThat(Files.readString(result.log())).contains("BUILD SUCCESS");
    assertThat(result.directory())
        .isEqualTo(temp.resolve("builds/multi-module").resolve(result.commit()));
    assertThat(sandbox.find("multi-module", result.commit())).contains(result);
  }

  @Test
  void storesBuildsOnAnotherFilesystemThanTheTemporaryDirectory() throws IOException {
    Path shm = Path.of("/dev/shm");
    Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
    assumeTrue(
        Files.isDirectory(shm)
            && Files.isWritable(shm)
            && !Files.getFileStore(shm).equals(Files.getFileStore(tmp)),
        "needs /dev/shm on another filesystem than " + tmp);
    Path builds = Files.createTempDirectory(shm, "lantern-builds-");
    try {
      Repo repo = repo(fixtures.resolve("multi-module"), "multi-module", RepoSettings.DEFAULT);
      BuildSandbox sandbox =
          new BuildSandbox(
              SandboxSettings.defaults(builds)
                  .withOwnerLabel(RUN_LABEL, run)
                  .withMinFreeDiskBytes(0)
                  .withMavenMirror(MIRROR));

      BuildResult result = sandbox.build(repo, TestRepos.head(repo));

      assertThat(result.status()).as(log(result)).isEqualTo(BuildStatus.SUCCEEDED);
      assertThat(sandbox.find("multi-module", result.commit())).contains(result);
      try (Stream<Path> left = Files.list(builds.resolve("multi-module"))) {
        assertThat(left).as("staging left behind").containsExactly(result.directory());
      }
    } finally {
      deleteRecursively(builds);
    }
  }

  @Test
  void buildsLombokFixtureWithItsClasspath() throws IOException {
    Repo repo = repo(fixtures.resolve("lombok"), "lombok", RepoSettings.DEFAULT);

    BuildResult result = new BuildSandbox(settings()).build(repo, TestRepos.head(repo));

    assertThat(result.status()).as(log(result)).isEqualTo(BuildStatus.SUCCEEDED);
    BuildModule module = result.modules().getFirst();
    assertThat(
            result.resolve(module.classes()).resolve("com/example/orders/Order$OrderBuilder.class"))
        .isRegularFile();
    String lombok = "lib/org/projectlombok/lombok/1.18.48/lombok-1.18.48.jar";
    assertThat(module.classpath()).containsExactly(lombok);
    assertThat(result.resolve(lombok)).isRegularFile();
  }

  @Test
  void buildsTheServiceWithTheArtifactsOfTheLibrarysBuild() throws IOException {
    Path source = fixtures.resolve("lib-and-service");
    Repo lib = repo(source.resolve("lib"), "greeting-lib", RepoSettings.DEFAULT);
    Repo service = repo(source.resolve("service"), "greeting-service", RepoSettings.DEFAULT);
    // A cache of its own, to check the library never gets into it. It starts seeded from the shared
    // cache, without the library, so the builds download little.
    Path cache = seededCache("lib-cache");
    try {
      assertThat(cache.resolve("com/example/greeting")).doesNotExist();
      BuildSandbox sandbox = new BuildSandbox(settings().withMavenCacheDirectory(cache));

      BuildResult libResult = sandbox.build(lib, TestRepos.head(lib));
      assertThat(libResult.status()).as(log(libResult)).isEqualTo(BuildStatus.SUCCEEDED);

      // The sandbox only compiles, so the library is not installed anywhere: alone, the service
      // fails...
      BuildResult missing = sandbox.build(service, TestRepos.head(service));
      assertThat(missing.status()).isEqualTo(BuildStatus.FAILED);
      assertThat(missing.degraded()).isTrue();
      assertThat(Files.readString(missing.log())).contains("greeting-lib");
      // Its dependencies did not resolve, but Maven still wrote the effective POM.
      assertThat(missing.modules()).isEmpty();
      assertThat(Files.readString(missing.resolve(missing.effectivePom())))
          .contains("<artifactId>greeting-service</artifactId>");

      // ...but given the library's build, it builds, the library's jar on its classpath...
      BuildResult built =
          sandbox.build(
              service,
              TestRepos.head(service),
              List.of(new UpstreamBuild(lib, libResult), new UpstreamBuild(service, missing)));
      assertThat(built.status()).as(log(built)).isEqualTo(BuildStatus.SUCCEEDED);
      String jar = "lib/com/example/greeting/greeting-lib/1.0.0/greeting-lib-1.0.0.jar";
      assertThat(built.modules().getFirst().classpath()).contains(jar);
      assertThat(built.resolve(jar)).isRegularFile();
      try (JarFile file = new JarFile(built.resolve(jar).toFile())) {
        assertThat(file.getEntry("com/example/greeting/lib/Greeter.class")).isNotNull();
      }
      assertThat(Files.readString(built.log()))
          .contains("artifacts of greeting-lib@" + libResult.commit())
          .contains("not using the artifacts of greeting-service@" + missing.commit());
      assertThat(sandbox.find("greeting-service", built.commit())).contains(built);
      // ...while the library never gets into the Maven cache, where other builds could use it.
      // (The service's build alone left only notes there that the download failed.)
      try (Stream<Path> files = Files.walk(cache.resolve("com/example/greeting"))) {
        assertThat(files.filter(Files::isRegularFile).map(p -> p.getFileName().toString()))
            .isNotEmpty()
            .allMatch(name -> name.endsWith(".lastUpdated"), "only download failure notes");
      }
    } finally {
      deleteIfExists(cache);
    }
  }

  @Test
  void buildsARepositoryOnAnotherOnesUnpublishedParentAndSnapshotLibrary()
      throws IOException, URISyntaxException {
    Repo shared = repo(sandboxFixture("shared"), "shared", RepoSettings.DEFAULT);
    Repo consumer = repo(sandboxFixture("consumer"), "consumer", RepoSettings.DEFAULT);
    Map<String, Repo> repos = Map.of("consumer", consumer, "shared", shared);
    BuildOrder order =
        BuildOrder.of(
            repos, Map.of("consumer", TestRepos.head(consumer), "shared", TestRepos.head(shared)));
    assertThat(order.order()).containsExactly("shared", "consumer");
    assertThat(order.upstream("consumer")).containsExactly("shared");
    // A cache of its own, seeded without the shared repository's artifacts, to check they never
    // get into it.
    Path cache = seededCache("shared-cache");
    try {
      BuildSandbox sandbox = new BuildSandbox(settings().withMavenCacheDirectory(cache));

      BuildResult sharedResult = sandbox.build(shared, TestRepos.head(shared));
      assertThat(sharedResult.status()).as(log(sharedResult)).isEqualTo(BuildStatus.SUCCEEDED);
      BuildResult built =
          sandbox.build(
              consumer, TestRepos.head(consumer), List.of(new UpstreamBuild(shared, sharedResult)));

      assertThat(built.status()).as(log(built)).isEqualTo(BuildStatus.SUCCEEDED);
      assertThat(built.modules().getFirst().classpath())
          .containsExactly(
              "lib/com/example/shared/shared-lib/1.0.0-SNAPSHOT/shared-lib-1.0.0-SNAPSHOT.jar");
      assertThat(built.upstream()).containsExactly("shared@" + sharedResult.commit());
      assertThat(built.shadowed()).isEmpty();
      assertThat(cache.resolve("com/example/shared")).doesNotExist();

      // Maven looks in the cache first: where it already holds one of those artifacts, here an
      // empty jar of the same snapshot, the build may use that copy, and says so.
      Path stale =
          cache.resolve(
              "com/example/shared/shared-lib/1.0.0-SNAPSHOT/shared-lib-1.0.0-SNAPSHOT.jar");
      Files.createDirectories(stale.getParent());
      new JarOutputStream(Files.newOutputStream(stale)).close();
      BuildResult shadowed =
          sandbox.build(
              consumer, TestRepos.head(consumer), List.of(new UpstreamBuild(shared, sharedResult)));
      assertThat(shadowed.shadowed())
          .containsExactly("com.example.shared:shared-lib:1.0.0-SNAPSHOT");
      assertThat(Files.readString(shadowed.log()))
          .contains("WARNING: the Maven cache already holds com.example.shared:shared-lib");
    } finally {
      deleteIfExists(cache);
    }
  }

  @Test
  void recordsACompileFailureWithWhatResolved() throws IOException, URISyntaxException {
    Repo repo = repo(sandboxFixture("broken"), "broken", RepoSettings.DEFAULT);
    BuildSandbox sandbox = new BuildSandbox(settings());

    BuildResult result = sandbox.build(repo, TestRepos.head(repo));

    assertThat(result.status()).isEqualTo(BuildStatus.FAILED);
    assertThat(result.degraded()).isTrue();
    assertThat(result.failure()).isEqualTo("Maven exited with code 1");
    assertThat(result.mavenExitCode()).isEqualTo(1);
    assertThat(Files.readString(result.log())).contains("COMPILATION ERROR");
    // Degraded-mode analysis still gets the classpath that resolved.
    String slf4j = "lib/org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar";
    assertThat(result.modules().getFirst().classpath()).containsExactly(slf4j);
    assertThat(result.resolve(slf4j)).isRegularFile();
    assertThat(sandbox.find("broken", result.commit())).contains(result);
  }

  @Test
  void recordsATimeout() throws IOException, URISyntaxException, InterruptedException {
    Repo repo = repo(sandboxFixture("broken"), "slow", RepoSettings.DEFAULT);

    BuildResult result =
        new BuildSandbox(settings().withTimeout(Duration.ofSeconds(1)))
            .build(repo, TestRepos.head(repo));

    assertThat(result.status()).isEqualTo(BuildStatus.FAILED);
    assertThat(result.failure()).isEqualTo("The build timed out after PT1S");
    assertThat(result.mavenExitCode()).isNull();
    // A create that the timeout cut short could still be completed by the daemon a little later
    // (#144); wait a moment so the leftover check after the test would see it.
    Thread.sleep(Duration.ofSeconds(5));
    assertThat(
            DOCKER.check(TIMEOUT, List.of("ps", "--all", "--quiet", "--filter", ownFilter())).out())
        .as("containers left behind after a timed-out build")
        .isEmpty();
    assertThat(result.modules()).isEmpty();
  }

  @Test
  void rejectsOutputOverTheLimit() throws IOException {
    Repo repo = repo(fixtures.resolve("lombok"), "lombok", RepoSettings.DEFAULT);

    BuildResult result =
        new BuildSandbox(settings().withMaxOutputBytes(100_000)).build(repo, TestRepos.head(repo));

    assertThat(result.status()).isEqualTo(BuildStatus.FAILED);
    assertThat(result.failure()).startsWith("The build output was rejected: ");
    assertThat(result.modules()).isEmpty();
    try (var files = Files.list(result.directory())) {
      assertThat(files.map(p -> p.getFileName().toString()))
          .containsExactlyInAnyOrder(BuildSandbox.LOG_FILE, BuildSandbox.RESULT_FILE);
    }
  }

  @Test
  void stopsABuildThatUsesTooMuchDisk() throws IOException, URISyntaxException {
    Repo repo = repo(sandboxFixture("filler"), "filler", RepoSettings.DEFAULT);

    BuildResult result =
        new BuildSandbox(
                settings().withDiskBytes(256L * 1024 * 1024).withTimeout(Duration.ofMinutes(10)))
            .build(repo, TestRepos.head(repo));

    assertThat(result.status()).as(log(result)).isEqualTo(BuildStatus.FAILED);
    assertThat(result.degraded()).isTrue();
    assertThat(result.failure()).isEqualTo("The build used more than 268435456 bytes of disk");
    assertThat(result.mavenExitCode()).isNull();
    assertThat(result.modules()).isEmpty();
    assertThat(Files.readString(result.log())).contains("filler wrote");
    assertThat(new BuildSandbox(settings()).find("filler", result.commit())).contains(result);
  }

  @Test
  void refusesToBuildWhenDockerStorageIsLow() throws IOException, URISyntaxException {
    Repo repo = repo(sandboxFixture("filler"), "filler", RepoSettings.DEFAULT);
    BuildSandbox sandbox = new BuildSandbox(settings().withMinFreeDiskBytes(Long.MAX_VALUE));

    // A problem of the host, not of the build: nothing is stored.
    assertThatThrownBy(() -> sandbox.build(repo, TestRepos.head(repo)))
        .isInstanceOf(SandboxException.class)
        .hasMessageStartingWith("Not building: Docker's storage has ")
        .hasMessageEndingWith(" bytes free, less than the " + Long.MAX_VALUE + " it must keep");
    assertThat(sandbox.find("filler", TestRepos.head(repo))).isEmpty();
  }

  @Test
  void givesTheCacheToTheBuildUserAndKeepsItDeletable() throws IOException {
    // A cache of its own, seeded from the shared cache, so the builds download little. That the
    // sandbox creates a cache that does not exist yet shows in a test of configured repositories.
    Path cache = seededCache("own-cache");
    String mount = "type=bind,source=" + cache + ",target=/m2";
    String image = SandboxSettings.DEFAULT_IMAGES.get(21);
    try {
      Repo repo = repo(fixtures.resolve("lombok"), "lombok", RepoSettings.DEFAULT);
      BuildSandbox sandbox = new BuildSandbox(settings().withMavenCacheDirectory(cache));
      BuildResult first = sandbox.build(repo, TestRepos.head(repo));
      assertThat(first.status()).as(log(first)).isEqualTo(BuildStatus.SUCCEEDED);
      // As root in a container, as an older Lantern that built as root left a cache, and in modes
      // that would keep the user running Lantern from deleting it.
      DOCKER.check(
          TIMEOUT,
          List.of(
              "run",
              "--rm",
              "--user",
              "0:0",
              "--mount",
              mount,
              "--entrypoint",
              "sh",
              image,
              "-c",
              "mkdir -p /m2/old && touch /m2/old/artifact.jar && chmod 700 /m2/old"
                  + " && chmod 700 /m2/org/projectlombok"));

      BuildResult second = sandbox.build(repo, TestRepos.head(repo));

      assertThat(second.status()).as(log(second)).isEqualTo(BuildStatus.SUCCEEDED);
      Docker.Result owners =
          DOCKER.check(
              TIMEOUT,
              List.of(
                  "run",
                  "--rm",
                  "--mount",
                  mount,
                  "--entrypoint",
                  "stat",
                  image,
                  "-c",
                  "%u",
                  "/m2",
                  "/m2/old",
                  "/m2/old/artifact.jar",
                  "/m2/org/projectlombok"));
      assertThat(owners.out().lines())
          .hasSize(4)
          .containsOnly(BuildSandbox.BUILD_USER.split(":")[0]);
      // Every directory is in the group of the user running the tests, who may write to it.
      Object group = Files.getAttribute(temp, "unix:gid");
      try (Stream<Path> paths = Files.walk(cache)) {
        for (Path directory : paths.filter(Files::isDirectory).toList()) {
          assertThat(Files.getAttribute(directory, "unix:gid"))
              .as("%s", directory)
              .isEqualTo(group);
          assertThat(Files.getPosixFilePermissions(directory))
              .as("%s", directory)
              .contains(
                  PosixFilePermission.GROUP_READ,
                  PosixFilePermission.GROUP_WRITE,
                  PosixFilePermission.GROUP_EXECUTE);
        }
      }
      deleteRecursively(cache);
      assertThat(cache).doesNotExist();
    } finally {
      deleteIfExists(cache);
    }
  }

  @Test
  void cleansUpWhenInterrupted() throws Exception {
    Repo repo = repo(sandboxFixture("broken"), "interrupted", RepoSettings.DEFAULT);
    BuildSandbox sandbox = new BuildSandbox(settings());
    AtomicReference<Throwable> thrown = new AtomicReference<>();
    Thread build =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    sandbox.build(repo, TestRepos.head(repo));
                  } catch (Throwable t) {
                    thrown.set(t);
                  }
                });
    long deadline = System.nanoTime() + Duration.ofMinutes(1).toNanos();
    while (DOCKER
        .check(
            TIMEOUT,
            List.of(
                "ps",
                "--quiet",
                "--filter",
                ownFilter(),
                "--filter",
                "name=lantern-build-[0-9a-f]+$"))
        .out()
        .isEmpty()) {
      assertThat(System.nanoTime()).as("build container never started").isLessThan(deadline);
      Thread.sleep(100);
    }
    build.interrupt();
    build.join(Duration.ofMinutes(2));

    assertThat(build.isAlive()).isFalse();
    assertThat(thrown.get()).isInstanceOf(SandboxException.class);
    assertThat(sandbox.find("interrupted", TestRepos.head(repo))).isEmpty();
    // Nothing a create cut short by the interrupt appears later (#98).
    Thread.sleep(Duration.ofSeconds(5));
    assertThat(
            DOCKER.check(TIMEOUT, List.of("ps", "--all", "--quiet", "--filter", ownFilter())).out())
        .as("containers left behind after an interrupted build")
        .isEmpty();
  }

  @Test
  void whatAKilledProcessLeavesEndsAndIsRemovedByTheNextBuild() throws Exception {
    Repo repo = repo(sandboxFixture("sleeper"), "killed", RepoSettings.DEFAULT);
    List<String> command =
        new ArrayList<>(
            List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir")));
    String properties = System.getProperty("lantern.test.properties");
    if (properties != null && !properties.isBlank()) {
      command.add("-Dlantern.test.properties=" + properties);
    }
    command.addAll(
        List.of(
            SandboxProcess.class.getName(),
            temp.resolve("builds").toString(),
            repo.name(),
            repo.path().toString(),
            TestRepos.head(repo),
            RUN_LABEL,
            run));
    Process child =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(temp.resolve("child.log").toFile())
            .start();
    try {
      awaitBuildContainer();
      assertThat(ownContainers("name=lantern-build-(proxy|disk)-")).isNotEmpty();
      // The build sleeps for ten minutes, so the process is still building.
      assertThat(child.isAlive())
          .as("the build process ended: " + Files.readString(temp.resolve("child.log")))
          .isTrue();
      // As a task timeout or a killed test fork would: no cleanup runs in the process.
      child.destroyForcibly().waitFor();

      long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      while (!ownContainers("name=lantern-build-(proxy|disk)-").isEmpty()) {
        assertThat(System.nanoTime())
            .as("the proxy and the disk monitor outlived the killed process")
            .isLessThan(deadline);
        Thread.sleep(200);
      }
      // The build sleeps for ten minutes, unless its input ends with the process (#174). A build
      // of a test running at the same time may already have removed it.
      while (!ownRunningContainers().isEmpty()) {
        assertThat(System.nanoTime())
            .as("the build container outlived the killed process")
            .isLessThan(deadline);
        Thread.sleep(200);
      }

      // The next build of any sandbox of this user removes what the killed process left.
      new BuildSandbox(settings()).removeLeftovers();

      assertThat(ownContainers("label=" + RUN_LABEL)).as("containers left behind").isEmpty();
      assertThat(ownNetworks()).as("networks left behind").isEmpty();
    } finally {
      child.destroyForcibly().waitFor();
      removeOwn();
    }
  }

  @Test
  void removesOnlyWhatProcessesThatEndedLeft() throws Exception {
    OwnerProcess self = OwnerProcess.current().orElse(null);
    assumeTrue(self != null, "needs Linux's /proc");
    Process child = new ProcessBuilder("sleep", "60").start();
    OwnerProcess ended;
    try {
      ended = OwnerProcess.of(child.pid()).orElseThrow();
    } finally {
      child.destroyForcibly().waitFor();
    }
    OwnerProcess elsewhere =
        new OwnerProcess(
            "00000000-0000-0000-0000-000000000000",
            ended.pidNamespace(),
            ended.uid(),
            ended.pid(),
            ended.startTicks());
    try {
      // As a helper a killed process created but never started: it stays in state Created.
      String created = createOwned("created", ended);
      String network = createOwnedNetwork("net", ended);
      String live = createOwned("live", self);
      String foreign = createOwned("foreign", elsewhere);

      new BuildSandbox(settings()).removeLeftovers();

      String left = ownContainers("label=" + RUN_LABEL);
      assertThat(left).doesNotContain(created.substring(0, 12));
      assertThat(left).contains(live.substring(0, 12)).contains(foreign.substring(0, 12));
      assertThat(ownNetworks()).doesNotContain(network.substring(0, 12));
    } finally {
      removeOwn();
    }
  }

  /** Creates a container of this test, not started, naming the process as its owner. */
  private String createOwned(String purpose, OwnerProcess owner) {
    return DOCKER
        .check(
            TIMEOUT,
            List.of(
                "create",
                "--name",
                "lantern-build-test-" + purpose + "-" + run,
                "--label",
                RUN_LABEL + "=" + run,
                "--label",
                BuildSandbox.OWNER_LABEL + "=" + owner.label(),
                "--network",
                "none",
                settings().proxyImage(),
                "true"))
        .out();
  }

  /** Creates a network of this test, naming the process as its owner. */
  private String createOwnedNetwork(String purpose, OwnerProcess owner) {
    return DOCKER
        .check(
            TIMEOUT,
            List.of(
                "network",
                "create",
                "--internal",
                "--label",
                RUN_LABEL + "=" + run,
                "--label",
                BuildSandbox.OWNER_LABEL + "=" + owner.label(),
                "lantern-build-test-" + purpose + "-" + run))
        .out();
  }

  /** The IDs of this test's running containers. */
  private String ownRunningContainers() {
    return DOCKER.check(TIMEOUT, List.of("ps", "--quiet", "--filter", ownFilter())).out();
  }

  /** The IDs of this test's networks. */
  private String ownNetworks() {
    return DOCKER
        .check(TIMEOUT, List.of("network", "ls", "--quiet", "--filter", ownFilter()))
        .out();
  }

  @Test
  void stopsTheBuildWhenAHelperStops() throws Exception {
    Repo repo = repo(sandboxFixture("sleeper"), "orphaned", RepoSettings.DEFAULT);
    BuildSandbox sandbox = new BuildSandbox(settings());
    AtomicReference<Throwable> thrown = new AtomicReference<>();
    Thread build =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    sandbox.build(repo, TestRepos.head(repo));
                  } catch (Throwable t) {
                    thrown.set(t);
                  }
                });
    awaitBuildContainer();
    String proxy = ownContainers("name=lantern-build-proxy-");
    assertThat(proxy).isNotEmpty();
    DOCKER.check(TIMEOUT, List.of("rm", "--force", proxy));
    build.join(Duration.ofMinutes(2));

    assertThat(build.isAlive()).as("the build went on without its proxy").isFalse();
    assertThat(thrown.get())
        .isInstanceOf(SandboxException.class)
        .hasMessageContaining("lantern-build-proxy-")
        .hasMessageContaining("stopped while the build ran");
    assertThat(sandbox.find("orphaned", TestRepos.head(repo))).isEmpty();
  }

  /** Waits until a build container of this test runs. */
  private void awaitBuildContainer() throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofMinutes(1).toNanos();
    while (DOCKER
        .check(
            TIMEOUT,
            List.of(
                "ps",
                "--quiet",
                "--filter",
                ownFilter(),
                "--filter",
                "name=lantern-build-[0-9a-f]+$"))
        .out()
        .isEmpty()) {
      assertThat(System.nanoTime()).as("build container never started").isLessThan(deadline);
      Thread.sleep(100);
    }
  }

  /** The IDs of this test's containers, running or not, that also match the filter. */
  private String ownContainers(String filter) {
    return DOCKER
        .check(
            TIMEOUT, List.of("ps", "--all", "--quiet", "--filter", ownFilter(), "--filter", filter))
        .out();
  }

  /** Removes this test's containers and networks, by its owner label. */
  private void removeOwn() {
    List<String> containers = ownContainers("label=" + RUN_LABEL).lines().toList();
    if (!containers.isEmpty()) {
      List<String> rm = new ArrayList<>(List.of("rm", "--force", "--volumes"));
      rm.addAll(containers);
      DOCKER.quietly(TIMEOUT, rm);
    }
    List<String> networks =
        DOCKER
            .check(TIMEOUT, List.of("network", "ls", "--quiet", "--filter", ownFilter()))
            .out()
            .lines()
            .toList();
    if (!networks.isEmpty()) {
      List<String> rm = new ArrayList<>(List.of("network", "rm"));
      rm.addAll(networks);
      DOCKER.quietly(TIMEOUT, rm);
    }
  }

  @Test
  void usesTheJdkOverride() throws IOException {
    Repo repo =
        repo(
            fixtures.resolve("multi-module"),
            "multi-module",
            new RepoSettings(25, List.of(), List.of()));

    BuildResult result = new BuildSandbox(settings()).build(repo, TestRepos.head(repo));

    assertThat(result.status()).as(log(result)).isEqualTo(BuildStatus.SUCCEEDED);
    assertThat(result.jdk()).isEqualTo(25);
    assertThat(result.jdkSource()).isEqualTo("repository setting");
    assertThat(result.image()).isEqualTo(SandboxSettings.DEFAULT_IMAGES.get(25));
    assertThat(Files.readString(result.log())).contains("Java version: 25");
  }

  @Test
  void failsWithoutAnImageForTheOverride() throws IOException {
    Repo repo =
        repo(fixtures.resolve("lombok"), "lombok", new RepoSettings(11, List.of(), List.of()));

    BuildResult result = new BuildSandbox(settings()).build(repo, TestRepos.head(repo));

    assertThat(result.status()).isEqualTo(BuildStatus.FAILED);
    assertThat(result.failure())
        .isEqualTo("No build image for JDK 11; images exist for [17, 21, 25]");
    assertThat(result.image()).isNull();
  }

  @Test
  void failsWithoutPom() throws IOException {
    Path source = Files.createDirectories(temp.resolve("no-pom"));
    Files.writeString(source.resolve("README.md"), "Not a Maven project\n");
    Repo repo = repo(source, "no-pom", RepoSettings.DEFAULT);

    BuildResult result = new BuildSandbox(settings()).build(repo, TestRepos.head(repo));

    assertThat(result.status()).isEqualTo(BuildStatus.FAILED);
    assertThat(result.failure()).isEqualTo("No pom.xml at the top of the repository");
  }

  @Test
  void refusesUnknownCommits() throws IOException {
    Repo repo = repo(fixtures.resolve("lombok"), "lombok", RepoSettings.DEFAULT);
    BuildSandbox sandbox = new BuildSandbox(settings());

    assertThatThrownBy(() -> sandbox.build(repo, "0".repeat(40)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> sandbox.build(repo, "HEAD"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(sandbox.find("lombok", "0".repeat(40))).isEmpty();
  }

  @Test
  void buildCannotLeaveTheSandbox() throws IOException, URISyntaxException {
    Repo repo = repo(sandboxFixture("probe"), "probe", RepoSettings.DEFAULT);
    SandboxSettings settings = settings();

    BuildResult result = new BuildSandbox(settings).build(repo, TestRepos.head(repo));

    assertThat(result.status()).as(log(result)).isEqualTo(BuildStatus.SUCCEEDED);
    assertThat(result.jdk()).isEqualTo(17);
    assertThat(Files.readString(result.log())).contains("Java version: 17");
    Properties probe = new Properties();
    try (InputStream in =
        Files.newInputStream(
            result.resolve(result.modules().getFirst().classes()).resolve("probe.properties"))) {
      probe.load(in);
    }
    // No route out except the proxy, and the proxy only reaches the artifact repository.
    assertThat(probe.getProperty("direct.name")).isEqualTo("blocked");
    assertThat(probe.getProperty("direct.address")).isEqualTo("blocked");
    assertThat(probe.getProperty("dns")).isEqualTo("failed");
    assertThat(probe.getProperty("metadata")).isEqualTo("blocked");
    assertThat(probe.getProperty("gateway")).isIn("none", "blocked");
    // By default only Maven Central; with a mirror, not even Maven Central.
    assertThat(probe.getProperty("proxy.allowed")).isEqualTo(MIRROR == null ? "200" : "403");
    assertThat(probe.getProperty("proxy.denied")).isEqualTo("403");
    assertThat(probe.getProperty("proxy.extra")).isEqualTo("403");
    assertThat(probe.getProperty("proxy.plain")).isEqualTo("403");
    // Limits as the build sees them.
    assertThat(probe.getProperty("memory.max")).isEqualTo(Long.toString(settings.memoryBytes()));
    assertThat(probe.getProperty("memory.swap.max")).isEqualTo("0");
    assertThat(probe.getProperty("pids.max")).isEqualTo(Integer.toString(settings.pidsLimit()));
    assertThat(probe.getProperty("cpu.max")).isEqualTo("200000 100000");
    // Not root, no capabilities, no git metadata and no Docker socket.
    assertThat(probe.getProperty("uid")).isEqualTo("10001 10001 10001 10001");
    assertThat(probe.getProperty("capabilities")).isEqualTo("0000000000000000");
    assertThat(probe.getProperty("git")).isEqualTo("false");
    assertThat(probe.getProperty("docker.socket")).isEqualTo("false");
    assertThat(Files.readString(result.log())).contains("denied CONNECT example.com:443");
  }

  @Test
  void reachesAConfiguredRepositoryAndStillNoOtherHost() throws IOException, URISyntaxException {
    // These two tests need Maven Central itself, so they are skipped where a mirror is set: there
    // every download goes through the mirror and nothing reaches Maven Central (#212).
    assumeTrue(MIRROR == null, "a test mirror is configured");
    Repo repo = repo(sandboxFixture("probe"), "probe", RepoSettings.DEFAULT);
    SandboxSettings settings =
        settings()
            .withMavenMirror(null)
            .withMavenRepositories(List.of(MavenRepository.CENTRAL, EXTRA));

    BuildResult result = new BuildSandbox(settings).build(repo, TestRepos.head(repo));

    assertThat(result.status()).as(log(result)).isEqualTo(BuildStatus.SUCCEEDED);
    Properties probe = new Properties();
    try (InputStream in =
        Files.newInputStream(
            result.resolve(result.modules().getFirst().classes()).resolve("probe.properties"))) {
      probe.load(in);
    }
    assertThat(probe.getProperty("proxy.allowed")).isEqualTo("200");
    assertThat(probe.getProperty("proxy.extra")).isEqualTo("200");
    // A host no repository names is still blocked, and so is everything around the proxy.
    assertThat(probe.getProperty("proxy.denied")).isEqualTo("403");
    assertThat(probe.getProperty("proxy.plain")).isEqualTo("403");
    assertThat(probe.getProperty("direct.name")).isEqualTo("blocked");
    assertThat(probe.getProperty("dns")).isEqualTo("failed");
    assertThat(Files.readString(result.log())).contains("denied CONNECT example.com:443");
  }

  @Test
  void downloadsDependenciesFromAConfiguredRepository() throws IOException {
    assumeTrue(MIRROR == null, "a test mirror is configured; see the test above");
    // A cache of its own, which does not exist yet, so the sandbox creates it, and the dependency
    // is not there yet and must be downloaded.
    Path cache = temp.resolve("extra-cache");
    try {
      Repo repo = repo(fixtures.resolve("lombok"), "lombok", RepoSettings.DEFAULT);
      SandboxSettings settings =
          settings()
              .withMavenMirror(null)
              .withMavenCacheDirectory(cache)
              .withMavenRepositories(List.of(EXTRA, MavenRepository.CENTRAL));

      BuildResult result = new BuildSandbox(settings).build(repo, TestRepos.head(repo));

      assertThat(result.status()).as(log(result)).isEqualTo(BuildStatus.SUCCEEDED);
      // Maven records in the cache which repository each downloaded file came from. Builds log
      // no transfers (--no-transfer-progress), so this is where the download shows.
      assertThat(origins(cache, "org/projectlombok/lombok"))
          .containsPattern("lombok-[^>]+\\.jar>" + EXTRA.id() + "=");
    } finally {
      deleteIfExists(cache);
    }
  }

  @Test
  void downloadsDependenciesAndPluginsThroughTheMirror() throws IOException {
    assumeTrue(MIRROR != null, "no test mirror configured");
    // A cache of its own, so everything the build needs must be downloaded.
    Path cache = temp.resolve("mirror-cache");
    try {
      Repo repo = repo(fixtures.resolve("lombok"), "lombok", RepoSettings.DEFAULT);

      BuildResult result =
          new BuildSandbox(settings().withMavenCacheDirectory(cache))
              .build(repo, TestRepos.head(repo));

      assertThat(result.status()).as(log(result)).isEqualTo(BuildStatus.SUCCEEDED);
      assertThat(origins(cache, "org/projectlombok/lombok"))
          .containsPattern("lombok-[^>]+\\.jar>" + Pattern.quote(MIRROR.id()) + "=");
      assertThat(origins(cache, "org/apache/maven/plugins/maven-compiler-plugin"))
          .containsPattern("maven-compiler-plugin-[^>]+\\.jar>" + Pattern.quote(MIRROR.id()) + "=");
      assertThat(origins(cache, "org/apache/maven/plugins/maven-dependency-plugin"))
          .containsPattern(
              "maven-dependency-plugin-[^>]+\\.jar>" + Pattern.quote(MIRROR.id()) + "=");
      // Everything downloaded came through the mirror: no file names another repository.
      try (Stream<Path> files = Files.walk(cache)) {
        for (Path file :
            files.filter(f -> f.getFileName().toString().equals("_remote.repositories")).toList()) {
          assertThat(Files.readString(file))
              .as(file.toString())
              .doesNotContainPattern(">(?!" + Pattern.quote(MIRROR.id()) + "=)[^=\\s]+=");
        }
      }
    } finally {
      deleteIfExists(cache);
    }
  }

  /**
   * The {@code _remote.repositories} files of every version of an artifact in a Maven cache, which
   * name the repository each downloaded file came from.
   */
  private static String origins(Path cache, String artifact) throws IOException {
    StringBuilder origins = new StringBuilder();
    try (Stream<Path> versions = Files.list(cache.resolve(artifact))) {
      for (Path version : versions.toList()) {
        Path file = version.resolve("_remote.repositories");
        if (Files.isRegularFile(file)) {
          origins.append(Files.readString(file));
        }
      }
    }
    return origins.toString();
  }

  /**
   * A new Maven cache in the test's temporary directory, holding a copy of the artifacts of the
   * shared Maven cache that {@code /sandbox/seed-cache.txt} lists, so builds of the fixtures
   * download only what that lacks. Not the whole shared cache, which grows with every analysis.
   * Files that cannot be read, or that builds remove meanwhile, are left out too: a build downloads
   * them if it needs them.
   */
  private Path seededCache(String name) throws IOException {
    Path shared = SandboxSettings.defaultMavenCacheDirectory();
    Path cache = Files.createDirectories(temp.resolve(name));
    List<String> artifacts;
    try (InputStream in = BuildSandboxIT.class.getResourceAsStream("/sandbox/seed-cache.txt")) {
      artifacts =
          new String(in.readAllBytes(), StandardCharsets.UTF_8)
              .lines()
              .map(String::strip)
              .filter(line -> !line.isEmpty() && !line.startsWith("#"))
              .toList();
    }
    SimpleFileVisitor<Path> copy =
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            Files.createDirectories(cache.resolve(shared.relativize(dir).toString()));
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (attrs.isRegularFile()) {
              try {
                Files.copy(file, cache.resolve(shared.relativize(file).toString()));
              } catch (IOException e) {
                // Not readable, or removed meanwhile: left out.
              }
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException e) {
            return FileVisitResult.CONTINUE;
          }
        };
    for (String artifact : artifacts) {
      Path dir = shared.resolve(artifact);
      if (Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
        Files.walkFileTree(dir, copy);
      }
    }
    return cache;
  }

  private static String log(BuildResult result) {
    try {
      return "build failed: " + result.failure() + "\n" + Files.readString(result.log());
    } catch (IOException e) {
      return "build failed: " + result.failure();
    }
  }
}

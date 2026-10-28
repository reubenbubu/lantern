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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Builds the fixture projects in real build containers. Needs Docker and network access to Maven
 * Central. Builds use the default shared Maven cache directory, which keeps downloads between runs;
 * the tests of the cache itself use caches of their own in their temporary directory, and delete
 * them as the user running the tests can.
 */
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
   * The owner label value of this test class, so leftover checks see only its own resources and not
   * those of other sandboxes on the same Docker.
   */
  private static final String RUN =
      HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextLong());

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

  /** The Docker filter for the containers and networks of this test class's sandboxes. */
  private static String ownFilter() {
    return "label=" + RUN_LABEL + "=" + RUN;
  }

  /**
   * The default settings, with this test class's owner label and no free space required on Docker's
   * storage.
   */
  private SandboxSettings settings() {
    return SandboxSettings.defaults(temp.resolve("builds"))
        .withOwnerLabel(RUN_LABEL, RUN)
        .withMinFreeDiskBytes(0);
  }

  private Repo repo(Path source, String name, RepoSettings settings) throws IOException {
    return TestRepos.create(source, temp.resolve("repos").resolve(name), name, settings);
  }

  /** Deletes a directory the test created, if it is there. */
  private static void deleteIfExists(Path dir) throws IOException {
    if (Files.exists(dir)) {
      deleteRecursively(dir);
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
                  .withOwnerLabel(RUN_LABEL, RUN)
                  .withMinFreeDiskBytes(0));

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
  void buildsLibAndServiceOnceTheLibIsInTheRepository() throws IOException {
    Path source = fixtures.resolve("lib-and-service");
    Repo lib = repo(source.resolve("lib"), "greeting-lib", RepoSettings.DEFAULT);
    Repo service = repo(source.resolve("service"), "greeting-service", RepoSettings.DEFAULT);
    // A cache of its own, which the library is installed into, so it is in no other cache.
    Path cache = temp.resolve("lib-cache");
    try {
      BuildSandbox sandbox = new BuildSandbox(settings().withMavenCacheDirectory(cache));

      BuildResult libResult = sandbox.build(lib, TestRepos.head(lib));
      assertThat(libResult.status()).as(log(libResult)).isEqualTo(BuildStatus.SUCCEEDED);

      // The sandbox only compiles, so the library is not installed anywhere: the service fails...
      BuildResult missing = sandbox.build(service, TestRepos.head(service));
      assertThat(missing.status()).isEqualTo(BuildStatus.FAILED);
      assertThat(missing.degraded()).isTrue();
      assertThat(Files.readString(missing.log())).contains("greeting-lib");
      // Its dependencies did not resolve, but Maven still wrote the effective POM.
      assertThat(missing.modules()).isEmpty();
      assertThat(Files.readString(missing.resolve(missing.effectivePom())))
          .contains("<artifactId>greeting-service</artifactId>");

      // ...until the library is in the artifact repository, here the Maven cache.
      installIntoCache(cache, source.resolve("lib"));
      BuildResult built = sandbox.build(service, TestRepos.head(service));
      assertThat(built.status()).as(log(built)).isEqualTo(BuildStatus.SUCCEEDED);
      assertThat(built.modules().getFirst().classpath())
          .contains("lib/com/example/greeting/greeting-lib/1.0.0/greeting-lib-1.0.0.jar");
      assertThat(sandbox.find("greeting-service", built.commit())).contains(built);
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
  void recordsATimeout() throws IOException, URISyntaxException {
    Repo repo = repo(sandboxFixture("broken"), "slow", RepoSettings.DEFAULT);

    BuildResult result =
        new BuildSandbox(settings().withTimeout(Duration.ofSeconds(1)))
            .build(repo, TestRepos.head(repo));

    assertThat(result.status()).isEqualTo(BuildStatus.FAILED);
    assertThat(result.failure()).isEqualTo("The build timed out after PT1S");
    assertThat(result.mavenExitCode()).isNull();
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
    // A cache of its own, which does not exist yet: the sandbox creates it.
    Path cache = temp.resolve("own-cache");
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
    assertThat(probe.getProperty("proxy.allowed")).isEqualTo("200");
    assertThat(probe.getProperty("proxy.denied")).isEqualTo("403");
    // By default only Maven Central.
    assertThat(probe.getProperty("proxy.extra")).isEqualTo("403");
    assertThat(probe.getProperty("proxy.plain")).isEqualTo("405");
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
    Repo repo = repo(sandboxFixture("probe"), "probe", RepoSettings.DEFAULT);
    SandboxSettings settings =
        settings().withMavenRepositories(List.of(MavenRepository.CENTRAL, EXTRA));

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
    assertThat(probe.getProperty("proxy.plain")).isEqualTo("405");
    assertThat(probe.getProperty("direct.name")).isEqualTo("blocked");
    assertThat(probe.getProperty("dns")).isEqualTo("failed");
    assertThat(Files.readString(result.log())).contains("denied CONNECT example.com:443");
  }

  @Test
  void downloadsDependenciesFromAConfiguredRepository() throws IOException {
    // A cache of its own, so the dependency is not there yet and must be downloaded.
    Path cache = temp.resolve("extra-cache");
    try {
      Repo repo = repo(fixtures.resolve("lombok"), "lombok", RepoSettings.DEFAULT);
      SandboxSettings settings =
          settings()
              .withMavenCacheDirectory(cache)
              .withMavenRepositories(List.of(EXTRA, MavenRepository.CENTRAL));

      BuildResult result = new BuildSandbox(settings).build(repo, TestRepos.head(repo));

      assertThat(result.status()).as(log(result)).isEqualTo(BuildStatus.SUCCEEDED);
      // Maven records in the cache which repository each downloaded file came from. Builds log
      // no transfers (--no-transfer-progress), so this is where the download shows.
      StringBuilder origins = new StringBuilder();
      try (Stream<Path> versions = Files.list(cache.resolve("org/projectlombok/lombok"))) {
        for (Path version : versions.toList()) {
          Path file = version.resolve("_remote.repositories");
          if (Files.isRegularFile(file)) {
            origins.append(Files.readString(file));
          }
        }
      }
      assertThat(origins).containsPattern("lombok-[^>]+\\.jar>" + EXTRA.id() + "=");
    } finally {
      deleteIfExists(cache);
    }
  }

  /**
   * Installs a fixture project into a Maven cache, as if it came from the repository. Runs as the
   * image's root user; the next build hands what it wrote to the build user.
   */
  private void installIntoCache(Path cache, Path project) {
    String name =
        "lantern-test-install-" + HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt());
    try {
      DOCKER.check(
          TIMEOUT,
          List.of(
              "create",
              "--name",
              name,
              "--mount",
              "type=bind,source=" + cache + ",target=/var/lantern/m2",
              "--workdir",
              "/project",
              "--entrypoint",
              "mvn",
              SandboxSettings.DEFAULT_IMAGES.get(21),
              "--batch-mode",
              "--no-transfer-progress",
              "-Dmaven.repo.local=/var/lantern/m2",
              "-DskipTests",
              "install"));
      DOCKER.check(TIMEOUT, List.of("cp", project + "/.", name + ":/project"));
      Docker.Result run = DOCKER.run(Duration.ofMinutes(10), List.of("start", "--attach", name));
      assertThat(run.exitCode()).as(run.out()).isZero();
    } finally {
      DOCKER.quietly(TIMEOUT, List.of("rm", "--force", "--volumes", name));
    }
  }

  private static String log(BuildResult result) {
    try {
      return "build failed: " + result.failure() + "\n" + Files.readString(result.log());
    } catch (IOException e) {
      return "build failed: " + result.failure();
    }
  }
}

package io.lantern.extract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Builds each fixture Maven project under {@code src/test/resources/fixtures} with Maven, so the
 * fixtures that extractor tests rely on are known to be valid builds.
 *
 * <p>Each build runs on a copy of the fixture with its own empty local repository, chained to the
 * outer build's local repository as a read-only tail. Fixture artifacts are installed only into the
 * temporary repository, so the tests leave nothing behind.
 */
class FixtureBuildIT {

  private static final long TIMEOUT_MINUTES = 10;

  @TempDir Path temp;

  private Path work;
  private Path localRepository;

  @BeforeEach
  void copyFixtures() throws IOException, URISyntaxException {
    Path fixtures = Path.of(FixtureBuildIT.class.getResource("/fixtures").toURI());
    work = temp.resolve("fixtures");
    localRepository = temp.resolve("m2");
    try (Stream<Path> paths = Files.walk(fixtures)) {
      for (Path source : paths.toList()) {
        Files.copy(source, work.resolve(fixtures.relativize(source).toString()));
      }
    }
  }

  @Test
  void multiModuleProjectBuilds() throws Exception {
    mvn(work.resolve("multi-module"), "package");

    assertThat(work.resolve("multi-module/api/target/shop-api-1.0.0.jar")).exists();
    assertThat(entries(work.resolve("multi-module/core/target/shop-core-1.0.0.jar")))
        .contains("com/example/shop/core/FixedPriceService.class");
  }

  @Test
  void lombokProjectBuilds() throws Exception {
    mvn(work.resolve("lombok"), "package");

    assertThat(entries(work.resolve("lombok/target/orders-1.0.0.jar")))
        .contains(
            "com/example/orders/Order.class",
            "com/example/orders/Order$OrderBuilder.class",
            "com/example/orders/OrderService.class");
  }

  @Test
  void thinServicePackagesItsLibrary() throws Exception {
    mvn(work.resolve("lib-and-service/lib"), "install");
    mvn(work.resolve("lib-and-service/service"), "package");

    assertThat(entries(work.resolve("lib-and-service/service/target/greeting-service-1.0.0.jar")))
        .contains(
            "BOOT-INF/classes/com/example/greeting/service/GreetingServiceMain.class",
            "BOOT-INF/lib/greeting-lib-1.0.0.jar");
  }

  private void mvn(Path project, String goal) throws IOException, InterruptedException {
    List<String> command = new ArrayList<>();
    command.add(requiredProperty("lantern.test.mvnw"));
    command.addAll(
        List.of(
            "--batch-mode",
            "--no-transfer-progress",
            "-Dmaven.repo.local=" + localRepository,
            "-Dmaven.repo.local.tail=" + requiredProperty("lantern.test.localRepository"),
            "-f",
            project.resolve("pom.xml").toString(),
            goal));
    Path log = temp.resolve(project.getFileName() + "-" + goal + ".log");
    Process process =
        new ProcessBuilder(command)
            .directory(project.toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
      process.destroyForcibly().waitFor();
      throw new AssertionError("Maven timed out on " + project + "\n" + readLog(log));
    }
    assertThat(process.exitValue())
        .as("mvn %s in %s exited with an error:%n%s", goal, project, readLog(log))
        .isZero();
  }

  private static List<String> entries(Path jar) throws IOException {
    try (JarFile file = new JarFile(jar.toFile())) {
      return file.stream().map(entry -> entry.getName()).toList();
    }
  }

  private static String readLog(Path log) throws IOException {
    return Files.readString(log, StandardCharsets.UTF_8);
  }

  private static String requiredProperty(String name) {
    String value = System.getProperty(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " is not set; run this test through Maven");
    }
    return value;
  }
}

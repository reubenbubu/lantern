package io.lantern.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Starts a PostgreSQL container for tests and points the app's data source at it. The image is read
 * from the repository's {@code compose.yaml}, so tests run against the same version as local
 * development. Its data lives in memory and initdb does not sync it to disk, so the container
 * starts quickly also while other tests' builds keep the disk busy. Starting is tried up to three
 * times: while many containers start at once, as when the integration tests of all modules run side
 * by side, Docker may pick a host port that another process takes before Docker can bind it.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

  private static final Pattern POSTGRES_IMAGE =
      Pattern.compile("^\\s*image:\\s*(postgres:\\S+)\\s*$", Pattern.MULTILINE);

  @Bean
  @ServiceConnection
  PostgreSQLContainer postgresContainer() {
    return newPostgres();
  }

  /** A PostgreSQL container as described above, not started, for tests that start their own. */
  static PostgreSQLContainer newPostgres() {
    return new PostgreSQLContainer(DockerImageName.parse(composePostgresImage()))
        .withEnv("POSTGRES_INITDB_ARGS", "--no-sync")
        .withTmpFs(Map.of("/var/lib/postgresql", "rw"))
        .withStartupAttempts(3);
  }

  static String composePostgresImage() {
    Path composeFile = findComposeFile();
    String compose;
    try {
      compose = Files.readString(composeFile);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + composeFile, e);
    }
    Matcher matcher = POSTGRES_IMAGE.matcher(compose);
    if (!matcher.find()) {
      throw new IllegalStateException("No postgres image found in " + composeFile);
    }
    return matcher.group(1);
  }

  /** Finds compose.yaml in the working directory or the nearest parent, so any module can run. */
  private static Path findComposeFile() {
    for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
      Path candidate = dir.resolve("compose.yaml");
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException("compose.yaml not found from " + Path.of("").toAbsolutePath());
  }
}

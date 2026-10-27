package io.lantern.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * development.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

  private static final Path COMPOSE_FILE = Path.of("..", "compose.yaml");
  private static final Pattern POSTGRES_IMAGE =
      Pattern.compile("^\\s*image:\\s*(postgres:\\S+)\\s*$", Pattern.MULTILINE);

  @Bean
  @ServiceConnection
  PostgreSQLContainer postgresContainer() {
    return new PostgreSQLContainer(DockerImageName.parse(composePostgresImage()));
  }

  static String composePostgresImage() {
    String compose;
    try {
      compose = Files.readString(COMPOSE_FILE);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + COMPOSE_FILE.toAbsolutePath(), e);
    }
    Matcher matcher = POSTGRES_IMAGE.matcher(compose);
    if (!matcher.find()) {
      throw new IllegalStateException("No postgres image found in " + COMPOSE_FILE);
    }
    return matcher.group(1);
  }
}

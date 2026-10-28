package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lantern.sandbox.MavenRepository;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Binds {@code lantern.sandbox} settings as Spring does. */
class SandboxPropertiesTest {

  private static SandboxProperties bind(Map<String, String> values) {
    return new Binder(new MapConfigurationPropertySource(values))
        .bindOrCreate("lantern.sandbox", SandboxProperties.class);
  }

  private static Map<String, String> load(InputStream in) throws IOException {
    Properties properties = new Properties();
    properties.load(in);
    Map<String, String> values = new HashMap<>();
    properties.stringPropertyNames().stream()
        .filter(key -> key.startsWith("lantern.sandbox."))
        .forEach(key -> values.put(key, properties.getProperty(key)));
    return values;
  }

  @Test
  void theApplicationDefaultsToMavenCentralOnly() throws IOException {
    Map<String, String> values;
    try (InputStream in = getClass().getResourceAsStream("/application.properties")) {
      values = load(in);
    }

    SandboxProperties sandbox = bind(values);

    assertThat(sandbox.mavenCacheVolume()).isEqualTo("lantern-maven-cache");
    assertThat(sandbox.mavenRepositories()).containsExactly(MavenRepository.CENTRAL);
    assertThat(bind(Map.of()).mavenRepositories()).containsExactly(MavenRepository.CENTRAL);
  }

  @Test
  void bindsTheOpenWmsTestSetAsTheSandboxReadsIt() throws IOException {
    Path file =
        Path.of("").toAbsolutePath().getParent().resolve("testsets/openwms/lantern.properties");
    Map<String, String> values;
    try (InputStream in = Files.newInputStream(file)) {
      values = load(in);
    }
    Properties properties = new Properties();
    properties.putAll(values);

    List<MavenRepository> repositories = bind(values).mavenRepositories();

    assertThat(repositories)
        .containsExactly(
            MavenRepository.CENTRAL,
            new MavenRepository(
                "activiti",
                URI.create(
                    "https://artifacts.alfresco.com/nexus/content/repositories/activiti-releases")));
    assertThat(repositories).isEqualTo(MavenRepository.listFrom(properties));
  }

  @Test
  void rejectsAWildcardHost() {
    Map<String, String> values =
        Map.of(
            "lantern.sandbox.maven-repositories[0].id", "any",
            "lantern.sandbox.maven-repositories[0].url", "https://*.example.com/maven2");

    assertThatThrownBy(() -> bind(values)).isInstanceOf(BindException.class);
  }
}

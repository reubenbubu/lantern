package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lantern.sandbox.MavenRepository;
import io.lantern.sandbox.SandboxSettings;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Binds {@code lantern.sandbox} settings as Spring does. */
class SandboxPropertiesTest {

  @SafeVarargs
  private static SandboxProperties bind(Map<String, String>... sources) {
    return new Binder(
            Arrays.stream(sources)
                .map(MapConfigurationPropertySource::new)
                .map(ConfigurationPropertySource.class::cast)
                .toList())
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

    assertThat(sandbox.mavenCacheDir()).isEqualTo("~/workspace/lantern-maven-cache");
    assertThat(AnalysisConfiguration.mavenCacheDirectory(sandbox.mavenCacheDir()))
        .isEqualTo(Path.of(System.getProperty("user.home"), "workspace", "lantern-maven-cache"))
        .isEqualTo(SandboxSettings.defaultMavenCacheDirectory());
    assertThat(sandbox.mavenRepositories()).containsExactly(MavenRepository.CENTRAL);
  }

  @Test
  void resolvesTheMavenCacheDirectoryFromHomeOrTheWorkingDirectory() {
    Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();

    assertThat(AnalysisConfiguration.mavenCacheDirectory("~/m2/../cache"))
        .isEqualTo(home.resolve("cache"));
    assertThat(AnalysisConfiguration.mavenCacheDirectory("~")).isEqualTo(home);
    assertThat(AnalysisConfiguration.mavenCacheDirectory("/var/cache/m2"))
        .isEqualTo(Path.of("/var/cache/m2"));
    assertThat(AnalysisConfiguration.mavenCacheDirectory("cache"))
        .isEqualTo(Path.of("cache").toAbsolutePath());
    assertThat(AnalysisConfiguration.mavenCacheDirectory(null))
        .isEqualTo(SandboxSettings.defaultMavenCacheDirectory());
  }

  @Test
  void aListSetElsewhereReplacesTheDefaultWhole() throws IOException {
    Map<String, String> defaults;
    try (InputStream in = getClass().getResourceAsStream("/application.properties")) {
      defaults = load(in);
    }
    Map<String, String> one =
        Map.of(
            "lantern.sandbox.maven-repositories[0].id", "internal",
            "lantern.sandbox.maven-repositories[0].url", "https://nexus.example.com/repository/x");
    Map<String, String> two = new HashMap<>(one);
    two.put("lantern.sandbox.maven-repositories[1].id", "releases");
    two.put(
        "lantern.sandbox.maven-repositories[1].url",
        "https://nexus.example.com/repository/releases");

    // Higher priority first, as Spring orders an additional location before the defaults.
    assertThat(bind(one, defaults).mavenRepositories())
        .extracting(MavenRepository::id)
        .containsExactly("internal");
    assertThat(bind(two, defaults).mavenRepositories())
        .extracting(MavenRepository::id)
        .containsExactly("internal", "releases");
    assertThat(bind(defaults, two).mavenRepositories()).containsExactly(MavenRepository.CENTRAL);
  }

  @Test
  void bindsAListAsTheSandboxReadsIt() {
    Map<String, String> values =
        Map.of(
            "lantern.sandbox.maven-repositories[0].id", "central",
            "lantern.sandbox.maven-repositories[0].url", "https://repo.maven.apache.org/maven2",
            "lantern.sandbox.maven-repositories[1].id", "internal",
            "lantern.sandbox.maven-repositories[1].url", "https://nexus.example.com/repository/x");
    Properties properties = new Properties();
    properties.putAll(values);

    List<MavenRepository> repositories = bind(values).mavenRepositories();

    assertThat(repositories)
        .containsExactly(
            MavenRepository.CENTRAL,
            new MavenRepository("internal", URI.create("https://nexus.example.com/repository/x")));
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

package io.lantern.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SandboxSettingsTest {

  private static final Path BUILDS = Path.of("/var/lantern/builds");

  private static SandboxSettings with(
      Map<Integer, String> images, int defaultJdk, List<String> hosts, Path builds) {
    SandboxSettings d = SandboxSettings.defaults(BUILDS);
    return new SandboxSettings(
        d.dockerCommand(),
        images,
        defaultJdk,
        d.cpus(),
        d.memoryBytes(),
        d.pidsLimit(),
        d.timeout(),
        d.maxConcurrentBuilds(),
        d.mavenCacheVolume(),
        hosts,
        builds,
        d.maxOutputBytes(),
        d.maxLogBytes());
  }

  @Test
  void defaultsHaveImagesForJdk17To25() {
    SandboxSettings settings = SandboxSettings.defaults(BUILDS);

    assertThat(settings.images().keySet()).containsExactly(17, 21, 25);
    assertThat(settings.defaultJdk()).isEqualTo(21);
    assertThat(settings.allowedHosts()).containsExactly("repo.maven.apache.org:443");
    assertThat(settings.proxyImage()).isEqualTo("maven:3.9.16-eclipse-temurin-25");
    assertThat(settings.withTimeout(Duration.ofMinutes(1)).timeout()).hasMinutes(1);
  }

  @Test
  void rejectsInconsistentSettings() {
    List<String> central = List.of(SandboxSettings.MAVEN_CENTRAL);
    assertThatThrownBy(() -> with(SandboxSettings.DEFAULT_IMAGES, 11, central, BUILDS))
        .hasMessageContaining("default JDK");
    assertThatThrownBy(() -> with(Map.of(17, "maven:17"), 17, central, BUILDS))
        .hasMessageContaining("egress proxy");
    assertThatThrownBy(() -> with(Map.of(21, "--privileged"), 21, central, BUILDS))
        .hasMessageContaining("Invalid image");
    assertThatThrownBy(
            () -> with(SandboxSettings.DEFAULT_IMAGES, 21, List.of("example.com"), BUILDS))
        .hasMessageContaining("host:port");
    assertThatThrownBy(() -> with(SandboxSettings.DEFAULT_IMAGES, 21, central, Path.of("relative")))
        .hasMessageContaining("absolute");
    assertThatThrownBy(() -> SandboxSettings.defaults(BUILDS).withMavenCacheVolume("../x"))
        .hasMessageContaining("volume");
    assertThatThrownBy(() -> SandboxSettings.defaults(BUILDS).withTimeout(Duration.ZERO))
        .hasMessageContaining("Timeout");
  }
}

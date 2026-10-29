package io.lantern.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SandboxSettingsTest {

  private static final Path BUILDS = Path.of("/var/lantern/builds");

  private static SandboxSettings with(
      Map<Integer, String> images,
      int defaultJdk,
      List<MavenRepository> repositories,
      Path builds) {
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
        d.mavenCacheDirectory(),
        repositories,
        builds,
        d.maxOutputBytes(),
        d.maxLogBytes(),
        d.diskBytes(),
        d.minFreeDiskBytes(),
        d.ownerLabel());
  }

  @Test
  void defaultsHaveImagesForJdk17To25() {
    SandboxSettings settings = SandboxSettings.defaults(BUILDS);

    assertThat(settings.images().keySet()).containsExactly(17, 21, 25);
    assertThat(settings.defaultJdk()).isEqualTo(21);
    assertThat(settings.mavenRepositories()).containsExactly(MavenRepository.CENTRAL);
    assertThat(settings.allowedHosts()).containsExactly("repo.maven.apache.org:443");
    assertThat(settings.proxyImage()).isEqualTo("maven:3.9.16-eclipse-temurin-25");
    assertThat(settings.withTimeout(Duration.ofMinutes(1)).timeout()).hasMinutes(1);
    assertThat(settings.diskBytes()).isEqualTo(8L << 30);
    assertThat(settings.withDiskBytes(1L << 30).diskBytes()).isEqualTo(1L << 30);
    assertThat(settings.withMinFreeDiskBytes(0).minFreeDiskBytes()).isZero();
    assertThat(settings.mavenCacheDirectory())
        .isEqualTo(Path.of(System.getProperty("user.home"), "workspace", "lantern-maven-cache"));
    assertThat(settings.withMavenCacheDirectory(Path.of("/m2/./x/..")).mavenCacheDirectory())
        .isEqualTo(Path.of("/m2"));
    assertThat(settings.ownerLabel()).isNull();
    assertThat(settings.withOwnerLabel("lantern.test.run", "0a1b").ownerLabel())
        .isEqualTo("lantern.test.run=0a1b");
  }

  @Test
  void allowsTheHostOfEachConfiguredRepositoryOnce() {
    MavenRepository releases =
        new MavenRepository(
            "releases", URI.create("https://nexus.example.com/repository/releases"));
    MavenRepository other =
        new MavenRepository(
            "other", URI.create("https://nexus.example.com:8443/repository/public"));
    MavenRepository sameHost =
        new MavenRepository("same-host", URI.create("https://nexus.example.com/repository/public"));

    SandboxSettings settings =
        SandboxSettings.defaults(BUILDS)
            .withMavenRepositories(List.of(MavenRepository.CENTRAL, releases, other, sameHost));

    assertThat(settings.allowedHosts())
        .containsExactly(
            "repo.maven.apache.org:443", "nexus.example.com:443", "nexus.example.com:8443");
  }

  @Test
  void rejectsInconsistentSettings() {
    List<MavenRepository> central = List.of(MavenRepository.CENTRAL);
    assertThatThrownBy(() -> with(SandboxSettings.DEFAULT_IMAGES, 11, central, BUILDS))
        .hasMessageContaining("default JDK");
    assertThatThrownBy(() -> with(Map.of(17, "maven:17"), 17, central, BUILDS))
        .hasMessageContaining("egress proxy");
    assertThatThrownBy(() -> with(Map.of(21, "--privileged"), 21, central, BUILDS))
        .hasMessageContaining("Invalid image");
    assertThatThrownBy(() -> with(SandboxSettings.DEFAULT_IMAGES, 21, List.of(), BUILDS))
        .hasMessageContaining("At least one Maven repository");
    MavenRepository otherCentral =
        new MavenRepository("central", URI.create("https://repo1.maven.org/maven2"));
    assertThatThrownBy(
            () ->
                SandboxSettings.defaults(BUILDS)
                    .withMavenRepositories(List.of(MavenRepository.CENTRAL, otherCentral)))
        .hasMessageContaining("distinct");
    assertThatThrownBy(() -> with(SandboxSettings.DEFAULT_IMAGES, 21, central, Path.of("relative")))
        .hasMessageContaining("absolute");
    assertThatThrownBy(
            () -> SandboxSettings.defaults(BUILDS).withMavenCacheDirectory(Path.of("relative")))
        .hasMessageContaining("Maven cache directory must be absolute");
    assertThatThrownBy(
            () -> SandboxSettings.defaults(BUILDS).withMavenCacheDirectory(Path.of("/a,readonly")))
        .hasMessageContaining("without commas");
    assertThatThrownBy(
            () -> SandboxSettings.defaults(BUILDS).withMavenCacheDirectory(Path.of("/a\"b")))
        .hasMessageContaining("without commas");
    assertThatThrownBy(() -> SandboxSettings.defaults(BUILDS).withTimeout(Duration.ZERO))
        .hasMessageContaining("Timeout");
    assertThatThrownBy(() -> SandboxSettings.defaults(BUILDS).withDiskBytes(1024))
        .hasMessageContaining("Disk limits");
    assertThatThrownBy(() -> SandboxSettings.defaults(BUILDS).withMinFreeDiskBytes(-1))
        .hasMessageContaining("Disk limits");
    assertThatThrownBy(() -> SandboxSettings.defaults(BUILDS).withOwnerLabel("--x", "y"))
        .hasMessageContaining("owner label");
    assertThatThrownBy(() -> SandboxSettings.defaults(BUILDS).withOwnerLabel(null, "y"))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> SandboxSettings.defaults(BUILDS).withOwnerLabel("x", ""))
        .hasMessageContaining("owner label");
    assertThatThrownBy(
            () -> SandboxSettings.defaults(BUILDS).withOwnerLabel(BuildSandbox.LABEL, "y"))
        .hasMessageContaining("owner label");
  }
}

package io.lantern.extract.maven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PomTest {

  private static Pom pom(String body) throws IOException {
    return Pom.of(
        XmlElement.parse(
            ("<project xmlns=\"http://maven.apache.org/POM/4.0.0\">" + body + "</project>")
                .getBytes(StandardCharsets.UTF_8)));
  }

  private static final String BOOT_PLUGIN_START =
      "<build><plugins><plugin><groupId>org.springframework.boot</groupId>"
          + "<artifactId>spring-boot-maven-plugin</artifactId>";
  private static final String BOOT_PLUGIN_END = "</plugin></plugins></build>";

  @Test
  void inheritsGroupAndVersionFromTheParent() throws IOException {
    Pom pom =
        pom(
            "<parent><groupId>g</groupId><artifactId>p</artifactId><version>1</version></parent>"
                + "<artifactId>a</artifactId>");

    assertThat(pom.effectiveGroupId()).isEqualTo("g");
    assertThat(pom.effectiveVersion()).isEqualTo("1");
    assertThat(pom.effectivePackaging()).isEqualTo("jar");
  }

  @Test
  void deployableWhenSpringBootRepackages() throws IOException {
    Pom bound =
        pom(
            BOOT_PLUGIN_START
                + "<executions><execution><goals><goal>repackage</goal></goals></execution>"
                + "</executions>"
                + BOOT_PLUGIN_END);
    Pom bare = pom(BOOT_PLUGIN_START + BOOT_PLUGIN_END);
    Pom skipped =
        pom(
            BOOT_PLUGIN_START
                + "<configuration><skip>true</skip></configuration>"
                + "<executions><execution><goals><goal>repackage</goal></goals></execution>"
                + "</executions>"
                + BOOT_PLUGIN_END);

    assertThat(bound.deployedBy("jar", Map.of(), false)).isNotNull();
    assertThat(bare.deployedBy("jar", Map.of(), false)).isNull();
    assertThat(bare.deployedBy("jar", Map.of(), true)).isNotNull();
    assertThat(skipped.deployedBy("jar", Map.of(), true)).isNull();
    assertThat(pom("").deployedBy("war", Map.of(), false)).isNotNull();
    assertThat(pom("").deployedBy("jar", Map.of(), true)).isNull();
  }

  @Test
  void repackageCanBeSkippedThroughProperties() throws IOException {
    Pom bySkip =
        pom(
            BOOT_PLUGIN_START
                + "<configuration><skip>${no.boot}</skip></configuration>"
                + BOOT_PLUGIN_END);
    Pom bare = pom(BOOT_PLUGIN_START + BOOT_PLUGIN_END);

    assertThat(bySkip.deployedBy("jar", Map.of("no.boot", "true"), true)).isNull();
    assertThat(bySkip.deployedBy("jar", Map.of("no.boot", "false"), true)).isNotNull();
    assertThat(bare.deployedBy("jar", Map.of("spring-boot.repackage.skip", "true"), true)).isNull();
  }

  private static String plugin(String groupId, String artifactId, String inside) {
    return "<plugin><groupId>"
        + groupId
        + "</groupId><artifactId>"
        + artifactId
        + "</artifactId>"
        + inside
        + "</plugin>";
  }

  private static Pom plugins(String... plugins) throws IOException {
    return pom("<build><plugins>" + String.join("", plugins) + "</plugins></build>");
  }

  private static String execution(String goal, String inside) {
    return "<executions><execution><goals><goal>"
        + goal
        + "</goal></goals>"
        + inside
        + "</execution></executions>";
  }

  @Test
  void otherWaysAModuleShips() throws IOException {
    Map<String, String> none = Map.of();

    assertThat(pom("").deployedBy("ear", none, false)).isEqualTo("ear");
    assertThat(pom("").deployedBy("quarkus", none, false)).isEqualTo("quarkus");
    assertThat(
            plugins(plugin("io.quarkus.platform", "quarkus-maven-plugin", execution("build", "")))
                .deployedBy("jar", none, false))
        .isEqualTo("quarkus");
    assertThat(
            plugins(plugin("com.google.cloud.tools", "jib-maven-plugin", ""))
                .deployedBy("jar", none, false))
        .isEqualTo("jib");
    assertThat(
            plugins(plugin("org.eclipse.jkube", "kubernetes-maven-plugin", ""))
                .deployedBy("jar", none, false))
        .isEqualTo("jkube");
    assertThat(
            plugins(plugin("com.spotify", "dockerfile-maven-plugin", ""))
                .deployedBy("jar", none, false))
        .isEqualTo("docker");
    assertThat(
            plugins(
                    plugin(
                        "org.springframework.boot",
                        "spring-boot-maven-plugin",
                        execution("build-image-no-fork", "")))
                .deployedBy("jar", none, false))
        .isEqualTo("spring-boot-build-image");
    assertThat(
            plugins(
                    plugin(
                        "org.apache.maven.plugins",
                        "maven-shade-plugin",
                        execution(
                            "shade",
                            "<configuration><transformers><transformer implementation=\"x\">"
                                + "<mainClass>com.example.Main</mainClass>"
                                + "</transformer></transformers></configuration>")))
                .deployedBy("jar", none, false))
        .isEqualTo("shade");
    assertThat(
            plugins(
                    plugin(
                        "org.apache.maven.plugins",
                        "maven-assembly-plugin",
                        execution(
                            "single",
                            "<configuration><archive><manifest><mainClass>com.example.Main"
                                + "</mainClass></manifest></archive></configuration>")))
                .deployedBy("jar", none, false))
        .isEqualTo("assembly");
    // In a POM as written, the main class may sit in the plugin's own configuration.
    assertThat(
            plugins(
                    plugin(
                        "org.apache.maven.plugins",
                        "maven-assembly-plugin",
                        "<configuration><archive><manifest><mainClass>com.example.Main"
                            + "</mainClass></manifest></archive></configuration>"
                            + execution(
                                "single", "<configuration><descriptorRefs/></configuration>")))
                .deployedBy("jar", none, false))
        .isEqualTo("assembly");
    assertThat(
            plugins(
                    plugin(
                        "org.apache.maven.plugins",
                        "maven-shade-plugin",
                        "<configuration><transformers><transformer>"
                            + "<mainClass>com.example.Main</mainClass>"
                            + "</transformer></transformers></configuration>"
                            + execution("shade", "")))
                .deployedBy("jar", none, false))
        .isEqualTo("shade");
    assertThat(
            plugins(plugin("org.eclipse.jkube", "openshift-maven-plugin", ""))
                .deployedBy("jar", none, false))
        .isEqualTo("jkube");
    assertThat(
            plugins(plugin("io.fabric8", "docker-maven-plugin", execution("build", "")))
                .deployedBy("jar", none, false))
        .isEqualTo("docker");
    assertThat(
            plugins(
                    plugin(
                        "io.fabric8",
                        "docker-maven-plugin",
                        "<configuration><images><image><name>x</name><build/></image></images>"
                            + "</configuration>"))
                .deployedBy("jar", none, false))
        .isEqualTo("docker");
    // A bare Quarkus plugin counts only in a POM as written.
    String bareQuarkus = plugin("io.quarkus.platform", "quarkus-maven-plugin", "");
    assertThat(plugins(bareQuarkus).deployedBy("jar", none, true)).isEqualTo("quarkus");
    assertThat(plugins(bareQuarkus).deployedBy("jar", none, false)).isNull();
  }

  @Test
  void whatDoesNotMakeAModuleShip() throws IOException {
    Map<String, String> none = Map.of();

    // Repackaging moved to phase none.
    assertThat(
            plugins(
                    plugin(
                        "org.springframework.boot",
                        "spring-boot-maven-plugin",
                        execution("repackage", "<phase>none</phase>")))
                .deployedBy("jar", none, false))
        .isNull();
    // Image plugins skipped by their property, as libraries of a parent declaring them do.
    assertThat(
            plugins(plugin("com.google.cloud.tools", "jib-maven-plugin", ""))
                .deployedBy("jar", Map.of("jib.skip", "true"), false))
        .isNull();
    assertThat(
            plugins(plugin("org.eclipse.jkube", "kubernetes-maven-plugin", ""))
                .deployedBy("jar", Map.of("jkube.skip", "true"), false))
        .isNull();
    // Image plugins skipped in their configuration, by their property, or with no execution left.
    assertThat(
            plugins(
                    plugin(
                        "com.google.cloud.tools",
                        "jib-maven-plugin",
                        "<configuration><skip>true</skip></configuration>"))
                .deployedBy("jar", none, false))
        .isNull();
    assertThat(
            plugins(plugin("com.spotify", "dockerfile-maven-plugin", ""))
                .deployedBy("jar", Map.of("dockerfile.skip", "true"), false))
        .isNull();
    assertThat(
            plugins(
                    plugin(
                        "com.google.cloud.tools",
                        "jib-maven-plugin",
                        execution("build", "<phase>none</phase>")))
                .deployedBy("jar", none, false))
        .isNull();
    // Docker started and stopped around integration tests builds nothing that ships.
    assertThat(
            plugins(
                    plugin(
                        "io.fabric8",
                        "docker-maven-plugin",
                        "<executions><execution><goals><goal>start</goal></goals></execution>"
                            + "<execution><goals><goal>stop</goal></goals></execution>"
                            + "</executions>"))
                .deployedBy("jar", none, false))
        .isNull();
    assertThat(
            plugins(plugin("io.fabric8", "docker-maven-plugin", "")).deployedBy("jar", none, false))
        .isNull();
    // Spring Boot's image and Quarkus's build skipped by their properties.
    assertThat(
            plugins(
                    plugin(
                        "org.springframework.boot",
                        "spring-boot-maven-plugin",
                        execution("build-image", "")))
                .deployedBy("jar", Map.of("spring-boot.build-image.skip", "true"), false))
        .isNull();
    assertThat(
            plugins(plugin("io.quarkus.platform", "quarkus-maven-plugin", execution("build", "")))
                .deployedBy("jar", Map.of("quarkus.build.skip", "true"), false))
        .isNull();
    // Shade relocating dependencies, and an unresolved main class.
    assertThat(
            plugins(
                    plugin(
                        "org.apache.maven.plugins", "maven-shade-plugin", execution("shade", "")))
                .deployedBy("jar", none, false))
        .isNull();
    assertThat(
            plugins(
                    plugin(
                        "org.apache.maven.plugins",
                        "maven-shade-plugin",
                        execution(
                            "shade",
                            "<configuration><transformers><transformer>"
                                + "<mainClass>${start-class}</mainClass>"
                                + "</transformer></transformers></configuration>")))
                .deployedBy("jar", none, false))
        .isNull();
    // A pom module never ships, whatever it declares.
    assertThat(
            plugins(plugin("com.google.cloud.tools", "jib-maven-plugin", ""))
                .deployedBy("pom", none, false))
        .isNull();
  }

  @Test
  void anExecutableJarRanksAboveItsImage() throws IOException {
    assertThat(
            plugins(
                    plugin("com.google.cloud.tools", "jib-maven-plugin", ""),
                    plugin(
                        "org.springframework.boot",
                        "spring-boot-maven-plugin",
                        execution("repackage", "")))
                .deployedBy("jar", Map.of(), false))
        .isEqualTo("spring-boot");
  }

  @Test
  void interpolatesNestedPropertiesAndGivesUpOnUnknownOnes() {
    Map<String, String> properties = Map.of("a", "${b}-x", "b", "1", "loop", "${loop}");

    assertThat(Pom.interpolate("v${a}", properties)).isEqualTo("v1-x");
    assertThat(Pom.interpolate("${missing}", properties)).isNull();
    assertThat(Pom.interpolate("${loop}", properties)).isNull();
    assertThat(Pom.interpolate(null, properties)).isNull();
  }

  @Test
  void refusesDoctypes() {
    byte[] xml =
        "<!DOCTYPE project [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><project>&x;</project>"
            .getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> XmlElement.parse(xml)).isInstanceOf(IOException.class);
  }

  @Test
  void recordsLines() throws IOException {
    XmlElement root =
        XmlElement.parse("<a>\n  <b>\n    <c>x</c>\n  </b>\n</a>".getBytes(StandardCharsets.UTF_8));
    XmlElement b = root.child("b").orElseThrow();

    assertThat(b.startLine()).isEqualTo(2);
    assertThat(b.endLine()).isEqualTo(4);
    assertThat(b.text("c")).isEqualTo("x");
  }

  @Test
  void modulePathsStayInsideTheRepository() {
    assertThat(MavenExtractor.modulePom("", "api")).isEqualTo("api/pom.xml");
    assertThat(MavenExtractor.modulePom("parent", "../core/")).isEqualTo("core/pom.xml");
    assertThat(MavenExtractor.modulePom("", "build/other.xml")).isEqualTo("build/other.xml");
    assertThat(MavenExtractor.modulePom("", "../outside")).isNull();
    assertThat(MavenExtractor.modulePom("a", "..")).isNull();
    assertThat(MavenExtractor.modulePom("", " ")).isNull();
  }

  @Test
  void namePartsLeaveOutTheGroupId() {
    assertThat(MavenExtractor.nameParts("org.openwms", "org.openwms.common.service.lib"))
        .containsExactly("common", "service", "lib");
    assertThat(MavenExtractor.nameParts("com.example.greeting", "greeting-service"))
        .containsExactly("greeting", "service");
  }
}

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

    assertThat(bound.deployable("jar", false)).isTrue();
    assertThat(bare.deployable("jar", false)).isFalse();
    assertThat(bare.deployable("jar", true)).isTrue();
    assertThat(skipped.deployable("jar", true)).isFalse();
    assertThat(pom("").deployable("war", false)).isTrue();
    assertThat(pom("").deployable("jar", true)).isFalse();
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

package io.lantern.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class JdkDetectorTest {

  private static Optional<JdkDetector.Detection> detect(String body) {
    return JdkDetector.detect(
        ("<project xmlns=\"http://maven.apache.org/POM/4.0.0\">" + body + "</project>")
            .getBytes(StandardCharsets.UTF_8));
  }

  private static String properties(String properties) {
    return "<properties>" + properties + "</properties>";
  }

  private static String plugin(String artifactId, String configuration) {
    return "<build><plugins><plugin><artifactId>"
        + artifactId
        + "</artifactId><configuration>"
        + configuration
        + "</configuration></plugin></plugins></build>";
  }

  @Test
  void readsTheCompilerReleaseProperty() {
    assertThat(detect(properties("<maven.compiler.release>21</maven.compiler.release>")))
        .contains(new JdkDetector.Detection(21, "maven.compiler.release"));
  }

  @Test
  void readsTheCompilerPluginReleaseThroughAProperty() {
    assertThat(
            detect(
                properties("<target.jdk>17</target.jdk>")
                    + plugin("maven-compiler-plugin", "<release>${target.jdk}</release>")))
        .contains(new JdkDetector.Detection(17, "compiler plugin release"));
  }

  @Test
  void readsPluginManagement() {
    assertThat(
            detect(
                "<build><pluginManagement><plugins><plugin>"
                    + "<artifactId>maven-compiler-plugin</artifactId>"
                    + "<configuration><release>25</release></configuration>"
                    + "</plugin></plugins></pluginManagement></build>"))
        .contains(new JdkDetector.Detection(25, "compiler plugin release"));
  }

  @Test
  void readsOldStyleSourceVersions() {
    assertThat(detect(properties("<maven.compiler.source>1.8</maven.compiler.source>")))
        .contains(new JdkDetector.Detection(8, "maven.compiler.source"));
  }

  @Test
  void prefersToolchainsThenReleaseThenSourceThenJavaVersion() {
    String all =
        "<maven.compiler.release>11</maven.compiler.release>"
            + "<maven.compiler.source>17</maven.compiler.source>"
            + "<java.version>21</java.version>";
    assertThat(
            detect(
                properties(all)
                    + plugin(
                        "maven-toolchains-plugin",
                        "<toolchains><jdk><version>[25,)</version></jdk></toolchains>")))
        .contains(new JdkDetector.Detection(25, "toolchains plugin jdk version"));
    assertThat(detect(properties(all)))
        .contains(new JdkDetector.Detection(11, "maven.compiler.release"));
    assertThat(
            detect(
                properties(
                    "<maven.compiler.source>17</maven.compiler.source><java.version>21</java.version>")))
        .contains(new JdkDetector.Detection(17, "maven.compiler.source"));
    assertThat(detect(properties("<java.version>21</java.version>")))
        .contains(new JdkDetector.Detection(21, "java.version"));
  }

  @Test
  void skipsValuesItCannotResolve() {
    assertThat(
            detect(
                properties(
                    "<maven.compiler.release>${from.parent}</maven.compiler.release>"
                        + "<maven.compiler.source>11</maven.compiler.source>")))
        .contains(new JdkDetector.Detection(11, "maven.compiler.source"));
    assertThat(detect(properties("<a>${b}</a><b>${a}</b><java.version>${a}</java.version>")))
        .isEmpty();
  }

  @Test
  void findsNothingInPomsThatDoNotSay() {
    assertThat(detect("<groupId>g</groupId>")).isEmpty();
    assertThat(JdkDetector.detect("<other/>".getBytes(StandardCharsets.UTF_8))).isEmpty();
    assertThat(JdkDetector.detect("not xml".getBytes(StandardCharsets.UTF_8))).isEmpty();
  }

  @Test
  void refusesDoctypesSoEntitiesCannotReadFiles() {
    String pom =
        "<?xml version=\"1.0\"?><!DOCTYPE project [<!ENTITY v SYSTEM \"file:///etc/hostname\">]>"
            + "<project><properties><java.version>&v;</java.version></properties></project>";
    assertThat(JdkDetector.detect(pom.getBytes(StandardCharsets.UTF_8))).isEmpty();
  }

  @Test
  void parsesVersions() {
    assertThat(JdkDetector.version("17")).hasValue(17);
    assertThat(JdkDetector.version(" 1.8 ")).hasValue(8);
    assertThat(JdkDetector.version("21.0.2")).hasValue(21);
    assertThat(JdkDetector.version("[17,)")).hasValue(17);
    assertThat(JdkDetector.version("(11,21]")).hasValue(11);
    assertThat(JdkDetector.version("17-ea")).isEmpty();
    assertThat(JdkDetector.version("java17")).isEmpty();
    assertThat(JdkDetector.version("0")).isEmpty();
    assertThat(JdkDetector.version(null)).isEmpty();
  }
}

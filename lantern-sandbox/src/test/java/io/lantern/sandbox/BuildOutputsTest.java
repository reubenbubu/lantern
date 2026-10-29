package io.lantern.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildOutputsTest {

  @TempDir Path outputs;

  private void write(String path, String content) throws IOException {
    Path file = outputs.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  @Test
  void readsModulesTheirGraphAndClasspaths() throws IOException {
    write("work/target/lantern/dependency-tree.txt", "com.example:parent:pom:1.0\n");
    write("work/target/lantern/classpath.txt", "");
    write("work/target/lantern/effective-pom.xml", "<projects/>");
    write("work/api/target/lantern/dependency-tree.txt", "com.example:api:jar:1.0\n");
    write("work/api/target/classes/A.class", "");
    write(
        "work/core/target/lantern/dependency-tree.txt",
        """
        com.example:core:jar:1.0
        +- com.example:api:jar:1.0:compile
        \\- org.slf4j:slf4j-api:jar:2.0.17:compile
           \\- com.example:parent:pom:1.0:compile
        """);
    write(
        "work/core/target/lantern/classpath.txt",
        "/work/api/target/classes:/var/lantern/m2/org/slf4j/slf4j-api.jar"
            + ":/lantern/upstream/com/other/lib.jar");
    write("lib/org/slf4j/slf4j-api.jar", "jar");
    write("lib/com/other/lib.jar", "jar");
    Files.createDirectories(outputs.resolve("work/core/target/generated-sources/annotations"));

    List<BuildModule> modules = BuildOutputs.modules(outputs);

    assertThat(modules).extracting(BuildModule::path).containsExactly("", "api", "core");
    BuildModule core = modules.get(2);
    assertThat(core.groupId()).isEqualTo("com.example");
    assertThat(core.artifactId()).isEqualTo("core");
    assertThat(core.packaging()).isEqualTo("jar");
    assertThat(core.version()).isEqualTo("1.0");
    // Only direct dependencies count; the parent appears only transitively.
    assertThat(core.dependsOn()).containsExactly("api");
    assertThat(core.classpath())
        .containsExactly(
            "work/api/target/classes", "lib/org/slf4j/slf4j-api.jar", "lib/com/other/lib.jar");
    assertThat(core.classes()).isNull();
    assertThat(core.generatedSources()).isEqualTo("work/core/target/generated-sources");
    assertThat(core.dependencyTree()).isEqualTo("work/core/target/lantern/dependency-tree.txt");
    assertThat(modules.get(1).classes()).isEqualTo("work/api/target/classes");
    assertThat(modules.get(0).packaging()).isEqualTo("pom");
    assertThat(BuildOutputs.effectivePom(outputs))
        .isEqualTo("work/target/lantern/effective-pom.xml");
    // The container classpath files are replaced by the rewritten classpaths.
    assertThat(outputs.resolve("work/core/target/lantern/classpath.txt")).doesNotExist();
  }

  @Test
  void keepsOnlyClasspathEntriesInsideTheOutputs() throws IOException {
    write("lib/a/a.jar", "a");
    write("outside.jar", "x");

    List<String> classpath =
        BuildOutputs.classpath(
            outputs,
            String.join(
                ":",
                "/var/lantern/m2/a/a.jar",
                "/var/lantern/m2/a/a.jar",
                "/var/lantern/m2/missing.jar",
                "/var/lantern/m2/../outside.jar",
                "/var/lantern/m2/../../../../etc/passwd",
                "/etc/passwd",
                "/work/missing/target/classes",
                ""));

    assertThat(classpath).containsExactly("lib/a/a.jar");
  }

  @Test
  void hasNoModulesWithoutOutputs() throws IOException {
    assertThat(BuildOutputs.modules(outputs)).isEmpty();
    assertThat(BuildOutputs.effectivePom(outputs)).isNull();
  }
}

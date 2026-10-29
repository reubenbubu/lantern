package io.lantern.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.repo.Repo;
import io.lantern.core.repo.RepoSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpstreamRepositoryTest {

  @TempDir Path temp;

  private Repo repo(String name) throws IOException {
    Path source = temp.resolve("source").resolve(name);
    Files.createDirectories(source.resolve("lib"));
    Files.writeString(source.resolve("pom.xml"), "<project>parent of " + name + "</project>");
    Files.writeString(source.resolve("lib/pom.xml"), "<project>lib of " + name + "</project>");
    return TestRepos.create(
        source, temp.resolve("repos").resolve(name), name, RepoSettings.DEFAULT);
  }

  private static BuildModule module(String path, String artifactId, String packaging) {
    return new BuildModule(
        path,
        "com.example",
        artifactId,
        packaging,
        "1.0-SNAPSHOT",
        List.of(),
        List.of(),
        path.isEmpty() ? null : "work/" + path + "/target/classes",
        null,
        "tree");
  }

  private BuildResult build(Repo repo, BuildStatus status, List<BuildModule> modules)
      throws IOException {
    Path directory = temp.resolve("builds").resolve(repo.name());
    Path classes = directory.resolve("work/lib/target/classes");
    Files.createDirectories(classes.resolve("com/example"));
    Files.writeString(classes.resolve("com/example/Lib.class"), "class");
    Files.writeString(classes.resolve("application.properties"), "a=b");
    return new BuildResult(
        repo.name(),
        TestRepos.head(repo),
        status,
        status == BuildStatus.FAILED ? "Maven exited with code 1" : null,
        21,
        "default",
        "image",
        status == BuildStatus.FAILED ? 1 : 0,
        Instant.EPOCH,
        Duration.ZERO,
        null,
        modules,
        directory);
  }

  @Test
  void writesEachModulesPomAndAJarOfItsClasses() throws IOException {
    Repo first = repo("first");
    Repo second = repo("second");
    Repo failed = repo("failed");
    List<BuildModule> modules = List.of(module("", "parent", "pom"), module("lib", "lib", "jar"));
    Path into = temp.resolve("upstream");

    UpstreamRepository.Written written =
        UpstreamRepository.write(
            List.of(
                new UpstreamBuild(failed, build(failed, BuildStatus.FAILED, modules)),
                new UpstreamBuild(first, build(first, BuildStatus.SUCCEEDED, modules)),
                new UpstreamBuild(second, build(second, BuildStatus.SUCCEEDED, modules))),
            into,
            Instant.parse("2026-10-29T08:09:10Z"));
    List<String> notes = written.notes();

    Path parent = into.resolve("com/example/parent/1.0-SNAPSHOT");
    assertThat(parent.resolve("parent-1.0-SNAPSHOT.pom"))
        .hasContent("<project>parent of first</project>");
    assertThat(parent.resolve("parent-1.0-SNAPSHOT.jar")).doesNotExist();
    Path lib = into.resolve("com/example/lib/1.0-SNAPSHOT");
    assertThat(lib.resolve("lib-1.0-SNAPSHOT.pom")).hasContent("<project>lib of first</project>");
    try (JarFile jar = new JarFile(lib.resolve("lib-1.0-SNAPSHOT.jar").toFile())) {
      assertThat(Collections.list(jar.entries()))
          .extracting(ZipEntry::getName)
          .containsExactly(
              "META-INF/MANIFEST.MF",
              "application.properties",
              "com/",
              "com/example/",
              "com/example/Lib.class");
    }
    // A snapshot gets the metadata of a local install, so Maven prefers it to a remote snapshot.
    assertThat(lib.resolve("maven-metadata-local.xml"))
        .content()
        .contains("<localCopy>true</localCopy>")
        .contains("<lastUpdated>20261029080910</lastUpdated>")
        .contains("<extension>jar</extension>")
        .contains("<value>1.0-SNAPSHOT</value>");
    assertThat(parent.resolve("maven-metadata-local.xml"))
        .content()
        .contains("<extension>pom</extension>")
        .doesNotContain("<extension>jar</extension>");
    assertThat(written.builds())
        .containsExactlyInAnyOrder(
            "first@" + TestRepos.head(first), "second@" + TestRepos.head(second));
    assertThat(written.artifacts())
        .containsEntry(
            "com.example:lib:1.0-SNAPSHOT",
            List.of(
                "com/example/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT.pom",
                "com/example/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT.jar"))
        .hasSize(2);
    assertThat(notes)
        .anySatisfy(n -> assertThat(n).startsWith("not using the artifacts of failed@"))
        .anySatisfy(
            n ->
                assertThat(n)
                    .startsWith("artifacts of first@")
                    .endsWith("[com.example:parent:1.0-SNAPSHOT, com.example:lib:1.0-SNAPSHOT]"))
        .anySatisfy(
            n ->
                assertThat(n)
                    .startsWith("not using com.example:lib:1.0-SNAPSHOT of second@")
                    .endsWith("an earlier repository builds it"));
  }

  @Test
  void skipsCoordinatesThatAreNoPlainPath() throws IOException {
    Repo repo = repo("odd");
    BuildModule odd =
        new BuildModule("lib", "..", "lib", "jar", "1.0", List.of(), List.of(), null, null, "tree");
    Path into = temp.resolve("upstream");

    List<String> notes =
        UpstreamRepository.write(
                List.of(new UpstreamBuild(repo, build(repo, BuildStatus.SUCCEEDED, List.of(odd)))),
                Files.createDirectories(into),
                Instant.EPOCH)
            .notes();

    try (var files = Files.list(into)) {
      assertThat(files).isEmpty();
    }
    assertThat(notes).anySatisfy(n -> assertThat(n).contains("not a plain Maven coordinate"));
  }
}

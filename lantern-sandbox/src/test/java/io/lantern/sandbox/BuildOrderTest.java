package io.lantern.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lantern.core.repo.Repo;
import io.lantern.core.repo.RepoSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildOrderTest {

  @TempDir Path temp;

  private final Map<String, Repo> repos = new TreeMap<>();
  private final Map<String, String> commits = new TreeMap<>();

  /** A repository of the POMs, each a path and the inside of its {@code project} element. */
  private void repo(String name, String... pathsAndBodies) throws IOException {
    Path source = temp.resolve("source").resolve(name);
    for (int i = 0; i < pathsAndBodies.length; i += 2) {
      Path file = source.resolve(pathsAndBodies[i]);
      Files.createDirectories(file.getParent());
      Files.writeString(
          file,
          "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
              + pathsAndBodies[i + 1]
              + "</project>");
    }
    Repo repo =
        TestRepos.create(source, temp.resolve("repos").resolve(name), name, RepoSettings.DEFAULT);
    repos.put(name, repo);
    commits.put(name, TestRepos.head(repo));
  }

  private static String gav(String groupId, String artifactId) {
    return "<groupId>" + groupId + "</groupId><artifactId>" + artifactId + "</artifactId>";
  }

  private static String dependency(String groupId, String artifactId) {
    return "<dependencies><dependency>" + gav(groupId, artifactId) + "</dependency></dependencies>";
  }

  @Test
  void ordersRepositoriesByTheModulesTheirPomsName() throws IOException {
    // The service's module names the library through a property, and the library's parent is the
    // parent POM another repository builds. The tools repository depends on nothing internal.
    repo(
        "a-service",
        "pom.xml",
        gav("com.example", "service-parent") + "<modules><module>app</module></modules>",
        "app/pom.xml",
        "<parent>"
            + gav("com.example", "service-parent")
            + "</parent><artifactId>app</artifactId>"
            + "<properties><lib.group>com.example.lib</lib.group></properties>"
            + dependency("${lib.group}", "greeting-lib"));
    repo(
        "b-lib",
        "pom.xml",
        "<parent>"
            + gav("com.example", "base")
            + "</parent>"
            + gav("com.example.lib", "greeting-lib")
            + dependency("org.slf4j", "slf4j-api"));
    repo("c-base", "pom.xml", gav("com.example", "base"));
    repo("d-tools", "pom.xml", gav("com.example", "tools"));

    BuildOrder order = BuildOrder.of(repos, commits);

    assertThat(order.order()).containsExactly("c-base", "b-lib", "a-service", "d-tools");
    assertThat(order.upstream("a-service")).containsExactly("b-lib", "c-base");
    assertThat(order.upstream("b-lib")).containsExactly("c-base");
    assertThat(order.upstream("c-base")).isEmpty();
    assertThat(order.upstream("d-tools")).isEmpty();
    assertThat(order.cycles()).isEmpty();
  }

  @Test
  void buildsRepositoriesThatDependOnEachOtherAsIfAlone() throws IOException {
    repo("a", "pom.xml", gav("com.example", "a") + dependency("com.example", "b"));
    // A plugin and a managed dependency count too.
    repo(
        "b",
        "pom.xml",
        gav("com.example", "b")
            + "<build><plugins><plugin>"
            + gav("com.example", "a")
            + "</plugin></plugins></build>");
    repo(
        "c",
        "pom.xml",
        gav("com.example", "c")
            + "<dependencyManagement>"
            + dependency("com.example", "a")
            + "</dependencyManagement>");

    BuildOrder order = BuildOrder.of(repos, commits);

    assertThat(order.cycles()).containsExactly(List.of("a", "b"));
    assertThat(order.upstream("a")).isEmpty();
    assertThat(order.upstream("b")).isEmpty();
    assertThat(order.upstream("c")).containsExactly("a", "b");
    assertThat(order.order()).containsExactly("a", "b", "c");
  }

  @Test
  void ignoresWhatCannotBeRead() throws IOException {
    // A module outside the repository, a coordinate with an unknown property, a module that is
    // not there and a POM that is not XML add nothing; the repository itself is no dependency.
    repo(
        "a",
        "pom.xml",
        gav("com.example", "a")
            + "<modules><module>../b</module><module>missing</module><module>bad</module>"
            + "</modules>"
            + dependency("${unknown}", "b")
            + dependency("com.example", "a"),
        "bad/pom.xml",
        "<not-closed>");
    repo("b", "pom.xml", gav("com.example", "b"));

    BuildOrder order = BuildOrder.of(repos, commits);

    assertThat(order.upstream("a")).isEmpty();
    assertThat(order.upstream("b")).isEmpty();
    assertThat(order.cycles()).isEmpty();
  }

  @Test
  void readsPomsAtTheCommitAndRequiresOne() throws IOException {
    repo("a", "pom.xml", gav("com.example", "a") + dependency("com.example", "b"));
    repo("b", "pom.xml", gav("com.example", "b"));
    // The working tree does not count.
    Files.writeString(repos.get("a").path().resolve("pom.xml"), "<project/>");

    assertThat(BuildOrder.of(repos, commits).upstream("a")).isEqualTo(Set.of("b"));
    commits.remove("b");
    assertThatThrownBy(() -> BuildOrder.of(repos, commits))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("b");
  }
}

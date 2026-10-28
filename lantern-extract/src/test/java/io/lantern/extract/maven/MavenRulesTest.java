package io.lantern.extract.maven;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.repo.Repo;
import io.lantern.extract.Fixtures;
import io.lantern.extract.RepoBuild;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The extractor's rules on small POMs written for each case, read without builds. */
class MavenRulesTest {

  @TempDir Path temp;

  private static String pom(String groupId, String artifactId, String body) {
    return "<project><modelVersion>4.0.0</modelVersion><groupId>"
        + groupId
        + "</groupId><artifactId>"
        + artifactId
        + "</artifactId><version>1</version>"
        + body
        + "</project>";
  }

  private static String dependency(String groupId, String artifactId, String extra) {
    return "<dependency><groupId>"
        + groupId
        + "</groupId><artifactId>"
        + artifactId
        + "</artifactId><version>1</version>"
        + extra
        + "</dependency>";
  }

  private static String dependencies(String... deps) {
    return "<dependencies>" + String.join("", deps) + "</dependencies>";
  }

  private static final String BOOT =
      "<build><plugins><plugin><groupId>org.springframework.boot</groupId>"
          + "<artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build>";

  private static String sources(int n) {
    return "src/main/java/A" + n + ".java";
  }

  private Repo repo(String name, Map<String, String> files) throws IOException {
    return Fixtures.repo(temp.resolve(name), name, files);
  }

  private List<String> extract(Repo... repos) throws IOException {
    List<RepoBuild> inputs =
        List.of(repos).stream().map(r -> new RepoBuild(r, Fixtures.head(r), null)).toList();
    List<Fact> facts = new MavenExtractor().extract(inputs, 1);
    Fixtures.checkEnvelope(facts, List.of(repos), 1, MavenExtractor.NAME, MavenExtractor.VERSION);
    return Fixtures.render(facts).lines().toList();
  }

  private static List<String> packages(List<String> facts) {
    return facts.stream().filter(f -> f.contains(" PACKAGES ")).toList();
  }

  @Test
  void aTieBetweenLibrariesLinksNone() throws IOException {
    Repo service =
        repo(
            "svc",
            Map.of(
                "pom.xml",
                pom(
                    "g",
                    "orders-service",
                    dependencies(dependency("g", "orders-a", ""), dependency("g", "orders-b", ""))
                        + BOOT)));
    Repo a = repo("a", Map.of("pom.xml", pom("g", "orders-a", ""), sources(1), "class A1 {}"));
    Repo b = repo("b", Map.of("pom.xml", pom("g", "orders-b", ""), sources(1), "class A1 {}"));

    assertThat(packages(extract(service, a, b))).isEmpty();
  }

  @Test
  void aLibraryWithLessCodeThanTheServiceIsNotItsLibrary() throws IOException {
    Repo service =
        repo(
            "svc",
            Map.of(
                "pom.xml",
                pom("g", "orders-service", dependencies(dependency("g", "orders-api", "")) + BOOT),
                sources(1),
                "class A1 {}",
                sources(2),
                "class A2 {}"));
    Repo api =
        repo("api", Map.of("pom.xml", pom("g", "orders-api", ""), sources(1), "class A1 {}"));

    assertThat(packages(extract(service, api))).isEmpty();
  }

  @Test
  void optionalAndTestDependenciesAreNotPackaged() throws IOException {
    Repo service =
        repo(
            "svc",
            Map.of(
                "pom.xml",
                pom(
                    "g",
                    "orders-service",
                    dependencies(
                            dependency("g", "orders-lib", "<optional>true</optional>"),
                            dependency("g", "orders-core", "<scope>test</scope>"))
                        + BOOT)));
    Repo lib = repo("lib", Map.of("pom.xml", pom("g", "orders-lib", "")));
    Repo core = repo("core", Map.of("pom.xml", pom("g", "orders-core", "")));

    List<String> facts = extract(service, lib, core);

    assertThat(packages(facts)).isEmpty();
    assertThat(facts)
        .anyMatch(f -> f.contains("BUILD_DEPENDS_ON module:g:orders-lib {\"optional\":true,"))
        .anyMatch(f -> f.contains("BUILD_DEPENDS_ON module:g:orders-core {\"scope\":\"test\""));
  }

  @Test
  void differingGroupIdsStillLinkAServiceToItsLibrary() throws IOException {
    Repo service =
        repo(
            "svc",
            Map.of(
                "pom.xml",
                pom(
                    "org.acme.wms",
                    "org.acme.wms.receiving",
                    dependencies(dependency("org.acme", "org.acme.wms.receiving.lib", ""))
                        + BOOT)));
    Repo lib = repo("lib", Map.of("pom.xml", pom("org.acme", "org.acme.wms.receiving.lib", "")));

    assertThat(packages(extract(service, lib)))
        .singleElement()
        .asString()
        .startsWith(
            "service:org.acme.wms:org.acme.wms.receiving PACKAGES"
                + " library:org.acme:org.acme.wms.receiving.lib");
  }

  @Test
  void writtenPomsInheritFromAParentInTheSnapshot() throws IOException {
    String parentPom =
        pom(
            "g",
            "parent",
            "<packaging>pom</packaging><properties><x.version>2.5</x.version></properties>"
                + "<dependencyManagement><dependencies><dependency><groupId>x</groupId>"
                + "<artifactId>x-lib</artifactId><version>${x.version}</version>"
                + "<classifier>all</classifier><type>test-jar</type><scope>test</scope>"
                + "</dependency></dependencies></dependencyManagement>"
                + BOOT);
    Repo parent = repo("parent", Map.of("pom.xml", parentPom));
    // The child turns the parent's Spring Boot plugin off by declaring it again.
    String childPom =
        "<project><parent><groupId>g</groupId><artifactId>parent</artifactId>"
            + "<version>1</version></parent><artifactId>child</artifactId>"
            + "<dependencies><dependency><groupId>x</groupId><artifactId>x-lib</artifactId>"
            + "<classifier>all</classifier><type>test-jar</type></dependency></dependencies>"
            + "<build><plugins><plugin><groupId>org.springframework.boot</groupId>"
            + "<artifactId>spring-boot-maven-plugin</artifactId>"
            + "<configuration><skip>true</skip></configuration></plugin></plugins></build>"
            + "</project>";
    Repo child = repo("child", Map.of("pom.xml", childPom));

    List<String> facts = extract(parent, child);

    assertThat(facts)
        .contains(
            "module:g:child BUILD_DEPENDS_ON artifact:x:x-lib"
                + " {\"artifactType\":\"test-jar\",\"classifier\":\"all\",\"scope\":\"test\","
                + "\"version\":\"2.5\"} @child:pom.xml#1-1 0.7",
            "library:g:child CONTAINS module:g:child @child:pom.xml 0.7");
  }

  @Test
  void unreadableAndNonProjectPomsAreSkipped() throws IOException {
    Repo broken =
        repo(
            "broken",
            Map.of(
                "pom.xml",
                pom(
                    "g",
                    "top",
                    "<packaging>pom</packaging><modules><module>a</module>"
                        + "<module>b</module><module>missing</module></modules>"),
                "a/pom.xml",
                "<project><artifactId>a</oops>",
                "b/pom.xml",
                "<settings/>"));

    assertThat(extract(broken))
        .containsExactly(
            "module:g:top packaging pom @broken:pom.xml#1-1 0.7",
            "module:g:top version 1 @broken:pom.xml#1-1 0.7");
  }
}

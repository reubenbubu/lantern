package io.lantern.extract.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.repo.Repo;
import io.lantern.extract.Fixtures;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.java.JavaAnalysis;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.sandbox.BuildModule;
import io.lantern.sandbox.BuildResult;
import io.lantern.sandbox.BuildStatus;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that need no Docker, on the {@code gateway} fixture: golden facts with its module parsed
 * (degraded mode), and the same facts with it read through javac, with Lantern's own test classpath
 * (Spring's context and beans annotations) and stand-ins for Spring Cloud Gateway, WebFlux and
 * Reactor.
 */
class GatewayExtractorTest {

  private static final long SNAPSHOT = 5;

  @TempDir Path temp;

  private static List<Fact> extract(List<RepoBuild> inputs) throws IOException {
    List<Fact> facts = new GatewayExtractor().extract(inputs, SNAPSHOT);
    Fixtures.checkEnvelope(
        facts,
        inputs.stream().map(RepoBuild::repo).toList(),
        SNAPSHOT,
        GatewayExtractor.NAME,
        GatewayExtractor.VERSION);
    // Running again on the same input gives the same facts.
    assertThat(new GatewayExtractor().extract(inputs, SNAPSHOT)).isEqualTo(facts);
    return facts;
  }

  private Repo fixture() throws IOException {
    return Fixtures.repo("gateway", temp.resolve("gateway"), "gateway");
  }

  private static List<RepoBuild> unbuilt(Repo repo) {
    return List.of(new RepoBuild(repo, Fixtures.head(repo), null));
  }

  @Test
  void parsed() throws IOException {
    List<Fact> facts = extract(unbuilt(fixture()));

    Fixtures.assertGolden("gateway/gateway-parsed.txt", facts);
    String rendered = Fixtures.render(facts);
    // A secret from the config never reaches a fact.
    assertThat(rendered).doesNotContain("s3cret");
    // Neither the abstract base nor a filter no route applies and no bean makes is a filter.
    assertThat(rendered).doesNotContain("BaseFilter gateway-filter");
    assertThat(rendered).doesNotContain("UnusedFilter");
  }

  /**
   * Routes config defines, Zuul's and the discovery locator are read in linking from the config
   * facts ({@code ValueResolutionTest} in lantern-link); this extractor reads no config.
   */
  @Test
  void noRouteIsReadFromConfig() throws IOException {
    assertThat(Fixtures.render(extract(unbuilt(fixture()))).lines())
        .noneMatch(l -> l.contains("\"definedIn\":\"config\""))
        .noneMatch(l -> l.contains(" gateway-discovery-locator "));
  }

  @Test
  void compiledGivesTheSameFactsAsParsed() throws IOException {
    Repo repo = fixture();
    String commit = Fixtures.head(repo);
    JavaAnalysis code =
        JavaExtractor.analyze(List.of(new RepoBuild(repo, commit, build(repo, commit))));
    List<Fact> compiled = new GatewayExtractor().extract(code, SNAPSHOT);
    List<Fact> parsed = new GatewayExtractor().extract(unbuilt(repo), SNAPSHOT);

    assertThat(code.modules())
        .extracting(m -> m.directory() + " " + m.compiled())
        .containsExactly("edge true");
    assertThat(withoutConfidence(compiled)).isEqualTo(withoutConfidence(parsed));
  }

  private static List<String> withoutConfidence(List<Fact> facts) {
    return Fixtures.render(facts).lines().map(l -> l.replaceAll(" [01]\\.[07]$", "")).toList();
  }

  /** A successful build of the fixture's module, with Lantern's test classpath and stand-ins. */
  private BuildResult build(Repo repo, String commit) throws IOException {
    List<String> classpath =
        new ArrayList<>(
            Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(entry -> entry.endsWith(".jar"))
                .toList());
    classpath.add(compileStubs().toString());
    Path directory = Files.createDirectories(temp.resolve("builds").resolve(repo.name()));
    return new BuildResult(
        repo.name(),
        commit,
        BuildStatus.SUCCEEDED,
        null,
        21,
        "test",
        "test",
        0,
        Instant.EPOCH,
        Duration.ZERO,
        null,
        List.of(
            new BuildModule(
                "edge",
                "com.example.edge",
                "edge",
                "jar",
                "1.0.0",
                List.of(),
                classpath,
                null,
                null,
                null)),
        directory);
  }

  private Path compileStubs() throws IOException {
    List<Path> sources = new ArrayList<>();
    try (Stream<Path> files = Files.walk(Fixtures.fixture("gateway-stubs"))) {
      files.filter(f -> f.toString().endsWith(".java")).forEach(sources::add);
    }
    Path classes = Files.createDirectories(temp.resolve("stub-classes"));
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null)) {
      boolean ok =
          compiler
              .getTask(
                  null,
                  files,
                  null,
                  List.of(
                      "-d",
                      classes.toString(),
                      "-classpath",
                      System.getProperty("java.class.path")),
                  null,
                  files.getJavaFileObjectsFromPaths(sources))
              .call();
      assertThat(ok).as("the stand-ins compile").isTrue();
    }
    return classes;
  }
}

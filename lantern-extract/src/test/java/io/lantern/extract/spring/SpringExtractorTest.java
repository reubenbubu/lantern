package io.lantern.extract.spring;

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
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that need no Docker, on the {@code spring} fixture: golden facts with every module parsed
 * (degraded mode), and its app module read through javac with Lantern's own test classpath, which
 * has Spring's context, scheduling and Boot annotations but not Spring MVC or Jakarta REST. {@link
 * Project0SpringIT} reads the synthetic test set built in the sandbox.
 */
class SpringExtractorTest {

  private static final long SNAPSHOT = 5;

  private static final String APP = "spring:app/";

  @TempDir Path temp;

  private static List<Fact> extract(List<RepoBuild> inputs) throws IOException {
    List<Fact> facts = new SpringExtractor().extract(inputs, SNAPSHOT);
    Fixtures.checkEnvelope(
        facts,
        inputs.stream().map(RepoBuild::repo).toList(),
        SNAPSHOT,
        SpringExtractor.NAME,
        SpringExtractor.VERSION);
    // Running again on the same input gives the same facts.
    assertThat(new SpringExtractor().extract(inputs, SNAPSHOT)).isEqualTo(facts);
    return facts;
  }

  private Repo fixture() throws IOException {
    return Fixtures.repo("spring", temp.resolve("spring"), "spring");
  }

  @Test
  void parsed() throws IOException {
    Repo repo = fixture();

    List<Fact> facts = extract(List.of(new RepoBuild(repo, Fixtures.head(repo), null)));

    Fixtures.assertGolden("spring/spring-parsed.txt", facts);
    // A secret from the config never reaches a fact.
    assertThat(Fixtures.render(facts)).doesNotContain("s3cret");
  }

  @Test
  void compiledGivesTheSameFactsAsParsed() throws IOException {
    Repo repo = fixture();
    String commit = Fixtures.head(repo);
    RepoBuild built = new RepoBuild(repo, commit, build(repo, commit));

    JavaAnalysis code = JavaExtractor.analyze(List.of(built));
    List<Fact> compiled = new SpringExtractor().extract(code, SNAPSHOT);
    List<Fact> parsed =
        new SpringExtractor().extract(List.of(new RepoBuild(repo, commit, null)), SNAPSHOT);

    assertThat(code.modules())
        .extracting(m -> m.directory() + " " + m.compiled())
        .containsExactly("app true", "web false");
    List<String> compiledApp = app(compiled);
    assertThat(compiledApp).isNotEmpty().allMatch(line -> line.endsWith(" 1.0"));
    assertThat(compiledApp)
        .isEqualTo(app(parsed).stream().map(line -> line.replaceAll(" 0\\.7$", " 1.0")).toList());
  }

  /** The app module's facts, rendered. */
  private static List<String> app(List<Fact> facts) {
    return Fixtures.render(facts).lines().filter(line -> line.contains(" @" + APP)).toList();
  }

  /** A successful build of the app module alone, with Lantern's test classpath as its own. */
  private BuildResult build(Repo repo, String commit) throws IOException {
    List<String> classpath =
        Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
            .filter(entry -> entry.endsWith(".jar"))
            .toList();
    BuildModule app =
        new BuildModule(
            "app",
            "com.example.shop",
            "shop-app",
            "jar",
            "1.0.0",
            List.of(),
            classpath,
            null,
            null,
            null);
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
        List.of(app),
        directory);
  }

  @Test
  void pathsAreJoinedWithOneSlash() {
    assertThat(SpringExtractor.join("", "")).isEqualTo("/");
    assertThat(SpringExtractor.join("/api/orders", "")).isEqualTo("/api/orders");
    assertThat(SpringExtractor.join("", "{id}")).isEqualTo("/{id}");
    assertThat(SpringExtractor.join("/api/", "/{id}")).isEqualTo("/api/{id}");
    assertThat(SpringExtractor.join("legacy", "lines")).isEqualTo("/legacy/lines");
  }
}

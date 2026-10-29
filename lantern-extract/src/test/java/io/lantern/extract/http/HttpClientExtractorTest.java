package io.lantern.extract.http;

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
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that need no Docker, on the {@code http} fixture: golden facts with both modules parsed
 * (degraded mode), and the same facts with them read through javac, with Lantern's own test
 * classpath (Spring's context, beans and Boot annotations) and stand-ins for the HTTP client APIs,
 * Spring MVC and Jakarta REST. {@link Project0HttpIT} reads the synthetic test set built in the
 * sandbox.
 */
class HttpClientExtractorTest {

  private static final long SNAPSHOT = 5;

  @TempDir Path temp;

  private static List<Fact> extract(List<RepoBuild> inputs) throws IOException {
    List<Fact> facts = new HttpClientExtractor().extract(inputs, SNAPSHOT);
    Fixtures.checkEnvelope(
        facts,
        inputs.stream().map(RepoBuild::repo).toList(),
        SNAPSHOT,
        HttpClientExtractor.NAME,
        HttpClientExtractor.VERSION);
    // Running again on the same input gives the same facts.
    assertThat(new HttpClientExtractor().extract(inputs, SNAPSHOT)).isEqualTo(facts);
    return facts;
  }

  private Repo fixture() throws IOException {
    return Fixtures.repo("http", temp.resolve("http"), "http");
  }

  private static List<RepoBuild> unbuilt(Repo repo) {
    return List.of(new RepoBuild(repo, Fixtures.head(repo), null));
  }

  @Test
  void parsed() throws IOException {
    List<Fact> facts = extract(unbuilt(fixture()));

    Fixtures.assertGolden("http/http-parsed.txt", facts);
    String rendered = Fixtures.render(facts);
    // Neither a secret from the config nor credentials or a query written in a URL reach a fact.
    assertThat(rendered)
        .doesNotContain("s3cret")
        .doesNotContain("pa55word")
        .doesNotContain("abc123");
    // A base URL's unfollowed parts are listed only where the base is part of the URL: not for a
    // method whose own URL is a whole URL.
    assertThat(rendered.lines().filter(l -> l.contains("QuotesApi#quote(")))
        .singleElement(InstanceOfAssertFactories.STRING)
        .contains("\"expressions\":[\"region()\"]");
    assertThat(rendered.lines().filter(l -> l.contains("QuotesApi#remove(")))
        .singleElement(InstanceOfAssertFactories.STRING)
        .contains("https://admin.example.com/quotes/{symbol}")
        .doesNotContain("expressions");
  }

  @Test
  void compiledGivesTheSameFactsAsParsed() throws IOException {
    Repo repo = fixture();
    String commit = Fixtures.head(repo);
    JavaAnalysis code =
        JavaExtractor.analyze(List.of(new RepoBuild(repo, commit, build(repo, commit))));
    List<Fact> compiled = new HttpClientExtractor().extract(code, SNAPSHOT);
    List<Fact> parsed = new HttpClientExtractor().extract(unbuilt(repo), SNAPSHOT);

    assertThat(code.modules())
        .extracting(m -> m.directory() + " " + m.compiled())
        .containsExactly("app true", "clients true");
    List<String> lines = withoutConfidence(compiled);
    assertThat(lines).hasSameSizeAs(compiled).isEqualTo(withoutConfidence(parsed));
    assertThat(Fixtures.render(compiled).lines()).allMatch(line -> line.endsWith(" 1.0"));
  }

  private static List<String> withoutConfidence(List<Fact> facts) {
    return Fixtures.render(facts).lines().map(l -> l.replaceAll(" [01]\\.[07]$", "")).toList();
  }

  /**
   * A successful build of the fixture's two modules, with Lantern's test classpath and stand-ins
   * for what that lacks, compiled here with the clients module, which the app module uses.
   */
  private BuildResult build(Repo repo, String commit) throws IOException {
    List<String> classpath =
        new ArrayList<>(
            Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(entry -> entry.endsWith(".jar"))
                .toList());
    classpath.add(compileStubs(repo).toString());
    List<BuildModule> modules = new ArrayList<>();
    for (String module : List.of("app", "clients")) {
      modules.add(
          new BuildModule(
              module,
              "com.example.shop",
              "shop-" + module,
              "jar",
              "1.0.0",
              List.of(),
              classpath,
              null,
              null,
              null));
    }
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
        modules,
        directory);
  }

  private Path compileStubs(Repo repo) throws IOException {
    List<Path> sources = new ArrayList<>();
    for (Path root :
        List.of(
            Fixtures.fixture("spring-web-stubs"),
            Fixtures.fixture("http-client-stubs"),
            repo.path().resolve("clients/src/main/java"))) {
      try (Stream<Path> files = Files.walk(root)) {
        files.filter(f -> f.toString().endsWith(".java")).forEach(sources::add);
      }
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
      assertThat(ok).as("the stand-ins and the clients module compile").isTrue();
    }
    return classes;
  }

  @Test
  void urlsLoseCredentialsQueriesAndFragments() {
    assertThat(HttpClientExtractor.clean("https://u:p@host:8443/a?x=1#f"))
        .isEqualTo("https://host:8443/a");
    assertThat(HttpClientExtractor.clean("${base}/q?amount={amount}")).isEqualTo("${base}/q");
    // A question mark inside a placeholder or expression is not a query.
    assertThat(HttpClientExtractor.clean("${a:?}/x")).isEqualTo("${a:?}/x");
    assertThat(HttpClientExtractor.clean("{x ? y : z}/p")).isEqualTo("{x ? y : z}/p");
    assertThat(HttpClientExtractor.clean("{format(\"http://u:p@h/%s\", id)}"))
        .isEqualTo("{format(\"http://h/%s\", id)}");
  }

  @Test
  void urlsSplitIntoBaseAndPath() {
    assertThat(HttpClientExtractor.split("http://h:8080/api/x"))
        .extracting("base", "path", "absolute")
        .containsExactly("http://h:8080", "/api/x", true);
    assertThat(HttpClientExtractor.split("http://h"))
        .extracting("base", "path", "absolute")
        .containsExactly("http://h", "/", true);
    assertThat(HttpClientExtractor.split("${TAX_URL}/v2/quotes"))
        .extracting("base", "path", "absolute")
        .containsExactly("${TAX_URL}", "/v2/quotes", false);
    assertThat(HttpClientExtractor.split("{url}"))
        .extracting("base", "path", "absolute")
        .containsExactly("{url}", null, false);
    assertThat(HttpClientExtractor.split("/api/x"))
        .extracting("base", "path", "absolute")
        .containsExactly(null, "/api/x", false);
  }

  @Test
  void feignUrlsWithoutASchemeGetHttp() {
    assertThat(HttpClientExtractor.withScheme("inventory:8080/stock"))
        .isEqualTo("http://inventory:8080/stock");
    assertThat(HttpClientExtractor.withScheme("https://h/x")).isEqualTo("https://h/x");
    assertThat(HttpClientExtractor.withScheme("${X}/x")).isEqualTo("${X}/x");
  }

  @Test
  void urlsJoinWithOneSlash() {
    assertThat(HttpClientExtractor.joinUrl("http://h/", "/x")).isEqualTo("http://h/x");
    assertThat(HttpClientExtractor.joinUrl("http://h", "x")).isEqualTo("http://h/x");
    assertThat(HttpClientExtractor.joinUrl("http://h", "")).isEqualTo("http://h");
    assertThat(HttpClientExtractor.joinUrl("http://h/v1?key=k", "/x")).isEqualTo("http://h/v1/x");
    assertThat(HttpClientExtractor.joinUrl("http://h", "https://other/y"))
        .isEqualTo("https://other/y");
  }
}

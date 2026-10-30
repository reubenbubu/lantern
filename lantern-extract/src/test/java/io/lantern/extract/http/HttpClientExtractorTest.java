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
import java.util.Map;
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
    // A fluent request made but not executed where it is made is not a call there.
    assertThat(rendered).doesNotContain("drafts:8080");
    // A RestClient group configurer does not set a WebClient group's base URL, and a method that
    // is not a @Bean does not make the adapter injected.
    assertThat(rendered).doesNotContain("not-alerts").doesNotContain("not-stock");
    assertThat(rendered)
        .contains("http://alerts:8080/alerts")
        .contains("http://stock:8080/api/stock");
    // A client bean is followed only when the choice is certain: duplicate bean names, and a
    // qualifier no bean has, give no call a base URL (the beans themselves still have theirs).
    assertThat(rendered.lines().filter(l -> l.contains(" http-call ")))
        .noneMatch(l -> l.contains("dup-a") || l.contains("dup-b"));
    assertThat(rendered.lines().filter(l -> l.contains("AcrossModules#duplicate(")))
        .singleElement(InstanceOfAssertFactories.STRING)
        .contains("GET /dup")
        .doesNotContain("\"url\"");
    assertThat(rendered.lines().filter(l -> l.contains("ClientCallers#lost(")))
        .singleElement(InstanceOfAssertFactories.STRING)
        .contains("GET /lost")
        .doesNotContain("\"url\"");
    // A base URL's unfollowed parts are listed only where the base is part of the URL: not for a
    // method whose own URL is a whole URL.
    assertThat(rendered.lines().filter(l -> l.contains("QuotesApi#quote(")))
        .singleElement(InstanceOfAssertFactories.STRING)
        .contains("\"expressions\":[\"region()\"]");
    // A URL passed as an argument is followed to each call of the method by its own type's code,
    // through another method and a lambda: a call per place it is made, none left as {target}.
    assertThat(rendered.lines().filter(l -> l.contains("ProxyClient#exchange(")))
        .hasSize(2)
        .noneMatch(l -> l.contains("{target}"))
        .anyMatch(l -> l.contains("\"baseUrl\":\"http://proxy:8080\",") && l.contains("{path}"))
        .anyMatch(l -> l.contains("GET http://proxy:8080/{service}/public"));
    // A method its own type never calls keeps its parameter.
    assertThat(rendered.lines().filter(l -> l.contains("ProxyClient#direct(")))
        .singleElement(InstanceOfAssertFactories.STRING)
        .contains("GET {target}");
    assertThat(rendered.lines().filter(l -> l.contains("QuotesApi#remove(")))
        .singleElement(InstanceOfAssertFactories.STRING)
        .contains("https://admin.example.com/quotes/{symbol}")
        .doesNotContain("expressions");
  }

  @Test
  void loadBalancedClientsNameTheirService() throws IOException {
    List<String> lines = Fixtures.render(extract(unbuilt(fixture()))).lines().toList();
    String callers = "method:com.example.shop.app.LoadBalancedCallers#";
    String beans = "method:com.example.shop.app.LoadBalancedBeans#";

    assertThat(lines)
        // The beans that make clients, load balanced or not, at the method.
        .anyMatch(
            l ->
                l.startsWith(beans + "lbBuilder() http-client-bean web-client ")
                    && l.contains("\"loadBalanced\":true")
                    && l.contains("LoadBalancedBeans.java#15-19 "))
        .anyMatch(
            l ->
                l.startsWith(beans + "plainRestTemplate() http-client-bean rest-template ")
                    && !l.contains("loadBalanced"))
        // Through the @LoadBalanced builder bean: the host is the service.
        .anyMatch(
            l ->
                l.startsWith(callers + "media(java.lang.String) http-call ")
                    && l.contains("\"service\":\"media-service\"")
                    && l.contains("\"loadBalanced\":true"))
        // The @LoadBalanced qualifier at the injection point picks the load-balanced bean.
        .anyMatch(
            l ->
                l.startsWith(callers + "stock() http-call ")
                    && l.contains("\"service\":\"inventory-service\""))
        // The @Primary plain bean: a host, no service.
        .anyMatch(
            l ->
                l.startsWith(callers + "external() http-call ")
                    && !l.contains("service")
                    && !l.contains("loadBalanced"))
        // An lb:// URL names its service.
        .anyMatch(
            l ->
                l.startsWith(callers + "routed() http-call ")
                    && l.contains("\"service\":\"billing\"")
                    && l.contains("\"loadBalanced\":true"))
        // @LoadBalanced on a field.
        .anyMatch(
            l ->
                l.startsWith(callers + "shipping() http-call ")
                    && l.contains("\"service\":\"shipping\""))
        // As a qualifier, @LoadBalanced passes over the @Primary bean.
        .anyMatch(
            l ->
                l.startsWith(callers + "order() http-call GET http://orders/orders ")
                    && l.contains("\"service\":\"orders\""))
        // A bean made from a load-balanced builder.
        .anyMatch(
            l ->
                l.startsWith(callers + "items() http-call GET http://catalog/items ")
                    && l.contains("\"service\":\"catalog\""))
        // A host from a placeholder: load balanced, no service.
        .anyMatch(
            l ->
                l.startsWith(callers + "charge() http-call ")
                    && l.contains("\"loadBalanced\":true")
                    && !l.contains("\"service\""))
        // Credentials in an lb:// URL are neither the service nor recorded.
        .anyMatch(
            l ->
                l.startsWith(callers + "ledger() http-call ")
                    && l.contains("\"service\":\"ledger\""))
        .noneMatch(l -> l.contains("t0ps3cret"));
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

  @Test
  void aValueSubstitutedFromAKnownSecretKeyIsMasked() throws IOException {
    Repo repo =
        Fixtures.repo(
            temp.resolve("jwt"),
            "shop",
            Map.of(
                "pom.xml",
                "<project><modelVersion>4.0.0</modelVersion><groupId>com.example</groupId>"
                    + "<artifactId>shop</artifactId><version>1</version></project>\n",
                "src/main/java/com/example/Remote.java",
                """
                package com.example;

                import org.springframework.cloud.openfeign.FeignClient;
                import org.springframework.web.bind.annotation.GetMapping;

                @FeignClient(name = "remote", url = "http://remote/${jwt.key}")
                public interface Remote {
                  @GetMapping("/things")
                  String things();
                }
                """,
                "src/main/resources/application.properties",
                "jwt.key=literal-test-value-0123456789abcdef\n"));
    JavaAnalysis code =
        JavaExtractor.analyze(List.of(new RepoBuild(repo, Fixtures.head(repo), null)));

    // Without the code's keys the name alone does not show jwt.key is a secret.
    assertThat(Fixtures.render(new HttpClientExtractor().extract(code, SNAPSHOT)))
        .contains("literal-test-value");
    String masked =
        Fixtures.render(
            new HttpClientExtractor().extract(code, SNAPSHOT, java.util.Set.of("jwt.key")));
    assertThat(masked).doesNotContain("literal-test-value").contains("${jwt.key}");
  }
}

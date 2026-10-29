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
 * Tests that need no Docker, on the {@code spring} fixture and the {@code spring-api} fixture whose
 * interfaces it implements: golden facts with every module parsed (degraded mode), and the same
 * facts with the spring fixture read through javac, with Lantern's own test classpath (Spring's
 * context, scheduling and Boot annotations) and stand-ins for Spring MVC and Jakarta REST. {@link
 * Project0SpringIT} reads the synthetic test set built in the sandbox.
 */
class SpringExtractorTest {

  private static final long SNAPSHOT = 5;

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

  /** The spring fixture and the spring-api fixture, whose interfaces the first implements. */
  private List<Repo> fixtures() throws IOException {
    return List.of(
        Fixtures.repo("spring", temp.resolve("spring"), "spring"),
        Fixtures.repo("spring-api", temp.resolve("spring-api"), "spring-api"));
  }

  private static List<RepoBuild> unbuilt(List<Repo> repos) {
    return repos.stream().map(r -> new RepoBuild(r, Fixtures.head(r), null)).toList();
  }

  @Test
  void parsed() throws IOException {
    List<Fact> facts = extract(unbuilt(fixtures()));

    Fixtures.assertGolden("spring/spring-parsed.txt", facts);
    // A secret from the config never reaches a fact.
    assertThat(Fixtures.render(facts)).doesNotContain("s3cret");
  }

  @Test
  void reviewCases() throws IOException {
    List<String> facts = Fixtures.render(extract(unbuilt(fixtures()))).lines().toList();

    assertThat(facts)
        .anyMatch(
            // A path on the custom annotation's meta-annotation counts.
            l ->
                l.startsWith(
                    "method:com.example.shop.web.v3.ItemController#items() endpoint GET"
                        + " /api/v3/items "))
        .anyMatch(
            // An inherited mapping's evidence is in the repository that declares it.
            l ->
                l.startsWith(
                        "method:com.example.shop.web.remote.RemoteOrders#orders() endpoint GET"
                            + " /v4/orders ")
                    && l.contains(" @spring-api:src/main/java/com/example/api/OrdersApi.java#9-9 "))
        .anyMatch(
            // A class whose @Path is on its interface is a resource.
            l ->
                l.startsWith("type:com.example.shop.web.remote.RemoteLegacy stereotype controller")
                    && l.contains(
                        " @spring-api:src/main/java/com/example/api/LegacyApi.java#7-7 "));
  }

  @Test
  void jobsOfJobRunrAndQuartz() throws IOException {
    List<String> facts = Fixtures.render(extract(unbuilt(fixtures()))).lines().toList();

    assertThat(facts)
        .anyMatch(
            // A request handler is a job, named by @Job, scheduled where its request is built.
            l ->
                l.startsWith(
                        "method:com.example.shop.app.jobs.ReminderHandler#run("
                            + "com.example.shop.app.jobs.ReminderRequest) background-job Task's reminder"
                            + " job ")
                    && l.contains("\"kind\":\"job\"")
                    && l.contains("Reminders#remindAt(long,java.time.Instant)")
                    && l.contains("Reminders#remindNow(long)"))
        .anyMatch(
            // A request scheduled recurrently with a cron is on a schedule, like @Scheduled, and
            // a call in another repository counts.
            l ->
                l.startsWith(
                        "method:com.example.shop.app.jobs.AuditHandler#run("
                            + "com.example.api.AuditRequest) scheduled 0 0 1 * * * ")
                    && l.contains("\"trigger\":\"cron\"")
                    && l.contains("method:com.example.api.AuditTrigger#auditNow()")
                    && l.contains("method:com.example.api.AuditTrigger#auditNightly()"))
        .anyMatch(
            // A Quartz job takes the cron of the trigger it is scheduled with.
            l ->
                l.startsWith(
                        "method:com.example.shop.app.jobs.ExpiryJob#execute("
                            + "org.quartz.JobExecutionContext) scheduled 0 15 2 * * ? ")
                    && l.contains("QuartzSetup#schedule(org.quartz.Scheduler)"))
        .anyMatch(
            l ->
                l.startsWith(
                    "method:com.example.shop.app.jobs.DigestJob#executeInternal("
                        + "org.quartz.JobExecutionContext) background-job"
                        + " com.example.shop.app.jobs.DigestJob "))
        .anyMatch(
            // @Recurring resolves its cron like @Scheduled.
            l ->
                l.startsWith(
                        "method:com.example.shop.app.jobs.NightlyJob#cleanUp() scheduled 0 0 3")
                    && l.contains("\"framework\":\"jobrunr\""))
        .anyMatch(
            // A @Recurring interval is a background job.
            l ->
                l.startsWith(
                        "method:com.example.shop.app.jobs.NightlyJob#poll() background-job PT5M ")
                    && l.contains("\"kind\":\"interval\""))
        .anyMatch(
            l ->
                l.startsWith(
                    "method:com.example.shop.app.jobs.NightlyJob#reindex() background-job Manual"
                        + " re-index "));
  }

  @Test
  void compiledGivesTheSameFactsAsParsed() throws IOException {
    List<Repo> repos = fixtures();
    Repo spring = repos.getFirst();
    String commit = Fixtures.head(spring);
    List<RepoBuild> inputs = new ArrayList<>(unbuilt(repos));
    inputs.set(0, new RepoBuild(spring, commit, build(spring, commit)));

    JavaAnalysis code = JavaExtractor.analyze(inputs);
    List<Fact> compiled = new SpringExtractor().extract(code, SNAPSHOT);
    List<Fact> parsed = new SpringExtractor().extract(unbuilt(repos), SNAPSHOT);

    assertThat(code.modules())
        .extracting(m -> m.repo().repo().name() + ":" + m.directory() + " " + m.compiled())
        .containsExactly("spring:app true", "spring:web true", "spring-api: false");
    List<String> lines = withoutConfidence(compiled);
    assertThat(lines).hasSameSizeAs(compiled).isEqualTo(withoutConfidence(parsed));
    assertThat(Fixtures.render(compiled).lines())
        .filteredOn(line -> line.contains(" @spring:"))
        .isNotEmpty()
        .allMatch(line -> line.endsWith(" 1.0"));
  }

  private static List<String> withoutConfidence(List<Fact> facts) {
    return Fixtures.render(facts).lines().map(l -> l.replaceAll(" [01]\\.[07]$", "")).toList();
  }

  /**
   * A successful build of the spring fixture's two modules, with Lantern's test classpath and, for
   * what that lacks, stand-ins for the Spring MVC and Jakarta REST annotations compiled here with
   * the spring-api fixture's interfaces.
   */
  private BuildResult build(Repo repo, String commit) throws IOException {
    List<String> classpath =
        new ArrayList<>(
            Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(entry -> entry.endsWith(".jar"))
                .toList());
    classpath.add(compileStubs().toString());
    List<BuildModule> modules = new ArrayList<>();
    for (String module : List.of("app", "web")) {
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

  private Path compileStubs() throws IOException {
    List<Path> sources = new ArrayList<>();
    for (String root : List.of("spring-web-stubs", "spring-api/src/main/java")) {
      try (Stream<Path> files = Files.walk(Fixtures.fixture(root))) {
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
      assertThat(ok).as("the stand-in annotations compile").isTrue();
    }
    return classes;
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

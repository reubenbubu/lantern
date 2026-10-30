package io.lantern.extract.persistence;

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
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that need no Docker, on the {@code persistence} fixture: golden facts with every module
 * parsed without a classpath (degraded mode), and its app module, which uses Spring JDBC only, read
 * through javac with Lantern's own test classpath, and parsed with that classpath as a failed build
 * leaves it. {@link Project0PersistenceIT} reads the synthetic test set.
 */
class PersistenceExtractorTest {

  private static final long SNAPSHOT = 7;

  private static final String APP = "persistence:app/";

  @TempDir Path temp;

  private static List<Fact> extract(List<RepoBuild> inputs) throws IOException {
    List<Fact> facts = new PersistenceExtractor().extract(inputs, SNAPSHOT);
    Fixtures.checkEnvelope(
        facts,
        inputs.stream().map(RepoBuild::repo).toList(),
        SNAPSHOT,
        PersistenceExtractor.NAME,
        PersistenceExtractor.VERSION);
    // Running again on the same input gives the same facts.
    assertThat(new PersistenceExtractor().extract(inputs, SNAPSHOT)).isEqualTo(facts);
    return facts;
  }

  private Repo fixture() throws IOException {
    return Fixtures.repo("persistence", temp.resolve("persistence"), "persistence");
  }

  @Test
  void parsed() throws IOException {
    Repo repo = fixture();

    List<Fact> facts = extract(List.of(new RepoBuild(repo, Fixtures.head(repo), null)));

    Fixtures.assertGolden("persistence/persistence-parsed.txt", facts);
    // No credential from the config reaches a fact.
    assertThat(Fixtures.render(facts))
        .doesNotContain("s3cret", "hunter2", "tiger", "scott", "user=sa", "shop@");
  }

  @Test
  void aDatasourceUrlAKnownSecretKeyWasSubstitutedIntoIsMasked() throws IOException {
    Repo repo =
        Fixtures.repo(
            temp.resolve("jwt"),
            "shop",
            Map.of(
                "pom.xml",
                "<project><modelVersion>4.0.0</modelVersion><groupId>com.example</groupId>"
                    + "<artifactId>shop</artifactId><version>1</version></project>\n",
                "src/main/java/com/example/Shop.java",
                "package com.example;\n\npublic class Shop {}\n",
                "src/main/resources/application.properties",
                "jwt.key=literal-test-value-0123456789abcdef\n"
                    + "spring.datasource.url=jdbc:postgresql://db:5432/${jwt.key}\n"));
    JavaAnalysis code =
        JavaExtractor.analyze(List.of(new RepoBuild(repo, Fixtures.head(repo), null)));

    assertThat(Fixtures.render(new PersistenceExtractor().extract(code, SNAPSHOT)))
        .contains("literal-test-value");
    String masked =
        Fixtures.render(new PersistenceExtractor().extract(code, SNAPSHOT, Set.of("jwt.key")));
    assertThat(masked).doesNotContain("literal-test-value").contains("\"masked\":true");
  }

  @Test
  void compiledAndParsedWithTheClasspathGiveTheSameSql() throws IOException {
    Repo repo = fixture();
    String commit = Fixtures.head(repo);

    JavaAnalysis code =
        JavaExtractor.analyze(
            List.of(new RepoBuild(repo, commit, build(repo, commit, BuildStatus.SUCCEEDED))));
    List<Fact> compiled = new PersistenceExtractor().extract(code, SNAPSHOT);
    List<Fact> parsed =
        new PersistenceExtractor()
            .extract(
                List.of(new RepoBuild(repo, commit, build(repo, commit, BuildStatus.FAILED))),
                SNAPSHOT);

    assertThat(code.modules())
        .extracting(m -> m.directory() + " " + m.compiled())
        .contains("app true");
    List<String> compiledSql = sql(compiled);
    Fixtures.assertGolden(
        "persistence/persistence-sql.txt",
        compiled.stream().filter(PersistenceExtractorTest::isSql).toList());
    assertThat(compiledSql).isNotEmpty().allMatch(line -> line.endsWith(" 1.0"));
    assertThat(sql(parsed))
        .isEqualTo(compiledSql.stream().map(line -> line.replaceAll(" 1\\.0$", " 0.7")).toList());
  }

  private static boolean isSql(Fact f) {
    return f.evidence().repo().equals("persistence")
        && f.evidence().path().startsWith("app/src/main/java")
        && (f.predicate().equals(PersistenceExtractor.READS_TABLE)
            || f.predicate().equals(PersistenceExtractor.WRITES_TABLE));
  }

  /** The app module's table access, rendered. */
  private static List<String> sql(List<Fact> facts) {
    return Fixtures.render(facts.stream().filter(PersistenceExtractorTest::isSql).toList())
        .lines()
        .filter(line -> line.contains(" @" + APP))
        .toList();
  }

  /** A build of the app module alone, with Lantern's test classpath as its own. */
  private BuildResult build(Repo repo, String commit, BuildStatus status) throws IOException {
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
    Path directory = Files.createDirectories(temp.resolve("builds").resolve(repo.name() + status));
    return new BuildResult(
        repo.name(),
        commit,
        status,
        status == BuildStatus.FAILED ? "test" : null,
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
  void jpaNamesFollowSpringBootsDefaultNaming() {
    assertThat(PersistenceExtractor.jpaName("CustomerOrder")).isEqualTo("customer_order");
    assertThat(PersistenceExtractor.jpaName("orders")).isEqualTo("orders");
    assertThat(PersistenceExtractor.jpaName("ORDERS")).isEqualTo("orders");
    assertThat(PersistenceExtractor.jpaName("Order2Line")).isEqualTo("order2_line");
    assertThat(PersistenceExtractor.jpaName("HTTPLog")).isEqualTo("httplog");
    assertThat(PersistenceExtractor.jpaName("\"OrderItems\"")).isEqualTo("\"order_items\"");
    assertThat(PersistenceExtractor.jpaName("`Mixed`")).isEqualTo("\"mixed\"");
    assertThat(PersistenceExtractor.snakeCase("StockLevel")).isEqualTo("stock_level");
  }
}

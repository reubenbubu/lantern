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
  void myBatisDocumentsAndNonRelationalStores() throws IOException {
    Repo repo = fixture();
    List<String> lines =
        Fixtures.render(extract(List.of(new RepoBuild(repo, Fixtures.head(repo), null))))
            .lines()
            .toList();
    String mapper = "method:com.example.shop.catalog.ProductMapper#";
    String table = "table:com.example.shop:shop-catalog:";
    assertThat(lines)
        // Documents written as records map like classes.
        .anyMatch(
            l ->
                l.startsWith("type:com.example.shop.catalog.ViewEvent MAPS_TO ")
                    && l.contains("view_events"))
        .anyMatch(
            l ->
                l.startsWith("type:com.example.shop.catalog.SearchSuggestion MAPS_TO ")
                    && l.contains("suggestions"))
        // MyBatis annotations: the strings of a value are one statement.
        .anyMatch(l -> l.startsWith(mapper + "findById(long) READS_TABLE " + table + "product "))
        .anyMatch(l -> l.startsWith(mapper + "insert(java.lang.Object) WRITES_TABLE " + table))
        // Text substitution may make the table at run time: nothing.
        .noneMatch(l -> l.startsWith(mapper + "rename("))
        // An XML mapper: an included fragment, <where> and a join.
        .anyMatch(
            l ->
                l.startsWith(
                        mapper
                            + "findByCategory(java.lang.String) READS_TABLE "
                            + table
                            + "category ")
                    && l.contains("mapper/ProductMapper.xml#8-15 "))
        // <set> with a trailing comma, <foreach>, and <choose> taking its first <when>.
        .anyMatch(
            l ->
                l.startsWith(
                    mapper + "archive(long,java.lang.String) WRITES_TABLE " + table + "product "))
        .anyMatch(
            l -> l.startsWith(mapper + "purge(java.util.List) WRITES_TABLE " + table + "product "))
        .anyMatch(
            l -> l.startsWith(mapper + "snapshot() WRITES_TABLE " + table + "product_history "))
        .noneMatch(l -> l.contains(table + "product_draft"))
        // Documents: a collection named after the class or as written, an index; none at run time.
        .anyMatch(
            l ->
                l.startsWith(
                        "type:com.example.shop.catalog.ReadHistory MAPS_TO "
                            + table
                            + "readHistory ")
                    && l.contains("\"framework\":\"mongodb\""))
        .anyMatch(
            l ->
                l.startsWith(
                    "type:com.example.shop.catalog.AuditLog MAPS_TO " + table + "audit_log "))
        .anyMatch(
            l ->
                l.startsWith(
                        "type:com.example.shop.catalog.SearchProduct MAPS_TO " + table + "pms ")
                    && l.contains("\"framework\":\"elasticsearch\""))
        .noneMatch(l -> l.startsWith("type:com.example.shop.catalog.TenantNote "))
        // Hibernate's standard naming, set in config: the name as written (folded as SQL folds it).
        .anyMatch(
            l ->
                l.startsWith(
                    "type:com.example.shop.catalog.CatalogItem MAPS_TO "
                        + table
                        + "shop.catalogitem "))
        // Stores: MongoDB without its credentials, per profile; Elasticsearch's first URI;
        // Cassandra made of its keys.
        .anyMatch(
            l ->
                l.startsWith(
                        "module:com.example.shop:shop-catalog data-store mongodb://mongo:27017/catalog?authSource=admin ")
                    && l.contains("\"database\":\"catalog\"")
                    && l.contains("\"technology\":\"MongoDB\""))
        .anyMatch(
            l ->
                l.startsWith(
                        "module:com.example.shop:shop-catalog data-store mongodb://localhost/catalog-dev ")
                    && l.contains("\"profile\":\"local\""))
        .anyMatch(
            l -> l.startsWith("module:com.example.shop:shop-catalog data-store http://es:9200 "))
        .anyMatch(
            l ->
                l.startsWith(
                        "module:com.example.shop:shop-catalog data-store cassandra://cass1:9042/events ")
                    && l.contains("\"database\":\"events\""))
        .noneMatch(l -> l.contains("s3cret"))
        // An overload runs the same statement; @Delete; <trim>, and a namespaced include whose
        // property fills the fragment; <set> without its leading comma; a start tag over lines.
        .anyMatch(
            l ->
                l.startsWith(
                    mapper
                        + "findByCategory(java.lang.String,int) READS_TABLE "
                        + table
                        + "product "))
        .anyMatch(l -> l.startsWith(mapper + "untag(long) WRITES_TABLE " + table + "product_tag "))
        .anyMatch(
            l ->
                l.startsWith(mapper + "search(java.lang.String) READS_TABLE " + table + "supplier ")
                    && l.contains("mapper/ProductMapper.xml#39-47 "))
        .anyMatch(
            l ->
                l.startsWith(
                    mapper
                        + "retitle(long,java.lang.String) WRITES_TABLE "
                        + table
                        + "catalog_entry "));
    String ledger = "module:com.example.shop:shop-ledger data-store ";
    assertThat(lines)
        // Boot 4's MongoDB keys, made into a URI.
        .anyMatch(l -> l.startsWith(ledger + "mongodb://mongo1:27018/ledger "))
        // A replica set keeps its hosts, and a comma in a password does not split it.
        .anyMatch(
            l ->
                l.startsWith(ledger + "mongodb://m1:27017,m2:27017/shop?replicaSet=rs0 ")
                    && l.contains("\"server\":\"m1:27017\""))
        .noneMatch(l -> l.contains("pa,ss") || l.contains("app:pa"))
        // A token in the URI's options: masked.
        .anyMatch(l -> l.startsWith(ledger + "****** ") && l.contains("\"profile\":\"aws\""))
        .noneMatch(l -> l.contains("t0ken"))
        // YAML lists: their first item; a contact point's own port is not doubled.
        .anyMatch(l -> l.startsWith(ledger + "http://search1:9200 "))
        .anyMatch(l -> l.startsWith(ledger + "cassandra://c1:9042/books "))
        // Neo4j by its URI; Couchbase's bucket alone connects to nothing.
        .anyMatch(
            l -> l.startsWith(ledger + "bolt://neo:7687 ") && l.contains("\"database\":\"graph\""))
        .noneMatch(l -> l.contains("Couchbase"))
        // A naming strategy of the code's own: the name as written, the strategy named.
        .anyMatch(
            l ->
                l.startsWith("type:com.example.shop.ledger.LedgerBook MAPS_TO ")
                    && l.contains("\"namingStrategy\":\"com.example.shop.ledger.PrefixedNaming\""));
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
    // Spring Data splits an acronym off as one part.
    assertThat(PersistenceExtractor.snakeCase("URLMapping")).isEqualTo("url_mapping");
    assertThat(PersistenceExtractor.snakeCase("OrderV2")).isEqualTo("order_v2");
  }
}

package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactRepository;
import io.lantern.core.fact.Validity;
import io.lantern.core.repo.Git;
import io.lantern.core.vocabulary.VocabularyRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A database of facts as V2 to V17 stored them is migrated to claims and observations (V19, ADR
 * 0091): each fact's evidence takes its file's blob from git at its commit, the same sighting at
 * two commits becomes one observation, a link's lineage names the claims of the facts it lists, and
 * a file git does not hold keeps its content hash. It has a database of its own, because the shared
 * test database cannot be rewound.
 */
class ClaimsMigrationIT {

  private static final String HASH = "e".repeat(64);
  private static final String KIND_1 = "1".repeat(64);
  private static final String KIND_2 = "2".repeat(64);
  private static final String GENERATED = "3".repeat(64);
  private static final String LINK = "4".repeat(64);

  @TempDir Path repo;

  @Test
  void storedFactsBecomeClaimsAndObservations() throws IOException {
    git("init", "-q", "-b", "main");
    Files.createDirectories(repo.resolve("src"));
    Files.writeString(repo.resolve("src/A.java"), "class A {}\n");
    git("add", ".");
    git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "one");
    String first = git("rev-parse", "HEAD");
    git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "--allow-empty", "-m", "two");
    String second = git("rev-parse", "HEAD");
    String blob = git("rev-parse", "HEAD:src/A.java");

    try (PostgreSQLContainer postgres = TestcontainersConfiguration.newPostgres()) {
      postgres.start();
      DriverManagerDataSource dataSource =
          new DriverManagerDataSource(
              postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
      Flyway.configure().dataSource(dataSource).target("17").load().migrate();
      JdbcClient jdbc = JdbcClient.create(dataSource);
      jdbc.sql("INSERT INTO repositories (name, path) VALUES ('shop', :path)")
          .param("path", repo.toRealPath().toString())
          .update();
      // The type's kind, seen at both commits; a fact of a file git does not hold; a link that
      // lists the kind fact of the second commit.
      insert(jdbc, KIND_1, "type:p.A", "kind", "class", "{}", evidence(first, "src/A.java"), 1, 2L);
      insert(
          jdbc, KIND_2, "type:p.A", "kind", "class", "{}", evidence(second, "src/A.java"), 2, null);
      insert(
          jdbc,
          GENERATED,
          "type:p.B",
          "kind",
          "class",
          "{}",
          evidence(second, "target/generated/B.java"),
          2,
          null);
      jdbc.sql(
              """
              INSERT INTO facts (id, subject, predicate, object_kind, object, attributes, origin,
                                 evidence, confidence, validity)
              VALUES (:id, 'service:shop', 'CALLS_HTTP', 'entity', 'service:billing',
                      CAST(:attributes AS jsonb),
                      '{"kind":"static","name":"cross-repo-links","version":"1"}',
                      CAST(:evidence AS jsonb), 0.9, int8range(2, NULL))
              """)
          .param("id", LINK)
          .param("attributes", "{\"protocol\":\"http\",\"facts\":[\"" + KIND_2 + "\"]}")
          .param("evidence", evidence(second, "src/A.java"))
          .update();

      Flyway.configure().dataSource(dataSource).load().migrate();

      assertThat(
              jdbc.sql("SELECT count(*) FROM pg_tables WHERE tablename = 'facts'")
                  .query(Long.class)
                  .single())
          .isZero();
      VocabularyRegistry registry =
          new VocabularyRegistry(
              jdbc, new TransactionTemplate(new JdbcTransactionManager(dataSource)));
      FactStoreConfiguration.loadVocabulary(registry);
      FactRepository facts = new FactRepository(jdbc, registry);
      List<Fact> held = facts.findValidAt(2);
      assertThat(held).hasSize(3);
      Fact kind = only(held, "type:p.A");
      // One sighting at two commits is one observation, holding from the first.
      assertThat(kind.validity()).isEqualTo(Validity.from(1));
      assertThat(kind.evidence().blob()).isEqualTo(blob);
      assertThat(kind.evidence().contentHash()).isNull();
      assertThat(kind.claimId()).isEqualTo(registry.current().claimId(kind));
      assertThat(facts.findValidAt(1)).containsExactly(kind);
      Fact generated = only(held, "type:p.B");
      assertThat(generated.evidence().blob()).isNull();
      assertThat(generated.evidence().contentHash()).isEqualTo(HASH);
      Fact link = only(held, "service:shop");
      assertThat(link.lineage()).containsExactly(kind.claimId());
      assertThat(link.claimId()).isEqualTo(registry.current().claimId(link));
      assertThat(jdbc.sql("SELECT count(*) FROM claims").query(Long.class).single()).isEqualTo(3L);
    }
  }

  private static Fact only(List<Fact> facts, String subject) {
    return facts.stream().filter(f -> f.subject().toString().equals(subject)).findFirst().get();
  }

  private static String evidence(String commit, String path) {
    return "{\"repo\":\"shop\",\"commit\":\""
        + commit
        + "\",\"path\":\""
        + path
        + "\",\"lines\":{\"start\":1,\"end\":1},\"contentHash\":\""
        + HASH
        + "\"}";
  }

  private static void insert(
      JdbcClient jdbc,
      String id,
      String subject,
      String predicate,
      String value,
      String attributes,
      String evidence,
      long from,
      Long to) {
    jdbc.sql(
            """
            INSERT INTO facts (id, subject, predicate, object_kind, object, attributes, origin,
                               evidence, confidence, validity)
            VALUES (:id, :subject, :predicate, 'string', :value, CAST(:attributes AS jsonb),
                    '{"kind":"static","name":"java","version":"3"}', CAST(:evidence AS jsonb),
                    1, int8range(:from, CAST(:to AS bigint), '[)'))
            """)
        .param("id", id)
        .param("subject", subject)
        .param("predicate", predicate)
        .param("value", value)
        .param("attributes", attributes)
        .param("evidence", evidence)
        .param("from", from)
        .param("to", to)
        .update();
  }

  private String git(String... args) {
    Git.Result result = Git.run(repo, args);
    assertThat(result.ok()).as(result.err()).isTrue();
    return result.out();
  }
}

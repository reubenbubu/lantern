package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactRepository;
import io.lantern.core.repo.Repo;
import io.lantern.core.repo.RepoSettings;
import io.lantern.core.vocabulary.VocabularyRegistry;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.sql.SqlExtractor;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The SQL extractor's types are loaded on startup, and its facts can be stored. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SqlFactsIT {

  @Autowired VocabularyRegistry vocabulary;
  @Autowired FactRepository facts;
  @Autowired JdbcClient jdbc;

  @TempDir Path temp;

  @Test
  void sqlTypesAreLoadedOnStartup() {
    assertThat(vocabulary.current().types()).containsAll(SqlExtractor.vocabulary());
  }

  @Test
  void extractedFactsAreStored() {
    TestGitRepo git = TestGitRepo.init(temp);
    git.commit(
        "pom.xml",
        """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>orders</artifactId>
          <version>1.0.0</version>
        </project>
        """);
    String commit =
        git.commit(
            "src/main/resources/db/migration/V1__orders.sql",
            """
            CREATE TABLE customer (id BIGINT PRIMARY KEY, email VARCHAR(200) UNIQUE);
            CREATE TABLE orders (
                id          BIGINT PRIMARY KEY,
                customer_id BIGINT NOT NULL REFERENCES customer (id)
            );
            """);
    Repo repo = new Repo("orders", git.dir().toAbsolutePath(), RepoSettings.DEFAULT);

    List<Fact> extracted =
        new SqlExtractor().extract(List.of(new RepoBuild(repo, commit, null)), 1);

    assertThat(extracted)
        .extracting(Fact::predicate)
        .contains(
            "CONTAINS",
            SqlExtractor.KIND,
            SqlExtractor.DATA_TYPE,
            SqlExtractor.NULLABLE,
            SqlExtractor.POSITION,
            SqlExtractor.PRIMARY_KEY,
            SqlExtractor.UNIQUE_KEY,
            SqlExtractor.REFERENCES);
    try {
      for (Fact fact : extracted) {
        assertThat(facts.save(fact)).isTrue();
        assertThat(facts.findById(fact.id())).contains(fact);
      }
    } finally {
      for (Fact fact : extracted) {
        jdbc.sql("DELETE FROM facts WHERE id = :id").param("id", fact.id()).update();
      }
    }
  }
}

package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactRepository;
import io.lantern.core.repo.Repo;
import io.lantern.core.repo.RepoSettings;
import io.lantern.core.vocabulary.VocabularyRegistry;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.config.ConfigExtractor;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The config extractor's types are loaded on startup, and its facts can be stored. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ConfigFactsIT {

  @Autowired VocabularyRegistry vocabulary;
  @Autowired FactRepository facts;
  @Autowired JdbcClient jdbc;

  @TempDir Path temp;

  @Test
  void configTypesAreLoadedOnStartup() {
    assertThat(vocabulary.current().types()).containsAll(ConfigExtractor.vocabulary());
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
    git.commit(
        "src/main/resources/application-local.yml",
        """
        clients:
          payments:
            url: http://localhost:8082
        """);
    String commit =
        git.commit(
            "src/main/resources/application.yml",
            """
            spring:
              application:
                name: orders
              datasource:
                password: s3cret
            clients:
              payments:
                url: http://payments:8080
              tax:
                url: ${TAX_SERVICE_URL}
            """);
    Repo repo = new Repo("orders", git.dir().toAbsolutePath(), RepoSettings.DEFAULT);

    List<Fact> extracted =
        new ConfigExtractor().extract(List.of(new RepoBuild(repo, commit, null)), 1);

    assertThat(extracted)
        .extracting(f -> f.attributes().toString())
        .contains(
            "{\"key\":\"clients.payments.url\",\"profile\":\"local\"}",
            "{\"key\":\"clients.tax.url\",\"unresolved\":[\"TAX_SERVICE_URL\"]}",
            "{\"key\":\"spring.datasource.password\",\"masked\":true}");
    try {
      for (Fact fact : extracted) {
        assertThat(facts.save(fact)).isTrue();
        // Stored, it is an observation of its claim (ADR 0091).
        assertThat(facts.findById(fact.id()).map(Fact::claimId)).isPresent();
        assertThat(facts.findById(fact.id()).map(f -> f.stored(null, f.validity()))).contains(fact);
      }
    } finally {
      for (Fact fact : extracted) {
        jdbc.sql("DELETE FROM observations WHERE id = :id").param("id", fact.id()).update();
      }
    }
  }
}

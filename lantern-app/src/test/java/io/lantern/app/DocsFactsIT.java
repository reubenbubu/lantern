package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactRepository;
import io.lantern.core.repo.Repo;
import io.lantern.core.repo.RepoSettings;
import io.lantern.core.vocabulary.VocabularyRegistry;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.docs.DocsExtractor;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The docs extractor's types are loaded on startup, and its facts can be stored. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DocsFactsIT {

  @Autowired VocabularyRegistry vocabulary;
  @Autowired FactRepository facts;
  @Autowired JdbcClient jdbc;

  @TempDir Path temp;

  @Test
  void docsTypesAreLoadedOnStartup() {
    assertThat(vocabulary.current().types()).containsAll(DocsExtractor.vocabulary());
  }

  @Test
  void extractedFactsAreStored() {
    TestGitRepo git = TestGitRepo.init(temp);
    git.commit("README.md", "# Orders\n\nOrders keeps the orders of the shop.\n");
    String commit =
        git.commit(
            "docs/adr/0003-use-postgres.md",
            """
            # 3. Use PostgreSQL

            Date: 2024-03-01

            ## Status

            Accepted

            ## Context

            Orders need a transactional store.

            ## Decision

            We use PostgreSQL.

            ## Consequences

            One more database to run.
            """);
    Repo repo = new Repo("orders", git.dir().toAbsolutePath(), RepoSettings.DEFAULT);

    List<Fact> extracted =
        new DocsExtractor().extract(List.of(new RepoBuild(repo, commit, null)), 1);

    assertThat(extracted)
        .extracting(Fact::predicate)
        .contains(
            DocsExtractor.CLAIM,
            DocsExtractor.TITLE,
            DocsExtractor.NUMBER,
            DocsExtractor.STATUS,
            DocsExtractor.DATE,
            DocsExtractor.CONTEXT,
            DocsExtractor.OUTCOME,
            DocsExtractor.CONSEQUENCES);
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

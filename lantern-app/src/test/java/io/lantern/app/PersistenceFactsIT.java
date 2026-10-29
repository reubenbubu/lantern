package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactRepository;
import io.lantern.core.repo.Repo;
import io.lantern.core.repo.RepoSettings;
import io.lantern.core.vocabulary.VocabularyRegistry;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.persistence.PersistenceExtractor;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The persistence extractor's types are loaded on startup, and its facts can be stored. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PersistenceFactsIT {

  @Autowired VocabularyRegistry vocabulary;
  @Autowired FactRepository facts;
  @Autowired JdbcClient jdbc;

  @TempDir Path temp;

  @Test
  void persistenceTypesAreLoadedOnStartup() {
    assertThat(vocabulary.current().types()).containsAll(PersistenceExtractor.vocabulary());
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
        "src/main/resources/application.yml",
        """
        spring:
          datasource:
            url: jdbc:postgresql://orders-db:5432/orders
        """);
    git.commit(
        "src/main/java/com/example/orders/Order.java",
        """
        package com.example.orders;

        import jakarta.persistence.Entity;
        import jakarta.persistence.Table;

        @Entity
        @Table(name = "orders")
        public class Order {}
        """);
    String commit =
        git.commit(
            "src/main/java/com/example/orders/OrderRepository.java",
            """
        package com.example.orders;

        import org.springframework.data.jpa.repository.JpaRepository;
        import org.springframework.data.jpa.repository.Query;

        public interface OrderRepository extends JpaRepository<Order, Long> {
          @Query(value = "UPDATE orders SET status = 'DONE'", nativeQuery = true)
          void finish();
        }
        """);
    Repo repo = new Repo("orders", git.dir().toAbsolutePath(), RepoSettings.DEFAULT);

    List<Fact> extracted =
        new PersistenceExtractor().extract(List.of(new RepoBuild(repo, commit, null)), 1);

    assertThat(extracted)
        .extracting(Fact::predicate)
        .containsExactlyInAnyOrder(
            PersistenceExtractor.DATASOURCE,
            PersistenceExtractor.MAPS_TO,
            PersistenceExtractor.REPOSITORY_FOR,
            PersistenceExtractor.WRITES_TABLE);
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

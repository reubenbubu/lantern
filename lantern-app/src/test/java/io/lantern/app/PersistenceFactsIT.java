package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactRepository;
import io.lantern.core.fact.Origin;
import io.lantern.core.repo.Repo;
import io.lantern.core.repo.RepoSettings;
import io.lantern.core.vocabulary.VocabularyRegistry;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.config.ModuleValues;
import io.lantern.extract.persistence.PersistenceExtractor;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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

    RepoBuild build = new RepoBuild(repo, commit, null);
    List<Fact> extracted = new ArrayList<>(new PersistenceExtractor().extract(List.of(build), 1));
    // The datasource, its entity and its address come from linking's reading of config (#560).
    extracted.addAll(
        PersistenceExtractor.datasources(
            ConfigExtractor.read(build).stream().map(ModuleValues::of).toList(),
            Set.of(),
            new Origin(Origin.Kind.STATIC, "values", "1"),
            1));

    assertThat(extracted)
        .extracting(Fact::predicate)
        .containsExactlyInAnyOrder(
            PersistenceExtractor.DATASOURCE,
            PersistenceExtractor.HAS_DATASOURCE,
            PersistenceExtractor.CONNECTS_TO,
            PersistenceExtractor.MAPS_TO,
            PersistenceExtractor.REPOSITORY_FOR,
            PersistenceExtractor.WRITES_TABLE);
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

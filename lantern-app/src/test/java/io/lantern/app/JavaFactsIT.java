package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactRepository;
import io.lantern.core.repo.Repo;
import io.lantern.core.repo.RepoSettings;
import io.lantern.core.vocabulary.VocabularyRegistry;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.java.JavaExtractor;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The Java extractor's types are loaded on startup, and its facts can be stored. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JavaFactsIT {

  @Autowired VocabularyRegistry vocabulary;
  @Autowired FactRepository facts;
  @Autowired JdbcClient jdbc;

  @TempDir Path temp;

  @Test
  void javaTypesAreLoadedOnStartup() {
    assertThat(vocabulary.current().types()).containsAll(JavaExtractor.vocabulary());
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
            "src/main/java/com/example/orders/Order.java",
            """
            package com.example.orders;

            public class Order implements Comparable<Order> {
              private final String id;

              public Order(String id) {
                this.id = id;
              }

              public int compareTo(Order other) {
                return id.compareTo(other.id());
              }

              String id() {
                return id;
              }
            }
            """);
    Repo repo = new Repo("orders", git.dir().toAbsolutePath(), RepoSettings.DEFAULT);

    List<Fact> extracted =
        new JavaExtractor().extract(List.of(new RepoBuild(repo, commit, null)), 1);

    assertThat(extracted)
        .extracting(Fact::predicate)
        .contains("CONTAINS", "CALLS", "IMPLEMENTS", JavaExtractor.KIND);
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

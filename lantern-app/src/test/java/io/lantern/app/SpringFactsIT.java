package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactRepository;
import io.lantern.core.repo.Repo;
import io.lantern.core.repo.RepoSettings;
import io.lantern.core.vocabulary.VocabularyRegistry;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.spring.SpringExtractor;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The Spring extractor's types are loaded on startup, and its facts can be stored. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SpringFactsIT {

  @Autowired VocabularyRegistry vocabulary;
  @Autowired FactRepository facts;
  @Autowired JdbcClient jdbc;

  @TempDir Path temp;

  @Test
  void springTypesAreLoadedOnStartup() {
    assertThat(vocabulary.current().types()).containsAll(SpringExtractor.vocabulary());
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
        orders:
          cron: "0 0 1 * * *"
        """);
    git.commit(
        "src/main/java/com/example/orders/OrdersApplication.java",
        """
        package com.example.orders;

        import org.springframework.boot.autoconfigure.SpringBootApplication;

        @SpringBootApplication
        public class OrdersApplication {
          public static void main(String[] args) {}
        }
        """);
    git.commit(
        "src/main/java/com/example/orders/OrderController.java",
        """
        package com.example.orders;

        import org.springframework.context.annotation.Profile;
        import org.springframework.web.bind.annotation.GetMapping;
        import org.springframework.web.bind.annotation.RestController;

        @RestController
        @Profile("api")
        public class OrderController {
          @GetMapping("/orders")
          public String orders() {
            return "";
          }
        }
        """);
    String commit =
        git.commit(
            "src/main/java/com/example/orders/Purge.java",
            """
            package com.example.orders;

            import org.springframework.context.annotation.Bean;
            import org.springframework.context.annotation.Configuration;
            import org.springframework.scheduling.annotation.Scheduled;

            @Configuration
            public class Purge {
              @Scheduled(cron = "${orders.cron}")
              public void purge() {}

              @Bean
              String name() {
                return "orders";
              }
            }
            """);
    Repo repo = new Repo("orders", git.dir().toAbsolutePath(), RepoSettings.DEFAULT);

    List<Fact> extracted =
        new SpringExtractor().extract(List.of(new RepoBuild(repo, commit, null)), 1);

    assertThat(extracted)
        .extracting(Fact::predicate)
        .containsExactlyInAnyOrder(
            SpringExtractor.MAIN_CLASS,
            SpringExtractor.STEREOTYPE,
            SpringExtractor.STEREOTYPE,
            SpringExtractor.STEREOTYPE,
            SpringExtractor.PROFILE_GATE,
            // The endpoint and the schedule as written; linking resolves them (#519).
            SpringExtractor.HANDLED_BY,
            SpringExtractor.SCHEDULED_WRITTEN,
            SpringExtractor.BEAN);
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

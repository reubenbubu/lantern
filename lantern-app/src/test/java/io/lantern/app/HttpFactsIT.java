package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactRepository;
import io.lantern.core.repo.Repo;
import io.lantern.core.repo.RepoSettings;
import io.lantern.core.vocabulary.VocabularyRegistry;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.http.HttpClientExtractor;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The HTTP client extractor's types are loaded on startup, and its facts can be stored. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class HttpFactsIT {

  @Autowired VocabularyRegistry vocabulary;
  @Autowired FactRepository facts;
  @Autowired JdbcClient jdbc;

  @TempDir Path temp;

  @Test
  void httpClientTypesAreLoadedOnStartup() {
    assertThat(vocabulary.current().types()).containsAll(HttpClientExtractor.vocabulary());
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
        clients:
          payments: http://payments:8080
        """);
    git.commit(
        "src/main/java/com/example/orders/OrdersApplication.java",
        """
        package com.example.orders;

        import org.springframework.cloud.openfeign.EnableFeignClients;

        @EnableFeignClients
        public class OrdersApplication {
          private final CatalogClient catalog;

          OrdersApplication(CatalogClient catalog) {
            this.catalog = catalog;
          }
        }
        """);
    git.commit(
        "src/main/java/com/example/orders/CatalogClient.java",
        """
        package com.example.orders;

        import org.springframework.cloud.openfeign.FeignClient;
        import org.springframework.web.bind.annotation.GetMapping;

        @FeignClient("catalog")
        public interface CatalogClient {
          @GetMapping("/products")
          String products();
        }
        """);
    String commit =
        git.commit(
            "src/main/java/com/example/orders/Payments.java",
            """
            package com.example.orders;

            import org.springframework.beans.factory.annotation.Value;
            import org.springframework.web.client.RestTemplate;

            public class Payments {
              private final RestTemplate rest = new RestTemplate();

              @Value("${clients.payments}")
              private String url;

              String pay() {
                return rest.postForObject(url + "/pay", null, String.class);
              }
            }
            """);
    Repo repo = new Repo("orders", git.dir().toAbsolutePath(), RepoSettings.DEFAULT);

    List<Fact> extracted =
        new HttpClientExtractor().extract(List.of(new RepoBuild(repo, commit, null)), 1);

    assertThat(extracted)
        .extracting(Fact::predicate)
        .containsExactlyInAnyOrder(
            HttpClientExtractor.HTTP_CLIENT,
            HttpClientExtractor.HTTP_CALL,
            HttpClientExtractor.HTTP_CALL,
            HttpClientExtractor.ENABLES,
            HttpClientExtractor.INJECTS,
            HttpClientExtractor.INJECTS);
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

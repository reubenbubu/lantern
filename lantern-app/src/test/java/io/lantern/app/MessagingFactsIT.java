package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactRepository;
import io.lantern.core.repo.Repo;
import io.lantern.core.repo.RepoSettings;
import io.lantern.core.vocabulary.VocabularyRegistry;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.messaging.MessagingExtractor;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The messaging extractor's types are loaded on startup, and its facts can be stored. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MessagingFactsIT {

  @Autowired VocabularyRegistry vocabulary;
  @Autowired FactRepository facts;
  @Autowired JdbcClient jdbc;

  @TempDir Path temp;

  @Test
  void messagingTypesAreLoadedOnStartup() {
    assertThat(vocabulary.current().types()).containsAll(MessagingExtractor.vocabulary());
    assertThat(vocabulary.current().isA(MessagingExtractor.LISTENER, "entry-point")).isTrue();
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
          topic: orders.placed
        """);
    git.commit(
        "src/main/java/com/example/orders/OrderEvents.java",
        """
        package com.example.orders;

        import org.springframework.beans.factory.annotation.Value;
        import org.springframework.jms.annotation.JmsListener;
        import org.springframework.kafka.core.KafkaTemplate;

        public class OrderEvents {
          private final KafkaTemplate<String, String> kafka;

          @Value("${orders.topic}")
          private String topic;

          public OrderEvents(KafkaTemplate<String, String> kafka) {
            this.kafka = kafka;
          }

          public void placed(String id) {
            kafka.send(topic, id);
          }

          @JmsListener(destination = "orders.cancelled")
          public void cancelled(String id) {}
        }
        """);
    String commit =
        git.commit(
            "src/main/java/com/example/orders/Bindings.java",
            """
            package com.example.orders;

            import org.springframework.amqp.core.Binding;
            import org.springframework.amqp.core.BindingBuilder;
            import org.springframework.amqp.core.Queue;
            import org.springframework.amqp.core.TopicExchange;

            public class Bindings {
              Binding binding() {
                return BindingBuilder.bind(new Queue("q"))
                    .to(new TopicExchange("x"))
                    .with("order.*");
              }
            }
            """);
    Repo repo = new Repo("orders", git.dir().toAbsolutePath(), RepoSettings.DEFAULT);

    List<Fact> extracted =
        new MessagingExtractor().extract(List.of(new RepoBuild(repo, commit, null)), 1);

    assertThat(extracted)
        .extracting(Fact::predicate)
        .containsExactlyInAnyOrder(
            MessagingExtractor.SEND, MessagingExtractor.LISTENER, MessagingExtractor.BINDING);
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

package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.FactJson;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.core.vocabulary.Vocabulary;
import io.lantern.core.vocabulary.VocabularyRegistry;
import io.lantern.core.vocabulary.VocabularyType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * A database that holds every source's types as they were before #518 takes the key attributes, the
 * new parents and {@code repo} as a built-in type at startup, as ordinary new revisions (ADR 0002
 * rules, ADR 0090). It has a database of its own, because the shared test database cannot be
 * rewound.
 */
class KeyAttributesUpgradeIT {

  @Test
  void anExistingDatabaseTakesTheKeyAttributesAndTheNewParents() throws IOException {
    try (PostgreSQLContainer postgres = TestcontainersConfiguration.newPostgres()) {
      postgres.start();
      DriverManagerDataSource dataSource =
          new DriverManagerDataSource(
              postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
      Flyway.configure().dataSource(dataSource).load().migrate();
      VocabularyRegistry registry =
          new VocabularyRegistry(
              JdbcClient.create(dataSource),
              new TransactionTemplate(new JdbcTransactionManager(dataSource)));

      // An installation made before: each source's old types, in the order the app applies them.
      JsonNode old = oldTypes();
      for (Map.Entry<String, JsonNode> source : old.properties()) {
        List<VocabularyType> types = new ArrayList<>();
        source.getValue().forEach(node -> types.add(VocabularyType.fromJson(node)));
        registry.apply(source.getKey(), types);
      }
      long before = registry.current().revision();
      assertThat(registry.current().isA("PUBLISHES", BuiltInTypes.DEPENDS_ON)).isTrue();
      assertThat(registry.owner(BuiltInTypes.REPO)).contains("redis-extractor");

      // Starting the app again applies the changes, and nothing refuses them.
      FactStoreConfiguration.loadVocabulary(registry);

      Vocabulary now = registry.current();
      assertThat(now.revision()).isGreaterThan(before);
      assertThat(now.isA("PUBLISHES", BuiltInTypes.INTERACTS_WITH)).isTrue();
      assertThat(now.isA("PUBLISHES", BuiltInTypes.DEPENDS_ON)).isFalse();
      assertThat(now.isA("person", BuiltInTypes.ACTOR)).isTrue();
      assertThat(now.isA("message-broker", BuiltInTypes.CONTAINER)).isTrue();
      assertThat(now.keyAttributes("endpoint")).containsExactly("httpMethod", "path");
      assertThat(registry.at(before).isA("PUBLISHES", BuiltInTypes.DEPENDS_ON)).isTrue();
      assertThat(registry.at(before).find("endpoint").orElseThrow().keyAttributes()).isEmpty();
      // The repository type keeps its definition, so its first owner keeps it (ADR 0002).
      assertThat(now.find(BuiltInTypes.REPO))
          .isEqualTo(registry.at(before).find(BuiltInTypes.REPO));

      // A third start changes nothing.
      long after = now.revision();
      FactStoreConfiguration.loadVocabulary(registry);
      assertThat(registry.current().revision()).isEqualTo(after);
    }
  }

  /** Every source's types before #518, by source in the order the app applies them. */
  static JsonNode oldTypes() throws IOException {
    try (InputStream in =
        KeyAttributesUpgradeIT.class.getResourceAsStream(
            "/vocabulary/vocabulary-before-518.json")) {
      return FactJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
  }
}

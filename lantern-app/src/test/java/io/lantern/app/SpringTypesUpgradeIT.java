package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.FactJson;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.core.vocabulary.VocabularyRegistry;
import io.lantern.core.vocabulary.VocabularyType;
import io.lantern.extract.spring.SpringExtractor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * A database that holds the Spring extractor's types as they were before #263 (no {@code
 * background-job}, {@code scheduled} without {@code framework}) accepts the current ones as one
 * additive revision. It has a database of its own, because the shared test database cannot be
 * rewound.
 */
class SpringTypesUpgradeIT {

  private static List<VocabularyType> oldTypes() throws IOException {
    try (InputStream in =
        SpringTypesUpgradeIT.class.getResourceAsStream(
            "/vocabulary/spring-types-before-263.json")) {
      JsonNode array = FactJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
      List<VocabularyType> types = new ArrayList<>();
      array.forEach(node -> types.add(VocabularyType.fromJson(node)));
      return types;
    }
  }

  @Test
  void anExistingDatabaseGetsTheJobTypesAsOneRevision() throws IOException {
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

      // The state of an installation made before: the built-in types, then the old Spring ones.
      registry.apply(BuiltInTypes.SOURCE, BuiltInTypes.definitions());
      long old = registry.apply(SpringExtractor.VOCABULARY_SOURCE, oldTypes());
      long before = registry.current().revision();
      assertThat(registry.current().find("background-job")).isEmpty();

      // Starting the app again adds the new types and attributes, and only that.
      FactStoreConfiguration.loadVocabulary(registry);

      assertThat(registry.current().revision()).isGreaterThan(before);
      assertThat(registry.current().find("background-job")).isPresent();
      assertThat(registry.current().isA("background-job", "entry-point")).isTrue();
      assertThat(registry.current().find("scheduled").orElseThrow().attributeSchema().toJson())
          .asString()
          .contains("framework");
      assertThat(registry.at(old).find("background-job")).isEmpty();

      // A third start changes nothing.
      long after = registry.current().revision();
      FactStoreConfiguration.loadVocabulary(registry);
      assertThat(registry.current().revision()).isEqualTo(after);
    }
  }
}

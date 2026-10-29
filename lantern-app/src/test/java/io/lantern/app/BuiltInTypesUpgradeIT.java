package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.FactRepository;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.core.vocabulary.C4Level;
import io.lantern.core.vocabulary.VocabularyRegistry;
import io.lantern.core.vocabulary.VocabularyType;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.docs.DocsExtractor;
import io.lantern.extract.http.HttpClientExtractor;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.extract.maven.MavenExtractor;
import io.lantern.extract.messaging.MessagingExtractor;
import io.lantern.extract.persistence.PersistenceExtractor;
import io.lantern.extract.spring.SpringExtractor;
import io.lantern.extract.sql.SqlExtractor;
import io.lantern.link.ClaimChecks;
import io.lantern.link.CrossRepoLinks;
import io.lantern.link.DeployableUnits;
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
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;

/**
 * Starts the app's vocabulary the way {@code FactStoreConfiguration} does, on a database that
 * already holds the built-in types as they were before #90 (library extends container, module and
 * package extend component). It has a database of its own, because the shared test database cannot
 * be rewound to an older revision.
 */
class BuiltInTypesUpgradeIT {

  static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

  /** The built-in types as they were before #90. */
  static List<VocabularyType> oldBuiltIns() throws IOException {
    try (InputStream in =
        BuiltInTypesUpgradeIT.class.getResourceAsStream(
            "/vocabulary/built-in-types-before-90.json")) {
      JsonNode array = FactJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
      List<VocabularyType> types = new ArrayList<>();
      array.forEach(node -> types.add(VocabularyType.fromJson(node)));
      return types;
    }
  }

  @Test
  void startingTheAppOnADatabaseWithTheOldBuiltInTypesAddsOneRevision() throws IOException {
    try (PostgreSQLContainer postgres =
        new PostgreSQLContainer(
            DockerImageName.parse(TestcontainersConfiguration.composePostgresImage()))) {
      postgres.start();
      DriverManagerDataSource dataSource =
          new DriverManagerDataSource(
              postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
      Flyway.configure().dataSource(dataSource).load().migrate();
      JdbcClient jdbc = JdbcClient.create(dataSource);
      VocabularyRegistry registry =
          new VocabularyRegistry(
              jdbc, new TransactionTemplate(new JdbcTransactionManager(dataSource)));
      FactRepository facts = new FactRepository(jdbc, registry);

      // The state an existing installation is in: old built-ins, then the extractors' types.
      long oldBuiltInsRevision = registry.apply(BuiltInTypes.SOURCE, oldBuiltIns());
      registry.apply(MavenExtractor.VOCABULARY_SOURCE, MavenExtractor.vocabulary());
      registry.apply(JavaExtractor.VOCABULARY_SOURCE, JavaExtractor.vocabulary());
      registry.apply(ConfigExtractor.VOCABULARY_SOURCE, ConfigExtractor.vocabulary());
      registry.apply(DocsExtractor.VOCABULARY_SOURCE, DocsExtractor.vocabulary());
      registry.apply(SqlExtractor.VOCABULARY_SOURCE, SqlExtractor.vocabulary());
      registry.apply(SpringExtractor.VOCABULARY_SOURCE, SpringExtractor.vocabulary());
      registry.apply(PersistenceExtractor.VOCABULARY_SOURCE, PersistenceExtractor.vocabulary());
      registry.apply(HttpClientExtractor.VOCABULARY_SOURCE, HttpClientExtractor.vocabulary());
      registry.apply(MessagingExtractor.VOCABULARY_SOURCE, MessagingExtractor.vocabulary());
      registry.apply(DeployableUnits.VOCABULARY_SOURCE, DeployableUnits.vocabulary());
      registry.apply(CrossRepoLinks.VOCABULARY_SOURCE, CrossRepoLinks.vocabulary());
      registry.apply(ClaimChecks.VOCABULARY_SOURCE, ClaimChecks.vocabulary());
      long before = registry.current().revision();
      Fact stored = dependsOn();
      assertThat(facts.save(stored)).isTrue();
      assertThat(registry.current().c4Level(BuiltInTypes.LIBRARY)).contains(C4Level.CONTAINER);
      assertThat(registry.current().c4Level(BuiltInTypes.MODULE)).contains(C4Level.COMPONENT);
      assertThat(registry.current().c4Level(BuiltInTypes.PACKAGE)).contains(C4Level.COMPONENT);

      // Starting the app again: the built-ins change, the extractors' types do not.
      FactStoreConfiguration.loadVocabulary(registry);

      assertThat(registry.current().revision()).isEqualTo(before + 1);
      assertThat(
              jdbc.sql("SELECT source FROM vocabulary_revisions WHERE revision = :r")
                  .param("r", before + 1)
                  .query(String.class)
                  .single())
          .isEqualTo(BuiltInTypes.SOURCE);
      assertThat(registry.current().c4Level(BuiltInTypes.SERVICE)).contains(C4Level.CONTAINER);
      assertThat(registry.current().c4Level(BuiltInTypes.CONTAINER)).contains(C4Level.CONTAINER);
      assertThat(registry.current().c4Level(BuiltInTypes.COMPONENT)).contains(C4Level.COMPONENT);
      assertThat(registry.current().c4Level(BuiltInTypes.TYPE)).contains(C4Level.CODE);
      assertThat(registry.current().c4Level(BuiltInTypes.LIBRARY)).isEmpty();
      assertThat(registry.current().c4Level(BuiltInTypes.MODULE)).isEmpty();
      assertThat(registry.current().c4Level(BuiltInTypes.PACKAGE)).isEmpty();
      assertThat(registry.current().isA(BuiltInTypes.LIBRARY, BuiltInTypes.CONTAINER)).isFalse();

      // The revision before the change still reads back with the old definitions.
      assertThat(registry.at(before).c4Level(BuiltInTypes.LIBRARY)).contains(C4Level.CONTAINER);
      assertThat(registry.at(oldBuiltInsRevision).isA(BuiltInTypes.MODULE, BuiltInTypes.COMPONENT))
          .isTrue();

      // Stored facts are still there, and new facts are still accepted.
      assertThat(facts.findById(stored.id())).contains(stored);
      Fact another = dependsOn("module:other");
      assertThat(facts.save(another)).isTrue();

      // A third start changes nothing.
      FactStoreConfiguration.loadVocabulary(registry);
      assertThat(registry.current().revision()).isEqualTo(before + 1);
    }
  }

  static Fact dependsOn() {
    return dependsOn("module:app");
  }

  static Fact dependsOn(String subject) {
    return Fact.create(
        EntityId.parse(subject),
        BuiltInTypes.DEPENDS_ON,
        FactObject.entity(EntityId.parse("library:greeting-lib")),
        FactJson.newObject(),
        new Origin(Origin.Kind.STATIC, "test", null),
        new Evidence("app", COMMIT, null, null, null),
        1.0,
        Validity.from(1));
  }
}

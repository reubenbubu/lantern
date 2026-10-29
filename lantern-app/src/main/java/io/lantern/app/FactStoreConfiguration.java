package io.lantern.app;

import io.lantern.core.fact.FactRepository;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.core.vocabulary.VocabularyRegistry;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Wires the fact store and the vocabulary registry to the app's database. */
@Configuration(proxyBeanMethods = false)
class FactStoreConfiguration {

  /**
   * The vocabulary registry, with the built-in types and those of the extractors loaded before
   * anything can use it.
   */
  @Bean
  VocabularyRegistry vocabularyRegistry(
      JdbcClient jdbcClient, PlatformTransactionManager transactionManager) {
    VocabularyRegistry registry =
        new VocabularyRegistry(jdbcClient, new TransactionTemplate(transactionManager));
    loadVocabulary(registry);
    return registry;
  }

  /**
   * Applies the built-in types, then those of the extractors and the linker. Unchanged ones add no
   * revision.
   */
  static void loadVocabulary(VocabularyRegistry registry) {
    registry.apply(BuiltInTypes.SOURCE, BuiltInTypes.definitions());
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
  }

  @Bean
  FactRepository factRepository(JdbcClient jdbcClient, VocabularyRegistry vocabularyRegistry) {
    return new FactRepository(jdbcClient, vocabularyRegistry);
  }
}

package io.lantern.app;

import io.lantern.core.fact.FactRepository;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.core.vocabulary.VocabularyRegistry;
import io.lantern.derive.Derivation;
import io.lantern.extract.Extractors;
import io.lantern.link.LinkVocabularies;
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
   * Applies the built-in types, then those of the extractors, the linker and derivation. Unchanged
   * ones add no revision.
   */
  static void loadVocabulary(VocabularyRegistry registry) {
    registry.apply(BuiltInTypes.SOURCE, BuiltInTypes.definitions());
    Extractors.vocabularies().forEach(registry::apply);
    LinkVocabularies.bySource().forEach(registry::apply);
    Derivation.vocabularies().forEach(registry::apply);
  }

  @Bean
  FactRepository factRepository(JdbcClient jdbcClient, VocabularyRegistry vocabularyRegistry) {
    return new FactRepository(jdbcClient, vocabularyRegistry, new SnapshotBlobs(jdbcClient));
  }
}

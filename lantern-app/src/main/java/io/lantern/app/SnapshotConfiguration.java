package io.lantern.app;

import io.lantern.core.analysis.AnalysisLock;
import io.lantern.core.repo.RepoRegistry;
import io.lantern.core.snapshot.SnapshotStore;
import io.lantern.core.vocabulary.VocabularyRegistry;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Wires the repository registry, snapshots and the analysis lock to the app's database. */
@Configuration(proxyBeanMethods = false)
class SnapshotConfiguration {

  @Bean
  RepoRegistry repoRegistry(JdbcClient jdbcClient) {
    return new RepoRegistry(jdbcClient);
  }

  /** Snapshots record the Lantern version, which the build writes into application.properties. */
  @Bean
  SnapshotStore snapshotStore(
      JdbcClient jdbcClient,
      PlatformTransactionManager transactionManager,
      RepoRegistry repoRegistry,
      VocabularyRegistry vocabularyRegistry,
      @Value("${lantern.version}") String toolVersion) {
    return new SnapshotStore(
        jdbcClient,
        new TransactionTemplate(transactionManager),
        repoRegistry,
        vocabularyRegistry,
        toolVersion);
  }

  @Bean
  AnalysisLock analysisLock(DataSource dataSource) {
    return new AnalysisLock(dataSource);
  }
}

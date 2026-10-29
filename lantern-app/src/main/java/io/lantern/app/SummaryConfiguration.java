package io.lantern.app;

import io.lantern.link.JdbcResolutionStore;
import io.lantern.summarize.JdbcSummaryStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/** Wires the caches of LLM results, summaries and link resolutions, to the app's database. */
@Configuration(proxyBeanMethods = false)
class SummaryConfiguration {

  @Bean
  JdbcSummaryStore summaryStore(JdbcClient jdbcClient) {
    return new JdbcSummaryStore(jdbcClient);
  }

  @Bean
  JdbcResolutionStore resolutionStore(
      JdbcClient jdbcClient, PlatformTransactionManager transactionManager) {
    return new JdbcResolutionStore(jdbcClient, transactionManager);
  }
}

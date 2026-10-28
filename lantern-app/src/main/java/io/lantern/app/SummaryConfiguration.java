package io.lantern.app;

import io.lantern.summarize.JdbcSummaryStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Wires the summary cache to the app's database. */
@Configuration(proxyBeanMethods = false)
class SummaryConfiguration {

  @Bean
  JdbcSummaryStore summaryStore(JdbcClient jdbcClient) {
    return new JdbcSummaryStore(jdbcClient);
  }
}

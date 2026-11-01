package io.lantern.app;

import io.lantern.harness.JdbcAnswerStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Wires the LLM harness's answer cache, the only cache of LLM answers (ADR 0100), to the database.
 */
@Configuration(proxyBeanMethods = false)
class SummaryConfiguration {

  @Bean
  JdbcAnswerStore answerStore(JdbcClient jdbcClient) {
    return new JdbcAnswerStore(jdbcClient);
  }
}

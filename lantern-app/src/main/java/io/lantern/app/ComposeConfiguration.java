package io.lantern.app;

import io.lantern.compose.JdbcViewDefinitionStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Wires the view definition store to the app's database. */
@Configuration(proxyBeanMethods = false)
class ComposeConfiguration {

  @Bean
  JdbcViewDefinitionStore viewDefinitionStore(JdbcClient jdbcClient) {
    return new JdbcViewDefinitionStore(jdbcClient);
  }
}

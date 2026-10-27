package io.lantern.app;

import io.lantern.core.fact.FactRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Wires the fact store to the app's database. */
@Configuration(proxyBeanMethods = false)
class FactStoreConfiguration {

  @Bean
  FactRepository factRepository(JdbcClient jdbcClient) {
    return new FactRepository(jdbcClient);
  }
}

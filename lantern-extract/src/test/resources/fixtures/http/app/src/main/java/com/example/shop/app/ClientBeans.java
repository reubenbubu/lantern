package com.example.shop.app;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;

/** Clients made in a bean method and injected elsewhere (#258). */
@Configuration
public class ClientBeans {

  @Bean
  RestClient booksRestClient(@Value("${shop.ledger-books.url}") String url) {
    return RestClient.builder().baseUrl(url).build();
  }

  @Bean
  RestClient trailRestClient(@Value("${shop.audit-trail.url}") String url) {
    return RestClient.builder().baseUrl(url).build();
  }

  @Bean
  RestClient.Builder shopBuilder() {
    return RestClient.builder();
  }

  @Bean
  RestClient quotesRestClient(RestClient.Builder shopBuilder) {
    return shopBuilder.baseUrl("http://quotes:8080").build();
  }

  /** The base URL is only on the builder bean: two hops from the client. */
  @Bean
  RestClient.Builder pricingBuilder() {
    return RestClient.builder().baseUrl("http://pricing:8080");
  }

  @Bean
  RestClient pricingRestClient(RestClient.Builder pricingBuilder) {
    return pricingBuilder.build();
  }

  @Bean
  RestClient fieldRestClient() {
    return RestClient.create("http://field:8080");
  }

  @Bean
  @Primary
  RestClient primaryRestClient() {
    return RestClient.create("http://primary:8080");
  }
}

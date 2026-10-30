package com.example.shop.app;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

/** Load-balanced clients: the hosts of their URLs are service names. */
@Configuration
public class LoadBalancedBeans {

  @Bean
  @LoadBalanced
  public WebClient.Builder lbBuilder() {
    return WebClient.builder();
  }

  @Bean
  @LoadBalanced
  public RestTemplate lbRestTemplate() {
    return new RestTemplate();
  }

  @Bean
  @Primary
  public RestTemplate plainRestTemplate() {
    return new RestTemplate();
  }

  /** Not @Primary: only an injection point marked @LoadBalanced takes it. */
  @Bean
  @LoadBalanced
  public RestClient lbRestClient() {
    return RestClient.builder().baseUrl("http://orders").build();
  }

  @Bean
  @LoadBalanced
  public RestClient.Builder lbRestClientBuilder() {
    return RestClient.builder();
  }

  /** Not itself @LoadBalanced, but made from the load-balanced builder. */
  @Bean
  public RestClient catalogRestClient(@LoadBalanced RestClient.Builder builder) {
    return builder.baseUrl("http://catalog").build();
  }
}

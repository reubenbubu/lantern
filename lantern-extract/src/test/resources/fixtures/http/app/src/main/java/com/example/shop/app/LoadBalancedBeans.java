package com.example.shop.app;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
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
}

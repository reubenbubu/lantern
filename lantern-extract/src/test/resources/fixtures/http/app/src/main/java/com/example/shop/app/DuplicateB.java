package com.example.shop.app;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** One of two beans of the same name, as profile or conditional variants are. */
@Configuration
public class DuplicateB {

  @Bean
  RestClient dup() {
    return RestClient.create("http://dup-b:8080");
  }
}

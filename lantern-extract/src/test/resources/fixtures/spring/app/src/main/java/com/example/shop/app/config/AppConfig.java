package com.example.shop.app.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
public class AppConfig {

  @Bean("shopClock")
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  @Profile("eu")
  String region() {
    return "eu";
  }
}

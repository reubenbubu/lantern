package com.example.shop.app;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientHttpServiceGroupConfigurer;
import org.springframework.web.service.registry.HttpServiceGroupConfigurer;

/** A group configurer written as a class, for a WebClient group. */
@Component
public class AlertsConfigurer implements WebClientHttpServiceGroupConfigurer {

  @Override
  public void configureGroups(HttpServiceGroupConfigurer.Groups<WebClient.Builder> groups) {
    groups
        .filterByName("alerts")
        .forEachGroup(
            (group, builder, factory) -> {
              builder.baseUrl("http://alerts:8080");
            });
  }
}

package com.example.shop.app;

import com.example.shop.clients.exchange.AlertsApi;
import com.example.shop.clients.exchange.MetricsApi;
import com.example.shop.clients.exchange.ReviewsApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;
import org.springframework.web.service.registry.HttpServiceGroup;
import org.springframework.web.service.registry.ImportHttpServices;

/** HTTP service groups: imported by package, by type and in the default group. */
@Configuration
@ImportHttpServices(group = "orders", basePackages = "com.example.shop.clients.exchange.orders")
@ImportHttpServices(group = "reviews", types = ReviewsApi.class)
@ImportHttpServices(
    group = "alerts",
    types = AlertsApi.class,
    clientType = HttpServiceGroup.ClientType.WEB_CLIENT)
@ImportHttpServices(MetricsApi.class)
public class HttpServiceGroups {

  @Bean
  RestClientHttpServiceGroupConfigurer reviewsConfigurer(
      @Value("${clients.reviews.url}") String reviewsUrl) {
    return groups ->
        groups
            .filterByName("reviews")
            .forEachClient((group, builder) -> builder.baseUrl(reviewsUrl));
  }
}

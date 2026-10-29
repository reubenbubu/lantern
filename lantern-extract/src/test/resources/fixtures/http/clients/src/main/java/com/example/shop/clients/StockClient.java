package com.example.shop.clients;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/** WebClient built from a @Value constructor argument. */
@Component
public class StockClient {

  private static final String STOCK = "/api/products/{id}" + "/stock";

  private final WebClient webClient;

  public StockClient(WebClient.Builder builder, @Value("${catalog.base-url}") String baseUrl) {
    this.webClient = builder.baseUrl(baseUrl).build();
  }

  public int available(long productId) {
    Integer level =
        webClient.get().uri(STOCK, productId).retrieve().bodyToMono(Integer.class).block();
    return level == null ? 0 : level;
  }

  public void adjust(long productId) {
    webClient.method(HttpMethod.PATCH).uri(STOCK, productId).retrieve();
  }
}

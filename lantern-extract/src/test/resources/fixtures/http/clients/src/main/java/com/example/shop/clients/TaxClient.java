package com.example.shop.clients;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** RestClient whose URL comes from an environment variable nothing sets. */
@Component
public class TaxClient {

  private final RestClient restClient;

  public TaxClient(RestClient.Builder builder, @Value("${clients.tax.url}") String taxUrl) {
    this.restClient = builder.baseUrl(taxUrl).build();
  }

  public String quote(long amount) {
    return restClient.get().uri("/v2/quotes?amount={amount}", amount).retrieve().body(String.class);
  }
}

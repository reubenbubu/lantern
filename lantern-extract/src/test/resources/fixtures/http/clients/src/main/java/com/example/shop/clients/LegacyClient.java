package com.example.shop.clients;

import jakarta.ws.rs.client.ClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** The Jakarta REST client API. */
@Component
public class LegacyClient {

  @Value("${clients.legacy.url}")
  private String legacyUrl;

  public String order(long id) {
    return ClientBuilder.newClient()
        .target(legacyUrl)
        .path("orders")
        .path("{id}")
        .request("application/json")
        .get(String.class);
  }
}

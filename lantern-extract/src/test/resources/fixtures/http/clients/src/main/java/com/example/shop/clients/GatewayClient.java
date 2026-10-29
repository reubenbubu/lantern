package com.example.shop.clients;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** A literal URL with credentials and a query, which are left out. */
@Component
public class GatewayClient {

  private final RestClient restClient =
      RestClient.create("https://shop:pa55word@gateway.example.com/v1?key=abc123");

  public String charge(String body) {
    return restClient.post().uri("/charges").body(body).retrieve().body(String.class);
  }
}

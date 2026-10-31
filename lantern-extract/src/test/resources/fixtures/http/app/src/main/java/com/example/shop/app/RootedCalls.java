package com.example.shop.app;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/** A RestTemplate with a root URI: it goes only before URLs that start with a slash. */
@Component
public class RootedCalls {

  private final RestTemplate rest;

  public RootedCalls(RestTemplateBuilder builder) {
    this.rest = builder.rootUri("http://ledger-api:8080").build();
  }

  public String orders() {
    return rest.getForObject("/orders", String.class);
  }

  public String anywhere(String target) {
    return rest.getForObject(target + "/status", String.class);
  }
}

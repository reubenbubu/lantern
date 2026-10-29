package com.example.shop.clients;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Injects beans that only the app module makes: a unique name is followed, a duplicate is not. */
@Component
public class AcrossModules {

  private final RestClient quotesRestClient;
  private final RestClient dup;

  public AcrossModules(RestClient quotesRestClient, RestClient dup) {
    this.quotesRestClient = quotesRestClient;
    this.dup = dup;
  }

  public void quote() {
    quotesRestClient.get().uri("/across").retrieve();
  }

  public void duplicate() {
    dup.get().uri("/dup").retrieve();
  }
}

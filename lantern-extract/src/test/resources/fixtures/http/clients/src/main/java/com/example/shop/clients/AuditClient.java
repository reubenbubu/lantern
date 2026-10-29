package com.example.shop.clients;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** A URL a secret is substituted into, which is recorded as written. */
@Component
public class AuditClient {

  @Value("${audit.url}")
  private String auditUrl;

  public void record(String event) {
    RestClient.create(auditUrl).post().uri("/events").body(event).retrieve();
  }
}

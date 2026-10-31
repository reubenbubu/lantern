package com.example.shop.clients;

import java.net.URI;
import java.net.http.HttpRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** A request whose URL a constructor builds into a URI field. */
@Component
public class StatusRequests {

  private final URI statusUri;

  public StatusRequests(@Value("${clients.ledger.url}") String base) {
    this.statusUri = URI.create(base + "/status");
  }

  public HttpRequest status() {
    return HttpRequest.newBuilder(statusUri).GET().build();
  }
}

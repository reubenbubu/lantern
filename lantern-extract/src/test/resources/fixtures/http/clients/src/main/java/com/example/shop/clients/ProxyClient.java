package com.example.shop.clients;

import java.net.URI;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * A URL built in one method and passed, through a lambda and another method, to the one that sends
 * it: a call per place the URL is made.
 */
@Component
public class ProxyClient {

  @Value("${clients.proxy.url}")
  private String proxyUrl;

  private final RestClient restClient = RestClient.builder().build();

  public String forward(String path, HttpMethod method) {
    URI target = URI.create(proxyUrl + path);
    return send(method, target);
  }

  public String forwardPublic(String service) {
    URI target = URI.create(proxyUrl + "/" + service + "/public");
    return this.send(HttpMethod.GET, target);
  }

  private String send(HttpMethod method, URI target) {
    return guarded(() -> exchange(method, target));
  }

  private String guarded(Supplier<String> call) {
    return call.get();
  }

  private String exchange(HttpMethod method, URI target) {
    return restClient.method(method).uri(target).retrieve().body(String.class);
  }

  /** Not followed: a URL that is a parameter of a method its own type never calls. */
  public String direct(URI target) {
    return restClient.get().uri(target).retrieve().body(String.class);
  }
}

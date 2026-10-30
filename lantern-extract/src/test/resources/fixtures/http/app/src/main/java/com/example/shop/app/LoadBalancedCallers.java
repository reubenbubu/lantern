package com.example.shop.app;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

@Service
public class LoadBalancedCallers {

  private final WebClient.Builder builder;
  private final RestTemplate balanced;
  private final RestTemplate plain;

  public LoadBalancedCallers(
      WebClient.Builder builder, @LoadBalanced RestTemplate balanced, RestTemplate plain) {
    this.builder = builder;
    this.balanced = balanced;
    this.plain = plain;
  }

  /** Through the @LoadBalanced builder bean, the only one of its type. */
  public Object media(String id) {
    return builder.build().get().uri("http://media-service/media/{id}", id).retrieve();
  }

  /** The @LoadBalanced qualifier at the injection point picks the load-balanced bean. */
  public String stock() {
    return balanced.getForObject("http://inventory-service/items", String.class);
  }

  /** The @Primary plain bean: a host, not a service name. */
  public String external() {
    return plain.getForObject("http://api.example.com/rates", String.class);
  }

  /** An lb:// URL names its service whatever the client. */
  public Object routed() {
    return WebClient.create("lb://billing").get().uri("/invoices").retrieve();
  }
}

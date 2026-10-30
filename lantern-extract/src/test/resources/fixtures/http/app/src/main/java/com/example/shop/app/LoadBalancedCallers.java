package com.example.shop.app;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

@Service
public class LoadBalancedCallers {

  private final WebClient.Builder builder;
  private final RestTemplate balanced;
  private final RestTemplate plain;

  @LoadBalanced private RestTemplate fieldBalanced;

  @LoadBalanced private RestClient orders;

  @Qualifier("catalogRestClient")
  private RestClient catalog;

  @Value("${payments.url}")
  private String paymentsUrl;

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

  /** @LoadBalanced on a field. */
  public String shipping() {
    return fieldBalanced.getForObject("http://shipping/parcels", String.class);
  }

  /** @LoadBalanced as a qualifier passes over the @Primary RestClient for the balanced one. */
  public String order() {
    return orders.get().uri("/orders").retrieve().body(String.class);
  }

  /** A bean made from a load-balanced builder is load balanced. */
  public String items() {
    return catalog.get().uri("/items").retrieve().body(String.class);
  }

  /** A host from config: load balanced, but no service can be read. */
  public String charge() {
    return balanced.getForObject(paymentsUrl + "/charge", String.class);
  }

  /** Credentials in an lb:// URL are not part of the service. */
  public Object ledger() {
    return WebClient.create("lb://ops:t0ps3cret@ledger").get().uri("/entries").retrieve();
  }
}

package com.example.shop.clients;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/** RestTemplate with its URL from a @Value field. */
@Component
public class PaymentsClient {

  private final RestTemplate restTemplate;

  @Value("${clients.payments.url}")
  private String paymentsUrl;

  public PaymentsClient(RestTemplate restTemplate) {
    this.restTemplate = restTemplate;
  }

  public String charge(String request) {
    return restTemplate.postForObject(paymentsUrl + "/api/payments", request, String.class);
  }

  public void refund(long id) {
    String url = paymentsUrl + "/api/payments/" + id;
    restTemplate.exchange(url, HttpMethod.PUT, null, String.class);
  }

  public String fetch(String url) {
    return restTemplate.getForObject(url, String.class);
  }
}

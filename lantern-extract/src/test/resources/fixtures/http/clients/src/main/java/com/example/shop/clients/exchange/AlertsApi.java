package com.example.shop.clients.exchange;

import org.springframework.web.service.annotation.PostExchange;

/** An HTTP interface in a WebClient group whose base URL a configurer class sets. */
public interface AlertsApi {

  @PostExchange("/alerts")
  void alert(String text);
}

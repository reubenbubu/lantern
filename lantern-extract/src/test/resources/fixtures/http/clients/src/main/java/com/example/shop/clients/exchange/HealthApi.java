package com.example.shop.clients.exchange;

import org.springframework.web.service.annotation.GetExchange;

/** An HTTP interface no code makes a proxy of. */
public interface HealthApi {

  @GetExchange("/health")
  String health();
}

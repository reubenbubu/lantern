package com.example.shop.clients.exchange;

import org.springframework.web.service.annotation.GetExchange;

/** An HTTP interface in the default group, whose base URL nothing sets. */
public interface MetricsApi {

  @GetExchange("/metrics")
  String metrics();
}

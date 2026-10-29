package com.example.shop.clients.exchange;

import org.springframework.web.service.annotation.GetExchange;

/** An HTTP interface whose proxy factory's adapter is a bean made in another method. */
public interface StockApi {

  @GetExchange("/stock/{sku}")
  int stock(String sku);
}

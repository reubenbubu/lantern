package com.example.shop.clients.exchange;

import org.springframework.web.service.annotation.GetExchange;

/** An HTTP interface whose proxy factory is a bean made in another method. */
public interface InventoryApi {

  @GetExchange("/inventory/{sku}")
  int level(String sku);
}

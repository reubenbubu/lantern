package com.example.shop.clients.exchange;

import org.springframework.web.service.annotation.GetExchange;

/** An HTTP interface whose proxy factory is injected, so its base URL is not known. */
public interface InventoryApi {

  @GetExchange("/inventory/{sku}")
  int level(String sku);
}

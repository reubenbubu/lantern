package com.example.shop.clients.exchange;

import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PatchExchange;

/** An HTTP interface behind a WebClient adapter made with forClient. */
@HttpExchange(url = "/prices")
public interface PricesApi {

  @PatchExchange("/{sku}")
  void adjust(String sku);
}

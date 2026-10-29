package com.example.shop.clients.exchange;

import org.springframework.web.service.annotation.PutExchange;

/** An HTTP interface behind a RestTemplate with a root URI. */
public interface LedgerApi {

  @PutExchange("/entries/{id}")
  void put(long id);
}

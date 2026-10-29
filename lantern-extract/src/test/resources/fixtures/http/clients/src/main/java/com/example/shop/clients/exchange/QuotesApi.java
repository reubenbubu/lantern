package com.example.shop.clients.exchange;

import org.springframework.web.service.annotation.DeleteExchange;
import org.springframework.web.service.annotation.GetExchange;

/** A Spring HTTP interface with annotations on its methods only. */
public interface QuotesApi {

  @GetExchange("quotes/{symbol}")
  String quote(String symbol);

  @DeleteExchange("https://admin.example.com/quotes/{symbol}")
  void remove(String symbol);
}

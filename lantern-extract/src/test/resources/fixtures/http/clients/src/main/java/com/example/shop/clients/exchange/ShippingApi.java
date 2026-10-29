package com.example.shop.clients.exchange;

import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/** A Spring HTTP interface with a URL on the interface; its proxy is made in the app. */
@HttpExchange("/api/shipments")
public interface ShippingApi {

  @GetExchange("/{id}")
  String shipment(long id);

  @PostExchange
  String ship(String order);

  @HttpExchange(url = "/{id}", method = "DELETE")
  void cancel(long id);

  default String track(long id) {
    return shipment(id);
  }
}

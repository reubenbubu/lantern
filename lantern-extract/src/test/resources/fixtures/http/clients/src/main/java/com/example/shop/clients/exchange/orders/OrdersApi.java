package com.example.shop.clients.exchange.orders;

import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/** An HTTP interface in a group imported by package, its base URL from Spring Boot's property. */
@HttpExchange("/orders")
public interface OrdersApi {

  @GetExchange("/{id}")
  String order(long id);
}

package com.example.shop.clients.exchange;

import org.springframework.web.service.annotation.PostExchange;

/** An HTTP interface in a group whose base URL a group configurer sets. */
public interface ReviewsApi {

  @PostExchange("/reviews")
  void review(String text);
}

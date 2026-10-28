package com.example.shop.api;

/** Looks up the price of a product in cents. */
public interface PriceService {

  long priceOf(String productId);
}

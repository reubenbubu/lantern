package com.example.shop.core;

import com.example.shop.api.PriceService;

/** Charges the same price for every product. */
public class FixedPriceService implements PriceService {

  private final long cents;

  public FixedPriceService(long cents) {
    this.cents = cents;
  }

  @Override
  public long priceOf(String productId) {
    return cents;
  }
}

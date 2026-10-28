package com.example.orders;

import lombok.Value;

/** One product on an order. */
@Value
public class OrderLine {

  String productId;
  int quantity;
  long unitPriceCents;
}

package com.example.orders;

import lombok.RequiredArgsConstructor;

/** Uses only Lombok-generated members, so it compiles only when Lombok runs. */
@RequiredArgsConstructor
public class OrderService {

  private final String currency;

  public Order newOrder(String id, String customer, String productId, int quantity) {
    return Order.builder()
        .id(id)
        .customer(customer)
        .line(new OrderLine(productId, quantity, 250))
        .build();
  }

  public String total(Order order) {
    long cents = 0;
    for (OrderLine line : order.getLines()) {
      cents += line.getQuantity() * line.getUnitPriceCents();
    }
    return cents + " " + currency;
  }
}

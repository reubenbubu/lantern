package com.example.events;

/** Names the code shares, folded where they are used. */
final class Topics {

  static final String AUDIT = "audit." + "events";
  static final String SHIPMENTS = "shipments.dispatched";
  static final String EXCHANGE = "shop.orders";
  static final String PREFIX = "order.";
  static final String ORDER_EVENTS = "payments.order-events";

  private Topics() {}
}

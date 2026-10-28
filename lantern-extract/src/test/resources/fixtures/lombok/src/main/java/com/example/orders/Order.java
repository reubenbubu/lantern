package com.example.orders;

import java.util.List;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;

/** An order. Lombok generates the constructor, getters, equals, hashCode and builder. */
@Value
@Builder
public class Order {

  String id;
  String customer;
  @Singular List<OrderLine> lines;
}

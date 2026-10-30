package com.example.orders;

import lombok.Builder;
import lombok.Data;

/** @Data with @Builder: Lombok gives no required-args constructor, only the builder's. */
@Data
@Builder
public class OrderDto {

  private String id;
  private int count;
}

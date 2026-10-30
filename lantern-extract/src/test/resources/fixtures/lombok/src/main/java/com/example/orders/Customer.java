package com.example.orders;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;

/** The usual JPA and DTO pattern: @Data adds no constructor of its own here. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Customer {

  @NonNull private String id;
  private String name;
}

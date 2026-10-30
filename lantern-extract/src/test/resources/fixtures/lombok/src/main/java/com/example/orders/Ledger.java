package com.example.orders;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Two Lombok constructors on one class. */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class Ledger {

  private String name;
  private int size;
}

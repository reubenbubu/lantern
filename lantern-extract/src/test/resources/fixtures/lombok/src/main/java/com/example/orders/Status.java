package com.example.orders;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** An enum with Lombok's constructor. */
@Getter
@RequiredArgsConstructor
public enum Status {
  OPEN("o"),
  CLOSED("c");

  private final String code;
}

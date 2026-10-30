package com.example.orders;

import lombok.Value;
import lombok.experimental.NonFinal;

/** @Value with a field that stays non-final and initialized. */
@Value
public class Money {

  long cents;
  @NonFinal int scale = 2;
}

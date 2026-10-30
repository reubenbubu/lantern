package com.example.orders;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;

/** Fields made final by @FieldDefaults, except the one marked @NonFinal. */
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class Billing {

  Ledger ledger;
  String currency;
  @NonFinal int retries;
}

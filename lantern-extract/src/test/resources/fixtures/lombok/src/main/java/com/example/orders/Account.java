package com.example.orders;

import lombok.Data;
import lombok.NonNull;
import lombok.extern.java.Log;

/** Lombok's required-args constructor (final and @NonNull fields) and a log field. */
@Log
@Data
public class Account {

  private static int count;
  private final String id;
  @NonNull private String owner;
  private long balance;
  private final String currency = "EUR";

  public void open() {
    log.info("opened " + id);
  }
}

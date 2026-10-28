package com.example.greeting.service;

import com.example.greeting.lib.Greeter;

/** The service's only class: it starts the logic that lives in greeting-lib. */
public final class GreetingServiceMain {

  private GreetingServiceMain() {}

  public static void main(String[] args) {
    System.out.println(new Greeter().greet(args.length > 0 ? args[0] : "world"));
  }
}

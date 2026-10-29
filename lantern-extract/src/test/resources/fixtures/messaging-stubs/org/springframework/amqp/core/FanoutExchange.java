package org.springframework.amqp.core;

/** Stand-in for Spring AMQP's class, for compiling the messaging fixture. */
public class FanoutExchange implements Exchange {
  public FanoutExchange(String name) {}
}

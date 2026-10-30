package org.springframework.amqp.core;

/** Stand-in for Spring AMQP's class, for compiling the messaging fixture. */
public class CustomExchange implements Exchange {
  public CustomExchange(String name, String type) {}

  public CustomExchange(String name, String type, boolean durable, boolean autoDelete) {}
}

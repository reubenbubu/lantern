package org.springframework.amqp.core;

import java.util.Map;

/** Stand-in for Spring AMQP's class, for compiling the messaging fixture. */
public class Binding {
  /** Where a binding routes to. */
  public enum DestinationType {
    QUEUE,
    EXCHANGE
  }

  public Binding(
      String destination,
      DestinationType destinationType,
      String exchange,
      String routingKey,
      Map<String, Object> arguments) {}
}

package org.springframework.amqp.core;

/** Stand-in for Spring AMQP's class, for compiling the messaging fixture. */
public class Queue {
  public Queue(String name) {}

  public Queue(String name, boolean durable) {}

  public Queue(
      String name,
      boolean durable,
      boolean exclusive,
      boolean autoDelete,
      java.util.Map<String, Object> arguments) {}
}

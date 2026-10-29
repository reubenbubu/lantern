package org.springframework.amqp.rabbit.core;

/** Stand-in for Spring AMQP's template, for compiling the messaging fixture. */
public class RabbitTemplate {
  public void convertAndSend(Object message) {}

  public void convertAndSend(String routingKey, Object message) {}

  public void convertAndSend(String exchange, String routingKey, Object message) {}
}

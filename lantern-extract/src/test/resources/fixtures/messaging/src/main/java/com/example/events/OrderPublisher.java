package com.example.events;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OrderPublisher {

  private final RabbitTemplate rabbit;

  @Value("${events.rabbit.exchange:}")
  private String exchange;

  public OrderPublisher(RabbitTemplate rabbit) {
    this.rabbit = rabbit;
  }

  public void created(String event) {
    rabbit.convertAndSend(Topics.EXCHANGE, Topics.PREFIX + "created", event);
  }

  public void direct(String event) {
    rabbit.convertAndSend("direct.queue", event);
  }

  public void routed(String event) {
    // The default exchange unless config names one: then a send to the queue the key names.
    rabbit.convertAndSend(exchange, "default.routed", event);
  }

  public void keyed(String key, String event) {
    // A routing key the caller chooses: the send is recorded, its key unknown.
    rabbit.convertAndSend(Topics.EXCHANGE, key, event);
  }

  public void relayed(String exchange, String event) {
    // An exchange the caller chooses, as an outbox relay sends: recorded, its exchange unknown.
    rabbit.convertAndSend(exchange, "order.relayed", event);
  }
}

package com.example.events;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class OrderPublisher {

  private final RabbitTemplate rabbit;

  public OrderPublisher(RabbitTemplate rabbit) {
    this.rabbit = rabbit;
  }

  public void created(String event) {
    rabbit.convertAndSend(Topics.EXCHANGE, Topics.PREFIX + "created", event);
  }

  public void direct(String event) {
    rabbit.convertAndSend("direct.queue", event);
  }
}

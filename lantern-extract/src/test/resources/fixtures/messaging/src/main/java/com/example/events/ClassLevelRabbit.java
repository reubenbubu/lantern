package com.example.events;

import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RabbitListener(queues = "class.queue")
public class ClassLevelRabbit {

  @RabbitHandler
  public void handle(String event) {}
}

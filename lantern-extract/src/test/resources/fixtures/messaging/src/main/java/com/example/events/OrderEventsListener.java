package com.example.events;

import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Component;

@Component
public class OrderEventsListener {

  @RabbitListener(queues = Topics.ORDER_EVENTS)
  @SendTo("replies/reply.key")
  public String orderEvent(String event) {
    return event;
  }

  @RabbitListener(
      bindings =
          @QueueBinding(
              value = @Queue("audit.queue"),
              exchange = @Exchange(value = "audit", type = ExchangeTypes.TOPIC),
              key = {"audit.#", "${events.rabbit.audit-key}"}))
  public void audit(String event) {}

  @RabbitListener(
      bindings =
          @QueueBinding(
              value = @Queue(exclusive = "true", autoDelete = "true"),
              exchange = @Exchange(name = "board", type = ExchangeTypes.FANOUT)))
  public void board(String event) {}

  @RabbitListener(
      bindings = {
        @QueueBinding(
            value = @Queue,
            exchange =
                @Exchange(name = "${events.rabbit.cache-exchange:cache}", type = ExchangeTypes.TOPIC),
            key = {"cache.product.*", "cache.price.#"}),
        @QueueBinding(
            value = @Queue(name = ""),
            exchange = @Exchange("refresh"),
            key = "refresh.all")
      })
  public void evict(String key) {}

  @RabbitListener(queuesToDeclare = @Queue(name = "declared.queue"))
  public void declared(String event) {}
}

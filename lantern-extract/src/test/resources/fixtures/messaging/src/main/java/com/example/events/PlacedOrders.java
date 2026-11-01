package com.example.events;

import java.util.List;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Component
public class PlacedOrders {

  private final RabbitTemplate rabbit;

  public PlacedOrders(RabbitTemplate rabbit) {
    this.rabbit = rabbit;
  }

  public void place(String id) {
    OrderPlaced event = new OrderPlaced(id);
    rabbit.convertAndSend(Topics.EXCHANGE, "order.placed", event);
  }

  @RabbitListener(queues = "orders.placed")
  public void placed(@Header("x-trace") String trace, @Payload List<OrderPlaced> batch) {}
}

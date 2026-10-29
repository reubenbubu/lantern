package com.example.events;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentListener {

  @KafkaListener(topics = "payments.completed", groupId = "fulfilment")
  public void completed(String id) {}

  @KafkaListener(topicPattern = "${events.kafka.pattern}", id = "pattern-listener")
  public void anyAudit(String event) {}
}

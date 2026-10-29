package com.example.events;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class RefundPublisher {

  private final KafkaTemplate<String, String> kafka;
  private final String topic;

  public RefundPublisher(
      KafkaTemplate<String, String> kafka,
      @Value("${events.kafka.refunds-topic:refunds}") String topic) {
    this.kafka = kafka;
    this.topic = topic;
  }

  public void refund(String id) {
    kafka.send(topic, id);
  }
}

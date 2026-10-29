package com.example.events;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class SecretTopicPublisher {

  private final KafkaTemplate<String, String> kafka;

  @Value("${events.kafka.secret-topic}")
  private String topic;

  public SecretTopicPublisher(KafkaTemplate<String, String> kafka) {
    this.kafka = kafka;
  }

  public void publish(String id) {
    kafka.send(topic, id);
  }
}

package com.example.events;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class PaymentPublisher {

  private final KafkaTemplate<String, String> kafka;

  @Value("${events.kafka.payments-topic}")
  private String topic;

  public PaymentPublisher(KafkaTemplate<String, String> kafka) {
    this.kafka = kafka;
  }

  public void publish(String id) {
    kafka.send(topic, id, id);
  }

  public void audit(String id) {
    kafka.send(new ProducerRecord<>(Topics.AUDIT, id));
  }

  public void unknown(String topic, String id) {
    // A topic the caller chooses: nothing says which.
    kafka.send(topic, id);
  }
}

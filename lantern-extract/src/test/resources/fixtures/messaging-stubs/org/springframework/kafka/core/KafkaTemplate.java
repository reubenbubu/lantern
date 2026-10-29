package org.springframework.kafka.core;

import org.apache.kafka.clients.producer.ProducerRecord;

/** Stand-in for Spring Kafka's template, for compiling the messaging fixture. */
public class KafkaTemplate<K, V> implements KafkaOperations<K, V> {
  @Override
  public Object send(String topic, V data) {
    return null;
  }

  @Override
  public Object send(String topic, K key, V data) {
    return null;
  }

  @Override
  public Object send(ProducerRecord<K, V> record) {
    return null;
  }

  public Object sendDefault(V data) {
    return null;
  }
}

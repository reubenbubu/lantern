package org.springframework.kafka.core;

import org.apache.kafka.clients.producer.ProducerRecord;

/** Stand-in for Spring Kafka's interface, for compiling the messaging fixture. */
public interface KafkaOperations<K, V> {
  Object send(String topic, V data);

  Object send(String topic, K key, V data);

  Object send(ProducerRecord<K, V> record);
}

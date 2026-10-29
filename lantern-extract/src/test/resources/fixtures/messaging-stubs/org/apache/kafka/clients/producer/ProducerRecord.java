package org.apache.kafka.clients.producer;

/** Stand-in for Kafka's record, for compiling the messaging fixture. */
public class ProducerRecord<K, V> {
  public ProducerRecord(String topic, V value) {}

  public ProducerRecord(String topic, K key, V value) {}
}

package org.springframework.kafka.config;

import org.apache.kafka.clients.admin.NewTopic;

/** Stand-in for Spring Kafka's builder, for compiling the messaging fixture. */
public final class TopicBuilder {
  public static TopicBuilder name(String name) {
    return new TopicBuilder();
  }

  public TopicBuilder partitions(int partitions) {
    return this;
  }

  public NewTopic build() {
    return null;
  }
}

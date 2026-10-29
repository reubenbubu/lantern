package org.springframework.amqp.core;

/** Stand-in for Spring AMQP's builder, for compiling the messaging fixture. */
public final class QueueBuilder {
  public static QueueBuilder durable(String name) {
    return new QueueBuilder();
  }

  public QueueBuilder withArgument(String key, Object value) {
    return this;
  }

  public Queue build() {
    return null;
  }
}

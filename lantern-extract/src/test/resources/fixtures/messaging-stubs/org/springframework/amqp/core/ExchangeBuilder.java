package org.springframework.amqp.core;

/** Stand-in for Spring AMQP's builder, for compiling the messaging fixture. */
public class ExchangeBuilder {
  public static ExchangeBuilder fanoutExchange(String name) {
    return new ExchangeBuilder();
  }

  public static ExchangeBuilder topicExchange(String name) {
    return new ExchangeBuilder();
  }

  public ExchangeBuilder durable(boolean durable) {
    return this;
  }

  @SuppressWarnings("unchecked")
  public <T extends Exchange> T build() {
    return null;
  }
}

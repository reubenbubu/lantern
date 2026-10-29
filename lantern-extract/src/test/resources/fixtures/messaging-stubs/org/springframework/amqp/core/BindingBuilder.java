package org.springframework.amqp.core;

/** Stand-in for Spring AMQP's builder, for compiling the messaging fixture. */
public final class BindingBuilder {
  public static DestinationConfigurer bind(Queue queue) {
    return new DestinationConfigurer();
  }

  /** The step that names the exchange. */
  public static final class DestinationConfigurer {
    public TopicExchangeRoutingKeyConfigurer to(TopicExchange exchange) {
      return new TopicExchangeRoutingKeyConfigurer();
    }

    public DirectExchangeRoutingKeyConfigurer to(DirectExchange exchange) {
      return new DirectExchangeRoutingKeyConfigurer();
    }

    public Binding to(FanoutExchange exchange) {
      return null;
    }
  }

  /** The step that names a topic exchange's routing pattern. */
  public static final class TopicExchangeRoutingKeyConfigurer {
    public Binding with(String routingKey) {
      return null;
    }
  }

  /** The step that names a direct exchange's routing key. */
  public static final class DirectExchangeRoutingKeyConfigurer {
    public Binding with(String routingKey) {
      return null;
    }

    public Binding withQueueName() {
      return null;
    }
  }
}

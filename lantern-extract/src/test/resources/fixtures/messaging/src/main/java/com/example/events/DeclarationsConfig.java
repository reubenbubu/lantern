package com.example.events;

import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.amqp.core.CustomExchange;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Queues, exchanges and topics declared but not bound, sent to or listened on here. */
@Configuration
public class DeclarationsConfig {

  private final Queue parked = new Queue("audit.parked");

  @Bean
  Declarables retries() {
    return new Declarables(
        QueueBuilder.nonDurable("orders.retry")
            .deadLetterExchange("shop.dlx")
            .deadLetterRoutingKey("orders.dead")
            .build(),
        new Queue("orders.parked", false),
        new DirectExchange("shop.dlx"));
  }

  @Bean
  Queue audit() {
    return QueueBuilder.durable("audit").withArgument("x-dead-letter-exchange", "").build();
  }

  @Bean
  NewTopic ordersTopic() {
    return TopicBuilder.name("orders.created").partitions(3).build();
  }

  @Bean
  NewTopic paymentsTopic() {
    return new NewTopic("payments.settled", 1, (short) 1);
  }

  @Bean
  Queue held() {
    return new Queue(
        "orders.held",
        true,
        false,
        false,
        Map.of("x-dead-letter-exchange", "shop.dlx", "x-dead-letter-routing-key", "held"));
  }

  @Bean
  Queue paymentsHeld(@Value("${events.rabbit.refunds-queue}") String deadLetters) {
    return QueueBuilder.durable("payments.held")
        .withArguments(Map.of("x-dead-letter-exchange", deadLetters))
        .build();
  }

  @Bean
  Declarables delayed() {
    return new Declarables(
        new CustomExchange("delayed", "x-delayed-message"),
        new CustomExchange("delayed.temp", "x-delayed-message", false, true));
  }

  @Bean
  Exchange auditEvents() {
    return ExchangeBuilder.topicExchange("audit.events").durable(false).build();
  }
}

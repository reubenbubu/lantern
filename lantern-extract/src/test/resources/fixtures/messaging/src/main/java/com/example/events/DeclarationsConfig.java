package com.example.events;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Queues, exchanges and topics declared but not bound, sent to or listened on here. */
@Configuration
public class DeclarationsConfig {

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
}

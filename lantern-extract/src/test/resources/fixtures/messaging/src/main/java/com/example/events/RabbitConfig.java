package com.example.events;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

  @Bean
  TopicExchange ordersExchange() {
    return new TopicExchange(Topics.EXCHANGE);
  }

  @Bean
  Queue orderEventsQueue() {
    return new Queue(Topics.ORDER_EVENTS);
  }

  @Bean
  Queue refundsQueue(@Value("${events.rabbit.refunds-queue}") String name) {
    return QueueBuilder.durable(name).withArgument("x-queue-type", "quorum").build();
  }

  @Bean
  FanoutExchange broadcast() {
    return ExchangeBuilder.fanoutExchange("broadcast").durable(true).build();
  }

  @Bean
  Binding orderEventsBinding(Queue orderEventsQueue, TopicExchange ordersExchange) {
    return BindingBuilder.bind(orderEventsQueue).to(ordersExchange).with("order.*");
  }

  @Bean
  Binding refundsBinding(Queue refundsQueue) {
    return BindingBuilder.bind(refundsQueue).to(ordersExchange()).with("refund.#");
  }

  @Bean
  Binding broadcastBinding(Queue refundsQueue, FanoutExchange broadcast) {
    return BindingBuilder.bind(refundsQueue).to(broadcast);
  }

  @Bean
  Binding legacyBinding() {
    return new Binding("legacy.queue", Binding.DestinationType.QUEUE, "legacy", "legacy.key", null);
  }
}

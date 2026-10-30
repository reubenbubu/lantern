# Messaging: which APIs Java systems use and how destination names are resolved

Research for #388 (Review the Messaging extractor), milestone "Extractor revisit". It leads to an amendment of `docs/adr/0037-messaging-extractor.md` and the messaging row of `docs/design.md` (Static analysis and extractors).

**Depth: high.** The note decides which destinations, producers and consumers an architecture document can show from the code, across Spring AMQP, Spring Kafka, JMS, Spring Cloud Stream, Spring Modulith, outbox and CDC, Pulsar and the cloud brokers. A gap here loses a queue or a connection on the container diagram. I read 22 official pages and papers in full, 4 in part, and the messaging code and config of 7 open-source projects, fetched raw. The research is generic: it does not read Lantern's test sets.

## Question

Which messaging APIs and styles do Java (mostly Spring) systems use? How is each destination name resolved from code and config: topic, queue, exchange, routing key, binding, subscription and dead letter? Which of these does the Messaging extractor (ADR 0037, ADR 0040) miss, and what should it add first?

## What we learned

### Spring AMQP and RabbitMQ

- **The broker routes by bindings.** A producer sends to an exchange with a routing key, and queues are bound to exchanges.
  - Direct exchanges match the key exactly, topic exchanges match patterns (`*`, `#`), and fanout and headers exchanges ignore it.
  - The default, nameless exchange routes to the queue named by the key.
  - So a producer never names a queue unless it uses the default exchange.
  - https://www.rabbitmq.com/tutorials/amqp-concepts
- **Declarations take many forms** (https://docs.spring.io/spring-amqp/reference/amqp/broker-configuration.html):
  - `Queue`, `Exchange` and `Binding` `@Bean`s, through `QueueBuilder`, `ExchangeBuilder` and `BindingBuilder`.
  - `Declarables` beans holding many of them, often built by helper methods, loops over config and string concatenation. FAForever's `RabbitConfiguration` and alejandro-torres's shared-library `MessagingTopology` both do this.
  - `AmqpAdmin.declareQueue` at runtime, `AnonymousQueue`, and XML `<rabbit:queue>`.
  - `@RabbitListener(queuesToDeclare = @Queue(...))`, which declares a queue bound to the default exchange (https://docs.spring.io/spring-amqp/reference/amqp/receiving-messages/async-annotation-driven.html).
- **A queue is declared even when nothing in the same code binds it.** A service often declares the queues it consumes and leaves the bindings to the producer, or to a shared library.
- **Dead letters** are queue arguments: `x-dead-letter-exchange` and `x-dead-letter-routing-key`.
  - They are set through `QueueBuilder.deadLetterExchange(...)` and `.deadLetterRoutingKey(...)`, or through `withArgument` and the arguments `Map` of `new Queue(...)`.
  - A broker policy can also set them, and that is invisible to code: FAForever says so in a comment.
- **Sending.** `RabbitTemplate.convertAndSend(exchange, key, message)`, `(key, message)` and `(message)`.
  - The explicit parameters override the template's defaults, which come from `setExchange` and `setRoutingKey` or from `spring.rabbitmq.template.exchange` and `.routing-key`.
  - Request and reply go through `convertSendAndReceive`, `AsyncRabbitTemplate` and direct reply-to.
  - https://docs.spring.io/spring-amqp/reference/amqp/sending-messages.html
- **Replies.** `@SendTo("exchange/routingKey")` applies only when the request has no `replyTo`. `#{}` in it is evaluated at startup, `!{}` per message, so `!{}` cannot be resolved statically. https://docs.spring.io/spring-amqp/reference/amqp/receiving-messages/async-annotation-driven/reply.html

### Spring Kafka

- **Listening** (https://docs.spring.io/spring-kafka/reference/kafka/receiving-messages/listener-annotation.html):
  - `@KafkaListener` takes exactly one of `topics`, `topicPattern` or `topicPartitions`.
  - The group is `groupId`, else the listener's `id`, else `spring.kafka.consumer.group-id`.
  - SpEL may use `__listener`, which needs bean state.
  - Class-level listeners use `@KafkaHandler` (https://docs.spring.io/spring-kafka/reference/kafka/receiving-messages/class-level-kafkalistener.html).
- **Topic declarations** are `NewTopic` beans, usually through `TopicBuilder.name(...)`, and `KafkaAdmin.NewTopics`. piomin's sample declares every topic this way. https://docs.spring.io/spring-kafka/reference/kafka/configuring-topics.html
- **Retries and dead letters.** `@RetryableTopic` creates `<topic>-retry` and `<topic>-dlt` topics by default; the suffixes can be changed, and Spring Kafka 4.1 changed the fixed back-off default to a single retry topic. https://docs.spring.io/spring-kafka/reference/retrytopic/topic-naming.html
- **Sending** (https://docs.spring.io/spring-kafka/reference/kafka/sending-messages.html):
  - `send(topic, …)`, `send(ProducerRecord)`, and `send(Message)` with the `KafkaHeaders.TOPIC` header.
  - `sendDefault` to the template's default topic.
  - `ReplyingKafkaTemplate` for request and reply.
- **Kafka Streams.** `builder.stream("payment-orders")` consumes and `.to("orders")` produces, with no `KafkaTemplate` or `@KafkaListener` at all (piomin's `OrderApp`). A state store in the same code shares a topic's name without being one.
- **Plain kafka-clients.** `KafkaConsumer.subscribe` or `assign`, with `group.id` from properties. https://kafka.apache.org/41/javadoc/org/apache/kafka/clients/consumer/KafkaConsumer.html

### JMS

- `@JmsListener(destination, containerFactory, subscription, selector)`. https://docs.spring.io/spring-framework/reference/integration/jms/annotated.html
- **Queue or topic is not in the annotation.** It is `pubSubDomain` on the container factory or template, or `spring.jms.pub-sub-domain`. Applications that need both define two factories (https://learn.microsoft.com/en-us/azure/developer/java/spring-framework/using-service-bus-in-spring-applications).
- `Destination` objects (`new ActiveMQQueue("x")`) and the template's default destination also name targets. `javax.jms` and `jakarta.jms` are the same API.

### Spring Cloud Stream

- **Functional binding names** are `<function>-in-<i>` and `<function>-out-<i>`, selected by `spring.cloud.function.definition` and renamed by `spring.cloud.stream.function.bindings`. https://docs.spring.io/spring-cloud-stream/reference/spring-cloud-stream/functional-binding-names.html
- **An unset `destination` is the binding name.** "If not, the actual binding name is used instead." https://docs.spring.io/spring-cloud-stream/reference/spring-cloud-stream/binding-properties.html
- **The RabbitMQ binder** makes the destination a topic exchange and the consumer queue `destination.group`, bound with `#` (https://docs.spring.io/spring-cloud-stream/reference/rabbit/rabbit_overview.html). ADR 0040 already reads this.

### Beyond the template APIs

- **Spring Modulith** `@Externalized("target::key")` sends an event type to a broker; the default target is the type's name relative to the base package. https://docs.spring.io/spring-modulith/reference/events.html
- **Outbox and CDC.** Debezium's outbox router sends a row to `outbox.event.<aggregatetype>`, decided by connector config outside the service (https://debezium.io/documentation/reference/stable/transformations/outbox-event-router.html). Eventuate Tram names channels after the aggregate's class.
- **Pulsar** resolves a topic from the call, then a type mapping or `@PulsarMessage`, then `spring.pulsar.*`, stopping at the first found. https://docs.spring.io/spring-pulsar/reference/reference/topic-resolution.html
- **Cloud brokers**: SQS (`@SqsListener`, `SqsTemplate`, queues created when missing), SNS, GCP Pub/Sub, Azure Service Bus (`@ServiceBusListener`), each annotation- or template-shaped. Read only in part.
- **Spring Integration** adapters name exchanges, routing keys, topics and destinations in DSL calls. https://docs.spring.io/spring-integration/reference/amqp/outbound-channel-adapter.html

### Prior art

- **Code2DFD** finds Kafka and RabbitMQ flows by keyword search and follows identifiers into config. It matches a producer's routing key against a consumer's queue name and ignores bindings, which only works with the default exchange. Its information-flow precision is 0.92 and recall 0.84, its errors mostly in flow direction. https://arxiv.org/abs/2304.12769
- **The 2024 comparison of nine tools** found Code2DFD best for connections (F1 0.87). So even the best static tool misses about one connection in six. https://arxiv.org/abs/2412.08352

## Pitfalls for static analysis

- **Names behind `@ConfigurationProperties`.** Real projects keep queue, exchange and topic names in properties records and reach them through accessors: `properties.newOrdersQueue()` bound from `orders.new-orders-queue`. One-hop `@Value` following does not reach them.
- **Topology built by code.** `Declarables` from helper methods and loops, and names made by concatenation (`queue + ".dlq"`), often in a shared library whose names come from each consuming service's config.
- **Implicit names.** The Stream binding name as destination, Kafka `-retry` and `-dlt` topics, the listener `id` as Kafka group, Modulith's type name and Debezium's `outbox.event.*`.
- **Defaults outside the call.** Template defaults, and JMS queue or topic chosen by the container factory.
- **Runtime-only names**: `!{}` and `__listener` SpEL, and topics set in a message header. Record what is known; never drop the fact.
- **The same string, different things.** A Kafka Streams state store, or a listener `id`, named like a topic.
- **Things code cannot see.** Broker policies, SNS-to-SQS subscriptions, Kafka Connect config, and topics created on first send.

## What the extractor misses today

Against the findings, ADR 0037's extractor reads listeners, sends and bindings. It misses:

- queues, exchanges and topics declared but neither bound, sent to nor listened on in the same code, which ADR 0037 left out on purpose, and the declaration of those that are;
- dead-letter routing, `NewTopic` and `TopicBuilder` declarations, and `@RetryableTopic` topics;
- names behind `@ConfigurationProperties` accessors and helper methods;
- template defaults (`sendDefault`, `setExchange`, `spring.rabbitmq.template.*`), and JMS queue or topic from the container factory;
- Kafka Streams, plain `KafkaConsumer.subscribe`, `topicPartitions`, and the Kafka group from config;
- Spring Integration adapters, Spring Modulith, outbox and CDC, request and reply templates, `AmqpAdmin` and XML declarations, exchange-to-exchange bindings, programmatic listener containers, and the cloud brokers.

## Options

- **A. Widen value following** to `@ConfigurationProperties` accessors and helper methods, then add the declaration families. This lifts most unresolved names at once and helps other extractors too. It changes the shared reading of values (ADR 0038 and the design's Following values), so it is a design-level step of its own.
- **B. Add declarations within the extractor, on the calls it already reads.** Cheap and contained: queues, exchanges and topics as declared, with dead-letter routing.
- **C. Keyword search, as Code2DFD does.** Fast, but it ignores bindings and does not fit typed facts with evidence.
- **D. Leave the hard cases** (runtime SpEL, connector config, broker policies) to the resolver and the questions queue. Right for the residue only.
- **E. Derive implicit destinations** (retry and dead-letter topics, Stream defaults) as facts that name the rule that made them. Necessary for completeness, but each rule depends on the library version.

## Recommendation

Do B now, inside this extractor:

1. **`message-destination`**: every RabbitMQ queue and exchange and every Kafka topic the code declares, on the method that declares it and at the call, whether or not anything binds, sends to or listens on it. It records `broker`, `destinationKind` (`queue`, `exchange`, `topic`), `exchangeType` and `durable` when the declaration tells. Declarations inside a `Declarables` or another call's arguments count.
2. **Dead-letter routing** on a queue's declaration: `deadLetterExchange` and `deadLetterRoutingKey`, from the builder or the `x-dead-letter-*` arguments.

Later issues, each with a project-0 case first:

- `@ConfigurationProperties` accessors and helper methods in value following (option A, a design change);
- template defaults and the JMS queue or topic from the container factory;
- Kafka retry and dead-letter topics, Kafka Streams, plain consumers, `topicPartitions` and the group from config;
- Spring Integration adapters and request and reply templates;
- Spring Modulith externalization, outbox and CDC;
- the cloud brokers and Pulsar;
- and drawing declared destinations and dead-letter routes in documents.

## What it changes in the bar and the design

- **Design, Static analysis and extractors**: the messaging row names declared queues, exchanges and topics, and dead-letter routing; ADR 0037 is amended.
- **Quality bar**: nothing yet. Whether dead-letter queues and retry topics appear as their own elements is a question for the issue that draws them.

## Sources read in full

- Spring AMQP, Configuring the Broker: https://docs.spring.io/spring-amqp/reference/amqp/broker-configuration.html
- Spring AMQP, Annotation-driven listener endpoints: https://docs.spring.io/spring-amqp/reference/amqp/receiving-messages/async-annotation-driven.html
- Spring AMQP, Sending messages: https://docs.spring.io/spring-amqp/reference/amqp/sending-messages.html
- Spring AMQP, Reply management: https://docs.spring.io/spring-amqp/reference/amqp/receiving-messages/async-annotation-driven/reply.html
- Spring Kafka, `@KafkaListener`: https://docs.spring.io/spring-kafka/reference/kafka/receiving-messages/listener-annotation.html
- Spring Kafka, Configuring topics: https://docs.spring.io/spring-kafka/reference/kafka/configuring-topics.html
- Spring Kafka, Retry topic naming: https://docs.spring.io/spring-kafka/reference/retrytopic/topic-naming.html
- Spring Kafka, Sending messages: https://docs.spring.io/spring-kafka/reference/kafka/sending-messages.html
- Spring Kafka, Class-level `@KafkaListener`: https://docs.spring.io/spring-kafka/reference/kafka/receiving-messages/class-level-kafkalistener.html
- Spring Framework, Annotated JMS endpoints: https://docs.spring.io/spring-framework/reference/integration/jms/annotated.html
- Spring Cloud Stream, Functional binding names: https://docs.spring.io/spring-cloud-stream/reference/spring-cloud-stream/functional-binding-names.html
- Spring Cloud Stream, Producing and consuming messages: https://docs.spring.io/spring-cloud-stream/reference/spring-cloud-stream/producing-and-consuming-messages.html
- Spring Cloud Stream, Binding properties: https://docs.spring.io/spring-cloud-stream/reference/spring-cloud-stream/binding-properties.html
- Spring Cloud Stream, RabbitMQ binder overview and consumer properties: https://docs.spring.io/spring-cloud-stream/reference/rabbit/rabbit_overview.html, https://docs.spring.io/spring-cloud-stream/reference/rabbit/rabbit_overview/rabbitmq-consumer-properties.html
- Spring Modulith, Events: https://docs.spring.io/spring-modulith/reference/events.html
- Spring for Apache Pulsar, Message consumption and Topic resolution: https://docs.spring.io/spring-pulsar/reference/reference/pulsar/message-consumption.html, https://docs.spring.io/spring-pulsar/reference/reference/topic-resolution.html
- Spring Integration, AMQP outbound channel adapter: https://docs.spring.io/spring-integration/reference/amqp/outbound-channel-adapter.html
- Debezium, Outbox Event Router: https://debezium.io/documentation/reference/stable/transformations/outbox-event-router.html
- RabbitMQ, AMQP 0-9-1 concepts: https://www.rabbitmq.com/tutorials/amqp-concepts
- Microsoft Learn, Use Azure Service Bus in Spring applications: https://learn.microsoft.com/en-us/azure/developer/java/spring-framework/using-service-bus-in-spring-applications
- Schneider and Scandariato, Automatic Extraction of Security-Rich Dataflow Diagrams for Microservice Applications written in Java (Code2DFD), JSS 2023, with its Kafka and RabbitMQ extractors: https://arxiv.org/abs/2304.12769
- Schneider et al., Comparison of Static Analysis Architecture Recovery Tools for Microservice Applications, 2024 (method and RQ4): https://arxiv.org/abs/2412.08352
- Open-source code, fetched raw from GitHub: piomin/sample-spring-kafka-microservices (`OrderApp`, `OrderController`, `PaymentApp`, `application.yml`); ewolff/microservice-kafka (`OrderService`, `OrderKafkaListener`, `application.properties`); eventuate-tram/eventuate-tram-examples-customers-and-orders (`OrderEventConsumer`, `CustomerEventPublisherImpl`, `application.properties`); spring-cloud/spring-cloud-stream-samples (`kafka-e2e-kotlin-sample` configs); sivaprasadreddy/spring-boot-microservices-course (`ApplicationProperties`, `RabbitMQConfig`, `OrderEventPublisher`, `OrderEventHandler`); FAForever/faf-java-api (`RabbitConfiguration`); alejandro-torres/spring-microservices-showcase (`MessagingTopology`, `MessagingProperties`).

## Sources read in part

- Spring Boot application properties (the Kafka, JMS, Pulsar and RabbitMQ ranges): https://docs.spring.io/spring-boot/appendix/application-properties/index.html
- Spring Cloud AWS 3.2 reference (about the first 100,000 characters, SQS and SNS): https://docs.awspring.io/spring-cloud-aws/docs/3.2.0/reference/html/index.html
- Spring Cloud GCP reference (the Pub/Sub template part): https://googlecloudplatform.github.io/spring-cloud-gcp/reference/html/index.html
- `KafkaConsumer` Javadoc (the class documentation): https://kafka.apache.org/41/javadoc/org/apache/kafka/clients/consumer/KafkaConsumer.html

## Sources not read (carry no weight)

- The Kafka Streams DSL page (a redirect only) and docs.rabbitmq.com (DNS failure).
- ARCHI4MOM (ECSA 2022), Bushong, Das and Cerny 2022, Zanatta et al. 2024: search results only.

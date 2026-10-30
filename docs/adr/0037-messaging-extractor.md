# 0037: Messaging extractor

Status: accepted (#129, part of #92)

## Context

`docs/design.md` (Static analysis and extractors) says the Messaging extractor reads Kafka, JMS
and RabbitMQ listeners and templates and produces the topics, exchanges, queues, bindings and
routing keys produced and consumed. Following values says names are rarely literals where they are
used: placeholders are resolved per profile, constants folded, and a value followed one hop from a
`@Value` field or constructor argument into the template call that uses it. The linker (#94)
matches what is produced with what is consumed, with AMQP topic wildcards; the container view
draws queues and topics (#95) and sequence flows start at listeners and step through sends (#96).
The design leaves open what the code reading must record for this, what the facts look like and
which APIs count.

## Decisions

- **The shared code reading** (ADR 0038's `Expression` model, `TrackedApis`) is used, with the
  messaging packages added to the tracked APIs: Spring Kafka (`org.springframework.kafka.core`),
  Kafka's producer, Spring JMS (`org.springframework.jms.core`) and Spring AMQP
  (`org.springframework.amqp`, templates and declarations). Three additions to it, all
  additive: an `Invocation` names the tracked type its chain starts from; an `Invoke` carries the
  callee's parameter types when javac resolved it (empty when parsed), so readers can tell
  overloads apart; and annotations written as annotation values (`@QueueBinding(value =
  @Queue(...))`) are kept, by element, instead of dropped. The parser also folds well-known
  string constants annotations take (`ExchangeTypes.TOPIC`) by name, as it does well-known enum
  constants.
- **Following values.** A value passed to a template is: a literal or folded constant, used as it
  is (a string in code is not a placeholder); a field with `@Value`, whose text is resolved; a
  field given everywhere the same literal or the same `@Value` constructor parameter; a parameter
  with `@Value` of the method itself (a `@Bean` method's); or a concatenation of those. That is
  one hop. Anything else (a getter, a parameter the caller chooses) is not known. Annotation
  values (listener destinations, `@SendTo`, `@QueueBinding`) are resolved as Spring resolves them;
  SpEL is kept as written.
- **Config** is chosen and applied as ADR 0035 does: the module's own config, or for a library the
  config of the one module of its repository that has config; the default view plus a fact per
  profile whose value differs, with `profile`, `written` and `unresolved`. A value a secret was
  substituted into (`ConfigExtractor.isSecret`) is never recorded resolved: every value of that
  fact is recorded as written, with `masked: true`.
- **Facts**, all attributes with a string object on a method (or on the type for a field
  initializer), confidence that of the module's reading (1.0 compiled, 0.7 parsed), evidence the
  annotation or call:
  - `message-listener`, a child of `entry-point` (ADR 0035), so #96 finds listeners with the
    other entry points: the topic, topic pattern, queue or JMS destination listened on, one fact
    each, with `broker` (`kafka`, `jms`, `rabbitmq`), `destinationKind` (`topic`,
    `topic-pattern`, `queue`, `destination`), `annotation` and, for Kafka, `group` (`groupId`, else
    `id`). `@KafkaListener`, `@JmsListener` and `@RabbitListener` count directly, through
    meta-annotations, or on a class for its `@KafkaHandler` and `@RabbitHandler` methods; for
    RabbitMQ, `queues`, `queuesToDeclare` and the queues of `bindings` count.
  - `message-send` on the method making the call: the Kafka topic, JMS destination or RabbitMQ
    exchange, with `broker`, `destinationKind`, `template` (the type whose method sends) and
    `method`, and for an exchange the `routingKey`. Calls counted: `send` on `KafkaTemplate` or
    `KafkaOperations` with a topic name or a `new ProducerRecord(topic, ...)`, and `send` on
    Kafka's own `Producer` with a `new ProducerRecord`; `send`, `convertAndSend`, `sendAndReceive`
    and `convertSendAndReceive` on `JmsTemplate`, `JmsOperations` and the JMS messaging templates
    with a destination name; and the same plus `convertSendAndReceiveAsType` on `RabbitTemplate`,
    `RabbitOperations`, `AmqpTemplate` and `RabbitMessagingTemplate`. RabbitMQ's arguments are
    told apart by the callee's parameter types (leading `String`s: exchange and routing key, or a
    routing key alone); without types (parsed), by their number, so three arguments read as
    exchange, key and message. A send whose routing key cannot be followed is kept, with `unknown:
    ["routingKey"]`, rather than dropped; parsed, this is also how `(routingKey, message,
    postProcessor)` shows, its key then recorded as the exchange. A send through the default
    exchange (a routing key alone, or an exchange that is `""` in a view, after placeholders are
    resolved, such as `${app.exchange:}`) is recorded in that view as a send to the queue the key
    names, `destinationKind: queue`, as the broker routes it. A
    listener's `@SendTo` reply is a send too (`exchange/routingKey` for RabbitMQ), with the
    annotation as `template`. `followed` lists the fields and methods a value came through.
  - `message-binding` on the method declaring it: the queue's name, with `exchange`,
    `exchangeType` when known and `routingKey` (none for fanout and headers exchanges), so the
    linker can match routing keys against `order.*` patterns. Read from `BindingBuilder.bind(q)
    .to(x).with(key)` (or `withQueueName()`, or no key), `new Binding(queue, QUEUE, exchange,
    key, args)` (one whose destination type is `EXCHANGE`, by its constant's name, is left out) and
    `@QueueBinding`, whose exchange type is `direct` unless written, and not known when written but
    not foldable. The queue and exchange are followed to where they are
    declared: created in place (`new Queue`, `QueueBuilder.durable`, `new TopicExchange` and the
    other exchange classes, `ExchangeBuilder.topicExchange` and the like, through the builder's
    further calls), returned by a `@Bean` method called directly or named by a `@Bean` method's
    parameter, or a field's single initializer. The exchange's type comes from its declaration, else from
    the `to(...)` overload called.
  - The `@Profile` gate of a producer or listener stays the Spring extractor's fact where it is
    written (ADR 0035); a reader applies a class's gate to its sends and listeners, so a link that
    holds only under a profile (SPR-4) is the linker's to mark.
- **New vocabulary**, applied by the app on startup after the Spring extractor's, with source
  `messaging-extractor`: the attribute types `message-listener`, `message-send` and
  `message-binding`. Queue, topic and exchange entities and `PUBLISHES`/`CONSUMES` relationships
  are left to the linker (#94), which sees both ends.
- **Not covered:** Spring Cloud Stream bindings and `StreamBridge` (since covered by ADR 0040, #139), `sendDefault` and a
  template's configured default topic, exchange or routing key, destinations as `Destination`
  objects, plain Kafka consumers (`subscribe`), Kafka `topicPartitions`, exchange-to-exchange
  bindings, queues and topics declared but neither bound, sent to nor listened on, and values
  more than one hop away.
- **project-0.** `Project0MessagingIT` checks the cases MSG-1 to MSG-4, ENTRY-2, SPR-4 (the
  send), VAL-1, VAL-3 and VAL-4, with the repositories built in the sandbox and read through
  javac, and parsed without builds, and checks both give the same facts. It runs when
  `lantern.test.project0` is set, with the same command as `Project0BuildIT` (see Commands in
  `CLAUDE.md`) but `-pl lantern-extract` and `-Dit.test=Project0MessagingIT`.

## Amendment (#388): declared queues, exchanges and topics

Research: [`docs/research/extractor-messaging.md`](../research/extractor-messaging.md).
`messaging` version 5:

- **`message-destination`**, a new attribute type: every RabbitMQ queue and exchange and every
  Kafka topic the code declares, on the method that declares it (or the type, for a field
  initializer) and at the chain, whether or not anything binds, sends to or listens on it. This
  lifts the "queues and topics declared but neither bound, sent to nor listened on" exclusion
  above. The object is the name, followed and resolved per view as a binding's queue is. Read
  from the same creations as a binding's declaration (`new Queue`, `QueueBuilder.durable` and
  `nonDurable`, `new TopicExchange` and the other exchange classes, `new CustomExchange`,
  `ExchangeBuilder`'s factories), and from Kafka's `TopicBuilder.name(...)` and `new NewTopic`
  (`org.springframework.kafka.config.TopicBuilder` and `org.apache.kafka.clients.admin.NewTopic`
  join the tracked APIs). A creation inside another call's arguments, such as a `Declarables`
  or a binding, is a chain of its own and gives its own fact.
- **Attributes:** `broker` (`rabbitmq`, `kafka`), `destinationKind` (`queue`, `exchange`,
  `topic`), `exchangeType` when known, and for RabbitMQ `durable` when the declaration tells (a
  `QueueBuilder` factory, `ExchangeBuilder`'s `durable(...)`, a constructor's literal `durable`
  argument, the second, or the third for `CustomExchange`; durable when the constructor has no
  such argument, and for `ExchangeBuilder` without `durable(...)`, as Spring's are). A queue's
  dead-letter routing: `deadLetterExchange` and `deadLetterRoutingKey`, from the builder's
  methods, or the `x-dead-letter-exchange` and `x-dead-letter-routing-key` arguments given to
  `withArgument` or written as `Map.of(...)` for `withArguments` or the queue constructor's
  arguments, followed and resolved like the name.
- **Still not covered**, each a later issue: values behind `@ConfigurationProperties` accessors
  and helper methods (#447); template defaults and the JMS queue or topic from the container
  factory (#448); Kafka retry and dead-letter topics, Kafka Streams, plain consumers,
  `topicPartitions` and the group from config (#449); Spring Integration adapters, request and
  reply templates, `AmqpAdmin` and XML declarations, exchange-to-exchange bindings and
  programmatic containers (#450); Spring Modulith, outbox and CDC, the cloud brokers and Pulsar
  (#451); queue arguments other than `Map.of(...)` written in place (a map built over statements
  or held in a field), and dead-letter routing set by a broker policy, which code does not show;
  and how documents show declared destinations and dead-letter routes (#452).

# 0037: Messaging extractor

Status: accepted (#129, part of #92), pending the user's review (#24)

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

- **The code reading records more.** `JavaAnalysis` (ADR 0035) now also gives, per module, every
  call and object creation as an `Invocation`: caller, callee (when it resolves), the declaring
  type, the method name, and what the receiver and each argument are: a constant (folded as javac
  folds it, enum constants by name), a field read, a parameter read, the result of another
  invocation, or something else. It also gives assignments to the code's own fields and field
  initializers, the parameters of methods and constructors with their annotations, and
  annotations written as annotation values (`@QueueBinding(value = @Queue(...))`). Unlike `Call`,
  an invocation is kept when its callee does not resolve, which in degraded mode is every call
  into a library without a jar: its declaring type is then guessed from the type the receiver is
  declared with (a field, parameter or local variable), or the type named or created, as a
  parameter type is guessed (ADR 0007). Facts of the Java structure and Spring extractors do not
  change.
- **Following values.** A value passed to a template is: a constant, used as it is (a string in
  code is not a placeholder); a field with `@Value`, whose text is resolved; a field assigned
  everywhere the same constant or the same `@Value` parameter (a constructor's, or a setter whose
  method carries `@Value`); or a parameter with `@Value` of the method itself (a `@Bean` method's).
  That is one hop. Anything else (a local variable, a getter, a parameter the caller chooses) is
  not known, and nothing is recorded for that call. Annotation values (listener destinations,
  `@SendTo`, `@QueueBinding`) are resolved as Spring resolves them; SpEL is kept as written.
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
    routing key alone); without types (degraded mode), by their number, and a call whose second
    argument cannot be followed is then left out, since it may be a message rather than a key. A
    send through the default exchange (a routing key alone, or the exchange `""`) is recorded as a
    send to the queue the key names, `destinationKind: queue`, as the broker routes it. A
    listener's `@SendTo` reply is a send too (`exchange/routingKey` for RabbitMQ), with the
    annotation as `template`. `followed` lists the fields and methods a value came through.
  - `message-binding` on the method declaring it: the queue's name, with `exchange`,
    `exchangeType` when known and `routingKey` (none for fanout and headers exchanges), so the
    linker can match routing keys against `order.*` patterns. Read from `BindingBuilder.bind(q)
    .to(x).with(key)` (or `withQueueName()`, or no key), `new Binding(queue, QUEUE, exchange,
    key, args)` and `@QueueBinding`. The queue and exchange are followed to where they are
    declared: created in place (`new Queue`, `QueueBuilder.durable`, `new TopicExchange` and the
    other exchange classes, `ExchangeBuilder.topicExchange` and the like, through the builder's
    further calls), returned by a `@Bean` method called directly or named by a `@Bean` method's
    parameter, or a field's initializer. The exchange's type comes from its declaration, else from
    the `to(...)` overload called.
  - The `@Profile` gate of a producer or listener stays the Spring extractor's fact where it is
    written (ADR 0035); a reader applies a class's gate to its sends and listeners, so a link that
    holds only under a profile (SPR-4) is the linker's to mark.
- **New vocabulary**, applied by the app on startup after the Spring extractor's, with source
  `messaging-extractor`: the attribute types `message-listener`, `message-send` and
  `message-binding`. Queue, topic and exchange entities and `PUBLISHES`/`CONSUMES` relationships
  are left to the linker (#94), which sees both ends.
- **Not covered:** Spring Cloud Stream bindings and `StreamBridge` (#139), `sendDefault` and a
  template's configured default topic, exchange or routing key, destinations as `Destination`
  objects, plain Kafka consumers (`subscribe`), Kafka `topicPartitions`, exchange-to-exchange
  bindings, queues and topics declared but neither bound, sent to nor listened on, and values
  more than one hop away.
- **project-0.** `Project0MessagingIT` checks the cases MSG-1 to MSG-4, ENTRY-2, SPR-4 (the
  send), VAL-1, VAL-3 and VAL-4, with the repositories built in the sandbox and read through
  javac, and parsed without builds, and checks both give the same facts. It runs when
  `lantern.test.project0` is set, with the same command as `Project0BuildIT` (see Commands in
  `CLAUDE.md`) but `-pl lantern-extract` and `-Dit.test=Project0MessagingIT`.

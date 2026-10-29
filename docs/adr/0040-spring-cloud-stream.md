# 0040: Spring Cloud Stream in the messaging extractor

Status: accepted (#139, follow-up of #129), pending the user's review (#24)

## Context

The messaging extractor (ADR 0037) reads Kafka, JMS and RabbitMQ listeners, templates and AMQP
bindings. Many Spring applications use Spring Cloud Stream instead: functions bound to
destinations by config, `StreamBridge.send(...)`, and in older code the `@StreamListener`,
`@Input` and `@Output` annotations. Their code names a binding, not a topic or queue; the config
maps the binding to a destination and a binder. Without reading them, their topics and queues are
missing, and a Stream producer cannot be matched with a plain `@KafkaListener` consumer.

## Decisions

- **Same facts.** Stream bindings give `message-listener`, `message-send` and `message-binding`
  facts shaped as ADR 0037's, with the same `broker`, `destinationKind`, object (the topic, queue or
  exchange name) and `routingKey`, so the linker (#94) matches them with templates and listeners
  without knowing about Stream. They add `binding` (the binding's name). A listener of a function
  has no `annotation`, and a function's send has no `template`: neither is required any more.
  The vocabulary change is additive.
- **Functions.** A function bean is a `@Bean` method returning `java.util.function.Function`,
  `Consumer`, `Supplier`, `BiFunction` or `BiConsumer`, or a component class implementing one (its
  `apply`, `accept` or `get` method is the subject). For this the Java structure now records a
  method's erased return type (`Member.returns`), read by javac and by the parser; nothing else
  changes there. Which functions are bound comes from `spring.cloud.function.definition` (or the
  older `spring.cloud.stream.function.definition`), split on `;` or `,`, a composition `a|b` being
  one function named `ab` with `a`'s inputs and `b`'s outputs. Without a definition the module's
  only function bean is bound, as Spring Cloud Stream does; with several, none. A module binds
  functions only when its config has a `spring.cloud.stream.*` or `spring.cloud.function.*` key
  or its build classpath has a `spring-cloud-stream-*` jar, so a `Supplier<Clock>` bean in a plain
  application is not taken for a producer. The bindings are `<name>-in-<n>` and `<name>-out-<n>`:
  the ones the function's type gives, plus any further index the config sets properties for
  (functions over tuples of reactive streams), each renamed by
  `spring.cloud.stream.function.bindings.<binding>` if set.
- **Destinations and groups** come from `spring.cloud.stream.bindings.<binding>.destination` and
  `.group` (the group also from `spring.cloud.stream.default.group`); a binding without a
  destination uses its own name, as the binder does. A consumer's comma-separated destinations
  are one fact each. The config's written value is resolved in each view like a placeholder in an
  annotation, so `written`, `unresolved`, `profile` and masking (ADR 0037) apply unchanged:
  `written` is the value as the config property writes it, and a destination a secret was
  substituted into is recorded as written, `masked`. Plain settings that are not recorded as
  values (binder names, flags, exchange types) are used only when they expose no secret. Keys are
  read in kebab case or camel case.
- **Binder and broker.** A binding's binder is the binding's `binder`, else
  `spring.cloud.stream.default-binder`, a binder name mapped through
  `spring.cloud.stream.binders.<name>.type`; else the one binder type configured; else the one
  binder jar on the module's build classpath (`spring-cloud-stream-binder-kafka*`,
  `-rabbit*`); else the one broker the config's keys point to (`spring.cloud.stream.kafka.*` or
  `spring.kafka.*`, `spring.cloud.stream.rabbit.*` or `spring.rabbitmq.*`). Types `kafka`,
  `kstream`, `ktable` and `globalktable` are Kafka, `rabbit` RabbitMQ. When none of these tells
  one broker, the binding gives no facts rather than a guess, so parsed without config hints a
  module may give fewer facts than built.
- **Kafka**: a consumer listens on the topic (`topic-pattern` when the Kafka binder's
  `destination-is-pattern` is true) with its `group`; a producer sends to the topic.
- **RabbitMQ**: as the binder declares them, the destination is a topic exchange (unless the
  consumer's `exchange-type` says otherwise). A consumer listens on the queue
  `<destination>.<group>` (the group alone with `queue-name-group-only`), recorded with a
  `message-binding` of that queue to the exchange with the `binding-routing-key` (default `#`).
  Without a group the queue is anonymous: it is recorded as `<destination>.anonymous` with
  `anonymous: true`, since its real name ends in a random suffix. A producer sends to the
  exchange with the producer's `routing-key`, else a `routing-key-expression` that is a SpEL
  string literal, else the destination's name, the binder's default; any other expression is
  `unknown: ["routingKey"]`.
- **StreamBridge.** `send(name, data, ...)` on `StreamBridge` is a send through the binding of
  that name, the name followed as any template argument (ADR 0037). A name without a configured
  binding is the destination itself, as `StreamBridge` creates one. The overloads with a binder
  name (`send(name, binder, data[, type])`) use that binder; parsed, a second argument that names
  no binder is taken for the data.
- **The legacy annotations**, removed in Spring Cloud Stream 4, fit the same way and are read: a
  `@StreamListener` (`value` or `target`) is a listener on that binding, with its `@SendTo` a
  send; a call to `output()` of `Source` or `Processor` is a send through `output`; and a call to a
  binding interface's `@Output` method is a send through that binding. The parser folds the
  `Sink.INPUT`, `Source.OUTPUT` and `Processor` constants by name. A call to an `@Output` method
  is only seen through javac: parsed, the channel's type does not resolve, so the call is not
  recorded. Subscribing to an `@Input` channel directly is not read.
- **Evidence** is the function's declaration, the `StreamBridge` call or the annotation, not the
  config line, as for other listeners and sends.
- **Not covered:** binders other than Kafka and RabbitMQ, Kafka Streams' own consumer settings
  (`application.id`), partitioned destinations (`destination-<partition>` routing keys), several
  binding routing keys per queue, `bind-queue: false`, the `spring.cloud.stream.output-bindings`
  list (its bindings are named in the `StreamBridge` calls anyway), and functions defined in
  libraries outside the module.
- `MessagingExtractor.VERSION` is 2. project-0 uses no Spring Cloud Stream, so the cases are in
  the `messaging-stream` fixture.

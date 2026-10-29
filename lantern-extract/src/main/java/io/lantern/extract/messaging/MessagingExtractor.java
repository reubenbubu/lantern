package io.lantern.extract.messaging;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.vocabulary.VocabularyType;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.config.CodeConfig;
import io.lantern.extract.config.CodeConfig.CodeValue;
import io.lantern.extract.java.Expression;
import io.lantern.extract.java.JavaAnalysis;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.extract.java.ModuleStructure;
import io.lantern.extract.java.ModuleStructure.Annotation;
import io.lantern.extract.java.ModuleStructure.Assignment;
import io.lantern.extract.java.ModuleStructure.Invocation;
import io.lantern.extract.java.ModuleStructure.Location;
import io.lantern.extract.java.ModuleStructure.Member;
import io.lantern.extract.java.ModuleStructure.TypeDecl;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns Kafka, JMS and RabbitMQ code into facts: the methods that listen on topics, queues and
 * destinations, the methods that send to them through a template or producer, and the bindings of
 * RabbitMQ queues to exchanges. See {@code docs/adr/0037-messaging-extractor.md}.
 *
 * <p>It reads the code as the Java structure extractor read it ({@link JavaAnalysis}): through
 * javac with the build's classpath, or parsed in degraded mode, with the same confidence. Names are
 * found as the design's Following values says: constants folded, placeholders in annotations and
 * {@code @Value}s resolved through the config extractor per profile, and a value followed one hop
 * from a {@code @Value} field or parameter, or a field assigned from one, into the call using it.
 */
public final class MessagingExtractor {

  /** The extractor's name, recorded as the origin of its facts. */
  public static final String NAME = "messaging";

  /** The extractor's version; change it whenever the facts it produces change. */
  public static final String VERSION = "1";

  /** The source recorded on the vocabulary revision that adds this extractor's types. */
  public static final String VOCABULARY_SOURCE = "messaging-extractor";

  /** Attribute: the method handles messages, an entry point. */
  public static final String LISTENER = "message-listener";

  /** Attribute: the method sends messages. */
  public static final String SEND = "message-send";

  /** Attribute: the method binds a RabbitMQ queue to an exchange. */
  public static final String BINDING = "message-binding";

  /** Brokers. */
  public static final String KAFKA = "kafka";

  public static final String JMS = "jms";
  public static final String RABBITMQ = "rabbitmq";

  private static final String RESOURCE = "/vocabulary/messaging-types.json";

  private static final Origin ORIGIN = new Origin(Origin.Kind.STATIC, NAME, VERSION);

  private static final String KAFKA_LISTENER = "org.springframework.kafka.annotation.KafkaListener";
  private static final String KAFKA_HANDLER = "org.springframework.kafka.annotation.KafkaHandler";
  private static final String JMS_LISTENER = "org.springframework.jms.annotation.JmsListener";
  private static final String RABBIT_LISTENER =
      "org.springframework.amqp.rabbit.annotation.RabbitListener";
  private static final String RABBIT_HANDLER =
      "org.springframework.amqp.rabbit.annotation.RabbitHandler";
  private static final String SEND_TO = "org.springframework.messaging.handler.annotation.SendTo";
  private static final String VALUE_ANNOTATION =
      "org.springframework.beans.factory.annotation.Value";
  private static final String BEAN = "org.springframework.context.annotation.Bean";

  /** The listener annotations, with the broker each is for. */
  private static final Map<String, String> LISTENERS =
      Map.of(KAFKA_LISTENER, KAFKA, JMS_LISTENER, JMS, RABBIT_LISTENER, RABBITMQ);

  /** The method annotations that mark the handlers of a listener written on a class. */
  private static final Map<String, String> HANDLERS =
      Map.of(KAFKA_LISTENER, KAFKA_HANDLER, RABBIT_LISTENER, RABBIT_HANDLER);

  private static final Set<String> KAFKA_TEMPLATES =
      Set.of(
          "org.springframework.kafka.core.KafkaTemplate",
          "org.springframework.kafka.core.KafkaOperations");
  private static final Set<String> KAFKA_PRODUCERS =
      Set.of(
          "org.apache.kafka.clients.producer.KafkaProducer",
          "org.apache.kafka.clients.producer.Producer");
  private static final String PRODUCER_RECORD = "org.apache.kafka.clients.producer.ProducerRecord";
  private static final Set<String> JMS_TEMPLATES =
      Set.of(
          "org.springframework.jms.core.JmsTemplate",
          "org.springframework.jms.core.JmsOperations",
          "org.springframework.jms.core.JmsMessagingTemplate",
          "org.springframework.jms.core.JmsMessageOperations");
  private static final Set<String> JMS_METHODS =
      Set.of("send", "convertAndSend", "sendAndReceive", "convertSendAndReceive");
  private static final Set<String> RABBIT_TEMPLATES =
      Set.of(
          "org.springframework.amqp.rabbit.core.RabbitTemplate",
          "org.springframework.amqp.rabbit.core.RabbitOperations",
          "org.springframework.amqp.rabbit.core.RabbitMessagingTemplate",
          "org.springframework.amqp.core.AmqpTemplate");
  private static final Set<String> RABBIT_METHODS =
      Set.of(
          "send",
          "convertAndSend",
          "sendAndReceive",
          "convertSendAndReceive",
          "convertSendAndReceiveAsType");

  private static final String AMQP = "org.springframework.amqp.core.";
  private static final String BINDING_BUILDER = AMQP + "BindingBuilder";
  private static final String BINDING_CLASS = AMQP + "Binding";
  private static final String QUEUE = AMQP + "Queue";
  private static final String QUEUE_BUILDER = AMQP + "QueueBuilder";
  private static final String EXCHANGE_BUILDER = AMQP + "ExchangeBuilder";

  /** Exchange classes, with the exchange type each makes. */
  private static final Map<String, String> EXCHANGES =
      Map.of(
          AMQP + "TopicExchange", "topic",
          AMQP + "DirectExchange", "direct",
          AMQP + "FanoutExchange", "fanout",
          AMQP + "HeadersExchange", "headers");

  /** ExchangeBuilder's factory methods, with the exchange type each makes. */
  private static final Map<String, String> EXCHANGE_FACTORIES =
      Map.of(
          "topicExchange", "topic",
          "directExchange", "direct",
          "fanoutExchange", "fanout",
          "headersExchange", "headers");

  private static final String STRING = "java.lang.String";

  /** How many steps following a declaration may take, through bean methods and fields. */
  private static final int MAX_DEPTH = 4;

  /** The vocabulary types this extractor's facts use besides the built-in and Spring ones. */
  public static List<VocabularyType> vocabulary() {
    try (InputStream in = MessagingExtractor.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Missing resource " + RESOURCE);
      }
      JsonNode array = FactJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
      List<VocabularyType> types = new ArrayList<>();
      for (JsonNode node : array) {
        types.add(VocabularyType.fromJson(node));
      }
      return List.copyOf(types);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + RESOURCE, e);
    }
  }

  /**
   * Extracts the facts of a snapshot's repositories, reading their code first.
   *
   * @param repos the repositories of the snapshot, each at its commit with its build, if any
   * @param snapshot the snapshot number the facts hold from
   * @return the facts, without duplicates, ordered by subject, predicate, object, attributes and ID
   * @throws UncheckedIOException if the sources cannot be read or copied
   * @throws IllegalStateException if git fails or times out, or Lantern runs without a compiler
   */
  public List<Fact> extract(List<RepoBuild> repos, long snapshot) {
    return extract(JavaExtractor.analyze(repos), snapshot);
  }

  /**
   * Extracts the facts of a snapshot's code already read by {@link JavaExtractor#analyze}.
   *
   * @param code the code of the snapshot's repositories
   * @param snapshot the snapshot number the facts hold from
   * @return the facts, without duplicates, ordered by subject, predicate, object, attributes and ID
   * @throws IllegalStateException if git fails or times out while reading config
   */
  public List<Fact> extract(JavaAnalysis code, long snapshot) {
    Objects.requireNonNull(code, "code");
    Run run = new Run(code, Validity.from(snapshot));
    for (JavaAnalysis.Module module : code.modules()) {
      run.module(module);
    }
    return run.facts.values().stream()
        .sorted(
            Comparator.comparing((Fact f) -> f.subject().toString())
                .thenComparing(Fact::predicate)
                .thenComparing(f -> f.object().value())
                .thenComparing(f -> f.attributes().toString())
                .thenComparing(Fact::id))
        .toList();
  }

  /**
   * A value as found in code, before placeholders are resolved.
   *
   * @param written the value as written in code, or in the {@code @Value} it was followed from
   * @param placeholders whether {@code ${...}} placeholders in it are resolved, as in annotations
   *     and {@code @Value}s; a string passed to a template is used as it is
   * @param followed the entity IDs of the fields and methods the value was followed through
   */
  private record Text(String written, boolean placeholders, SortedSet<String> followed) {

    static Text literal(String value) {
      return new Text(value, false, new TreeSet<>());
    }

    static Text annotated(String value) {
      return new Text(value, true, new TreeSet<>());
    }

    Text via(EntityId id) {
      SortedSet<String> all = new TreeSet<>(followed);
      all.add(id.toString());
      return new Text(written, placeholders, all);
    }

    static Text concat(List<Text> parts) {
      StringBuilder written = new StringBuilder();
      boolean placeholders = false;
      SortedSet<String> followed = new TreeSet<>();
      for (Text t : parts) {
        written.append(t.written());
        placeholders |= t.placeholders();
        followed.addAll(t.followed());
      }
      return new Text(written.toString(), placeholders, followed);
    }
  }

  /** A queue or exchange declared in code: its name and, for an exchange, its type if known. */
  private record Declared(Text name, String exchangeType) {}

  /**
   * What one view of a fact holds before its values are resolved: the object, the attributes
   * resolved like it, by name, and the attributes that are not values.
   */
  private record Shape(Text object, Map<String, Text> named, ObjectNode attributes) {}

  /** One extraction: the facts so far, the code indexed, and the config of each repository. */
  private static final class Run {

    private final JavaAnalysis code;
    private final Validity validity;
    private final Map<String, Fact> facts = new LinkedHashMap<>();
    private final CodeConfig configs = new CodeConfig();
    private final Map<EntityId, Member> members = new HashMap<>();
    private final Map<EntityId, List<Assignment>> assignments = new HashMap<>();
    private final Map<EntityId, List<Invocation>> invocationsByCaller = new HashMap<>();

    private JavaAnalysis.Module module;
    private CodeConfig.View config;
    private Map<String, EntityId> beans;

    Run(JavaAnalysis code, Validity validity) {
      this.code = code;
      this.validity = validity;
      for (JavaAnalysis.Module m : code.modules()) {
        for (TypeDecl t : m.structure().types()) {
          for (Member member : t.members()) {
            members.putIfAbsent(member.id(), member);
          }
        }
        for (Assignment a : m.structure().assignments()) {
          assignments.computeIfAbsent(a.field(), k -> new ArrayList<>()).add(a);
        }
        for (Invocation i : m.structure().invocations()) {
          invocationsByCaller.computeIfAbsent(i.caller(), k -> new ArrayList<>()).add(i);
        }
      }
    }

    void module(JavaAnalysis.Module m) {
      this.module = m;
      this.config = configs.forModule(m.repo(), m.directory());
      this.beans = new HashMap<>();
      for (TypeDecl type : m.structure().types()) {
        for (Member member : type.members()) {
          String bean = beanName(member);
          if (bean != null) {
            beans.putIfAbsent(bean, member.id());
          }
        }
      }
      for (TypeDecl type : m.structure().types()) {
        if (type.kind().equals(ModuleStructure.CLASS)) {
          listeners(type);
        }
      }
      for (Invocation i : m.structure().invocations()) {
        send(i);
        binding(i);
      }
    }

    // -------------------------------------------------------------------------------------------
    // Listeners

    private void listeners(TypeDecl type) {
      for (Map.Entry<String, String> handler : HANDLERS.entrySet()) {
        List<Annotation> onClass = code.annotationPath(type.annotations(), handler.getKey());
        if (onClass == null) {
          continue;
        }
        for (Member m : type.members()) {
          if (isMethod(m) && code.annotationPath(m.annotations(), handler.getValue()) != null) {
            listener(m, onClass);
          }
        }
      }
      for (Member m : type.members()) {
        if (!isMethod(m)) {
          continue;
        }
        for (Annotation a : m.annotations()) {
          for (String listener : LISTENERS.keySet()) {
            List<Annotation> path = code.annotationPath(a, listener);
            if (path != null) {
              listener(m, path);
            }
          }
        }
        String broker = listenerBroker(m, type);
        if (broker != null) {
          sendTo(m, type, broker);
        }
      }
    }

    /** The facts of one listener annotation: the path from the one written to the listener. */
    private void listener(Member method, List<Annotation> path) {
      Annotation written = path.getFirst();
      String broker = LISTENERS.get(path.getLast().type());
      ObjectNode attributes = FactJson.newObject();
      attributes.put("broker", broker);
      attributes.put("annotation", written.type());
      Location where = written.where() != null ? written.where() : method.where();
      switch (broker) {
        case KAFKA -> {
          Text group = null;
          List<String> groups = element(path, "groupId");
          if (groups.isEmpty()) {
            groups = element(path, "id");
          }
          if (!groups.isEmpty()) {
            group = Text.annotated(groups.getFirst());
          }
          for (String topic : element(path, "topics")) {
            listen(method, Text.annotated(topic), "topic", group, attributes, where);
          }
          for (String pattern : element(path, "topicPattern")) {
            listen(method, Text.annotated(pattern), "topic-pattern", group, attributes, where);
          }
        }
        case JMS -> {
          for (String destination : element(path, "destination")) {
            listen(method, Text.annotated(destination), "destination", null, attributes, where);
          }
        }
        default -> {
          for (String queue : element(path, "queues")) {
            listen(method, Text.annotated(queue), "queue", null, attributes, where);
          }
          for (Annotation queue : nested(path, "queuesToDeclare")) {
            String name = firstOf(queue, "name", "value");
            if (name != null) {
              listen(method, Text.annotated(name), "queue", null, attributes, where);
            }
          }
          for (Annotation binding : nested(path, "bindings")) {
            queueBinding(method, binding, attributes, where);
          }
        }
      }
    }

    private void listen(
        Member method,
        Text destination,
        String kind,
        Text group,
        ObjectNode common,
        Location where) {
      ObjectNode attributes = common.deepCopy();
      attributes.put("destinationKind", kind);
      Map<String, Text> named = new LinkedHashMap<>();
      if (group != null) {
        named.put("group", group);
      }
      emit(method.id(), LISTENER, destination, named, attributes, where);
    }

    /** A {@code @QueueBinding} of a {@code @RabbitListener}: the queue listened on and bound. */
    private void queueBinding(
        Member method, Annotation binding, ObjectNode common, Location where) {
      List<Annotation> queues = binding.nested("value");
      List<Annotation> exchanges = binding.nested("exchange");
      if (queues.isEmpty()) {
        return;
      }
      String queue = firstOf(queues.getFirst(), "name", "value");
      if (queue == null) {
        return;
      }
      listen(method, Text.annotated(queue), "queue", null, common, where);
      if (exchanges.isEmpty()) {
        return;
      }
      Annotation written = exchanges.getFirst();
      String exchange = firstOf(written, "name", "value");
      if (exchange == null) {
        return;
      }
      // @Exchange's type defaults to direct; one written that could not be folded is not known.
      String exchangeType =
          !written.values().containsKey("type")
              ? "direct"
              : written.values("type").isEmpty() ? null : written.values("type").getFirst();
      Declared declared = new Declared(Text.annotated(exchange), exchangeType);
      List<String> keys = binding.values("key");
      if (keys.isEmpty()) {
        bindingFact(method.id(), Text.annotated(queue), declared, null, where);
      }
      for (String key : keys) {
        bindingFact(method.id(), Text.annotated(queue), declared, Text.annotated(key), where);
      }
    }

    /**
     * A listener's reply, {@code @SendTo}: to a Kafka topic or JMS destination, or to a RabbitMQ
     * {@code exchange/routingKey}.
     */
    private void sendTo(Member method, TypeDecl type, String broker) {
      List<Annotation> path = code.annotationPath(method.annotations(), SEND_TO);
      if (path == null) {
        path = code.annotationPath(type.annotations(), SEND_TO);
      }
      if (path == null) {
        return;
      }
      Annotation written = path.getFirst();
      Location where = written.where() != null ? written.where() : method.where();
      for (String to : element(path, "value")) {
        if (to.isBlank() || to.startsWith("!{")) {
          continue;
        }
        ObjectNode attributes = FactJson.newObject();
        attributes.put("broker", broker);
        attributes.put("template", written.type());
        if (!broker.equals(RABBITMQ)) {
          attributes.put("destinationKind", broker.equals(KAFKA) ? "topic" : "destination");
          emit(method.id(), SEND, Text.annotated(to), Map.of(), attributes, where);
          continue;
        }
        int slash = to.indexOf('/');
        String exchange = slash < 0 ? "" : to.substring(0, slash);
        String key = slash < 0 ? to : to.substring(slash + 1);
        rabbitSend(
            method.id(),
            Text.annotated(exchange),
            key.isEmpty() ? null : Text.annotated(key),
            false,
            attributes,
            where);
      }
    }

    /** The broker of the listener a method is, or null if it is none. */
    private String listenerBroker(Member method, TypeDecl type) {
      for (Annotation a : method.annotations()) {
        for (String listener : LISTENERS.keySet()) {
          if (code.annotationPath(a, listener) != null) {
            return LISTENERS.get(listener);
          }
        }
      }
      for (Map.Entry<String, String> handler : HANDLERS.entrySet()) {
        if (code.annotationPath(type.annotations(), handler.getKey()) != null
            && code.annotationPath(method.annotations(), handler.getValue()) != null) {
          return LISTENERS.get(handler.getKey());
        }
      }
      return null;
    }

    // -------------------------------------------------------------------------------------------
    // Sends

    /** A send at the start of a chain on a template or producer ({@code send(...).get()}). */
    private void send(Invocation i) {
      String type = i.rootType();
      Expression.Invoke call = i.expression().chain().getFirst();
      List<Expression> arguments = call.arguments();
      if (type == null || arguments.isEmpty() || call.receiver() == null) {
        return;
      }
      List<String> parameters = call.parameterTypes();
      boolean typed = !parameters.isEmpty();
      Expression first = arguments.getFirst();
      EntityId caller = i.caller();
      ObjectNode attributes = FactJson.newObject();
      attributes.put("template", type);
      attributes.put("method", call.method());
      boolean kafkaTemplate = KAFKA_TEMPLATES.contains(type) && call.method().equals("send");
      if (kafkaTemplate || (KAFKA_PRODUCERS.contains(type) && call.method().equals("send"))) {
        attributes.put("broker", KAFKA);
        attributes.put("destinationKind", "topic");
        Text topic = null;
        if (first instanceof Expression.Invoke record
            && record.receiver() instanceof Expression.TypeRef t
            && t.binaryName().equals(PRODUCER_RECORD)
            && record.method().equals(ModuleStructure.CONSTRUCTOR)
            && !record.arguments().isEmpty()) {
          topic = text(record.arguments().getFirst(), caller);
        } else if (kafkaTemplate
            && (typed ? parameters.getFirst().equals(STRING) : arguments.size() >= 2)) {
          topic = text(first, caller);
        }
        if (topic != null) {
          emit(caller, SEND, topic, Map.of(), attributes, i.where());
        }
        return;
      }
      if (JMS_TEMPLATES.contains(type) && JMS_METHODS.contains(call.method())) {
        boolean named = typed ? parameters.getFirst().equals(STRING) : arguments.size() >= 2;
        Text destination = named ? text(first, caller) : null;
        if (destination != null) {
          attributes.put("broker", JMS);
          attributes.put("destinationKind", "destination");
          emit(caller, SEND, destination, Map.of(), attributes, i.where());
        }
        return;
      }
      if (RABBIT_TEMPLATES.contains(type) && RABBIT_METHODS.contains(call.method())) {
        // Leading String parameters name the exchange and routing key, or the routing key alone.
        int names;
        if (typed) {
          names = 0;
          while (names < 2
              && names < parameters.size() - 1
              && parameters.get(names).equals(STRING)) {
            names++;
          }
        } else {
          names = Math.min(2, arguments.size() - 1);
        }
        attributes.put("broker", RABBITMQ);
        if (names == 1) {
          rabbitSend(caller, Text.literal(""), text(first, caller), false, attributes, i.where());
        } else if (names == 2) {
          Text key = text(arguments.get(1), caller);
          // Without types, (routing key, message, post-processor) reads as (exchange, key,
          // message): the key is then marked unknown rather than the send dropped.
          rabbitSend(caller, text(first, caller), key, key == null, attributes, i.where());
        }
      }
    }

    /**
     * A RabbitMQ send: to the exchange with the routing key, or, in a view where the exchange is
     * the default one ({@code ""}), to the queue the routing key names, as the broker routes it.
     */
    private void rabbitSend(
        EntityId subject,
        Text exchange,
        Text key,
        boolean keyUnknown,
        ObjectNode common,
        Location where) {
      if (exchange == null) {
        return;
      }
      emitViews(
          subject,
          SEND,
          where,
          profile -> {
            CodeValue resolved = resolve(profile, exchange);
            ObjectNode attributes = common.deepCopy();
            if (!resolved.masked() && resolved.value().isEmpty()) {
              if (key == null) {
                return null;
              }
              attributes.put("destinationKind", "queue");
              return new Shape(key, Map.of(), attributes);
            }
            attributes.put("destinationKind", "exchange");
            Map<String, Text> named = new LinkedHashMap<>();
            if (key != null) {
              named.put("routingKey", key);
            } else if (keyUnknown) {
              attributes.putArray("unknown").add("routingKey");
            }
            return new Shape(exchange, named, attributes);
          });
    }

    // -------------------------------------------------------------------------------------------
    // Bindings

    private void binding(Invocation i) {
      List<Expression.Invoke> chain = i.expression().chain();
      Expression.Invoke first = chain.getFirst();
      EntityId caller = i.caller();
      if (BINDING_CLASS.equals(i.rootType())
          && first.method().equals(ModuleStructure.CONSTRUCTOR)
          && first.arguments().size() >= 4) {
        // new Binding(destination, destinationType, exchange, routingKey, arguments)
        if (isExchangeDestination(first.arguments().get(1))) {
          return;
        }
        Text queue = text(first.arguments().getFirst(), caller);
        Text exchange = text(first.arguments().get(2), caller);
        if (queue != null && exchange != null) {
          bindingFact(
              caller,
              queue,
              new Declared(exchange, null),
              text(first.arguments().get(3), caller),
              i.where());
        }
        return;
      }
      if (!BINDING_BUILDER.equals(i.rootType())
          || !first.method().equals("bind")
          || first.arguments().size() != 1
          || chain.size() < 2
          || !chain.get(1).method().equals("to")
          || chain.get(1).arguments().size() != 1) {
        return;
      }
      Declared queue = declared(first.arguments().getFirst(), caller, false, 0);
      Expression.Invoke to = chain.get(1);
      Declared exchange = declared(to.arguments().getFirst(), caller, true, 0);
      if (queue == null || exchange == null) {
        return;
      }
      if (exchange.exchangeType() == null && !to.parameterTypes().isEmpty()) {
        exchange = new Declared(exchange.name(), EXCHANGES.get(to.parameterTypes().getFirst()));
      }
      Text key = null;
      if (chain.size() > 2) {
        Expression.Invoke with = chain.get(2);
        key =
            switch (with.method()) {
              case "with" ->
                  with.arguments().size() == 1 ? text(with.arguments().getFirst(), caller) : null;
              case "withQueueName" -> queue.name();
              default -> null;
            };
      }
      bindingFact(caller, queue.name(), exchange, key, i.where());
    }

    /** Whether a binding's destination type is {@code EXCHANGE}, named by its constant. */
    private static boolean isExchangeDestination(Expression e) {
      return switch (e) {
        case Expression.FieldRef f -> f.name().equals("EXCHANGE");
        case Expression.Literal l -> l.value().equals("EXCHANGE");
        case Expression.Unknown u -> u.source().endsWith("EXCHANGE");
        default -> false;
      };
    }

    private void bindingFact(
        EntityId subject, Text queue, Declared exchange, Text key, Location where) {
      ObjectNode attributes = FactJson.newObject();
      attributes.put("broker", RABBITMQ);
      if (exchange.exchangeType() != null) {
        attributes.put("exchangeType", exchange.exchangeType());
      }
      Map<String, Text> named = new LinkedHashMap<>();
      named.put("exchange", exchange.name());
      boolean keyless =
          "fanout".equals(exchange.exchangeType()) || "headers".equals(exchange.exchangeType());
      if (key != null && !keyless) {
        named.put("routingKey", key);
      }
      emit(subject, BINDING, queue, named, attributes, where);
    }

    /**
     * The queue or exchange a value is: created where it is used, returned by a {@code @Bean}
     * method it calls, the bean a {@code @Bean} method's parameter names, or a field's initializer.
     */
    private Declared declared(Expression e, EntityId context, boolean exchange, int depth) {
      if (depth > MAX_DEPTH) {
        return null;
      }
      switch (e) {
        case Expression.Invoke call -> {
          Declared d = declaration(call, context, exchange);
          if (d != null) {
            return d;
          }
          if (call.receiver() == null) {
            EntityId target = ownMethod(context, call.method(), call.arguments().size());
            if (target != null) {
              return declaredIn(target, exchange, depth + 1);
            }
          }
          return null;
        }
        case Expression.Param p -> {
          ModuleStructure.Parameter parameter = parameter(context, p.index());
          EntityId bean = parameter == null ? null : beans.get(parameter.name());
          return bean != null && !bean.equals(context)
              ? declaredIn(bean, exchange, depth + 1)
              : null;
        }
        case Expression.FieldRef f -> {
          EntityId id = fieldId(f);
          List<Assignment> given = id == null ? List.of() : assignments.getOrDefault(id, List.of());
          return given.size() == 1
              ? declared(given.getFirst().value(), given.getFirst().in(), exchange, depth + 1)
              : null;
        }
        default -> {
          return null;
        }
      }
    }

    /** The one queue or exchange a method declares, or null if none or several. */
    private Declared declaredIn(EntityId method, boolean exchange, int depth) {
      Set<Declared> found = new LinkedHashSet<>();
      for (Invocation i : invocationsByCaller.getOrDefault(method, List.of())) {
        Declared d = declaration(i.expression(), method, exchange);
        if (d != null) {
          found.add(d);
        }
      }
      return found.size() == 1 ? found.iterator().next() : null;
    }

    /**
     * The queue or exchange a chain creates: a constructor, or a builder's factory method and the
     * builder's further calls on what it made ({@code durable(true)}, {@code build()}).
     */
    private Declared declaration(Expression.Invoke call, EntityId context, boolean exchange) {
      Expression.Invoke first = call.chain().getFirst();
      if (!(first.receiver() instanceof Expression.TypeRef t) || first.arguments().isEmpty()) {
        return null;
      }
      String type = t.binaryName();
      String method = first.method();
      boolean creation = method.equals(ModuleStructure.CONSTRUCTOR);
      if (!exchange) {
        boolean queue =
            (QUEUE.equals(type) && creation)
                || (QUEUE_BUILDER.equals(type)
                    && (method.equals("durable") || method.equals("nonDurable")));
        return queue ? named(first, context, null) : null;
      }
      if (creation && EXCHANGES.containsKey(type)) {
        return named(first, context, EXCHANGES.get(type));
      }
      if ((AMQP + "CustomExchange").equals(type) && creation && first.arguments().size() >= 2) {
        String kind = first.arguments().get(1) instanceof Expression.Literal l ? l.value() : null;
        return named(first, context, kind);
      }
      if (EXCHANGE_BUILDER.equals(type) && EXCHANGE_FACTORIES.containsKey(method)) {
        return named(first, context, EXCHANGE_FACTORIES.get(method));
      }
      return null;
    }

    private Declared named(Expression.Invoke call, EntityId context, String exchangeType) {
      Text name = text(call.arguments().getFirst(), context);
      return name == null ? null : new Declared(name, exchangeType);
    }

    /** The method of the caller's own type with that name and number of parameters, or null. */
    private EntityId ownMethod(EntityId caller, String name, int parameters) {
      String key = caller.key();
      int hash = key.indexOf('#');
      TypeDecl type = code.type(hash < 0 ? key : key.substring(0, hash));
      if (type == null) {
        return null;
      }
      EntityId found = null;
      for (Member m : type.members()) {
        String id = m.id().key();
        if (isMethod(m)
            && id.substring(id.indexOf('#') + 1, id.indexOf('(')).equals(name)
            && parameterCount(m.id()) == parameters) {
          if (found != null) {
            return null;
          }
          found = m.id();
        }
      }
      return found;
    }

    // -------------------------------------------------------------------------------------------
    // Following values

    /**
     * What a value in code is, as a text: a constant as it is, or followed one hop from a field or
     * parameter with {@code @Value}, or a field given everywhere the same constant or
     * {@code @Value} constructor parameter, and concatenations of those. Null if it cannot be told.
     */
    private Text text(Expression e, EntityId context) {
      return switch (e) {
        case Expression.Literal l -> Text.literal(l.value());
        case Expression.FieldRef f -> field(f);
        case Expression.Param p -> parameter(context, p.index(), context);
        case Expression.Concat c -> {
          List<Text> parts = new ArrayList<>();
          for (Expression part : c.parts()) {
            Text t = text(part, context);
            if (t == null) {
              yield null;
            }
            parts.add(t);
          }
          yield Text.concat(parts);
        }
        default -> null;
      };
    }

    private Text field(Expression.FieldRef f) {
      EntityId id = fieldId(f);
      if (id == null) {
        return null;
      }
      Member m = members.get(id);
      if (m != null) {
        String value = valueAnnotation(m.annotations());
        if (value != null) {
          return Text.annotated(value).via(id);
        }
      }
      Set<Text> found = new LinkedHashSet<>();
      for (Assignment a : assignments.getOrDefault(id, List.of())) {
        Text t =
            switch (a.value()) {
              case Expression.Literal l -> Text.literal(l.value());
              case Expression.Param p -> parameter(a.in(), p.index(), a.in());
              default -> null;
            };
        if (t == null) {
          return null;
        }
        found.add(t);
      }
      return found.size() == 1 ? found.iterator().next().via(id) : null;
    }

    /** A parameter with {@code @Value}, followed through the method it belongs to. */
    private Text parameter(EntityId method, int index, EntityId via) {
      ModuleStructure.Parameter p = parameter(method, index);
      String value = p == null ? null : valueAnnotation(p.annotations());
      return value == null ? null : Text.annotated(value).via(via);
    }

    private ModuleStructure.Parameter parameter(EntityId method, int index) {
      Member m = members.get(method);
      return m == null || index >= m.parameters().size() ? null : m.parameters().get(index);
    }

    private String valueAnnotation(List<Annotation> annotations) {
      List<Annotation> path = code.annotationPath(annotations, VALUE_ANNOTATION);
      if (path == null) {
        return null;
      }
      List<String> values = path.getLast().values("value");
      return values.isEmpty() ? null : values.getFirst();
    }

    private static String beanName(Member member) {
      if (!isMethod(member)) {
        return null;
      }
      for (Annotation a : member.annotations()) {
        if (a.type().equals(BEAN)) {
          String name = firstOf(a, "name", "value");
          if (name != null) {
            return name;
          }
          String key = member.id().key();
          return key.substring(key.indexOf('#') + 1, key.indexOf('('));
        }
      }
      return null;
    }

    // -------------------------------------------------------------------------------------------
    // Annotations

    /** An element's values: from the nearest annotation along the path that sets it. */
    private static List<String> element(List<Annotation> path, String name) {
      for (Annotation a : path) {
        if (!a.values(name).isEmpty()) {
          return a.values(name);
        }
      }
      return List.of();
    }

    /** An element's nested annotations: from the nearest annotation along the path with them. */
    private static List<Annotation> nested(List<Annotation> path, String name) {
      for (Annotation a : path) {
        if (!a.nested(name).isEmpty()) {
          return a.nested(name);
        }
      }
      return List.of();
    }

    private static String firstOf(Annotation a, String... elements) {
      for (String e : elements) {
        if (!a.values(e).isEmpty()) {
          return a.values(e).getFirst();
        }
      }
      return null;
    }

    private static boolean isMethod(Member m) {
      return m.id().type().equals(ModuleStructure.METHOD)
          && !m.id().key().contains("#" + ModuleStructure.CONSTRUCTOR + "(");
    }

    private static int parameterCount(EntityId method) {
      String key = method.key();
      String list = key.substring(key.indexOf('(') + 1, key.length() - 1);
      return list.isEmpty() ? 0 : list.split(",").length;
    }

    private static EntityId fieldId(Expression.FieldRef f) {
      try {
        return new EntityId(ModuleStructure.FIELD, f.owner() + "#" + f.name());
      } catch (IllegalArgumentException e) {
        return null;
      }
    }

    // -------------------------------------------------------------------------------------------
    // Values and facts

    private CodeValue resolve(String profile, Text text) {
      if (!text.placeholders()) {
        return new CodeValue(text.written(), text.written(), new TreeSet<>(), false);
      }
      return CodeValue.of(config.resolve(profile, text.written()), text.written());
    }

    /** The attributes whose value as written is recorded too, when it differs. */
    private static final Set<String> WITH_WRITTEN = Set.of("routingKey", "exchange");

    private void emit(
        EntityId subject,
        String predicate,
        Text object,
        Map<String, Text> named,
        ObjectNode attributes,
        Location where) {
      Shape shape = new Shape(object, named, attributes);
      emitViews(subject, predicate, where, profile -> shape);
    }

    /**
     * Records a fact for the default view, and one for each profile in which it differs, with the
     * values as resolved in that view: the object, and the named attributes. A value a secret was
     * substituted into is never recorded resolved: all are recorded as written instead, with {@code
     * masked}.
     *
     * @param shape what the fact holds in a view (null for the default view), or null for none
     */
    private void emitViews(
        EntityId subject, String predicate, Location where, Function<String, Shape> shape) {
      ObjectNode byDefault = null;
      String defaultValue = null;
      for (String profile : config.views()) {
        Shape s = shape.apply(profile);
        if (s == null) {
          continue;
        }
        List<CodeValue> values = new ArrayList<>();
        values.add(resolve(profile, s.object()));
        for (Text t : s.named().values()) {
          values.add(resolve(profile, t));
        }
        boolean masked = values.stream().anyMatch(CodeValue::masked);
        ObjectNode attributes = s.attributes().deepCopy();
        CodeValue first = values.getFirst();
        int i = 1;
        SortedSet<String> unresolved = new TreeSet<>(first.unresolved());
        for (String name : s.named().keySet()) {
          CodeValue r = values.get(i++);
          attributes.put(name, masked ? r.written() : r.value());
          if (!masked && WITH_WRITTEN.contains(name) && !r.written().equals(r.value())) {
            attributes.put(name + "Written", r.written());
          }
          unresolved.addAll(r.unresolved());
        }
        if (masked) {
          attributes.put("masked", true);
        } else {
          if (!first.written().equals(first.value())) {
            attributes.put("written", first.written());
          }
          if (!unresolved.isEmpty()) {
            ArrayNode keys = attributes.putArray("unresolved");
            unresolved.forEach(keys::add);
          }
        }
        SortedSet<String> followed = new TreeSet<>(s.object().followed());
        s.named().values().forEach(t -> followed.addAll(t.followed()));
        if (!followed.isEmpty() && !predicate.equals(LISTENER)) {
          ArrayNode via = attributes.putArray("followed");
          followed.forEach(via::add);
        }
        String value = masked ? first.written() : first.value();
        if (profile == null) {
          byDefault = attributes;
          defaultValue = value;
        } else if (value.equals(defaultValue) && attributes.equals(byDefault)) {
          continue;
        } else {
          attributes.put("profile", profile);
        }
        if (!value.isEmpty()) {
          fact(subject, predicate, value, attributes, where);
        }
      }
    }

    private void fact(
        EntityId subject, String predicate, String object, ObjectNode attributes, Location where) {
      if (subject == null) {
        return;
      }
      RepoBuild repo = module.repo();
      Evidence evidence =
          new Evidence(
              repo.repo().name(),
              repo.commit(),
              where.file().path(),
              where.start() > 0 ? new Evidence.LineRange(where.start(), where.end()) : null,
              where.file().contentHash());
      Fact fact =
          Fact.create(
              subject,
              predicate,
              FactObject.string(object),
              attributes,
              ORIGIN,
              evidence,
              module.confidence(),
              validity);
      facts.putIfAbsent(fact.id(), fact);
    }
  }
}

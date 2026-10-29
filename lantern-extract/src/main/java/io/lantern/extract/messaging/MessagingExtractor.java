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
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.config.ModuleConfig;
import io.lantern.extract.config.Resolution;
import io.lantern.extract.java.JavaAnalysis;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.extract.java.ModuleStructure;
import io.lantern.extract.java.ModuleStructure.Annotation;
import io.lantern.extract.java.ModuleStructure.Assignment;
import io.lantern.extract.java.ModuleStructure.Constant;
import io.lantern.extract.java.ModuleStructure.FieldRead;
import io.lantern.extract.java.ModuleStructure.Invocation;
import io.lantern.extract.java.ModuleStructure.Location;
import io.lantern.extract.java.ModuleStructure.Member;
import io.lantern.extract.java.ModuleStructure.ParameterRead;
import io.lantern.extract.java.ModuleStructure.Result;
import io.lantern.extract.java.ModuleStructure.TypeDecl;
import io.lantern.extract.java.ModuleStructure.Value;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
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
  }

  /** A queue or exchange declared in code: its name and, for an exchange, its type if known. */
  private record Declared(Text name, String exchangeType) {}

  /** A value with its placeholders resolved in one view, as it may be recorded. */
  private record Resolved(
      String value, String written, SortedSet<String> unresolved, boolean masked) {}

  /** One extraction: the facts so far, the code indexed, and the config of each repository. */
  private static final class Run {

    private final JavaAnalysis code;
    private final Validity validity;
    private final Map<String, Fact> facts = new LinkedHashMap<>();
    private final Map<RepoBuild, List<ModuleConfig>> configs = new HashMap<>();
    private final Map<EntityId, Member> members = new HashMap<>();
    private final Map<EntityId, List<Assignment>> assignments = new HashMap<>();
    private final Map<EntityId, List<Invocation>> invocationsByCaller = new HashMap<>();

    private JavaAnalysis.Module module;
    private ModuleConfig config;
    private Map<String, EntityId> beans;
    private Map<Invocation, List<Invocation>> users;

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
      this.config = config(m);
      this.beans = new HashMap<>();
      this.users = new IdentityHashMap<>();
      for (TypeDecl type : m.structure().types()) {
        for (Member member : type.members()) {
          String bean = beanName(member);
          if (bean != null) {
            beans.putIfAbsent(bean, member.id());
          }
        }
      }
      for (Invocation i : m.structure().invocations()) {
        if (i.receiver() instanceof Result r) {
          users.computeIfAbsent(r.invocation(), k -> new ArrayList<>()).add(i);
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

    /**
     * The config that applies to a module's code: its own, or if it has none (a library), that of
     * the one module of the repository that has config, if there is exactly one (as ADR 0035).
     */
    private ModuleConfig config(JavaAnalysis.Module m) {
      List<ModuleConfig> all = configs.computeIfAbsent(m.repo(), ConfigExtractor::read);
      for (ModuleConfig c : all) {
        if (c.directory().equals(m.directory())) {
          return c;
        }
      }
      return all.size() == 1 ? all.getFirst() : null;
    }

    // -------------------------------------------------------------------------------------------
    // Listeners

    private void listeners(TypeDecl type) {
      for (Map.Entry<String, String> handler : HANDLERS.entrySet()) {
        List<Annotation> onClass = first(type.annotations(), handler.getKey());
        if (onClass == null) {
          continue;
        }
        for (Member m : type.members()) {
          if (isMethod(m) && first(m.annotations(), handler.getValue()) != null) {
            listener(m, onClass);
          }
        }
      }
      for (Member m : type.members()) {
        if (!isMethod(m)) {
          continue;
        }
        boolean listens = false;
        for (Annotation a : m.annotations()) {
          for (String listener : LISTENERS.keySet()) {
            List<Annotation> path = find(a, listener, new HashSet<>());
            if (path != null) {
              listener(m, path);
              listens = true;
            }
          }
        }
        if (listens || hasHandler(type, m)) {
          sendTo(m, type);
        }
      }
    }

    private boolean hasHandler(TypeDecl type, Member m) {
      for (Map.Entry<String, String> handler : HANDLERS.entrySet()) {
        if (first(type.annotations(), handler.getKey()) != null
            && first(m.annotations(), handler.getValue()) != null) {
          return true;
        }
      }
      return false;
    }

    /** The facts of one listener annotation: the path from the one written to the listener. */
    private void listener(Member method, List<Annotation> path) {
      Annotation written = path.getFirst();
      String type = path.getLast().type();
      String broker = LISTENERS.get(type);
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
      if (queues.isEmpty() || exchanges.isEmpty()) {
        return;
      }
      String queue = firstOf(queues.getFirst(), "name", "value");
      String exchange = firstOf(exchanges.getFirst(), "name", "value");
      if (queue == null) {
        return;
      }
      listen(method, Text.annotated(queue), "queue", null, common, where);
      if (exchange == null) {
        return;
      }
      String exchangeType = firstOf(exchanges.getFirst(), "type");
      // @Exchange's type defaults to direct.
      Declared declared =
          new Declared(Text.annotated(exchange), exchangeType == null ? "direct" : exchangeType);
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
    private void sendTo(Member method, TypeDecl type) {
      List<Annotation> path = first(method.annotations(), SEND_TO);
      if (path == null) {
        path = first(type.annotations(), SEND_TO);
      }
      if (path == null) {
        return;
      }
      String broker = listenerBroker(method, type);
      if (broker == null) {
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
        sendToExchange(
            method.id(),
            Text.annotated(exchange),
            key.isEmpty() ? null : Text.annotated(key),
            attributes,
            where);
      }
    }

    private String listenerBroker(Member method, TypeDecl type) {
      for (Annotation a : method.annotations()) {
        for (String listener : LISTENERS.keySet()) {
          if (find(a, listener, new HashSet<>()) != null) {
            return LISTENERS.get(listener);
          }
        }
      }
      for (String listener : HANDLERS.keySet()) {
        if (first(type.annotations(), listener) != null) {
          return LISTENERS.get(listener);
        }
      }
      return null;
    }

    // -------------------------------------------------------------------------------------------
    // Sends

    private void send(Invocation i) {
      String type = i.type();
      if (type == null || i.arguments().isEmpty()) {
        return;
      }
      List<String> parameters = i.parameterTypes();
      Value first = i.arguments().getFirst();
      ObjectNode attributes = FactJson.newObject();
      attributes.put("template", type);
      attributes.put("method", i.method());
      boolean kafkaTemplate = KAFKA_TEMPLATES.contains(type) && i.method().equals("send");
      if (kafkaTemplate || (KAFKA_PRODUCERS.contains(type) && i.method().equals("send"))) {
        attributes.put("broker", KAFKA);
        attributes.put("destinationKind", "topic");
        Text topic = null;
        if (first instanceof Result r
            && PRODUCER_RECORD.equals(r.invocation().type())
            && r.invocation().method().equals(ModuleStructure.CONSTRUCTOR)
            && !r.invocation().arguments().isEmpty()) {
          topic = text(r.invocation().arguments().getFirst(), r.invocation().caller());
        } else if (kafkaTemplate
            && (parameters != null
                ? parameters.getFirst().equals(STRING)
                : i.arguments().size() >= 2)) {
          topic = text(first, i.caller());
        }
        if (topic != null) {
          emit(i.caller(), SEND, topic, Map.of(), attributes, i.where());
        }
        return;
      }
      if (JMS_TEMPLATES.contains(type) && JMS_METHODS.contains(i.method())) {
        boolean named =
            parameters != null ? parameters.getFirst().equals(STRING) : i.arguments().size() >= 2;
        Text destination = named ? text(first, i.caller()) : null;
        if (destination != null) {
          attributes.put("broker", JMS);
          attributes.put("destinationKind", "destination");
          emit(i.caller(), SEND, destination, Map.of(), attributes, i.where());
        }
        return;
      }
      if (RABBIT_TEMPLATES.contains(type) && RABBIT_METHODS.contains(i.method())) {
        int names;
        if (parameters != null) {
          names = 0;
          while (names < 2
              && names < parameters.size() - 1
              && parameters.get(names).equals(STRING)) {
            names++;
          }
        } else {
          names = Math.min(2, i.arguments().size() - 1);
        }
        attributes.put("broker", RABBITMQ);
        if (names == 1) {
          // The default exchange routes to the queue the routing key names.
          sendToExchange(
              i.caller(), Text.literal(""), text(first, i.caller()), attributes, i.where());
        } else if (names == 2) {
          Text key = text(i.arguments().get(1), i.caller());
          if (key == null && parameters == null) {
            // Without types, (routing key, message, post-processor) cannot be told apart.
            return;
          }
          sendToExchange(i.caller(), text(first, i.caller()), key, attributes, i.where());
        }
      }
    }

    /** A RabbitMQ send: to the exchange with the routing key, or to a queue by default. */
    private void sendToExchange(
        EntityId subject, Text exchange, Text key, ObjectNode common, Location where) {
      if (exchange == null) {
        return;
      }
      ObjectNode attributes = common.deepCopy();
      if (exchange.written().isEmpty() && !exchange.placeholders()) {
        if (key == null) {
          return;
        }
        attributes.put("destinationKind", "queue");
        emit(subject, SEND, key, Map.of(), attributes, where);
        return;
      }
      attributes.put("destinationKind", "exchange");
      Map<String, Text> named = new LinkedHashMap<>();
      if (key != null) {
        named.put("routingKey", key);
      }
      emit(subject, SEND, exchange, named, attributes, where);
    }

    // -------------------------------------------------------------------------------------------
    // Bindings

    private void binding(Invocation i) {
      if (BINDING_CLASS.equals(i.type())
          && i.method().equals(ModuleStructure.CONSTRUCTOR)
          && i.arguments().size() >= 4) {
        // new Binding(destination, destinationType, exchange, routingKey, arguments)
        if (i.arguments().get(1) instanceof Constant c && c.value().equals("EXCHANGE")) {
          return;
        }
        Text queue = text(i.arguments().getFirst(), i.caller());
        Text exchange = text(i.arguments().get(2), i.caller());
        if (queue != null && exchange != null) {
          bindingFact(
              i.caller(),
              queue,
              new Declared(exchange, null),
              text(i.arguments().get(3), i.caller()),
              i.where());
        }
        return;
      }
      if (!BINDING_BUILDER.equals(i.type())
          || !i.method().equals("bind")
          || i.arguments().size() != 1) {
        return;
      }
      Declared queue = declared(i.arguments().getFirst(), i.caller(), false, 0);
      if (queue == null) {
        return;
      }
      for (Invocation to : users.getOrDefault(i, List.of())) {
        if (!to.method().equals("to") || to.arguments().size() != 1) {
          continue;
        }
        Declared exchange = declared(to.arguments().getFirst(), to.caller(), true, 0);
        if (exchange == null) {
          continue;
        }
        if (exchange.exchangeType() == null && to.parameterTypes() != null) {
          String type = EXCHANGES.get(to.parameterTypes().getFirst());
          exchange = new Declared(exchange.name(), type);
        }
        boolean keyed = false;
        for (Invocation with : users.getOrDefault(to, List.of())) {
          Text key =
              switch (with.method()) {
                case "with" ->
                    with.arguments().size() == 1
                        ? text(with.arguments().getFirst(), with.caller())
                        : null;
                case "withQueueName" -> queue.name();
                default -> null;
              };
          if (key != null) {
            bindingFact(with.caller(), queue.name(), exchange, key, with.where());
            keyed = true;
          }
        }
        if (!keyed) {
          bindingFact(to.caller(), queue.name(), exchange, null, to.where());
        }
      }
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
    private Declared declared(Value v, EntityId context, boolean exchange, int depth) {
      if (depth > MAX_DEPTH) {
        return null;
      }
      switch (v) {
        case Result r -> {
          Declared d = declaration(r.invocation(), exchange, depth);
          if (d != null) {
            return d;
          }
          Invocation call = r.invocation();
          if (call.receiver() == null) {
            EntityId target = call.callee() != null ? call.callee() : ownMethod(call);
            if (target != null && members.containsKey(target)) {
              return declaredIn(target, exchange, depth + 1);
            }
          }
          return null;
        }
        case ParameterRead p -> {
          EntityId bean = beans.get(p.name());
          if (bean != null && !bean.equals(context)) {
            return declaredIn(bean, exchange, depth + 1);
          }
          return null;
        }
        case FieldRead f -> {
          List<Assignment> found = assignments.getOrDefault(f.field(), List.of());
          return found.size() == 1
              ? declared(found.getFirst().value(), found.getFirst().in(), exchange, depth + 1)
              : null;
        }
        default -> {
          return null;
        }
      }
    }

    /**
     * The method of the caller's own type an unqualified call that did not resolve (degraded mode)
     * names: the one with that name and number of parameters, or null.
     */
    private EntityId ownMethod(Invocation call) {
      String key = call.caller().key();
      int hash = key.indexOf('#');
      TypeDecl type = code.type(hash < 0 ? key : key.substring(0, hash));
      if (type == null) {
        return null;
      }
      EntityId found = null;
      for (Member m : type.members()) {
        String id = m.id().key();
        if (isMethod(m)
            && id.substring(id.indexOf('#') + 1, id.indexOf('(')).equals(call.method())
            && m.parameters().size() == call.arguments().size()) {
          if (found != null) {
            return null;
          }
          found = m.id();
        }
      }
      return found;
    }

    /** The one queue or exchange a method declares, or null if none or several. */
    private Declared declaredIn(EntityId method, boolean exchange, int depth) {
      Set<Declared> found = new LinkedHashSet<>();
      for (Invocation i : invocationsByCaller.getOrDefault(method, List.of())) {
        Declared d = declaration(i, exchange, depth);
        if (d != null) {
          found.add(d);
        }
      }
      return found.size() == 1 ? found.iterator().next() : null;
    }

    /** The queue or exchange an invocation creates, directly or through a builder chain. */
    private Declared declaration(Invocation i, boolean exchange, int depth) {
      if (depth > MAX_DEPTH) {
        return null;
      }
      String type = i.type();
      boolean creation = i.method().equals(ModuleStructure.CONSTRUCTOR);
      if (!exchange) {
        if (QUEUE.equals(type) && creation && !i.arguments().isEmpty()) {
          return named(i, 0, null);
        }
        if (QUEUE_BUILDER.equals(type)
            && (i.method().equals("durable") || i.method().equals("nonDurable"))
            && i.arguments().size() == 1) {
          return named(i, 0, null);
        }
      } else {
        if (creation && EXCHANGES.containsKey(type) && !i.arguments().isEmpty()) {
          return named(i, 0, EXCHANGES.get(type));
        }
        if ((AMQP + "CustomExchange").equals(type) && creation && i.arguments().size() >= 2) {
          String kind = i.arguments().get(1) instanceof Constant c ? c.value() : null;
          return named(i, 0, kind);
        }
        if (EXCHANGE_BUILDER.equals(type)
            && EXCHANGE_FACTORIES.containsKey(i.method())
            && i.arguments().size() == 1) {
          return named(i, 0, EXCHANGE_FACTORIES.get(i.method()));
        }
      }
      // A builder's further calls (durable(true), build()) on what a factory method made.
      if ((type == null || type.startsWith(AMQP)) && i.receiver() instanceof Result r) {
        return declaration(r.invocation(), exchange, depth + 1);
      }
      return null;
    }

    private Declared named(Invocation i, int argument, String exchangeType) {
      Text name = text(i.arguments().get(argument), i.caller());
      return name == null ? null : new Declared(name, exchangeType);
    }

    // -------------------------------------------------------------------------------------------
    // Following values

    /**
     * What a value in code is, as a text: a constant as it is, or followed one hop from a field or
     * parameter with {@code @Value}, or a field assigned once from a constant or such a parameter.
     * Null if it cannot be told.
     */
    private Text text(Value v, EntityId context) {
      return switch (v) {
        case Constant c -> Text.literal(c.value());
        case FieldRead f -> field(f.field());
        case ParameterRead p -> parameter(context, p.index());
        default -> null;
      };
    }

    private Text field(EntityId field) {
      Member m = members.get(field);
      if (m != null) {
        String value = valueAnnotation(m.annotations());
        if (value != null) {
          return Text.annotated(value).via(field);
        }
      }
      Set<Text> found = new LinkedHashSet<>();
      for (Assignment a : assignments.getOrDefault(field, List.of())) {
        Text t =
            switch (a.value()) {
              case Constant c -> Text.literal(c.value());
              case ParameterRead p -> parameter(a.in(), p.index());
              default -> null;
            };
        if (t == null) {
          return null;
        }
        found.add(t);
      }
      return found.size() == 1 ? found.iterator().next().via(field) : null;
    }

    /** A parameter with {@code @Value}, or the one parameter of a method with it (a setter). */
    private Text parameter(EntityId method, int index) {
      Member m = members.get(method);
      if (m == null || index >= m.parameters().size()) {
        return null;
      }
      String value = valueAnnotation(m.parameters().get(index).annotations());
      if (value == null && m.parameters().size() == 1) {
        value = valueAnnotation(m.annotations());
      }
      return value == null ? null : Text.annotated(value).via(method);
    }

    private String valueAnnotation(List<Annotation> annotations) {
      List<Annotation> path = first(annotations, VALUE_ANNOTATION);
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

    /**
     * The first annotation written that is, or carries through meta-annotations, the given type:
     * the path from the one written to the one found, or null.
     */
    private List<Annotation> first(List<Annotation> annotations, String type) {
      for (Annotation a : annotations) {
        List<Annotation> path = find(a, type, new HashSet<>());
        if (path != null) {
          return path;
        }
      }
      return null;
    }

    /** The path from an annotation to one of the given type among its meta-annotations, or null. */
    private List<Annotation> find(Annotation a, String type, Set<String> seen) {
      if (a.type().equals(type)) {
        return List.of(a);
      }
      if (!seen.add(a.type())) {
        return null;
      }
      for (Annotation meta : code.metaAnnotations(a.type())) {
        List<Annotation> path = find(meta, type, seen);
        if (path != null) {
          List<Annotation> full = new ArrayList<>();
          full.add(a);
          full.addAll(path);
          return full;
        }
      }
      return null;
    }

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

    // -------------------------------------------------------------------------------------------
    // Values and facts

    /** The views to resolve placeholders in: the default view (null), then each profile. */
    private List<String> views() {
      List<String> views = new ArrayList<>();
      views.add(null);
      if (config != null) {
        views.addAll(config.profiles());
      }
      return views;
    }

    private Resolved resolve(String profile, Text text) {
      if (!text.placeholders()) {
        return new Resolved(text.written(), text.written(), new TreeSet<>(), false);
      }
      Resolution r =
          config == null
              ? ConfigExtractor.resolveWithoutConfig(text.written())
              : config.resolve(profile, text.written());
      if (r.used().stream().anyMatch(ConfigExtractor::isSecret)) {
        return new Resolved(text.written(), text.written(), new TreeSet<>(), true);
      }
      return new Resolved(r.value(), text.written(), new TreeSet<>(r.unresolved()), false);
    }

    /** The attributes whose value as written is recorded too, when it differs. */
    private static final Set<String> WITH_WRITTEN = Set.of("routingKey", "exchange");

    /**
     * Records a fact for the default view, and one for each profile in which a value resolves
     * differently, with the values as resolved: the object, and the named attributes. A value a
     * secret was substituted into is never recorded resolved: all are recorded as written instead,
     * with {@code masked}.
     */
    private void emit(
        EntityId subject,
        String predicate,
        Text object,
        Map<String, Text> named,
        ObjectNode common,
        Location where) {
      List<Resolved> byDefault = null;
      for (String profile : views()) {
        List<Resolved> values = new ArrayList<>();
        values.add(resolve(profile, object));
        for (Text t : named.values()) {
          values.add(resolve(profile, t));
        }
        if (profile == null) {
          byDefault = values;
        } else if (values.equals(byDefault)) {
          continue;
        }
        boolean masked = values.stream().anyMatch(Resolved::masked);
        ObjectNode attributes = common.deepCopy();
        if (profile != null) {
          attributes.put("profile", profile);
        }
        Resolved first = values.getFirst();
        int i = 1;
        SortedSet<String> unresolved = new TreeSet<>(first.unresolved());
        for (String name : named.keySet()) {
          Resolved r = values.get(i++);
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
        SortedSet<String> followed = new TreeSet<>(object.followed());
        named.values().forEach(t -> followed.addAll(t.followed()));
        if (!followed.isEmpty() && !predicate.equals(LISTENER)) {
          ArrayNode via = attributes.putArray("followed");
          followed.forEach(via::add);
        }
        String value = masked ? first.written() : first.value();
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

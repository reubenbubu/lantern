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
import io.lantern.extract.config.ConfigValue;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns Kafka, JMS and RabbitMQ code into facts: the methods that listen on topics, queues and
 * destinations, the methods that send to them through a template or producer, and the bindings of
 * RabbitMQ queues to exchanges. See {@code docs/adr/0037-messaging-extractor.md}. Spring Cloud
 * Stream's bindings (functions, {@code StreamBridge} and the legacy annotations) give the same
 * facts, their destinations read from config; see {@code docs/adr/0040-spring-cloud-stream.md}.
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
  public static final String VERSION = "6";

  /** The source recorded on the vocabulary revision that adds this extractor's types. */
  public static final String VOCABULARY_SOURCE = "messaging-extractor";

  /** Attribute: the method handles messages, an entry point. */
  public static final String LISTENER = "message-listener";

  /** Attribute: the method sends messages. */
  public static final String SEND = "message-send";

  /** Attribute: the method binds a RabbitMQ queue to an exchange. */
  public static final String BINDING = "message-binding";

  /** Attribute: the method declares a queue, exchange or topic. */
  public static final String DESTINATION = "message-destination";

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
  private static final String QUALIFIER = "org.springframework.beans.factory.annotation.Qualifier";
  private static final String BEAN = "org.springframework.context.annotation.Bean";
  private static final String COMPONENT = "org.springframework.stereotype.Component";

  private static final String STREAM_BRIDGE =
      "org.springframework.cloud.stream.function.StreamBridge";
  private static final String STREAM_LISTENER =
      "org.springframework.cloud.stream.annotation.StreamListener";
  private static final String OUTPUT = "org.springframework.cloud.stream.annotation.Output";

  /** The legacy binding interfaces whose {@code output()} is the {@code output} binding. */
  private static final Set<String> LEGACY_OUTPUTS =
      Set.of(
          "org.springframework.cloud.stream.messaging.Source",
          "org.springframework.cloud.stream.messaging.Processor");

  /**
   * A kind of function Spring Cloud Stream binds: how many inputs and outputs it has, and the
   * method a class implementing it declares.
   */
  private record FunctionKind(int inputs, int outputs, String method) {}

  private static final Map<String, FunctionKind> FUNCTION_KINDS =
      Map.of(
          "java.util.function.Function", new FunctionKind(1, 1, "apply"),
          "java.util.function.Consumer", new FunctionKind(1, 0, "accept"),
          "java.util.function.Supplier", new FunctionKind(0, 1, "get"),
          "java.util.function.BiFunction", new FunctionKind(2, 1, "apply"),
          "java.util.function.BiConsumer", new FunctionKind(2, 0, "accept"));

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

  private static final String TOPIC_BUILDER = "org.springframework.kafka.config.TopicBuilder";
  private static final String NEW_TOPIC = "org.apache.kafka.clients.admin.NewTopic";

  /** Queue arguments naming where rejected and expired messages go, by their attribute. */
  private static final Map<String, String> DEAD_LETTER_ARGUMENTS =
      Map.of(
          "x-dead-letter-exchange", "deadLetterExchange",
          "x-dead-letter-routing-key", "deadLetterRoutingKey");

  private static final String STRING = "java.lang.String";

  private static final Pattern SPEL_LITERAL = Pattern.compile("'([^']*)'");

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
    return extract(code, snapshot, Set.of());
  }

  /**
   * As {@link #extract(JavaAnalysis, long)}, masking the values of config keys known to hold a
   * secret whatever they are called (ADR 0032, amendment for #338).
   *
   * @param secretKeys config keys known to hold a secret, such as {@link
   *     io.lantern.extract.config.ConfigExtractor#jwtSecretKeys}
   */
  public List<Fact> extract(JavaAnalysis code, long snapshot, Set<String> secretKeys) {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(secretKeys, "secretKeys");
    Run run = new Run(code, Validity.from(snapshot), secretKeys);
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

  /** The object of a RabbitMQ send whose exchange the code does not fix. */
  public static final String UNKNOWN_EXCHANGE = "{...}";

  /** One extraction: the facts so far, the code indexed, and the config of each repository. */
  private static final class Run {

    private final JavaAnalysis code;
    private final Validity validity;
    private final Map<String, Fact> facts = new LinkedHashMap<>();
    private final CodeConfig configs;
    private final Map<EntityId, Member> members = new HashMap<>();
    private final Map<EntityId, List<Assignment>> assignments = new HashMap<>();
    private final Map<EntityId, List<Invocation>> invocationsByCaller = new HashMap<>();

    private JavaAnalysis.Module module;
    private CodeConfig.View config;

    /** The module's {@code @Bean} methods by bean name. */
    private Map<String, EntityId> beans;

    /** The module's {@code @Bean} methods by the {@code @Qualifier} written on them. */
    private Map<String, EntityId> qualified;

    private StreamConfig stream;

    Run(JavaAnalysis code, Validity validity, Set<String> secretKeys) {
      this.configs = new CodeConfig(secretKeys);
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
      this.qualified = new HashMap<>();
      for (TypeDecl type : m.structure().types()) {
        for (Member member : type.members()) {
          String bean = beanName(member);
          if (bean != null) {
            beans.putIfAbsent(bean, member.id());
            String qualifier = qualifier(member.annotations());
            if (qualifier != null) {
              qualified.putIfAbsent(qualifier, member.id());
            }
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
        declaration(i);
      }
      this.stream = new StreamConfig(config, m.repo(), m.directory());
      functions(m);
      for (TypeDecl type : m.structure().types()) {
        streamListeners(type);
      }
      for (Invocation i : m.structure().invocations()) {
        streamSend(i);
      }
      for (ModuleStructure.Call c : m.structure().calls()) {
        outputCall(c);
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
        // An exchange the code does not fix (a getter on the message) is kept, marked unknown,
        // as a send with an unknown routing key is.
        ObjectNode attributes = common.deepCopy();
        attributes.put("destinationKind", "exchange");
        ArrayNode unknown = attributes.putArray("unknown");
        unknown.add("exchange");
        Map<String, Text> named = new LinkedHashMap<>();
        if (key != null) {
          named.put("routingKey", key);
        } else if (keyUnknown) {
          unknown.add("routingKey");
        }
        emit(subject, SEND, Text.literal(UNKNOWN_EXCHANGE), named, attributes, where);
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
          EntityId bean = parameter == null ? null : injected(parameter);
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

    /**
     * The queue, exchange or Kafka topic a chain creates, as a {@code message-destination} fact on
     * the method, at the chain: declared, whether or not anything binds, sends to or listens on it.
     * One created in another call's arguments (inside a {@code Declarables} or a binding) is a
     * chain of its own.
     */
    private void declaration(Invocation i) {
      Expression.Invoke call = i.expression();
      EntityId context = i.caller();
      Location where = i.where();
      for (boolean exchange : List.of(false, true)) {
        Declared d = declaration(call, context, exchange);
        if (d != null) {
          rabbitDestination(call, context, d, exchange, where);
          return;
        }
      }
      Text topic = kafkaTopic(call, context);
      if (topic != null) {
        ObjectNode attributes = FactJson.newObject();
        attributes.put("broker", KAFKA);
        attributes.put("destinationKind", "topic");
        emit(context, DESTINATION, topic, Map.of(), attributes, where);
      }
    }

    private void rabbitDestination(
        Expression.Invoke call, EntityId context, Declared d, boolean exchange, Location where) {
      ObjectNode attributes = FactJson.newObject();
      attributes.put("broker", RABBITMQ);
      attributes.put("destinationKind", exchange ? "exchange" : "queue");
      if (d.exchangeType() != null) {
        attributes.put("exchangeType", d.exchangeType());
      }
      Map<String, Text> named = new LinkedHashMap<>();
      List<Expression.Invoke> chain = call.chain();
      Expression.Invoke first = chain.getFirst();
      Boolean durable = null;
      if (first.method().equals(ModuleStructure.CONSTRUCTOR)) {
        // new Queue(name), new Queue(name, durable, exclusive, autoDelete, arguments), new
        // TopicExchange(name, durable, ...), new CustomExchange(name, type, durable, ...)
        boolean custom =
            first.receiver() instanceof Expression.TypeRef t
                && t.binaryName().equals(AMQP + "CustomExchange");
        int index = custom ? 2 : 1;
        List<Expression> arguments = first.arguments();
        durable =
            arguments.size() <= index
                ? Boolean.TRUE
                : arguments.get(index) instanceof Expression.Literal l
                        && (l.value().equals("true") || l.value().equals("false"))
                    ? Boolean.valueOf(l.value())
                    : null;
        if (!exchange && arguments.size() == 5) {
          deadLetters(arguments.get(4), context, named);
        }
      } else if (first.method().equals("durable") || first.method().equals("nonDurable")) {
        durable = first.method().equals("durable");
      } else if (exchange) {
        durable = true;
      }
      for (Expression.Invoke link : chain.subList(1, chain.size())) {
        switch (link.method()) {
          case "durable" -> {
            if (link.arguments().size() == 1
                && link.arguments().getFirst() instanceof Expression.Literal l) {
              durable = Boolean.valueOf(l.value());
            }
          }
          case "deadLetterExchange", "deadLetterRoutingKey" -> {
            Text t =
                link.arguments().size() == 1 ? text(link.arguments().getFirst(), context) : null;
            if (t != null) {
              named.put(link.method(), t);
            }
          }
          case "withArguments" -> {
            if (link.arguments().size() == 1) {
              deadLetters(link.arguments().getFirst(), context, named);
            }
          }
          case "withArgument" -> {
            if (link.arguments().size() == 2
                && link.arguments().getFirst() instanceof Expression.Literal key
                && DEAD_LETTER_ARGUMENTS.containsKey(key.value())) {
              Text t = text(link.arguments().get(1), context);
              if (t != null) {
                named.put(DEAD_LETTER_ARGUMENTS.get(key.value()), t);
              }
            }
          }
          default -> {}
        }
      }
      if (durable != null) {
        attributes.put("durable", durable);
      }
      emit(context, DESTINATION, d.name(), named, attributes, where);
    }

    /**
     * The dead-letter routing in a queue's arguments written as {@code Map.of(key, value, ...)}.
     */
    private void deadLetters(Expression map, EntityId context, Map<String, Text> named) {
      if (!(map instanceof Expression.Invoke call)
          || !call.method().equals("of")
          || !(call.receiver() instanceof Expression.TypeRef t)
          || !t.binaryName().equals("java.util.Map")) {
        return;
      }
      List<Expression> pairs = call.arguments();
      for (int k = 0; k + 1 < pairs.size(); k += 2) {
        if (pairs.get(k) instanceof Expression.Literal key
            && DEAD_LETTER_ARGUMENTS.containsKey(key.value())) {
          Text value = text(pairs.get(k + 1), context);
          if (value != null) {
            named.put(DEAD_LETTER_ARGUMENTS.get(key.value()), value);
          }
        }
      }
    }

    /** The Kafka topic a chain declares: {@code TopicBuilder.name(...)} or {@code new NewTopic}. */
    private Text kafkaTopic(Expression.Invoke call, EntityId context) {
      Expression.Invoke first = call.chain().getFirst();
      if (!(first.receiver() instanceof Expression.TypeRef t) || first.arguments().isEmpty()) {
        return null;
      }
      boolean declares =
          (t.binaryName().equals(TOPIC_BUILDER) && first.method().equals("name"))
              || (t.binaryName().equals(NEW_TOPIC)
                  && first.method().equals(ModuleStructure.CONSTRUCTOR));
      return declares ? text(first.arguments().getFirst(), context) : null;
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
    // Spring Cloud Stream

    /** A function bean Spring Cloud Stream may bind: the method that is it, and its kind. */
    private record Bound(EntityId method, Location where, FunctionKind kind) {}

    /** A binding of a function: the method it is on, whether it is an input, and its name. */
    private record FunctionBinding(EntityId method, Location where, boolean input, String name) {}

    /**
     * The functions' bindings: {@code @Bean} methods returning a {@code Function}, {@code Consumer}
     * or {@code Supplier} (or their two-input forms), and components implementing one, bound in
     * each view as the function definition says, or the only one when none is set.
     */
    private void functions(JavaAnalysis.Module m) {
      Map<String, Bound> functions = new LinkedHashMap<>();
      for (TypeDecl type : m.structure().types()) {
        for (Member member : type.members()) {
          FunctionKind kind =
              member.returns() == null ? null : FUNCTION_KINDS.get(member.returns());
          String bean = kind == null ? null : beanName(member);
          if (bean != null) {
            functions.putIfAbsent(bean, new Bound(member.id(), member.where(), kind));
          }
        }
        Bound component = component(type);
        if (component != null) {
          functions.putIfAbsent(componentName(type), component);
        }
      }
      if (functions.isEmpty()) {
        return;
      }
      Map<String, Set<FunctionBinding>> byView = new HashMap<>();
      Set<FunctionBinding> all = new LinkedHashSet<>();
      for (String profile : config.views()) {
        Set<FunctionBinding> bound = functionBindings(profile, functions);
        byView.put(profile, bound);
        all.addAll(bound);
      }
      for (FunctionBinding b : all) {
        Function<String, String> name =
            profile -> byView.get(profile).contains(b) ? b.name() : null;
        if (b.input()) {
          streamInput(b.method(), b.where(), name, FactJson.newObject());
        } else {
          streamOutput(
              b.method(),
              b.where(),
              profile -> name.apply(profile) == null ? null : Text.literal(b.name()),
              null,
              FactJson.newObject());
        }
      }
    }

    private Set<FunctionBinding> functionBindings(String profile, Map<String, Bound> functions) {
      Set<FunctionBinding> bindings = new LinkedHashSet<>();
      if (!stream.inUse(profile)) {
        return bindings;
      }
      List<List<String>> definition = stream.definition(profile);
      if (definition == null) {
        // Without a definition, Spring Cloud Stream binds the function if there is only one.
        definition = functions.size() == 1 ? List.of(List.copyOf(functions.keySet())) : List.of();
      }
      for (List<String> composed : definition) {
        List<Bound> parts = new ArrayList<>();
        for (String function : composed) {
          parts.add(functions.get(function));
        }
        if (parts.contains(null)) {
          continue;
        }
        // A composition (a|b) is one binding named after all of them: a's input, b's output.
        String name = String.join("", composed);
        addBindings(bindings, profile, parts.getFirst(), name, true);
        addBindings(bindings, profile, parts.getLast(), name, false);
      }
      return bindings;
    }

    private void addBindings(
        Set<FunctionBinding> to, String profile, Bound function, String name, boolean input) {
      int count = input ? function.kind().inputs() : function.kind().outputs();
      if (count == 0) {
        return;
      }
      // More inputs or outputs than the type tells (tuples of reactive streams) show in config.
      SortedSet<Integer> indexes = stream.indexes(profile, name, input);
      for (int i = 0; i < count; i++) {
        indexes.add(i);
      }
      for (int i : indexes) {
        String binding = stream.alias(profile, name + (input ? "-in-" : "-out-") + i);
        to.add(new FunctionBinding(function.method(), function.where(), input, binding));
      }
    }

    /** A component class implementing a function: its function method, or null if it is none. */
    private Bound component(TypeDecl type) {
      if (!type.kind().equals(ModuleStructure.CLASS)
          || code.annotationPath(type.annotations(), COMPONENT) == null) {
        return null;
      }
      for (ModuleStructure.Supertype supertype : type.supertypes()) {
        FunctionKind kind = FUNCTION_KINDS.get(supertype.binaryName());
        if (kind == null) {
          continue;
        }
        for (Member m : type.members()) {
          if (isMethod(m)
              && methodName(m.id()).equals(kind.method())
              && parameterCount(m.id()) == kind.inputs()) {
            return new Bound(m.id(), m.where(), kind);
          }
        }
      }
      return null;
    }

    /** A component's bean name: written on its stereotype, else its simple name decapitalized. */
    private String componentName(TypeDecl type) {
      List<String> written =
          code.annotationPath(type.annotations(), COMPONENT).getFirst().values("value");
      if (!written.isEmpty() && !written.getFirst().isBlank()) {
        return written.getFirst();
      }
      String binary = type.binaryName();
      String simple = binary.substring(binary.lastIndexOf('.') + 1).replace('$', '.');
      if (simple.length() > 1
          && Character.isUpperCase(simple.charAt(0))
          && Character.isUpperCase(simple.charAt(1))) {
        return simple;
      }
      return Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
    }

    /** The legacy {@code @StreamListener}s of a type, and their {@code @SendTo} replies. */
    private void streamListeners(TypeDecl type) {
      for (Member m : type.members()) {
        List<Annotation> path =
            isMethod(m) ? code.annotationPath(m.annotations(), STREAM_LISTENER) : null;
        if (path == null) {
          continue;
        }
        Annotation written = path.getFirst();
        Location where = written.where() != null ? written.where() : m.where();
        List<String> names = element(path, "value");
        if (names.isEmpty()) {
          names = element(path, "target");
        }
        ObjectNode common = FactJson.newObject();
        common.put("annotation", written.type());
        for (String name : names) {
          if (!name.isBlank()) {
            streamInput(m.id(), where, profile -> name, common);
          }
        }
        List<Annotation> sendTo = code.annotationPath(m.annotations(), SEND_TO);
        if (sendTo == null) {
          sendTo = code.annotationPath(type.annotations(), SEND_TO);
        }
        if (sendTo == null) {
          continue;
        }
        Annotation to = sendTo.getFirst();
        ObjectNode attributes = FactJson.newObject();
        attributes.put("template", to.type());
        for (String name : element(sendTo, "value")) {
          if (!name.isBlank()) {
            streamOutput(
                m.id(),
                to.where() != null ? to.where() : m.where(),
                profile -> Text.literal(name),
                null,
                attributes);
          }
        }
      }
    }

    /**
     * A send through {@code StreamBridge.send(bindingOrDestination, [binder,] data, ...)}, or to
     * the legacy {@code output} binding of {@code Source} or {@code Processor}.
     */
    private void streamSend(Invocation i) {
      Expression.Invoke call = i.expression().chain().getFirst();
      if (call.receiver() == null) {
        return;
      }
      ObjectNode attributes = FactJson.newObject();
      attributes.put("template", i.rootType());
      attributes.put("method", call.method());
      if (LEGACY_OUTPUTS.contains(i.rootType())
          && call.method().equals("output")
          && call.arguments().isEmpty()) {
        streamOutput(i.caller(), i.where(), profile -> Text.literal("output"), null, attributes);
        return;
      }
      List<String> parameters = call.parameterTypes();
      boolean typed = !parameters.isEmpty();
      if (!STREAM_BRIDGE.equals(i.rootType())
          || !call.method().equals("send")
          || call.arguments().size() < 2
          || (typed && !parameters.getFirst().equals(STRING))) {
        return;
      }
      Text binding = text(call.arguments().getFirst(), i.caller());
      if (binding == null) {
        return;
      }
      // send(binding, binderName, data[, contentType]); parsed, a second argument that names no
      // binder is the data, and is passed over.
      boolean named =
          typed
              ? parameters.size() >= 3 && parameters.get(1).equals(STRING)
              : call.arguments().size() >= 3;
      Text binder = named ? text(call.arguments().get(1), i.caller()) : null;
      streamOutput(
          i.caller(),
          i.where(),
          profile -> binding,
          binder == null ? null : profile -> name(profile, binder),
          attributes);
    }

    /** A call to a legacy binding interface's {@code @Output} method: a send to that binding. */
    private void outputCall(ModuleStructure.Call c) {
      Member callee = c.callee() == null ? null : members.get(c.callee());
      List<Annotation> path =
          callee == null ? null : code.annotationPath(callee.annotations(), OUTPUT);
      if (path == null) {
        return;
      }
      List<String> values = element(path, "value");
      String name =
          values.isEmpty() || values.getFirst().isBlank()
              ? methodName(callee.id())
              : values.getFirst();
      String key = callee.id().key();
      ObjectNode attributes = FactJson.newObject();
      attributes.put("template", key.substring(0, key.indexOf('#')));
      attributes.put("method", methodName(callee.id()));
      streamOutput(c.caller(), c.where(), profile -> Text.literal(name), null, attributes);
    }

    /**
     * A binding's consumer: a listener on its destination, a Kafka topic or, for RabbitMQ, the
     * queue the binder declares for its group, and that queue's binding to the destination's
     * exchange.
     *
     * @param binding the binding's name in each view, or null in a view it is not bound in
     */
    private void streamInput(
        EntityId subject, Location where, Function<String, String> binding, ObjectNode common) {
      emitSlots(subject, LISTENER, where, p -> inputs(p, binding.apply(p), common, false));
      emitSlots(subject, BINDING, where, p -> inputs(p, binding.apply(p), common, true));
    }

    private Map<String, Shape> inputs(
        String profile, String binding, ObjectNode common, boolean bindings) {
      Map<String, Shape> shapes = new LinkedHashMap<>();
      String broker = binding == null ? null : stream.broker(profile, binding, null);
      if (broker == null) {
        return shapes;
      }
      ConfigValue groupValue = stream.property(profile, binding, "group", true);
      Text group =
          groupValue == null || groupValue.written().isBlank()
              ? null
              : Text.annotated(groupValue.written());
      List<Text> destinations = destinations(profile, binding);
      if (destinations == null) {
        destinations = List.of(Text.literal(binding));
      }
      for (int k = 0; k < destinations.size(); k++) {
        Text destination = destinations.get(k);
        ObjectNode attributes = common.deepCopy();
        attributes.put("broker", broker);
        attributes.put("binding", binding);
        Map<String, Text> named = new LinkedHashMap<>();
        String slot = Integer.toString(k);
        if (broker.equals(KAFKA)) {
          if (!bindings) {
            boolean pattern =
                isTrue(
                    stream.binderProperty(
                        profile, "kafka", binding, "consumer", "destination-is-pattern"));
            attributes.put("destinationKind", pattern ? "topic-pattern" : "topic");
            if (group != null) {
              named.put("group", group);
            }
            shapes.put(slot, new Shape(destination, named, attributes));
          }
          continue;
        }
        // RabbitMQ: the destination is a topic exchange, consumed through a queue named after it
        // and the group, or the group alone; without a group the queue is anonymous, its name
        // the destination's with a random suffix the code cannot tell.
        boolean groupOnly =
            isTrue(
                stream.binderProperty(
                    profile, "rabbit", binding, "consumer", "queue-name-group-only"));
        Text queue =
            group == null
                ? Text.concat(List.of(destination, Text.literal(".anonymous")))
                : groupOnly ? group : Text.concat(List.of(destination, Text.literal("."), group));
        if (group == null) {
          attributes.put("anonymous", true);
        }
        if (!bindings) {
          attributes.put("destinationKind", "queue");
          shapes.put(slot, new Shape(queue, named, attributes));
          continue;
        }
        String type =
            stream.plain(
                stream.binderProperty(profile, "rabbit", binding, "consumer", "exchange-type"));
        String exchangeType = type == null || type.isBlank() ? "topic" : type.trim();
        attributes.put("exchangeType", exchangeType);
        named.put("exchange", destination);
        if (!exchangeType.equals("fanout") && !exchangeType.equals("headers")) {
          ConfigValue key =
              stream.binderProperty(profile, "rabbit", binding, "consumer", "binding-routing-key");
          named.put("routingKey", key == null ? Text.literal("#") : Text.annotated(key.written()));
        }
        shapes.put(slot, new Shape(queue, named, attributes));
      }
      return shapes;
    }

    /**
     * A binding's producer: a send to its destination, a Kafka topic or a RabbitMQ exchange with
     * the routing key the binder uses (the destination's name unless configured).
     *
     * @param binding the binding's name, or a destination's, in each view, or null in a view it is
     *     not bound in
     * @param binder the binder named in code in each view, or null if none is
     */
    private void streamOutput(
        EntityId subject,
        Location where,
        Function<String, Text> binding,
        Function<String, String> binder,
        ObjectNode common) {
      emitSlots(
          subject,
          SEND,
          where,
          p -> output(p, binding.apply(p), binder == null ? null : binder.apply(p), common));
    }

    private Map<String, Shape> output(
        String profile, Text binding, String binder, ObjectNode common) {
      if (binding == null) {
        return Map.of();
      }
      String name = name(profile, binding);
      String broker = stream.broker(profile, name, binder);
      if (broker == null) {
        return Map.of();
      }
      // A name no binding is configured for is the destination itself, as StreamBridge sends.
      List<Text> configured = name == null ? null : destinations(profile, name);
      Text destination = binding;
      if (configured != null) {
        Text d = configured.getFirst();
        SortedSet<String> followed = new TreeSet<>(d.followed());
        followed.addAll(binding.followed());
        destination = new Text(d.written(), d.placeholders(), followed);
      }
      ObjectNode attributes = common.deepCopy();
      attributes.put("broker", broker);
      if (name != null) {
        attributes.put("binding", name);
      }
      Map<String, Text> named = new LinkedHashMap<>();
      if (broker.equals(KAFKA)) {
        attributes.put("destinationKind", "topic");
        return Map.of("0", new Shape(destination, named, attributes));
      }
      attributes.put("destinationKind", "exchange");
      ConfigValue key =
          name == null
              ? null
              : stream.binderProperty(profile, "rabbit", name, "producer", "routing-key");
      ConfigValue expression =
          name == null
              ? null
              : stream.binderProperty(
                  profile, "rabbit", name, "producer", "routing-key-expression");
      if (key != null) {
        named.put("routingKey", Text.annotated(key.written()));
      } else if (expression != null) {
        // A SpEL string literal is a fixed key; any other expression is decided per message.
        String spel = stream.plain(expression);
        Matcher literal = spel == null ? null : SPEL_LITERAL.matcher(spel.trim());
        if (literal != null && literal.matches()) {
          named.put("routingKey", Text.literal(literal.group(1)));
        } else {
          attributes.putArray("unknown").add("routingKey");
        }
      } else {
        named.put("routingKey", destination);
      }
      return Map.of("0", new Shape(destination, named, attributes));
    }

    /**
     * A binding's destinations as configured: one, or for a consumer several separated by commas;
     * null if no destination is configured, so the binding's name is the destination.
     */
    private List<Text> destinations(String profile, String binding) {
      ConfigValue v = stream.property(profile, binding, "destination", false);
      if (v == null || v.written().isBlank()) {
        return null;
      }
      Text whole = Text.annotated(v.written());
      CodeValue r = resolve(profile, whole);
      if (r.masked() || !r.unresolved().isEmpty() || !r.value().contains(",")) {
        return List.of(whole);
      }
      List<Text> parts = new ArrayList<>();
      for (String part : r.value().split(",")) {
        if (!part.isBlank()) {
          parts.add(Text.literal(part.trim()));
        }
      }
      return parts.isEmpty() ? List.of(whole) : parts;
    }

    /** A text's value in a view when it can be told and recorded, else null. */
    private String name(String profile, Text text) {
      CodeValue r = resolve(profile, text);
      return r.masked() || !r.unresolved().isEmpty() || r.value().isBlank() ? null : r.value();
    }

    private boolean isTrue(ConfigValue v) {
      String value = stream.plain(v);
      return value != null && value.trim().equalsIgnoreCase("true");
    }

    /**
     * Records facts whose number may differ by view, such as a binding with several destinations:
     * each view's shapes by slot, the facts of a slot recorded as {@link #emitViews} does.
     */
    private void emitSlots(
        EntityId subject,
        String predicate,
        Location where,
        Function<String, Map<String, Shape>> byView) {
      Map<String, Map<String, Shape>> shapes = new HashMap<>();
      Set<String> slots = new LinkedHashSet<>();
      for (String profile : config.views()) {
        Map<String, Shape> s = byView.apply(profile);
        shapes.put(profile, s);
        slots.addAll(s.keySet());
      }
      for (String slot : slots) {
        emitViews(subject, predicate, where, profile -> shapes.get(profile).get(slot));
      }
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

    /**
     * The {@code @Bean} method Spring injects for a parameter of a {@code @Bean} method: the one
     * its {@code @Qualifier} names (a bean's name, or the qualifier written on a bean), else the
     * one named as the parameter.
     */
    private EntityId injected(ModuleStructure.Parameter parameter) {
      String qualifier = qualifier(parameter.annotations());
      if (qualifier != null) {
        EntityId bean = beans.get(qualifier);
        return bean != null ? bean : qualified.get(qualifier);
      }
      return beans.get(parameter.name());
    }

    private String qualifier(List<Annotation> annotations) {
      List<Annotation> path = code.annotationPath(annotations, QUALIFIER);
      if (path == null) {
        return null;
      }
      List<String> values = path.getLast().values("value");
      return values.isEmpty() ? null : values.getFirst();
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

    private static String methodName(EntityId method) {
      String key = method.key();
      return key.substring(key.indexOf('#') + 1, key.indexOf('('));
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
      return config.value(config.resolve(profile, text.written()), text.written());
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
          Evidence.of(
              repo.repo().name(),
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

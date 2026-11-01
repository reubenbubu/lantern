package io.lantern.extract.messaging;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.vocabulary.VocabularyType;
import io.lantern.extract.CacheUnit;
import io.lantern.extract.ClaimSink;
import io.lantern.extract.EntityKeys;
import io.lantern.extract.ExtractionContext;
import io.lantern.extract.ExtractionUnit;
import io.lantern.extract.Extractor;
import io.lantern.extract.ExtractorInfo;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.config.CodeConfig.CodeValue;
import io.lantern.extract.config.ConfigValue;
import io.lantern.extract.config.ValueLookup;
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
import io.lantern.extract.java.Values;
import java.io.UncheckedIOException;
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
public final class MessagingExtractor implements Extractor {

  /** The extractor's name, recorded as the origin of its facts. */
  public static final String NAME = "messaging";

  /** The extractor's version; change it whenever the facts it produces change. */
  public static final String VERSION = "9";

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

  /** Entity: a destination of a broker, as code names it. */
  public static final String DESTINATION_ENTITY = "destination";

  /** Relationship: the method sends to the destination (ADR 0094, #559). */
  public static final String SENDS_TO = "SENDS_TO";

  /** Relationship: the method listens on the destination. */
  public static final String LISTENS_ON = "LISTENS_ON";

  /** Relationship: the method binds the destination, a RabbitMQ queue, to an exchange. */
  public static final String BINDS = "BINDS";

  /** Relationship: the method, or a type for a field initializer, declares the destination. */
  public static final String DECLARES = "DECLARES";

  /** Attribute: the method is a function bean Spring Cloud Stream may bind. */
  public static final String STREAM_FUNCTION = "stream-function";

  /**
   * Attribute: the type of the messages a method sends or receives, or (from the HTTP client
   * extractor) of a request body or response a client method declares (#520).
   */
  public static final String PAYLOAD_TYPE = "payload-type";

  /** Listener parameters that are not the message: headers, channels, acknowledgements. */
  private static final Set<String> NOT_PAYLOADS =
      Set.of(
          "com.rabbitmq.client.Channel",
          "org.springframework.amqp.core.Message",
          "org.springframework.kafka.support.Acknowledgment",
          "org.apache.kafka.clients.consumer.Consumer",
          "org.springframework.messaging.MessageHeaders",
          "jakarta.jms.Session",
          "javax.jms.Session",
          "jakarta.jms.Message",
          "javax.jms.Message");

  private static final String HANDLER_ANNOTATIONS =
      "org.springframework.messaging.handler.annotation.";

  /** The broker of a destination that is a Spring Cloud Stream binding, as code names it. */
  public static final String STREAM = "stream";

  /** The kind of a destination that is a Spring Cloud Stream binding. */
  public static final String BINDING_KIND = "binding";

  /**
   * The brokers of the destinations this extractor records, a Spring Cloud Stream binding's too.
   */
  private static final Set<String> BROKERS = Set.of("kafka", "jms", "rabbitmq", STREAM);

  /** The relationship recorded as written for each fact linking records, and back. */
  private static final Map<String, String> RELATIONS =
      Map.of(SEND, SENDS_TO, LISTENER, LISTENS_ON, BINDING, BINDS, DESTINATION, DECLARES);

  private static final Map<String, String> PREDICATES =
      Map.of(SENDS_TO, SEND, LISTENS_ON, LISTENER, BINDS, BINDING, DECLARES, DESTINATION);

  /** The attribute of a written fact holding the destination's name as written. */
  private static final String NAME_VALUE = "name";

  /** The values besides the name a written fact holds as written, which config may resolve. */
  private static final List<String> VALUES =
      List.of(
          "routingKey",
          "group",
          "exchange",
          "deadLetterExchange",
          "deadLetterRoutingKey",
          "binder");

  private static final String FOLLOWED_SUFFIX = "Followed";

  /** The attributes of a written fact that only say where its values come from. */
  private static final List<String> WRITTEN_ONLY =
      List.of(
          NAME_VALUE,
          "configured",
          "followed",
          "configModule",
          "configRepo",
          "streamOnClasspath",
          "binders");

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

  private static final Set<String> JMS_POST_PROCESSORS =
      Set.of(
          "org.springframework.jms.core.MessagePostProcessor",
          "org.springframework.messaging.core.MessagePostProcessor");

  private static final Pattern SPEL_LITERAL = Pattern.compile("'([^']*)'");

  /** How many steps following a declaration may take, through bean methods and fields. */
  private static final int MAX_DEPTH = 4;

  /** The vocabulary types this extractor's facts use besides the built-in and Spring ones. */
  public static List<VocabularyType> vocabulary() {
    return ExtractorInfo.load(RESOURCE);
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
   * Extracts the facts of a snapshot's code already read by {@link JavaExtractor#analyze}: each
   * send, listener, binding and declaration as written, a relationship to its destination, and the
   * functions Spring Cloud Stream may bind. {@link #resolve} gives the facts their values give in
   * each view.
   *
   * @param code the code of the snapshot's repositories
   * @param snapshot the snapshot number the facts hold from
   * @return the facts, without duplicates, ordered by subject, predicate, object, attributes and ID
   * @throws UncheckedIOException if a POM cannot be read
   */
  public List<Fact> extract(JavaAnalysis code, long snapshot) {
    Objects.requireNonNull(code, "code");
    Run run = new Run(code, Validity.from(snapshot));
    for (JavaAnalysis.Module module : code.modules()) {
      run.module(module);
    }
    return sorted(run.facts.values());
  }

  private static List<Fact> sorted(java.util.Collection<Fact> facts) {
    return facts.stream()
        .sorted(
            Comparator.comparing((Fact f) -> f.subject().toString())
                .thenComparing(Fact::predicate)
                .thenComparing(f -> f.object().value())
                .thenComparing(f -> f.attributes().toString())
                .thenComparing(Fact::id))
        .toList();
  }

  /**
   * The facts linking gives from the values this extractor keeps as written (ADR 0094, #559): each
   * written send, listener, binding and declaration resolved in each view of its module's config,
   * and the bindings of each module's Spring Cloud Stream functions, as this extractor recorded
   * them before: {@value #SEND}, {@value #LISTENER}, {@value #BINDING} and {@value #DESTINATION}
   * facts, the default view's and each differing profile's. A value a secret was substituted into
   * is recorded as written, with {@code masked}.
   *
   * @param facts the snapshot's facts; only the written ones of this extractor are read
   * @param config the config of a module, by its entity ID and its repository's name
   * @param origin the origin of the facts
   * @param snapshot the snapshot number the facts hold from
   * @return the facts, ordered as {@link #extract(JavaAnalysis, long)} orders its own
   */
  public static List<Fact> resolve(
      List<Fact> facts,
      java.util.function.BiFunction<EntityId, String, ValueLookup> config,
      Origin origin,
      long snapshot) {
    Objects.requireNonNull(facts, "facts");
    Objects.requireNonNull(config, "config");
    Validity validity = Validity.from(snapshot);
    Map<String, Fact> out = new LinkedHashMap<>();
    Map<String, Emitter> emitters = new LinkedHashMap<>();
    Map<String, Map<String, Emitter.Bound>> functions = new LinkedHashMap<>();
    for (Fact f : facts) {
      boolean function = f.predicate().equals(STREAM_FUNCTION);
      boolean written =
          PREDICATES.containsKey(f.predicate())
              && f.object().kind() == FactObject.Kind.ENTITY
              && f.object().entityId().type().equals(DESTINATION_ENTITY)
              && BROKERS.contains(f.attributes().path("broker").stringValue(null));
      if (!function && !written) {
        continue;
      }
      JsonNode a = f.attributes();
      String module = a.path("configModule").stringValue(null);
      String repo = a.path("configRepo").stringValue(null);
      if (module == null || repo == null) {
        continue;
      }
      String key = module + "\0" + repo;
      Emitter emitter =
          emitters.computeIfAbsent(
              key,
              k -> new Emitter(config.apply(EntityId.parse(module), repo), origin, validity, out));
      if (function || STREAM.equals(a.path("broker").stringValue(null))) {
        Set<String> binders = new TreeSet<>();
        a.path("binders").forEach(b -> binders.add(b.stringValue()));
        emitter.stream(
            a.path("streamOnClasspath").asBoolean(false), binders, module + " (" + repo + ")");
      }
      Source src = new Source(f.evidence(), f.confidence());
      if (function) {
        FunctionKind kind =
            new FunctionKind(a.path("inputs").asInt(0), a.path("outputs").asInt(0), null);
        functions
            .computeIfAbsent(key, k -> new LinkedHashMap<>())
            .putIfAbsent(f.object().value(), new Emitter.Bound(f.subject(), src, kind));
      } else {
        emitter.written(f.subject(), f.predicate(), a, src);
      }
    }
    functions.forEach((key, bound) -> emitters.get(key).functions(bound));
    return sorted(out.values());
  }

  /** Whether config may resolve a text: it is read with placeholders and has one. */
  private static boolean configured(Text t) {
    return t.placeholders() && t.written().contains("${");
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
    private final Map<EntityId, List<Invocation>> invocationsByCaller = new HashMap<>();

    private JavaAnalysis.Module module;

    /** Whether Spring Cloud Stream is on the module's classpath, read when first needed. */
    private Boolean streamOnClasspath;

    /** The brokers of the Spring Cloud Stream binders on the module's classpath. */
    private Set<String> binders;

    /** The module's {@code @Bean} methods by bean name. */
    private Map<String, EntityId> beans;

    /** The module's {@code @Bean} methods by the {@code @Qualifier} written on them. */
    private Map<String, EntityId> qualified;

    Run(JavaAnalysis code, Validity validity) {
      this.code = code;
      this.validity = validity;
      for (JavaAnalysis.Module m : code.modules()) {
        for (Invocation i : m.structure().invocations()) {
          invocationsByCaller.computeIfAbsent(i.caller(), k -> new ArrayList<>()).add(i);
        }
      }
    }

    void module(JavaAnalysis.Module m) {
      this.module = m;
      this.streamOnClasspath = null;
      this.binders = null;
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
      listenerPayload(method, broker, where);
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
        payload(caller, "send", i, arguments.size() - 1, KAFKA);
        return;
      }
      if (JMS_TEMPLATES.contains(type) && JMS_METHODS.contains(call.method())) {
        // (destination name or Destination, payload[, post-processor]), or (payload[,
        // post-processor]): the overload says which, by its first parameter's type (#612).
        boolean addressed = jmsAddressed(typed ? parameters : List.of(), i, arguments.size());
        boolean named = typed ? parameters.getFirst().equals(STRING) : addressed;
        Text destination = named ? text(first, caller) : null;
        if (destination != null) {
          attributes.put("broker", JMS);
          attributes.put("destinationKind", "destination");
          emit(caller, SEND, destination, Map.of(), attributes, i.where());
        }
        if (call.method().startsWith("convertAndSend")) {
          payload(caller, "send", i, addressed ? 1 : 0, JMS);
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
        payload(caller, "send", i, names, RABBITMQ);
      }
    }

    /**
     * Whether a JMS template call names its destination first (#612). With the overload: its first
     * parameter is a String or a Destination; or, for JmsMessagingTemplate's generic destination,
     * which erases to Object, a first parameter of Object followed by another that is not a
     * post-processor or a header map ({@code convertAndSend(payload, postProcessor)} sends its
     * first argument). Without the overload: three arguments or more, or two of which the second is
     * not typed as a post-processor or a map.
     */
    private static boolean jmsAddressed(List<String> parameters, Invocation i, int arguments) {
      if (!parameters.isEmpty()) {
        // A String, or jakarta.jms.Destination (javax.jms.Destination before Jakarta EE).
        String first = parameters.getFirst();
        if (first.equals(STRING)
            || first.endsWith(".Destination")
            || first.endsWith("$Destination")) {
          return true;
        }
        return first.equals("java.lang.Object")
            && parameters.size() >= 2
            && !payloadCompanion(parameters.get(1));
      }
      if (arguments != 2) {
        return arguments > 2;
      }
      List<ModuleStructure.TypeUse> types = i.argumentTypes(0);
      ModuleStructure.TypeUse second = types.size() == 2 ? types.get(1) : null;
      return second == null || !payloadCompanion(second.type());
    }

    /** Whether a parameter that follows a payload is a post-processor or a header map. */
    private static boolean payloadCompanion(String type) {
      return JMS_POST_PROCESSORS.contains(type) || type.equals("java.util.Map");
    }

    /**
     * Records the type of the message a send passes (#520), the argument at the index of the
     * chain's first call, as the compiler typed it.
     */
    private void payload(EntityId caller, String role, Invocation i, int index, String broker) {
      List<ModuleStructure.TypeUse> types = i.argumentTypes(0);
      if (index >= 0 && index < types.size()) {
        payload(caller, role, types.get(index), broker, i.where());
      }
    }

    /**
     * Records the type of the message a listener method receives (#520): its {@code @Payload}
     * parameter, else its first that is not a header, a channel or an acknowledgement.
     */
    private void listenerPayload(Member method, String broker, Location where) {
      List<String> erased = parameterTypes(method.id());
      List<ModuleStructure.Parameter> parameters = method.parameters();
      int chosen = -1;
      for (int n = 0; n < parameters.size() && n < erased.size(); n++) {
        List<Annotation> annotations = parameters.get(n).annotations();
        if (code.annotationPath(annotations, HANDLER_ANNOTATIONS + "Payload") != null) {
          chosen = n;
          break;
        }
        if (chosen < 0
            && !NOT_PAYLOADS.contains(erased.get(n))
            && code.annotationPath(annotations, HANDLER_ANNOTATIONS + "Header") == null
            && code.annotationPath(annotations, HANDLER_ANNOTATIONS + "Headers") == null) {
          chosen = n;
        }
      }
      if (chosen >= 0) {
        ModuleStructure.TypeUse type = parameters.get(chosen).type();
        payload(
            method.id(),
            "receive",
            type != null ? type : new ModuleStructure.TypeUse(erased.get(chosen), List.of()),
            broker,
            where);
      }
    }

    private void payload(
        EntityId subject,
        String role,
        ModuleStructure.TypeUse type,
        String broker,
        Location where) {
      if (!code.declaresAny(type)) {
        // A String or a library's type says nothing a lead can check; a type the code declares,
        // such as a shared library's message class, does.
        return;
      }
      ObjectNode attributes = FactJson.newObject();
      attributes.put("role", role);
      if (broker != null) {
        attributes.put("broker", broker);
      }
      if (!type.arguments().isEmpty()) {
        ArrayNode arguments = attributes.putArray("typeArguments");
        type.arguments().forEach(arguments::add);
      }
      fact(subject, PAYLOAD_TYPE, FactObject.string(type.type()), attributes, where);
    }

    /** The erased parameter types in a method's ID. */
    private static List<String> parameterTypes(EntityId method) {
      String key = method.key();
      String inside = key.substring(key.indexOf('(') + 1, key.lastIndexOf(')'));
      return inside.isEmpty() ? List.of() : List.of(inside.split(","));
    }

    /**
     * A RabbitMQ send: to the exchange with the routing key, or, where the exchange is the default
     * one ({@code ""}), to the queue the routing key names, as the broker routes it. An exchange
     * config gives is recorded as written; linking tells in each view whether it is the default
     * one.
     */
    private void rabbitSend(
        EntityId subject,
        Text exchange,
        Text key,
        boolean keyUnknown,
        ObjectNode common,
        Location where) {
      ObjectNode attributes = common.deepCopy();
      Map<String, Text> named = new LinkedHashMap<>();
      if (exchange == null) {
        // An exchange the code does not fix (a getter on the message) is kept, marked unknown,
        // as a send with an unknown routing key is.
        attributes.put("destinationKind", "exchange");
        ArrayNode unknown = attributes.putArray("unknown");
        unknown.add("exchange");
        if (key != null) {
          named.put("routingKey", key);
        } else if (keyUnknown) {
          unknown.add("routingKey");
        }
        emit(subject, SEND, Text.literal(UNKNOWN_EXCHANGE), named, attributes, where);
        return;
      }
      if (!configured(exchange) && exchange.written().isEmpty()) {
        if (key != null) {
          attributes.put("destinationKind", "queue");
          emit(subject, SEND, key, named, attributes, where);
        }
        return;
      }
      attributes.put("destinationKind", "exchange");
      if (key != null) {
        named.put("routingKey", key);
      } else if (keyUnknown) {
        attributes.putArray("unknown").add("routingKey");
      }
      emit(subject, SEND, exchange, named, attributes, where);
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
          List<Assignment> given = code.values().assignments(f);
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

    /**
     * The functions Spring Cloud Stream may bind: {@code @Bean} methods returning a {@code
     * Function}, {@code Consumer} or {@code Supplier} (or their two-input forms), and components
     * implementing one, each a {@value #STREAM_FUNCTION} fact named after its bean. Linking binds
     * them in each view as the module's config says.
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
      functions.forEach(
          (bean, b) -> {
            ObjectNode attributes = FactJson.newObject();
            attributes.put("inputs", b.kind().inputs());
            attributes.put("outputs", b.kind().outputs());
            module(attributes, true);
            fact(b.method(), STREAM_FUNCTION, FactObject.string(bean), attributes, b.where());
          });
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
            streamInput(m.id(), where, name, common);
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
                Text.literal(name),
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
        streamOutput(i.caller(), i.where(), Text.literal("output"), null, attributes);
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
      streamOutput(i.caller(), i.where(), binding, binder, attributes);
      payload(i.caller(), "send", i, named ? 2 : 1, STREAM);
    }

    /** A call to a legacy binding interface's {@code @Output} method: a send to that binding. */
    private void outputCall(ModuleStructure.Call c) {
      Member callee = c.callee() == null ? null : code.values().member(c.callee());
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
      streamOutput(c.caller(), c.where(), Text.literal(name), null, attributes);
    }

    /**
     * A binding's consumer, the binding named as written: linking reads its destination and group
     * from config in each view.
     */
    private void streamInput(EntityId subject, Location where, String binding, ObjectNode common) {
      ObjectNode attributes = common.deepCopy();
      attributes.put("broker", STREAM);
      attributes.put("destinationKind", BINDING_KIND);
      emit(subject, LISTENER, Text.literal(binding), Map.of(), attributes, where);
    }

    /**
     * A binding's producer, the binding (or a destination's name) and the binder named in code as
     * written: linking reads the destination and routing key from config in each view.
     *
     * @param binder the binder named in code, or null if none is
     */
    private void streamOutput(
        EntityId subject, Location where, Text binding, Text binder, ObjectNode common) {
      ObjectNode attributes = common.deepCopy();
      attributes.put("broker", STREAM);
      attributes.put("destinationKind", BINDING_KIND);
      Map<String, Text> named = new LinkedHashMap<>();
      if (binder != null) {
        named.put("binder", binder);
      }
      emit(subject, SEND, binding, named, attributes, where);
    }

    // -------------------------------------------------------------------------------------------
    // Following values

    /**
     * What a value in code is, as a text, followed by the shared follower ({@link Values}): null if
     * any part of it cannot be told.
     */
    private Text text(Expression e, EntityId context) {
      Values.Value value = code.values().follow(e, context, true);
      String written = value.text();
      if (written == null) {
        return null;
      }
      SortedSet<String> via = new TreeSet<>();
      value.via().forEach(id -> via.add(id.toString()));
      return new Text(written, value.annotated(), via);
    }

    private ModuleStructure.Parameter parameter(EntityId method, int index) {
      return code.values().parameter(method, index);
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

    // -------------------------------------------------------------------------------------------
    // Facts as written

    /**
     * Records a value-bearing fact as written: the subject's {@value #SENDS_TO}, {@value
     * #LISTENS_ON}, {@value #BINDS} or {@value #DECLARES} relationship to the destination the
     * object names, with the values as written in code, which config may resolve ({@code
     * configured}), and the fields or parameters each was followed through.
     */
    private void emit(
        EntityId subject,
        String predicate,
        Text object,
        Map<String, Text> named,
        ObjectNode attributes,
        Location where) {
      String broker = attributes.get("broker").stringValue();
      String kind =
          predicate.equals(BINDING) ? "queue" : attributes.get("destinationKind").stringValue();
      boolean stream = broker.equals(STREAM);
      if (object.written().isEmpty()) {
        // An empty name names nothing, as an empty value gave no fact before.
        return;
      }
      boolean scoped = stream || configured(object) || object.written().equals(UNKNOWN_EXCHANGE);
      ObjectNode a = attributes.deepCopy();
      if (predicate.equals(BINDING)) {
        a.remove("destinationKind");
      }
      ArrayNode configured = FactJson.newObject().arrayNode();
      a.put(NAME_VALUE, object.written());
      if (object.placeholders()) {
        configured.add(NAME_VALUE);
      }
      followed(a, "followed", object.followed());
      for (Map.Entry<String, Text> e : named.entrySet()) {
        Text t = e.getValue();
        a.put(e.getKey(), t.written());
        if (t.placeholders()) {
          configured.add(e.getKey());
        }
        followed(a, e.getKey() + FOLLOWED_SUFFIX, t.followed());
      }
      if (!configured.isEmpty()) {
        a.set("configured", configured);
      }
      module(a, stream);
      String key = broker + ":" + kind + ":" + object.written();
      EntityId destination =
          EntityKeys.of(DESTINATION_ENTITY, scoped ? module.id().key() + ":" + key : key);
      fact(subject, RELATIONS.get(predicate), FactObject.entity(destination), a, where);
    }

    private static void followed(ObjectNode a, String name, SortedSet<String> ids) {
      if (!ids.isEmpty()) {
        ArrayNode via = a.putArray(name);
        ids.forEach(via::add);
      }
    }

    /** Names the module whose config resolves a fact's values, and for a binding its classpath. */
    private void module(ObjectNode a, boolean stream) {
      a.put("configModule", module.id().toString());
      a.put("configRepo", module.repo().repo().name());
      if (stream) {
        if (streamOnClasspath == null) {
          streamOnClasspath = StreamConfig.onClasspath(module.repo(), module.directory());
          binders = StreamConfig.binders(module.repo(), module.directory());
        }
        if (streamOnClasspath) {
          a.put("streamOnClasspath", true);
        }
        if (!binders.isEmpty()) {
          ArrayNode found = a.putArray("binders");
          binders.forEach(found::add);
        }
      }
    }

    private void fact(
        EntityId subject,
        String predicate,
        FactObject object,
        ObjectNode attributes,
        Location where) {
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
              object,
              attributes,
              ORIGIN,
              evidence,
              module.confidence(),
              validity);
      facts.putIfAbsent(fact.id(), fact);
    }
  }

  /** Where a fact is recorded from: the evidence and confidence of the fact written in code. */
  private record Source(Evidence evidence, double confidence) {}

  /**
   * The facts of one module's values as written, resolved in each view of its config: the {@value
   * #SEND}, {@value #LISTENER}, {@value #BINDING} and {@value #DESTINATION} facts this extractor
   * recorded before it kept values as written (ADR 0094, #559).
   */
  private static final class Emitter {

    private final ValueLookup config;
    private final Origin origin;
    private final Validity validity;
    private final Map<String, Fact> facts;
    private StreamConfig stream;

    Emitter(ValueLookup config, Origin origin, Validity validity, Map<String, Fact> facts) {
      this.config = config;
      this.origin = origin;
      this.validity = validity;
      this.facts = facts;
    }

    /** Reads the module's Spring Cloud Stream config, once, with what its classpath holds. */
    void stream(boolean onClasspath, Set<String> binders, String label) {
      if (stream == null) {
        stream = new StreamConfig(config, onClasspath, binders, label);
      }
    }

    /** The facts of one value-bearing fact written in code: see {@link Run#emit}. */
    void written(EntityId subject, String relation, JsonNode a, Source src) {
      String predicate = PREDICATES.get(relation);
      Text object = value(a, NAME_VALUE);
      Map<String, Text> named = new LinkedHashMap<>();
      for (String v : VALUES) {
        if (a.has(v)) {
          named.put(v, value(a, v));
        }
      }
      ObjectNode base = ((ObjectNode) a).deepCopy();
      for (String v : WRITTEN_ONLY) {
        base.remove(v);
      }
      for (String v : VALUES) {
        base.remove(v);
        base.remove(v + FOLLOWED_SUFFIX);
      }
      String broker = a.path("broker").stringValue(null);
      if (STREAM.equals(broker)) {
        // The binding's broker and kind come from config, not from the code.
        base.remove("broker");
        base.remove("destinationKind");
        if (predicate.equals(LISTENER)) {
          streamInput(subject, src, profile -> object.written(), base);
        } else {
          Text binder = named.get("binder");
          streamOutput(
              subject,
              src,
              profile -> object,
              binder == null ? null : profile -> name(profile, binder),
              base);
        }
        return;
      }
      List<String> unknown = new ArrayList<>();
      a.path("unknown").forEach(u -> unknown.add(u.stringValue()));
      if (predicate.equals(SEND)
          && RABBITMQ.equals(broker)
          && "exchange".equals(a.path("destinationKind").stringValue(null))
          && !unknown.contains("exchange")) {
        base.remove("destinationKind");
        base.remove("unknown");
        rabbitSend(
            subject, object, named.get("routingKey"), unknown.contains("routingKey"), base, src);
        return;
      }
      emit(subject, predicate, object, named, base, src);
    }

    /** A value of a fact written in code, by its attribute's name. */
    private static Text value(JsonNode a, String name) {
      SortedSet<String> followed = new TreeSet<>();
      String via = name.equals(NAME_VALUE) ? "followed" : name + FOLLOWED_SUFFIX;
      a.path(via).forEach(id -> followed.add(id.stringValue()));
      boolean configured = false;
      for (JsonNode c : a.path("configured")) {
        configured |= c.stringValue().equals(name);
      }
      return new Text(a.get(name).stringValue(), configured, followed);
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
        Source src) {
      emitViews(
          subject,
          SEND,
          src,
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

    /** A function bean Spring Cloud Stream may bind: the method that is it, and its kind. */
    private record Bound(EntityId method, Source src, FunctionKind kind) {}

    /** A binding of a function: the method it is on, whether it is an input, and its name. */
    private record FunctionBinding(EntityId method, Source src, boolean input, String name) {}

    /**
     * The bindings of a module's functions ({@value #STREAM_FUNCTION} facts), by bean name: bound
     * in each view as the function definition says, or the only one when none is set.
     */
    void functions(Map<String, Bound> functions) {
      if (functions.isEmpty() || stream == null) {
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
          streamInput(b.method(), b.src(), name, FactJson.newObject());
        } else {
          streamOutput(
              b.method(),
              b.src(),
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
        to.add(new FunctionBinding(function.method(), function.src(), input, binding));
      }
    }

    /**
     * A binding's consumer: a listener on its destination, a Kafka topic or, for RabbitMQ, the
     * queue the binder declares for its group, and that queue's binding to the destination's
     * exchange.
     *
     * @param binding the binding's name in each view, or null in a view it is not bound in
     */
    private void streamInput(
        EntityId subject, Source src, Function<String, String> binding, ObjectNode common) {
      emitSlots(subject, LISTENER, src, p -> inputs(p, binding.apply(p), common, false));
      emitSlots(subject, BINDING, src, p -> inputs(p, binding.apply(p), common, true));
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
        Source src,
        Function<String, Text> binding,
        Function<String, String> binder,
        ObjectNode common) {
      emitSlots(
          subject,
          SEND,
          src,
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
        Source src,
        Function<String, Map<String, Shape>> byView) {
      Map<String, Map<String, Shape>> shapes = new HashMap<>();
      Set<String> slots = new LinkedHashSet<>();
      for (String profile : config.views()) {
        Map<String, Shape> s = byView.apply(profile);
        shapes.put(profile, s);
        slots.addAll(s.keySet());
      }
      for (String slot : slots) {
        emitViews(subject, predicate, src, profile -> shapes.get(profile).get(slot));
      }
    }

    private CodeValue resolve(String profile, Text text) {
      if (!text.placeholders()) {
        return new CodeValue(text.written(), text.written(), new TreeSet<>(), false);
      }
      return config.value(profile, text.written());
    }

    /** The attributes whose value as written is recorded too, when it differs. */
    private static final Set<String> WITH_WRITTEN = Set.of("routingKey", "exchange");

    private void emit(
        EntityId subject,
        String predicate,
        Text object,
        Map<String, Text> named,
        ObjectNode attributes,
        Source src) {
      Shape shape = new Shape(object, named, attributes);
      emitViews(subject, predicate, src, profile -> shape);
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
        EntityId subject, String predicate, Source src, Function<String, Shape> shape) {
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
          fact(subject, predicate, value, attributes, src);
        }
      }
    }

    private void fact(
        EntityId subject, String predicate, String object, ObjectNode attributes, Source src) {
      if (subject == null) {
        return;
      }
      Fact fact =
          Fact.create(
              subject,
              predicate,
              FactObject.string(object),
              attributes,
              origin,
              src.evidence(),
              src.confidence(),
              validity);
      facts.putIfAbsent(fact.id(), fact);
    }
  }

  private static final ExtractorInfo INFO =
      ExtractorInfo.of(NAME, VERSION, VOCABULARY_SOURCE, RESOURCE, Set.of());

  @Override
  public ExtractorInfo info() {
    return INFO;
  }

  @Override
  public CacheUnit unit() {
    return CacheUnit.SNAPSHOT;
  }

  @Override
  public void extract(ExtractionContext context, ExtractionUnit unit, ClaimSink sink) {
    sink.emitAll(extract(context.code(), context.snapshot()));
  }
}

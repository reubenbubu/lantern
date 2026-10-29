package io.lantern.extract.websocket;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.vocabulary.VocabularyType;
import io.lantern.extract.RepoBuild;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns Spring's WebSocket and STOMP code into facts: the endpoints clients connect to, the
 * broker's destination prefixes, the {@code @MessageMapping} handlers, the messages pushed to
 * subscribers through {@code SimpMessagingTemplate}, and the interceptors on the client channel.
 * See {@code docs/adr/0080-websocket-and-stomp.md}.
 *
 * <p>It reads the code as the Java structure extractor read it ({@link JavaAnalysis}): through
 * javac with the build's classpath, or parsed in degraded mode, with the same confidence.
 */
public final class WebSocketExtractor {

  /** The extractor's name, recorded as the origin of its facts. */
  public static final String NAME = "websocket";

  /** The extractor's version; change it whenever the facts it produces change. */
  public static final String VERSION = "1";

  /** The source recorded on the vocabulary revision that adds this extractor's types. */
  public static final String VOCABULARY_SOURCE = "websocket-extractor";

  /** Attribute: the method registers a WebSocket endpoint. */
  public static final String ENDPOINT = "websocket-endpoint";

  /** Attribute: the method configures a STOMP destination prefix. */
  public static final String PREFIX = "stomp-prefix";

  /** Attribute: the method handles STOMP messages or subscriptions, an entry point. */
  public static final String MAPPING = "stomp-mapping";

  /** Attribute: the method pushes messages to STOMP subscribers. */
  public static final String SEND = "stomp-send";

  /** Attribute: the method registers an interceptor on the STOMP client channel. */
  public static final String INTERCEPTOR = "stomp-interceptor";

  /** Protocols of a WebSocket endpoint. */
  public static final String STOMP = "stomp";

  public static final String WEBSOCKET = "websocket";

  private static final String RESOURCE = "/vocabulary/websocket-types.json";

  private static final Origin ORIGIN = new Origin(Origin.Kind.STATIC, NAME, VERSION);

  private static final String CONFIG = "org.springframework.web.socket.config.annotation.";
  private static final String STOMP_REGISTRY = CONFIG + "StompEndpointRegistry";
  private static final String HANDLER_REGISTRY = CONFIG + "WebSocketHandlerRegistry";
  private static final String BROKER_REGISTRY =
      "org.springframework.messaging.simp.config.MessageBrokerRegistry";
  private static final String CHANNEL_REGISTRATION =
      "org.springframework.messaging.simp.config.ChannelRegistration";
  private static final String MESSAGE_MAPPING =
      "org.springframework.messaging.handler.annotation.MessageMapping";
  private static final String SUBSCRIBE_MAPPING =
      "org.springframework.messaging.simp.annotation.SubscribeMapping";
  private static final String SEND_TO = "org.springframework.messaging.handler.annotation.SendTo";
  private static final String SEND_TO_USER =
      "org.springframework.messaging.simp.annotation.SendToUser";
  private static final List<String> SENDING_TYPES =
      List.of(
          "org.springframework.messaging.simp.SimpMessagingTemplate",
          "org.springframework.messaging.simp.SimpMessageSendingOperations");

  private static final Pattern FORMAT =
      Pattern.compile("%(?:\\d+\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z]");
  private static final List<String> INTERCEPTOR_METHODS =
      List.of(
          "preSend",
          "postSend",
          "afterSendCompletion",
          "preReceive",
          "postReceive",
          "afterReceiveCompletion",
          "beforeHandle",
          "afterMessageHandled");

  /** The vocabulary types this extractor's facts use. */
  public static List<VocabularyType> vocabulary() {
    try (InputStream in = WebSocketExtractor.class.getResourceAsStream(RESOURCE)) {
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
   */
  public List<Fact> extract(JavaAnalysis code, long snapshot) {
    Objects.requireNonNull(code, "code");
    Run run = new Run(code, Validity.from(snapshot));
    for (JavaAnalysis.Module module : code.modules()) {
      run.module(module);
    }
    run.endpoints.values().forEach(Endpoint::fact);
    return run.facts.values().stream()
        .sorted(
            Comparator.comparing((Fact f) -> f.subject().toString())
                .thenComparing(Fact::predicate)
                .thenComparing(f -> f.object().value())
                .thenComparing(f -> f.attributes().toString())
                .thenComparing(Fact::id))
        .toList();
  }

  /** A destination as found: its text and the fields it was followed through. */
  private record Text(String value, boolean dynamic, SortedSet<String> followed) {}

  /** One endpoint path of one method, gathered over its registrations. */
  private static final class Endpoint {
    final Run run;
    final JavaAnalysis.Module module;
    final EntityId method;
    final String path;
    final String protocol;
    final Location where;
    boolean sockjs;
    boolean plain;
    final SortedSet<String> origins = new TreeSet<>();
    String handler;

    Endpoint(
        Run run,
        JavaAnalysis.Module module,
        EntityId method,
        String path,
        String protocol,
        Location where) {
      this.run = run;
      this.module = module;
      this.method = method;
      this.path = path;
      this.protocol = protocol;
      this.where = where;
    }

    void fact() {
      ObjectNode attributes = FactJson.newObject();
      attributes.put("path", path);
      attributes.put("protocol", protocol);
      attributes.put("sockjs", sockjs);
      attributes.put("nativeWebSocket", plain);
      if (!origins.isEmpty()) {
        ArrayNode array = attributes.putArray("allowedOrigins");
        origins.forEach(array::add);
      }
      if (handler != null) {
        attributes.put("handler", handler);
      }
      run.fact(module, method, ENDPOINT, path, attributes, where);
    }
  }

  /** One extraction: the facts so far and the code indexed. */
  private static final class Run {

    final JavaAnalysis code;
    final Validity validity;
    final Map<String, Fact> facts = new LinkedHashMap<>();
    final Map<String, Endpoint> endpoints = new LinkedHashMap<>();
    final Map<EntityId, Member> members = new HashMap<>();
    final Map<EntityId, List<Assignment>> assignments = new HashMap<>();

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
      }
    }

    void module(JavaAnalysis.Module module) {
      for (TypeDecl type : module.structure().types()) {
        if (type.kind().equals(ModuleStructure.CLASS)) {
          mappings(module, type);
        }
      }
      for (Invocation i : module.structure().invocations()) {
        registration(module, i);
        brokerConfig(module, i);
        channel(module, i);
        send(module, i);
      }
    }

    // -------------------------------------------------------------------------------------------
    // Endpoints

    private void registration(JavaAnalysis.Module module, Invocation i) {
      boolean stomp = i.rootType().equals(STOMP_REGISTRY);
      if (!stomp && !i.rootType().equals(HANDLER_REGISTRY)) {
        return;
      }
      List<Expression.Invoke> chain = i.expression().chain();
      Expression.Invoke first = chain.getFirst();
      if (!first.method().equals(stomp ? "addEndpoint" : "addHandler")) {
        return;
      }
      List<Expression> paths = first.arguments();
      String handler = null;
      if (!stomp) {
        if (paths.isEmpty()) {
          return;
        }
        handler = typeOf(module, paths.getFirst());
        paths = paths.subList(1, paths.size());
      }
      boolean sockjs = false;
      List<String> origins = new ArrayList<>();
      for (Expression.Invoke call : chain.subList(1, chain.size())) {
        switch (call.method()) {
          case "withSockJS" -> sockjs = true;
          case "setAllowedOrigins", "setAllowedOriginPatterns" ->
              call.arguments().forEach(a -> literal(a).ifPresent(origins::add));
          default -> {}
        }
      }
      for (Expression path : paths) {
        String text = literal(path).orElse(null);
        if (text == null) {
          continue;
        }
        String key = i.caller() + " " + text;
        Endpoint endpoint =
            endpoints.computeIfAbsent(
                key,
                k ->
                    new Endpoint(
                        this, module, i.caller(), text, stomp ? STOMP : WEBSOCKET, i.where()));
        if (sockjs) {
          endpoint.sockjs = true;
        } else {
          endpoint.plain = true;
        }
        endpoint.origins.addAll(origins);
        if (handler != null) {
          endpoint.handler = handler;
        }
      }
    }

    // -------------------------------------------------------------------------------------------
    // Broker prefixes

    private void brokerConfig(JavaAnalysis.Module module, Invocation i) {
      if (!i.rootType().equals(BROKER_REGISTRY)) {
        return;
      }
      for (Expression.Invoke call : i.expression().chain()) {
        String kind =
            switch (call.method()) {
              case "enableSimpleBroker" -> "simple-broker";
              case "enableStompBrokerRelay" -> "broker-relay";
              case "setApplicationDestinationPrefixes" -> "application";
              case "setUserDestinationPrefix" -> "user";
              default -> null;
            };
        if (kind == null) {
          continue;
        }
        for (Expression argument : call.arguments()) {
          literal(argument)
              .ifPresent(
                  prefix -> {
                    ObjectNode attributes = FactJson.newObject();
                    attributes.put("kind", kind);
                    fact(module, i.caller(), PREFIX, prefix, attributes, i.where());
                  });
        }
      }
    }

    // -------------------------------------------------------------------------------------------
    // Client channel interceptors

    private void channel(JavaAnalysis.Module module, Invocation i) {
      if (!i.rootType().equals(CHANNEL_REGISTRATION)) {
        return;
      }
      String caller = i.caller().key();
      String channel =
          caller.contains("#configureClientInboundChannel(")
              ? "inbound"
              : caller.contains("#configureClientOutboundChannel(") ? "outbound" : null;
      if (channel == null) {
        return;
      }
      for (Expression.Invoke call : i.expression().chain()) {
        if (!call.method().equals("interceptors")) {
          continue;
        }
        for (Expression argument : call.arguments()) {
          String type = typeOf(module, argument);
          if (type == null) {
            continue;
          }
          ObjectNode attributes = FactJson.newObject();
          attributes.put("channel", channel);
          TypeDecl declared = code.type(type);
          if (declared != null) {
            List<String> methods = new ArrayList<>();
            for (Member m : declared.members()) {
              String name = methodName(m.id());
              if (name != null && INTERCEPTOR_METHODS.contains(name) && !methods.contains(name)) {
                methods.add(name);
              }
            }
            if (!methods.isEmpty()) {
              ArrayNode array = attributes.putArray("intercepts");
              methods.stream().sorted().forEach(array::add);
            }
          }
          fact(module, i.caller(), INTERCEPTOR, type, attributes, i.where());
        }
      }
    }

    // -------------------------------------------------------------------------------------------
    // Handlers

    private void mappings(JavaAnalysis.Module module, TypeDecl type) {
      List<Annotation> onClass = mappingPath(type.annotations(), MESSAGE_MAPPING);
      String prefix = onClass == null ? "" : first(onClass, "value").orElse("");
      List<String> classSendTo = values(type.annotations(), SEND_TO);
      for (Member m : type.members()) {
        if (m.id().type().equals(ModuleStructure.METHOD) && methodName(m.id()) != null) {
          for (String mapping : List.of(MESSAGE_MAPPING, SUBSCRIBE_MAPPING)) {
            List<Annotation> path = mappingPath(m.annotations(), mapping);
            if (path != null) {
              mapping(module, m, path, mapping, prefix, classSendTo);
            }
          }
        }
      }
    }

    /**
     * The path from the annotation written to the mapping annotation, unless the annotation written
     * is one of the framework's other than the mapping itself: {@code @RabbitListener},
     * {@code @KafkaListener} and others carry {@code @MessageMapping} as a meta-annotation, but
     * their methods do not handle STOMP messages.
     */
    private List<Annotation> mappingPath(List<Annotation> annotations, String type) {
      for (Annotation a : annotations) {
        List<Annotation> path = code.annotationPath(a, type);
        if (path != null && (path.size() == 1 || !a.type().startsWith("org.springframework."))) {
          return path;
        }
      }
      return null;
    }

    private void mapping(
        JavaAnalysis.Module module,
        Member method,
        List<Annotation> path,
        String mapping,
        String prefix,
        List<String> classSendTo) {
      Annotation written = path.getFirst();
      Annotation found = path.getLast();
      List<String> destinations = found.values("value");
      if (destinations.isEmpty()) {
        destinations = List.of("");
      }
      List<String> sendTo = values(method.annotations(), SEND_TO);
      if (sendTo.isEmpty()) {
        sendTo = classSendTo;
      }
      List<String> sendToUser = values(method.annotations(), SEND_TO_USER);
      for (String destination : destinations) {
        ObjectNode attributes = FactJson.newObject();
        attributes.put("kind", mapping.equals(MESSAGE_MAPPING) ? "message" : "subscribe");
        attributes.put("annotation", written.type());
        if (!sendTo.isEmpty()) {
          ArrayNode array = attributes.putArray("sendTo");
          sendTo.forEach(array::add);
        }
        if (!sendToUser.isEmpty()) {
          ArrayNode array = attributes.putArray("sendToUser");
          sendToUser.forEach(array::add);
        }
        fact(
            module,
            method.id(),
            MAPPING,
            join(prefix, destination).isEmpty() ? "/" : join(prefix, destination),
            attributes,
            written.where() != null ? written.where() : method.where());
      }
    }

    private static List<String> values(List<Annotation> annotations, String type) {
      for (Annotation a : annotations) {
        if (a.type().equals(type)) {
          List<String> values = a.values("value");
          return values.isEmpty() ? a.values("destinations") : values;
        }
      }
      return List.of();
    }

    private static Optional<String> first(List<Annotation> path, String element) {
      for (Annotation a : path) {
        if (!a.values(element).isEmpty()) {
          return Optional.of(a.values(element).getFirst());
        }
      }
      return Optional.empty();
    }

    private static String join(String prefix, String destination) {
      if (prefix.isEmpty()) {
        return destination;
      }
      if (destination.isEmpty()) {
        return prefix;
      }
      return prefix.endsWith("/") || destination.startsWith("/")
          ? prefix + destination
          : prefix + "/" + destination;
    }

    // -------------------------------------------------------------------------------------------
    // Sends

    private void send(JavaAnalysis.Module module, Invocation i) {
      Expression.Invoke call = i.expression();
      if (!SENDING_TYPES.contains(i.rootType()) || call.receiver() == null) {
        return;
      }
      boolean toUser;
      Expression destination;
      switch (call.method()) {
        case "convertAndSendToUser" -> {
          if (call.arguments().size() < 3) {
            return;
          }
          toUser = true;
          destination = call.arguments().get(1);
        }
        case "convertAndSend", "send" -> {
          // Without a destination the template's default is used, which is not read.
          if (call.arguments().size() < 2) {
            return;
          }
          toUser = false;
          destination = call.arguments().getFirst();
        }
        default -> {
          return;
        }
      }
      Text text = text(module, destination, 0);
      if (text == null) {
        return;
      }
      ObjectNode attributes = FactJson.newObject();
      attributes.put("method", call.method());
      attributes.put("template", i.rootType());
      if (toUser) {
        attributes.put("toUser", true);
      }
      if (text.dynamic()) {
        attributes.put("dynamic", true);
      }
      if (!text.followed().isEmpty()) {
        ArrayNode array = attributes.putArray("followed");
        text.followed().forEach(array::add);
      }
      fact(module, i.caller(), SEND, text.value(), attributes, i.where());
    }

    /**
     * A destination: constants folded, a format's specifiers and a part that is not a constant as
     * {@code {}}, a field given one value everywhere followed; null when nothing of it is known.
     */
    private Text text(JavaAnalysis.Module module, Expression e, int depth) {
      switch (e) {
        case Expression.Literal l -> {
          return new Text(l.value(), false, new TreeSet<>());
        }
        case Expression.Concat c -> {
          StringBuilder value = new StringBuilder();
          boolean dynamic = false;
          boolean known = false;
          SortedSet<String> followed = new TreeSet<>();
          for (Expression part : c.parts()) {
            Text t = text(module, part, depth);
            if (t == null) {
              value.append("{}");
              dynamic = true;
            } else {
              value.append(t.value());
              dynamic |= t.dynamic();
              followed.addAll(t.followed());
              known = true;
            }
          }
          return known ? new Text(value.toString(), dynamic, followed) : null;
        }
        case Expression.Invoke call -> {
          // "pattern".formatted(args) and String.format("pattern", args)
          Expression pattern = null;
          if (call.method().equals("formatted") && call.receiver() != null) {
            pattern = call.receiver();
          } else if (call.method().equals("format")
              && call.receiver() instanceof Expression.TypeRef t
              && t.binaryName().equals("java.lang.String")
              && !call.arguments().isEmpty()) {
            pattern = call.arguments().getFirst();
          }
          Text t = pattern == null ? null : text(module, pattern, depth);
          if (t == null) {
            return null;
          }
          return new Text(FORMAT.matcher(t.value()).replaceAll("{}"), true, t.followed());
        }
        case Expression.FieldRef f -> {
          if (depth > 2) {
            return null;
          }
          EntityId field = new EntityId("field", f.owner() + "#" + f.name());
          List<Assignment> given = assignments.getOrDefault(field, List.of());
          Text only = null;
          for (Assignment a : given) {
            Text t = text(module, a.value(), depth + 1);
            if (t == null || (only != null && !only.value().equals(t.value()))) {
              return null;
            }
            only = t;
          }
          if (only == null) {
            return null;
          }
          SortedSet<String> followed = new TreeSet<>(only.followed());
          followed.add(field.toString());
          return new Text(only.value(), only.dynamic(), followed);
        }
        default -> {
          return null;
        }
      }
    }

    // -------------------------------------------------------------------------------------------
    // Helpers

    private static Optional<String> literal(Expression e) {
      return e instanceof Expression.Literal l ? Optional.of(l.value()) : Optional.empty();
    }

    /** The binary name of the type an expression is: a field's, a new object's, a class literal. */
    private String typeOf(JavaAnalysis.Module module, Expression e) {
      switch (e) {
        case Expression.FieldRef f -> {
          Member field = members.get(new EntityId("field", f.owner() + "#" + f.name()));
          if (field != null && field.type() != null) {
            return field.type();
          }
          List<Assignment> given =
              assignments.getOrDefault(
                  new EntityId("field", f.owner() + "#" + f.name()), List.of());
          return given.size() == 1 ? typeOf(module, given.getFirst().value()) : null;
        }
        case Expression.Invoke i when i.method().equals("<init>") -> {
          return i.receiver() instanceof Expression.TypeRef t ? t.binaryName() : null;
        }
        case Expression.TypeRef t -> {
          return t.binaryName();
        }
        default -> {
          return null;
        }
      }
    }

    private static String methodName(EntityId method) {
      String key = method.key();
      int hash = key.indexOf('#');
      int paren = key.indexOf('(');
      if (hash < 0 || paren < hash) {
        return null;
      }
      String name = key.substring(hash + 1, paren);
      return name.equals(ModuleStructure.CONSTRUCTOR) ? null : name;
    }

    void fact(
        JavaAnalysis.Module module,
        EntityId subject,
        String predicate,
        String object,
        ObjectNode attributes,
        Location where) {
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

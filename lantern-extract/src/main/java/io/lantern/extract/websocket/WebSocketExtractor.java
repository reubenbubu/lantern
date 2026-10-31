package io.lantern.extract.websocket;

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
import io.lantern.extract.ExtractionContext;
import io.lantern.extract.ExtractionUnit;
import io.lantern.extract.Extractor;
import io.lantern.extract.ExtractorInfo;
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
public final class WebSocketExtractor implements Extractor {

  /** The extractor's name, recorded as the origin of its facts. */
  public static final String NAME = "websocket";

  /** The extractor's version; change it whenever the facts it produces change. */
  public static final String VERSION = "2";

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

  /** Attribute: the subject class intercepts the messages of a STOMP client channel. */
  public static final String CHANNEL_INTERCEPTOR = "stomp-channel-interceptor";

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
          "org.springframework.messaging.simp.SimpMessageSendingOperations",
          "org.springframework.messaging.core.MessageSendingOperations");
  private static final List<String> INTERCEPTOR_TYPES =
      List.of(
          "org.springframework.messaging.support.ChannelInterceptor",
          "org.springframework.messaging.support.ExecutorChannelInterceptor",
          "org.springframework.messaging.support.ChannelInterceptorAdapter");
  private static final List<String> HEADER_ACCESSORS =
      List.of(
          "org.springframework.messaging.simp.stomp.StompHeaderAccessor",
          "org.springframework.messaging.simp.SimpMessageHeaderAccessor");
  // An argument typed as one of these interfaces says nothing of what it does.
  private static final String HANDSHAKE_INTERCEPTOR =
      "org.springframework.web.socket.server.HandshakeInterceptor";
  private static final String HANDSHAKE_HANDLER =
      "org.springframework.web.socket.server.HandshakeHandler";
  private static final Map<String, String> RELAY_SETTINGS =
      Map.of(
          "setRelayHost", "relayHost",
          "setRelayPort", "relayPort",
          "setVirtualHost", "virtualHost",
          "setUserDestinationBroadcast", "userDestinationBroadcast",
          "setUserRegistryBroadcast", "userRegistryBroadcast");

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
    final SortedSet<String> handshakeInterceptors = new TreeSet<>();
    String handler;
    String handshakeHandler;

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
      if (!handshakeInterceptors.isEmpty()) {
        ArrayNode array = attributes.putArray("handshakeInterceptors");
        handshakeInterceptors.forEach(array::add);
      }
      if (handshakeHandler != null) {
        attributes.put("handshakeHandler", handshakeHandler);
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

    /** Whether the code configures a STOMP broker or endpoint anywhere. */
    final boolean stompBroker;

    Run(JavaAnalysis code, Validity validity) {
      this.code = code;
      this.validity = validity;
      stompBroker =
          code.modules().stream()
              .flatMap(m -> m.structure().invocations().stream())
              .anyMatch(
                  i -> i.rootType().equals(BROKER_REGISTRY) || i.rootType().equals(STOMP_REGISTRY));
    }

    void module(JavaAnalysis.Module module) {
      for (TypeDecl type : module.structure().types()) {
        if (type.kind().equals(ModuleStructure.CLASS)) {
          mappings(module, type);
          if (isInterceptor(type, 0)) {
            interceptor(module, type);
          }
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
      List<String> handshakeInterceptors = new ArrayList<>();
      String handshakeHandler = null;
      for (Expression.Invoke call : chain.subList(1, chain.size())) {
        switch (call.method()) {
          case "withSockJS" -> sockjs = true;
          case "setAllowedOrigins", "setAllowedOriginPatterns" ->
              call.arguments().forEach(a -> literal(a).ifPresent(origins::add));
          case "addInterceptors", "setInterceptors" ->
              call.arguments().stream()
                  .map(a -> typeOf(module, a))
                  .filter(t -> t != null && !t.equals(HANDSHAKE_INTERCEPTOR))
                  .forEach(handshakeInterceptors::add);
          case "setHandshakeHandler" -> {
            String type =
                call.arguments().isEmpty() ? null : typeOf(module, call.arguments().getFirst());
            handshakeHandler = HANDSHAKE_HANDLER.equals(type) ? null : type;
          }
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
        endpoint.handshakeInterceptors.addAll(handshakeInterceptors);
        if (handler != null) {
          endpoint.handler = handler;
        }
        if (handshakeHandler != null) {
          endpoint.handshakeHandler = handshakeHandler;
        }
      }
    }

    // -------------------------------------------------------------------------------------------
    // Broker prefixes

    private void brokerConfig(JavaAnalysis.Module module, Invocation i) {
      if (!i.rootType().equals(BROKER_REGISTRY)) {
        return;
      }
      List<Expression.Invoke> chain = i.expression().chain();
      for (int c = 0; c < chain.size(); c++) {
        Expression.Invoke call = chain.get(c);
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
        // A relay's connection is set on the registration enableStompBrokerRelay returns.
        ObjectNode relay = FactJson.newObject();
        if (kind.equals("broker-relay")) {
          for (Expression.Invoke setting : chain.subList(c + 1, chain.size())) {
            String name = RELAY_SETTINGS.get(setting.method());
            if (name != null && setting.arguments().size() == 1) {
              String value = setting(setting.arguments().getFirst());
              if (value != null) {
                relay.put(name, value);
              }
            }
          }
        }
        for (Expression argument : call.arguments()) {
          literal(argument)
              .ifPresent(
                  prefix -> {
                    ObjectNode attributes = FactJson.newObject();
                    attributes.put("kind", kind);
                    attributes.setAll(relay);
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
            intercepts(declared, attributes);
          }
          fact(module, i.caller(), INTERCEPTOR, type, attributes, i.where());
        }
      }
    }

    /** The {@code ChannelInterceptor} methods a class declares, as {@code intercepts}. */
    private static void intercepts(TypeDecl type, ObjectNode attributes) {
      SortedSet<String> methods = new TreeSet<>();
      for (Member m : type.members()) {
        String name = methodName(m.id());
        if (name != null && INTERCEPTOR_METHODS.contains(name)) {
          methods.add(name);
        }
      }
      if (!methods.isEmpty()) {
        ArrayNode array = attributes.putArray("intercepts");
        methods.forEach(array::add);
      }
    }

    /** Whether a class implements {@code ChannelInterceptor}, directly or through its own types. */
    private boolean isInterceptor(TypeDecl type, int depth) {
      for (ModuleStructure.Supertype s : type.supertypes()) {
        if (INTERCEPTOR_TYPES.contains(s.binaryName())) {
          return true;
        }
        TypeDecl declared = code.type(s.binaryName());
        if (declared != null && depth < 5 && isInterceptor(declared, depth + 1)) {
          return true;
        }
      }
      return false;
    }

    /**
     * A class that intercepts the messages of a client channel, at its declaration: the methods it
     * declares, whether it reads a message's destination and sets its user, the native headers it
     * reads, and the destinations (or destination patterns) its code names.
     */
    private void interceptor(JavaAnalysis.Module module, TypeDecl type) {
      String owner = type.binaryName();
      boolean readsDestination = false;
      boolean setsUser = false;
      SortedSet<String> headers = new TreeSet<>();
      for (Invocation i : module.structure().invocations()) {
        if (!HEADER_ACCESSORS.contains(i.rootType()) || !ownedBy(i.caller(), owner)) {
          continue;
        }
        for (Expression.Invoke call : i.expression().chain()) {
          switch (call.method()) {
            case "getDestination" -> readsDestination = true;
            case "setUser" -> setsUser = true;
            case "getFirstNativeHeader", "getNativeHeader" -> {
              if (!call.arguments().isEmpty()) {
                literal(call.arguments().getFirst()).ifPresent(headers::add);
              }
            }
            default -> {}
          }
        }
      }
      SortedSet<String> destinations = new TreeSet<>();
      for (ModuleStructure.Call call : module.structure().calls()) {
        if (ownedBy(call.caller(), owner)) {
          for (String argument : call.arguments()) {
            if (argument != null && argument.replaceFirst("^\\^", "").startsWith("/")) {
              destinations.add(argument);
            }
          }
        }
      }
      ObjectNode attributes = FactJson.newObject();
      intercepts(type, attributes);
      if (readsDestination) {
        attributes.put("readsDestination", true);
      }
      if (setsUser) {
        attributes.put("setsUser", true);
      }
      if (!headers.isEmpty()) {
        ArrayNode array = attributes.putArray("headers");
        headers.forEach(array::add);
      }
      if (!destinations.isEmpty()) {
        ArrayNode array = attributes.putArray("destinations");
        destinations.forEach(array::add);
      }
      fact(
          module,
          new EntityId("type", owner),
          CHANNEL_INTERCEPTOR,
          owner,
          attributes,
          declaration(type));
    }

    /**
     * A type's declaration: its annotations and header, up to its first member other than a
     * constructor (which javac may place on the header when it declares one implicitly).
     */
    private static Location declaration(TypeDecl type) {
      Location where = type.where();
      int end = where.end();
      for (Member m : type.members()) {
        boolean constructor = m.id().key().contains("#" + ModuleStructure.CONSTRUCTOR + "(");
        if (!constructor
            && m.where() != null
            && m.where().file().equals(where.file())
            && m.where().start() > where.start()) {
          end = Math.min(end, m.where().start() - 1);
        }
      }
      return new Location(where.file(), where.start(), Math.max(where.start(), end));
    }

    /** Whether code is the type's own: in one of its methods or outside methods. */
    private static boolean ownedBy(EntityId caller, String owner) {
      String key = caller.key();
      return key.equals(owner) || key.startsWith(owner + "#");
    }

    // -------------------------------------------------------------------------------------------
    // Handlers

    private void mappings(JavaAnalysis.Module module, TypeDecl type) {
      List<Annotation> onClass = mappingPath(type.annotations(), MESSAGE_MAPPING);
      String prefix = onClass == null ? "" : first(onClass, "value").orElse("");
      for (Member m : type.members()) {
        if (m.id().type().equals(ModuleStructure.METHOD) && methodName(m.id()) != null) {
          for (String mapping : List.of(MESSAGE_MAPPING, SUBSCRIBE_MAPPING)) {
            List<Annotation> path = mappingPath(m.annotations(), mapping);
            if (path != null) {
              mapping(module, m, path, mapping, prefix, type.annotations());
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
        List<Annotation> classAnnotations) {
      Annotation written = path.getFirst();
      Annotation found = path.getLast();
      List<String> destinations = found.values("value");
      if (destinations.isEmpty()) {
        destinations = List.of("");
      }
      boolean returns = method.returns() != null && !method.returns().equals("void");
      Annotation reply = returns ? reply(method.annotations(), classAnnotations) : null;
      for (String destination : destinations) {
        ObjectNode attributes = FactJson.newObject();
        attributes.put("kind", mapping.equals(MESSAGE_MAPPING) ? "message" : "subscribe");
        attributes.put("annotation", written.type());
        String handled = join(prefix, destination);
        if (reply != null) {
          // Without destinations, @SendTo sends to /topic and the destination handled, and
          // @SendToUser to the user's /queue and the destination handled.
          boolean toUser = reply.type().equals(SEND_TO_USER);
          List<String> targets = destinations(reply);
          ArrayNode array = attributes.putArray(toUser ? "sendToUser" : "sendTo");
          if (targets.isEmpty()) {
            array.add(join(toUser ? "/queue" : "/topic", handled));
            attributes.put("defaultSendTo", true);
          } else {
            targets.forEach(array::add);
          }
        } else if (returns && stompBroker) {
          // Without either, a @MessageMapping method's return value goes to the broker at /topic
          // and the destination it handles; a @SubscribeMapping method's goes to the subscriber.
          // Only where a STOMP broker is configured: RSocket controllers use @MessageMapping too.
          if (mapping.equals(MESSAGE_MAPPING)) {
            attributes.putArray("sendTo").add(join("/topic", handled));
            attributes.put("defaultSendTo", true);
          } else {
            attributes.put("repliesToSubscriber", true);
          }
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

    /**
     * The annotation that decides where a method's return value goes, as Spring chooses it: the
     * method's {@code @SendToUser}, then its {@code @SendTo}, then the class's, preferring one that
     * names destinations; null when there is none.
     */
    private static Annotation reply(List<Annotation> onMethod, List<Annotation> onClass) {
      List<Annotation> candidates = new ArrayList<>();
      for (List<Annotation> annotations : List.of(onMethod, onClass)) {
        for (String type : List.of(SEND_TO_USER, SEND_TO)) {
          annotations.stream()
              .filter(a -> a.type().equals(type))
              .findFirst()
              .ifPresent(candidates::add);
        }
      }
      return candidates.stream()
          .filter(a -> !destinations(a).isEmpty())
          .findFirst()
          .orElse(candidates.isEmpty() ? null : candidates.getFirst());
    }

    private static List<String> destinations(Annotation a) {
      List<String> values = a.values("value");
      return values.isEmpty() ? a.values("destinations") : values;
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
          List<Assignment> given = code.values().assignments(f);
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

    /**
     * A setting's value: a constant, or the placeholder of the {@code @Value} on the field it is
     * read from, such as {@code ${relay.host}}; null otherwise.
     */
    private String setting(Expression e) {
      if (e instanceof Expression.Literal l) {
        return l.value();
      }
      if (e instanceof Expression.FieldRef f) {
        Member field = code.values().field(f);
        return field == null ? null : code.values().annotated(field.annotations());
      }
      return null;
    }

    /** The binary name of the type an expression is: a field's, a new object's, a class literal. */
    private String typeOf(JavaAnalysis.Module module, Expression e) {
      switch (e) {
        case Expression.FieldRef f -> {
          Member field = code.values().field(f);
          if (field != null && field.type() != null) {
            return field.type();
          }
          List<Assignment> given = code.values().assignments(f);
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

  @Override
  public ExtractorInfo info() {
    return new ExtractorInfo(NAME, VERSION);
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

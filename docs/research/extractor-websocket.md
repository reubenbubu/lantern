# WebSocket and server push: what the WebSocket extractor reads

Research for #391 (Review the WebSocket extractor), milestone "Extractor revisit". It leads to an amendment of `docs/adr/0080-websocket-and-stomp.md` and the WebSocket row of `docs/design.md` (Static analysis and extractors).

**Depth: high.** The note decides what an architecture document needs from a system's push interfaces (who may connect and subscribe, which broker is behind them, where replies go) and which of it the extractor can read from the code, across Spring's STOMP and raw WebSocket support, Server-Sent Events, WebFlux, Jakarta WebSocket, Socket.IO and Spring Integration. I read 17 official pages and one paper in full, and the WebSocket code of 15 open-source projects, fetched raw from GitHub. The research is generic: it does not read Lantern's test sets.

## Question

Which WebSocket and server-push styles occur in Java systems (raw WebSocket handlers, STOMP with a simple broker or a broker relay, SSE, reactive WebSocket, Jakarta `@ServerEndpoint`, Socket.IO), and for each, what in the code names:

- the endpoint path,
- the destinations or events, and who subscribes to them,
- who may connect or subscribe,
- the broker behind it?

What does an architecture document need from each, and what does the extractor of ADR 0080 miss?

## What we learned

### Raw Spring WebSocket handlers

- **The path** is the second argument of `WebSocketHandlerRegistry.addHandler(handler, paths...)` in `registerWebSocketHandlers` of an `@EnableWebSocket` configurer, or `<websocket:mapping path=...>` in XML. Paths may be Ant patterns: ThingsBoard registers `/api/ws/**` and its one handler dispatches on the request path, so one pattern can hide several logical endpoints. The handler argument is often a field typed as the `WebSocketHandler` interface, the concrete class known only from the bean. https://docs.spring.io/spring-framework/reference/web/websocket/server.html
- **Destinations**: none in the framework. The handler's own protocol decides, and pushes are `session.sendMessage(...)` on sessions the handler keeps.
- **Who may connect**: `setAllowedOrigins` and `setAllowedOriginPatterns` (same origin by default); handshake interceptors added with `addInterceptors(...)`, whose `beforeHandshake` may return `false` to refuse the upgrade; a `DefaultHandshakeHandler` subclass set with `setHandshakeHandler(...)` that overrides `determineUser` (JHipster, Jasper).
- **Pitfall:** HTTP security often has `permitAll()` on the WebSocket path because the check moved into the protocol. ThingsBoard authenticates with a JWT in the first message or a `?token=` parameter inside its handler. `permitAll` on a WebSocket path does not mean "open".

### STOMP with the simple broker

- **The path** is `StompEndpointRegistry.addEndpoint(paths...)` in `registerStompEndpoints`. Spring Session's `AbstractSessionWebSocketMessageBrokerConfigurer` uses `configureStompEndpoints` instead. https://docs.spring.io/spring-framework/reference/web/websocket/stomp/enable.html
- **Destinations.** Application prefixes come from `setApplicationDestinationPrefixes`, broker prefixes from `enableSimpleBroker`, and the user prefix from `setUserDestinationPrefix` (`/user` by default). https://docs.spring.io/spring-framework/reference/web/websocket/stomp/user-destination.html
- **Where replies go**, which the plan missed (https://docs.spring.io/spring-framework/reference/web/websocket/stomp/handle-annotations.html):
  - A `@MessageMapping` method's return value without `@SendTo` or `@SendToUser` goes to the broker at the inbound destination prefixed with `/topic`.
  - A `@SubscribeMapping` method's return value goes straight back to the subscribing client, not through the broker, unless `@SendTo` or `@SendToUser` is present.
  - `@SendTo` and `@SendToUser` on the class are defaults for its methods.
  - `@MessageExceptionHandler` methods, also in `@ControllerAdvice`, reply with `@SendToUser` (the Spring team's portfolio sample sends errors to `/queue/errors`).
- **Pushes** go through `SimpMessagingTemplate` or `SimpMessageSendingOperations`, and in real code also through the generic `MessageSendingOperations<String>` (portfolio's `QuoteService`). Destinations are built by concatenation: `"/topic/price.stock." + ticker`.
- **Lifecycle listeners** act on connections: `ApplicationListener<SessionDisconnectEvent>` (JHipster pushes a logout activity), `SessionConnectEvent`, `SessionSubscribeEvent`, `BrokerAvailabilityEvent`.
- **Subscribers are browsers.** Server code names them only through security rules and interceptors that inspect `SUBSCRIBE` destinations.

### Who may connect or subscribe: three mechanisms, often combined

1. **Message security.** Spring Security 5.8+ has `@EnableWebSocketSecurity` and an `AuthorizationManager<Message<?>>` bean built with `MessageMatcherDelegatingAuthorizationManager.Builder`: `nullDestMatcher()`, `simpDestMatchers(...)`, `simpSubscribeDestMatchers(...)`, `simpTypeMatchers(...)`, `anyMessage()`, each followed by `permitAll()`, `authenticated()`, `hasRole(..)`, `denyAll()` or `access(...)`, first match wins. CSRF on `CONNECT` is on by default. The legacy `AbstractSecurityWebSocketMessageBrokerConfigurer.configureInbound` was removed in Spring Security 7 but is still in older code. Spring Security secures only the inbound channel. https://docs.spring.io/spring-security/reference/servlet/integrations/websocket.html
2. **Channel interceptors** on `configureClientInboundChannel`. The class's `preSend` reads `StompHeaderAccessor.getCommand()` and then:
   - on `CONNECT`, validates a native `Authorization` header and calls `accessor.setUser(...)`;
   - on `SUBSCRIBE`, checks `accessor.getDestination()` against the user's rights (Jasper's `auth.canSubscribeTo(destination)`) or records presence (KOIN);
   - rejects commands outright by returning `null` or throwing (Jasper rejects `SEND`, which makes the endpoint push-only).

   The docs order a custom authentication interceptor ahead of Spring Security's with `@Order(Ordered.HIGHEST_PRECEDENCE + 99)` on a separate configurer. Interceptor classes are often `@Component` beans injected into the configuration, and sometimes anonymous or inner classes. https://docs.spring.io/spring-framework/reference/web/websocket/stomp/authentication-token-based.html, https://docs.spring.io/spring-framework/reference/web/websocket/stomp/interceptors.html
3. **The handshake**: handshake interceptors, a handshake handler's `determineUser`, and allowed origins, often from properties.

### STOMP with a broker relay

- `enableStompBrokerRelay(prefixes...)` returns a registration configured with `setRelayHost`, `setRelayPort` (default `127.0.0.1:61613`), `setVirtualHost`, system and client logins, heartbeats, `setTcpClient(...)` for several broker addresses, and `setUserDestinationBroadcast` and `setUserRegistryBroadcast` for several application instances sharing user destinations. https://docs.spring.io/spring-framework/reference/web/websocket/stomp/handle-broker-relay-configure.html, https://docs.spring.io/spring-framework/reference/web/websocket/stomp/handle-broker-relay.html
- **The values are usually indirect**: `@Value("${ebook.chat.relay.host}")` fields (jorgeacetozi), `properties.getRabbitmq().getHost()` (dzhw), a hard-coded `"localhost"` and `61613` (PNUSW).
- **Relay or simple broker is often chosen at runtime**, both branches in the code: dzhw under a profile, ClipCascade under a property, with `@ConditionalOnProperty` on the whole configuration.
- For an architecture document the relay is the main fact the simple broker lacks: an external broker (RabbitMQ, ActiveMQ) the application connects to, and that several instances can share.

### Server-Sent Events

- A Spring MVC handler returns `SseEmitter` (or `ResponseBodyEmitter`), usually with `produces = text/event-stream` but not always (Lingxi-BI's `@GetMapping("/user/connect")` has none). A `Flux<ServerSentEvent<T>>` return works in MVC and WebFlux; the functional form is `ServerResponse.sse(...)`. https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html, https://docs.spring.io/spring-framework/reference/web/webmvc-functional.html
- Event names come from `SseEmitter.event().name(...)` or `ServerSentEvent.builder().event(...)`.
- Emitters live in registries keyed by request data (`Map<Long, SseEmitter>` by user ID), and publishers reach the browser only through the registry. In Lingxi-BI a `@RabbitListener` consumer calls the registry, so the flow is queue, consumer, registry, browser stream.

### Other styles

- **Reactive WebSocket (WebFlux).** A `WebSocketHandler` mapped by a `SimpleUrlHandlerMapping` bean from a `Map` of paths, often filled over several statements. Halo writes its own `AbstractHandlerMapping` and computes paths from methods of every implementation of its own interface: no registry call names the path. https://docs.spring.io/spring-framework/reference/web/webflux-websocket.html
- **Jakarta WebSocket.** `@ServerEndpoint(value = "/chatrooms/{room-name}", configurator = ...)` with `@OnOpen`, `@OnMessage`, `@OnClose`, `@OnError`; in Spring Boot deployed only with a `ServerEndpointExporter` bean, which thinglinks keeps in another Maven module. The container makes one instance per connection, so real code pulls dependencies through static `getBean` lookups. Spring Security does not see its messages, only the upgrade. https://jakarta.ee/learn/docs/jakartaee-tutorial/current/web/websocket/websocket.html
- **Socket.IO (netty-socketio).** A `SocketIOServer` on its own port beside the HTTP server (xkcoding: 8080 and 8081), `@OnEvent("chat")` methods active with a `SpringAnnotationScanner`, rooms, an authorization listener, and a Redisson or Hazelcast store factory for several nodes.
- **Spring Integration.** `ServerWebSocketContainer` hosts endpoints without `@EnableWebSocket`; the common lighter pattern is `@ServiceActivator` methods that call `SimpMessagingTemplate.convertAndSend` (Jasper), which the extractor's `stomp-send` already sees. https://docs.spring.io/spring-integration/reference/web-sockets.html
- **Quarkus WebSockets Next** (for breadth): `@WebSocket(path=...)`, `@OnTextMessage`, `@Authenticated` on the upgrade or per message. It shows the same general shape: endpoint, per-message handlers, upgrade security, broadcast. https://quarkus.io/guides/websockets-next-reference
- **Gateways.** Spring Cloud Gateway forwards WebSocket on `ws://`, `wss://` and `lb:ws://` route URIs; a SockJS fallback needs a second `http://` route for `/info`. https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/global-filters.html

### Prior art

MicroGraal's WebSocket extension (Pokhvalenko, Excel@FIT 2025) finds `addHandler`-style registrations, handler classes and STOMP topics on GraalVM's IR, infers message payload types and links services by matching URIs. It confirms ADR 0080's core and adds one idea Lantern lacks, payload types; it says nothing about security, brokers or SSE. https://excel.fit.vutbr.cz/submissions/2025/021/21.pdf

## Pitfalls for static analysis

- A registration split over statements or beans: a registration configured later, a handler typed as the interface, WebFlux paths in a `Map`, paths computed from an application interface.
- Placeholders in relay host and port, origins and Socket.IO ports: `@Value`, `@ConfigurationProperties` getters, `Environment` lookups.
- Conditional configuration: relay or simple broker under a profile or property, whole configurations under `@ConditionalOnProperty` or `@Profile`. Record the condition; do not pick a branch.
- Default reply destinations, which no annotation writes.
- Senders typed as the generic `MessageSendingOperations`.
- Message-security rules in several code shapes, first match wins.
- Anonymous and inner interceptor classes.

## What the extractor misses today

Against the findings, ADR 0080's extractor:

- cites an interceptor only at its registration, so the class that does the checking, and what it checks, has no fact;
- records a broker relay's prefixes but not the broker it connects to;
- does not record handshake interceptors or a handshake handler;
- leaves `sendTo` empty for a `@MessageMapping` method that replies to `/topic` by default, and does not say that a `@SubscribeMapping` method replies to the subscriber;
- ignores a class's `@SendToUser`;
- misses pushes through `MessageSendingOperations`;
- reads none of: message security rules, the STOMP commands an interceptor checks or rejects, SSE, Jakarta, WebFlux, Socket.IO and Spring Integration endpoints, `@MessageExceptionHandler` replies, lifecycle listeners, conditions on the configuration, registrations split over statements.

## Options

- **A. Nothing more now.** Leaves the three questions a reader asks first (who may connect, which broker, where replies go) unanswered.
- **B. Deepen STOMP within the extractor, on the calls and tracked packages it already reads.** Cheap and contained: the interceptor class with what it reads, relay settings, handshake interceptors and handler, default replies, `MessageSendingOperations`. Message security and the commands an interceptor checks need more of the code than the analysis keeps today (a builder chain of pairs, switch labels), so they are a step of their own.
- **C. SSE** as an attribute of the HTTP endpoint plus event-name facts. Moderate: it needs the HTTP endpoint fact and return types, and linking publishers through an emitter registry is a heuristic.
- **D. The other WebSocket styles** as `websocket-endpoint` with a new `protocol`, their message handlers as entry points. Each is small; Socket.IO also needs a second port in the deployment view.
- **E. Outbound WebSocket clients** as links between containers. Rare in the code read.

## Recommendation

Do B now, inside this extractor:

1. **`stomp-channel-interceptor`** on every class that implements `ChannelInterceptor`, directly or through the application's own types, cited at its declaration: the interceptor methods it declares, whether it reads the destination (`getDestination`) and sets the user (`setUser`), the native headers it reads, and the constant destinations or destination patterns its code names.
2. **The relay's connection** on its `stomp-prefix` facts: `relayHost`, `relayPort`, `virtualHost`, `userDestinationBroadcast`, `userRegistryBroadcast`, each a constant or the placeholder of the `@Value` field it is read from. Logins and passcodes are never recorded.
3. **Handshake** interceptors and handler on `websocket-endpoint`.
4. **Replies**: Spring's default `sendTo` (`/topic` and the destination, `defaultSendTo`), `repliesToSubscriber` for `@SubscribeMapping`, class-level `@SendToUser`, both only on methods that return a value.
5. **`MessageSendingOperations`** as a sender.

Later issues, each with a project-0 case first: message security rules and the commands an interceptor checks or rejects; SSE; the other WebSocket styles; the remaining STOMP details (split registrations, conditions, `@MessageExceptionHandler`, lifecycle listeners); and the composition of the new facts (the relay's broker in the container view, the access line of an endpoint).

## What it changes in the bar and the design

- **Design, Static analysis and extractors**: the WebSocket row names the interceptor classes, the relay's broker and the default replies; ADR 0080 is amended with the facts and the gaps above.
- **Quality bar**: nothing new. How a document shows who may connect to a push interface and the broker behind it is composition's, decided in the issue that draws these facts.

## Sources read in full

- Spring Framework, Enable STOMP: https://docs.spring.io/spring-framework/reference/web/websocket/stomp/enable.html
- Spring Framework, Annotated Controllers: https://docs.spring.io/spring-framework/reference/web/websocket/stomp/handle-annotations.html
- Spring Framework, External Broker: https://docs.spring.io/spring-framework/reference/web/websocket/stomp/handle-broker-relay.html
- Spring Framework, Connecting to a Broker: https://docs.spring.io/spring-framework/reference/web/websocket/stomp/handle-broker-relay-configure.html
- Spring Framework, User Destinations: https://docs.spring.io/spring-framework/reference/web/websocket/stomp/user-destination.html
- Spring Framework, Token Authentication: https://docs.spring.io/spring-framework/reference/web/websocket/stomp/authentication-token-based.html
- Spring Framework, Interception: https://docs.spring.io/spring-framework/reference/web/websocket/stomp/interceptors.html
- Spring Framework, WebSocket API: https://docs.spring.io/spring-framework/reference/web/websocket/server.html
- Spring Framework, WebFlux WebSockets: https://docs.spring.io/spring-framework/reference/web/webflux-websocket.html
- Spring Framework, Asynchronous Requests: https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html
- Spring Framework, WebMvc.fn: https://docs.spring.io/spring-framework/reference/web/webmvc-functional.html
- Spring Security, WebSocket Security: https://docs.spring.io/spring-security/reference/servlet/integrations/websocket.html
- Spring Integration, WebSocket Support: https://docs.spring.io/spring-integration/reference/web-sockets.html
- Spring Cloud Gateway, Global Filters: https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/global-filters.html
- Jakarta EE Tutorial, WebSocket: https://jakarta.ee/learn/docs/jakartaee-tutorial/current/web/websocket/websocket.html
- Quarkus, WebSockets Next reference (about the first 100,000 characters): https://quarkus.io/guides/websockets-next-reference
- Pokhvalenko, Static Analysis of Microservices using GraalVM, Excel@FIT 2025: https://excel.fit.vutbr.cz/submissions/2025/021/21.pdf
- Open-source code, fetched raw from GitHub: rstoyanchev/spring-websocket-portfolio (`WebSocketConfig`, `WebSocketSecurityConfig`, `QuoteService`, `TradeServiceImpl`, `PortfolioController`); jhipster/jhipster-sample-app-websocket (`WebsocketConfiguration`, `WebsocketSecurityConfiguration`, `ActivityService`); thingsboard/thingsboard (`WebSocketConfiguration`, `TbWebSocketHandler`, `ThingsboardSecurityConfiguration`); cjmalloy/jasper (`WebSocketConfig`, `Stomp`); dzhw/metadatamanagement (`WebSocketConfig`); jorgeacetozi/ebook-chat-app-spring-websocket-cassandra-redis-rabbitmq (`WebSocketConfigSpringSession`); Sathvik-Rao/ClipCascade (`StompWebSocketConfig`); The-5th-PNU-Convergence-SW-Hackathon/PNUSW-2024-team-08 (`WebSocketConfig`); BCSDLab/KOIN_API_V2 (`SubscribeInterceptor`); nagisa77/OpenIsle (`WebSocketAuthInterceptor`); xkcoding/spring-boot-demo (netty-socketio and STOMP demos); dnwwdwd/Lingxi-BI (`SSEManager`, `SSEController`, `BIMessageConsumerAdmin`); halo-dev/halo (`WebSocketEndpoint`, `WebSocketHandlerMapping`, `WebFluxConfig`); mqttsnet/thinglinks (`WsDeviceHandshakeConfigurator`, `WebSocketDeviceOpenAccessProtocolEndpoint`).

## Sources not read or read only in part (carry no weight)

- netty-socketio's README (a feature list only): https://github.com/mrniko/netty-socketio
- Spring WebFlux reactive core page (short SSE notes only): https://docs.spring.io/spring-framework/reference/web/webflux/reactive-spring.html
- Schneider et al., Comparison of Static Analysis Architecture Recovery Tools for Microservice Applications, abstract only: https://arxiv.org/abs/2412.08352
- GitHub code search listings, used only to find the projects above.

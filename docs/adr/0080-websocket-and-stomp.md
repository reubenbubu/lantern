# 0080: WebSocket and STOMP endpoints and messages

Status: accepted (#262, part of 2B), pending the user's review (#24)

## Context

project-4's notification-service pushes notifications to the browser over STOMP at
`/ws-notifications`: a RabbitMQ listener receives a notification and `SimpMessagingTemplate` pushes
it to subscribers; an interceptor checks CONNECT and SUBSCRIBE. None of it was extracted
(`org.springframework.messaging.simp` was not tracked), so the flow stopped at the queue, and the
gateway's `lb:ws://` route (#257) had no endpoint to land on. The design (Static analysis and
extractors) lists extractors; this adds one.

## Decisions

- **A WebSocket extractor** (`io.lantern.extract.websocket`, origin `websocket`, vocabulary source
  `websocket-extractor`), applied after the messaging extractor. It reads the code as the others do
  (javac with the build's classpath, else parsed), through the tracked calls on
  `org.springframework.web.socket.config.annotation.*` and `org.springframework.messaging.simp.*`.
- **Facts**, attributes with a string object on a method, evidence the call or annotation:
  - `websocket-endpoint`: the path, on the method that registers it (`addEndpoint` of a
    `StompEndpointRegistry`, `addHandler` of a `WebSocketHandlerRegistry`). `protocol` (`stomp` or
    `websocket`), `sockjs`, `nativeWebSocket`, `allowedOrigins`, and for a raw handler its `handler`
    type. A path registered twice (plain and `withSockJS()`) is one fact. The path is also an
    attribute named `path`, as on `endpoint`, so **the gateway route linker (#257) matches a
    route's path predicate and `lb:ws://` uri against `websocket-endpoint` facts by that
    attribute** (the route `/ws-notifications/**` reaches `/ws-notifications`), as it matches HTTP
    routes against `endpoint` facts.
  - `stomp-prefix`: one per prefix of `enableSimpleBroker`, `enableStompBrokerRelay`,
    `setApplicationDestinationPrefixes` and `setUserDestinationPrefix`, with `kind`.
  - `stomp-mapping`, a child of `entry-point` so flows can start at it: `@MessageMapping` and
    `@SubscribeMapping` methods; the object is the class's mapping joined with the method's,
    without the application prefix. `sendTo` and `sendToUser` from the annotations. A framework
    annotation that carries `@MessageMapping` as a meta-annotation (`@RabbitListener`,
    `@KafkaListener`) does not count; an application's own annotation does.
  - `stomp-send`: `convertAndSend`, `send` (two or more arguments, the first the destination) and
    `convertAndSendToUser` (the destination is the second argument, `toUser`) on
    `SimpMessagingTemplate` and `SimpMessageSendingOperations`. Constants are folded; a format
    (`"/topic/orgs/%d".formatted(id)`, `String.format`) or a part that is not a constant is written
    `{}` with `dynamic: true`; a field given one value everywhere is followed. A destination that
    cannot be told (a parameter) gives no fact.
  - `stomp-interceptor`: the type given to `ChannelRegistration.interceptors` in
    `configureClientInboundChannel` or `configureClientOutboundChannel`, with `channel` and, when
    its class is in the analyzed sources, the `ChannelInterceptor` methods it declares.
- **Composition.** Sequence flows (ADR 0047) draw a `stomp-send` as a note over the unit, after the
  steps of the method that pushes ("notification-service pushes to the STOMP subscribers of
  /topic/notifications, connected over /ws-notifications"), citing the send and the unit's STOMP
  endpoints. A push counts as leaving the unit, so an entry point that reaches nothing else but
  pushes still makes a flow. The subscribers are browsers that no fact of the code names (a frontend extractor,
  #267, may), so there is no participant to draw a message to. `stomp-mapping` also starts flows.
  The catalog lists WebSocket endpoints and STOMP handlers with the other entry points; the LLM's
  fact sheets and describer inputs include the new facts.
- **Not covered:** placeholders in paths or prefixes (not resolved), the commands an interceptor
  checks (the source is not kept after the analysis), `@SendTo` return values as sends in flows,
  clients that subscribe (frontends, #267), Spring Integration and Jakarta WebSocket
  (`@ServerEndpoint`).
- **Tests.** The `websocket` fixture, parsed (golden) and through javac with stand-ins; the same
  facts both ways. `Project4WebSocketIT` reads project-4's Notification-Service when
  `lantern.test.project4` names the folder `testsets/project-4/setup.sh` set up.

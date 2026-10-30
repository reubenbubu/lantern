# 0080: WebSocket and STOMP endpoints and messages

Status: accepted (#262, part of 2B), pending the user's review (#24)

Amends ADR 0077 (a WebSocket route names the endpoint it reaches).

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
    attribute named `path`, as on `endpoint`, which the gateway route links use (below).
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
  A flow that starts at a `stomp-mapping` is titled "STOMP message to /chat/say on chat" (or
  "STOMP subscription to ..." for `@SubscribeMapping`). In the component view, a class with a
  `stomp-mapping` ("STOMP message handler") or that registers a `websocket-endpoint` ("STOMP
  WebSocket endpoint", "WebSocket endpoint") is an entry point, so a gateway route that names the
  endpoint (below) is drawn to it.
  The catalog lists WebSocket endpoints and STOMP handlers with the other entry points; the LLM's
  fact sheets and describer inputs include the new facts.
- **Gateway routes to a WebSocket endpoint.** A gateway route whose URI is `ws://`, `wss://` or
  `lb:ws://` (ADR 0077) is linked to its unit as before; then the route's paths, as the unit sees
  them (through `StripPrefix` and `PrefixPath`) and behind the unit's servlet prefix, are matched
  against the unit's `websocket-endpoint` paths as ADR 0077 matches them against endpoints (the
  route `/ws-notifications/**` reaches `/ws-notifications`). When one endpoint is reached, the
  `CALLS_HTTP` link names its path in `endpoint` and its registering method in `target`, so the
  component view draws the route to the component that registers it, and cites its fact; when
  several are, it lists their paths in `endpoints`. A route that rewrites the path otherwise names
  none. `CrossRepoLinks` is version 8.
- **Not covered:** placeholders in paths or prefixes (not resolved), the commands an interceptor
  checks (the source is not kept after the analysis), `@SendTo` return values as sends in flows,
  clients that subscribe (frontends, #267), Spring Integration and Jakarta WebSocket
  (`@ServerEndpoint`); `convertAndSend(payload, postProcessor)`, whose first argument is read as
  the destination; an endpoint registration split over statements (its `withSockJS()` is not
  seen); and the profiles under which an endpoint is registered, in a push's note.
- **Tests.** The `websocket` fixture, parsed (golden) and through javac with stand-ins; the same
  facts both ways. `Project4WebSocketIT` reads project-4's Notification-Service when
  `lantern.test.project4` names the folder `testsets/project-4/setup.sh` set up.

package io.lantern.extract.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.repo.Repo;
import io.lantern.core.vocabulary.Vocabulary;
import io.lantern.extract.Fixtures;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.java.JavaAnalysis;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.sandbox.BuildModule;
import io.lantern.sandbox.BuildResult;
import io.lantern.sandbox.BuildStatus;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that need no Docker, on the {@code websocket} fixture: golden facts with it parsed
 * (degraded mode), the cases, and the same facts with it read through javac, with stand-ins for
 * Spring's WebSocket and Messaging.
 */
class WebSocketExtractorTest {

  private static final long SNAPSHOT = 5;
  private static final String LIVE = "method:com.example.live.";

  @TempDir Path temp;

  private static List<Fact> extract(List<RepoBuild> inputs) throws IOException {
    List<Fact> facts = new WebSocketExtractor().extract(inputs, SNAPSHOT);
    Fixtures.checkEnvelope(
        facts,
        inputs.stream().map(RepoBuild::repo).toList(),
        SNAPSHOT,
        WebSocketExtractor.NAME,
        WebSocketExtractor.VERSION);
    assertThat(new WebSocketExtractor().extract(inputs, SNAPSHOT)).isEqualTo(facts);
    return facts;
  }

  private Repo fixture() throws IOException {
    return Fixtures.repo("websocket", temp.resolve("websocket"), "websocket");
  }

  @Test
  void parsed() throws IOException {
    Repo repo = fixture();
    List<Fact> facts = extract(List.of(new RepoBuild(repo, Fixtures.head(repo), null)));

    Fixtures.assertGolden("websocket/websocket-parsed.txt", facts);
  }

  @Test
  void cases() throws IOException {
    Repo repo = fixture();
    List<String> facts =
        Fixtures.render(extract(List.of(new RepoBuild(repo, Fixtures.head(repo), null))))
            .lines()
            .toList();

    assertThat(facts)
        // A path registered plain and with SockJS is one endpoint, offering both.
        .anyMatch(
            l ->
                l.contains(" websocket-endpoint /ws-live ")
                    && l.contains("\"sockjs\":true")
                    && l.contains("\"nativeWebSocket\":true")
                    && l.contains("\"allowedOrigins\":[\"*\"]"))
        // SockJS only, several paths in one call.
        .anyMatch(
            l ->
                l.contains(" websocket-endpoint /ws-other ")
                    && l.contains("\"sockjs\":true")
                    && l.contains("\"nativeWebSocket\":false"))
        .anyMatch(l -> l.contains(" websocket-endpoint /ws-sockjs-only "))
        // A raw handler, its type followed from the field.
        .anyMatch(
            l ->
                l.contains(" websocket-endpoint /raw ")
                    && l.contains("\"protocol\":\"websocket\"")
                    && l.contains("\"handler\":\"org.springframework.web.socket.WebSocketHandler\"")
                    && l.contains("\"allowedOrigins\":[\"https://example.com\"]"))
        // The broker's prefixes.
        .anyMatch(l -> l.contains(" stomp-prefix /topic ") && l.contains("simple-broker"))
        .anyMatch(l -> l.contains(" stomp-prefix /queue ") && l.contains("simple-broker"))
        .anyMatch(l -> l.contains(" stomp-prefix /app ") && l.contains("\"application\""))
        .anyMatch(l -> l.contains(" stomp-prefix /user ") && l.contains("\"user\""))
        // Interceptors: one from a field, with the methods its class declares; one created in
        // place on the outbound channel.
        .anyMatch(
            l ->
                l.startsWith(LIVE + "StompConfig#configureClientInboundChannel")
                    && l.contains(" stomp-interceptor com.example.live.AuthInterceptor ")
                    && l.contains("\"channel\":\"inbound\"")
                    && l.contains("\"intercepts\":[\"preSend\"]"))
        .anyMatch(
            l ->
                l.contains(" stomp-interceptor com.example.live.AuditInterceptor ")
                    && l.contains("\"channel\":\"outbound\"")
                    && l.contains("\"intercepts\":[\"afterSendCompletion\",\"postSend\"]"))
        // Handlers: the class mapping joined with the method's, with where the reply goes.
        .anyMatch(
            l ->
                l.startsWith(LIVE + "ChatController#say(java.lang.String) stomp-mapping /chat/say ")
                    && l.contains("\"sendTo\":[\"/topic/chat\"]"))
        .anyMatch(
            l ->
                l.contains(" stomp-mapping /chat/whisper ")
                    && l.contains("\"sendToUser\":[\"/queue/whispers\"]"))
        .anyMatch(
            l ->
                l.contains(" stomp-mapping /chat/history ") && l.contains("\"kind\":\"subscribe\""))
        // The application's own annotation carrying @MessageMapping counts (it has a class prefix
        // of /chat); a framework annotation that carries it does not.
        .anyMatch(
            l ->
                l.startsWith(LIVE + "ChatController#custom(")
                    && l.contains(" stomp-mapping /chat/live ")
                    && l.contains("\"annotation\":\"com.example.live.Live\""))
        .noneMatch(l -> l.startsWith(LIVE + "ChatController#activated("))
        .noneMatch(l -> l.startsWith(LIVE + "ChatController#notMapped("))
        // Sends: a constant, a format, a concatenation, a constant field.
        .anyMatch(
            l ->
                l.startsWith(LIVE + "Pusher#toUser(")
                    && l.contains(" stomp-send /topic/notifications ")
                    && l.contains("\"toUser\":true"))
        .anyMatch(
            l ->
                l.startsWith(LIVE + "Pusher#toOrganization(")
                    && l.contains(" stomp-send /topic/organizations/{}/notifications ")
                    && l.contains("\"dynamic\":true"))
        .anyMatch(
            l ->
                l.startsWith(LIVE + "Pusher#toRoom(") && l.contains(" stomp-send /topic/rooms/{} "))
        .anyMatch(
            l -> l.startsWith(LIVE + "Pusher#toAudit(") && l.contains(" stomp-send /topic/audit "))
        // A destination the caller chooses is not guessed, and the default destination is not read.
        .noneMatch(l -> l.startsWith(LIVE + "Pusher#toChosen("))
        .noneMatch(l -> l.startsWith(LIVE + "Pusher#toDefault("));
  }

  @Test
  void interceptorClassesRelaysHandshakesAndDefaultReplies() throws IOException {
    Repo repo = fixture();
    List<String> facts =
        Fixtures.render(extract(List.of(new RepoBuild(repo, Fixtures.head(repo), null))))
            .lines()
            .toList();

    assertThat(facts)
        // An interceptor class at its declaration, with what its code checks.
        .anyMatch(
            l ->
                l.startsWith(
                        "type:com.example.live.AuthInterceptor stomp-channel-interceptor"
                            + " com.example.live.AuthInterceptor ")
                    && l.contains("\"readsDestination\":true")
                    && l.contains("\"setsUser\":true")
                    && l.contains("\"headers\":[\"Authorization\"]")
                    && l.contains(
                        "\"destinations\":[\"^/topic/organizations/(\\\\d+)/notifications$\"]")
                    && l.contains("AuthInterceptor.java#10-12 "))
        // Through the application's own base class, registered or not.
        .anyMatch(
            l ->
                l.startsWith("type:com.example.live.TenantInterceptor stomp-channel-interceptor ")
                    && l.contains("\"intercepts\":[\"postSend\"]"))
        .anyMatch(l -> l.startsWith("type:com.example.live.AuditInterceptor "))
        // A broker relay's connection, a placeholder read through @Value.
        .anyMatch(
            l ->
                l.contains(" stomp-prefix /exchange ")
                    && l.contains("\"kind\":\"broker-relay\"")
                    && l.contains("\"relayHost\":\"${relay.host}\"")
                    && l.contains("\"relayPort\":\"61613\"")
                    && l.contains("\"virtualHost\":\"/live\"")
                    && l.contains("\"userDestinationBroadcast\":\"/topic/unresolved-user\""))
        .anyMatch(l -> l.contains(" stomp-prefix /amq/queue ") && l.contains("relayHost"))
        .noneMatch(l -> l.contains(" stomp-prefix /topic ") && l.contains("relayHost"))
        // Handshake interceptors and handler of an endpoint.
        .anyMatch(
            l ->
                l.contains(" websocket-endpoint /ws-relay ")
                    && l.contains(
                        "\"handshakeInterceptors\":[\"com.example.live.TokenHandshakeInterceptor\"]")
                    && l.contains("\"handshakeHandler\":\"com.example.live.UserHandshakeHandler\""))
        // Spring's default replies: /topic and the destination, or back to the subscriber.
        .anyMatch(
            l ->
                l.contains(" stomp-mapping /echo ")
                    && l.contains("\"sendTo\":[\"/topic/echo\"]")
                    && l.contains("\"defaultSendTo\":true"))
        .anyMatch(
            l -> l.contains(" stomp-mapping /chat/history ") && l.contains("repliesToSubscriber"))
        .anyMatch(
            l ->
                l.contains(" stomp-mapping /chat/say ")
                    && !l.contains("defaultSendTo")
                    && l.contains("\"sendTo\":[\"/topic/chat\"]"))
        // A class's @SendToUser applies to a method that returns a value, not to a void one.
        .anyMatch(
            l ->
                l.contains(" stomp-mapping /rooms/join ")
                    && l.contains("\"sendToUser\":[\"/queue/rooms\"]")
                    && !l.contains("sendTo\""))
        .anyMatch(
            l ->
                l.contains(" stomp-mapping /rooms/ping ")
                    && !l.contains("sendToUser")
                    && !l.contains("sendTo"))
        // A bare @SendToUser: the user's /queue and the destination, never /topic.
        .anyMatch(
            l ->
                l.contains(" stomp-mapping /echo/me ")
                    && l.contains("\"sendToUser\":[\"/queue/echo/me\"]")
                    && l.contains("\"defaultSendTo\":true")
                    && !l.contains("\"sendTo\""))
        // Spring picks one annotation, preferring one that names destinations: the class's
        // @SendToUser over the method's bare one, the method's @SendTo over the class's.
        .anyMatch(
            l ->
                l.contains(" stomp-mapping /rooms/ack ")
                    && l.contains("\"sendToUser\":[\"/queue/rooms\"]")
                    && !l.contains("defaultSendTo"))
        .anyMatch(
            l ->
                l.contains(" stomp-mapping /rooms/announce ")
                    && l.contains("\"sendTo\":[\"/topic/rooms\"]")
                    && !l.contains("sendToUser"))
        // ExecutorChannelInterceptor, SimpMessageHeaderAccessor and getNativeHeader.
        .anyMatch(
            l ->
                l.startsWith("type:com.example.live.PresenceInterceptor stomp-channel-interceptor ")
                    && l.contains("\"headers\":[\"X-Tenant\"]"))
        .anyMatch(
            l ->
                l.contains(" stomp-prefix /exchange ")
                    && l.contains("\"userRegistryBroadcast\":\"/topic/user-registry\""))
        // Handshake interceptors on the SockJS service.
        .anyMatch(
            l ->
                l.contains(" websocket-endpoint /ws-relay-sockjs ")
                    && l.contains(
                        "\"handshakeInterceptors\":[\"com.example.live.TokenHandshakeInterceptor\"]"))
        // A template injected as the generic MessageSendingOperations sends too.
        .anyMatch(
            l ->
                l.startsWith(LIVE + "RoomController#join(")
                    && l.contains(" stomp-send /topic/rooms/joined ")
                    && l.contains("org.springframework.messaging.core.MessageSendingOperations"));
  }

  @Test
  void noDefaultReplyWithoutAStompBroker() throws IOException {
    // RSocket controllers use @MessageMapping too; without a STOMP broker or endpoint configured,
    // no reply is assumed.
    Repo repo =
        Fixtures.repo(
            temp.resolve("rsocket"),
            "rsocket",
            java.util.Map.of(
                "pom.xml",
                "<project><modelVersion>4.0.0</modelVersion><groupId>com.example</groupId>"
                    + "<artifactId>rsocket</artifactId><version>1</version></project>",
                "src/main/java/com/example/rs/Orders.java",
                """
                package com.example.rs;

                import org.springframework.messaging.handler.annotation.MessageMapping;
                import org.springframework.stereotype.Controller;

                @Controller
                public class Orders {
                  @MessageMapping("orders.get")
                  public String get(String id) {
                    return id;
                  }
                }
                """));
    List<String> facts =
        Fixtures.render(extract(List.of(new RepoBuild(repo, Fixtures.head(repo), null))))
            .lines()
            .toList();

    assertThat(facts)
        .anyMatch(l -> l.contains(" stomp-mapping orders.get "))
        .noneMatch(l -> l.contains("defaultSendTo") || l.contains("sendTo"));
  }

  @Test
  void compiledGivesTheSameFactsAsParsed() throws IOException {
    Repo repo = fixture();
    String commit = Fixtures.head(repo);
    RepoBuild built = new RepoBuild(repo, commit, build(temp, repo, commit));

    JavaAnalysis code = JavaExtractor.analyze(List.of(built));
    List<Fact> compiled = new WebSocketExtractor().extract(code, SNAPSHOT);
    List<Fact> parsed =
        new WebSocketExtractor().extract(List.of(new RepoBuild(repo, commit, null)), SNAPSHOT);

    assertThat(code.modules()).singleElement().matches(JavaAnalysis.Module::compiled);
    assertThat(withoutConfidence(compiled)).isNotEmpty().isEqualTo(withoutConfidence(parsed));
    assertThat(Fixtures.render(compiled).lines()).allMatch(line -> line.endsWith(" 1.0"));
  }

  @Test
  void vocabularyFitsOnTheSpringTypes() {
    Vocabulary vocabulary = Fixtures.vocabulary();

    // A STOMP handler is an entry point, as flows find them.
    assertThat(vocabulary.isA(WebSocketExtractor.MAPPING, "entry-point")).isTrue();
  }

  private static List<String> withoutConfidence(List<Fact> facts) {
    return Fixtures.render(facts).lines().map(l -> l.replaceAll(" [01]\\.[07]$", "")).toList();
  }

  /** A successful build of the one-module fixture with Lantern's test classpath and stand-ins. */
  private static BuildResult build(Path temp, Repo repo, String commit) throws IOException {
    List<String> classpath =
        new ArrayList<>(
            Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(entry -> entry.endsWith(".jar"))
                .toList());
    classpath.add(compileStubs(temp).toString());
    BuildModule module =
        new BuildModule(
            "", "com.example.live", "live", "jar", "1.0.0", List.of(), classpath, null, null, null);
    Path directory = Files.createDirectories(temp.resolve("builds").resolve(repo.name()));
    return new BuildResult(
        repo.name(),
        commit,
        BuildStatus.SUCCEEDED,
        null,
        21,
        "test",
        "test",
        0,
        Instant.EPOCH,
        Duration.ZERO,
        null,
        List.of(module),
        directory);
  }

  private static Path compileStubs(Path temp) throws IOException {
    List<Path> sources = new ArrayList<>();
    for (String stubs : List.of("websocket-stubs", "messaging-stubs")) {
      try (Stream<Path> files = Files.walk(Fixtures.fixture(stubs))) {
        files.filter(f -> f.toString().endsWith(".java")).forEach(sources::add);
      }
    }
    Path classes = Files.createDirectories(temp.resolve("stub-classes"));
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null)) {
      boolean ok =
          compiler
              .getTask(
                  null,
                  files,
                  null,
                  List.of("-d", classes.toString()),
                  null,
                  files.getJavaFileObjectsFromPaths(sources))
              .call();
      assertThat(ok).as("the stand-ins compile").isTrue();
    }
    return classes;
  }
}

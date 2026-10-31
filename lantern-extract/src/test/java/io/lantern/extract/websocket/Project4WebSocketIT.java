package io.lantern.extract.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.repo.Git;
import io.lantern.core.repo.Repo;
import io.lantern.core.repo.RepoSettings;
import io.lantern.extract.Fixtures;
import io.lantern.extract.RepoBuild;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The WebSocket and STOMP facts of project-4's Notification-Service (testsets/project-4), read
 * parsed from git without a build. Runs only when {@code lantern.test.project4} names the folder
 * {@code testsets/project-4/setup.sh} set the repositories up in.
 */
@EnabledIfSystemProperty(named = "lantern.test.project4", matches = ".+")
class Project4WebSocketIT {

  @Test
  void notificationServicePushesOverStomp() throws IOException {
    Path path =
        Path.of(System.getProperty("lantern.test.project4")).resolve("Notification-Service");
    Repo repo = new Repo("Notification-Service", path.toRealPath(), RepoSettings.DEFAULT);
    Git.Result head = Git.run(repo.path(), "rev-parse", "HEAD");
    assertThat(head.ok()).isTrue();
    List<Fact> facts =
        Sends.resolved(
            new WebSocketExtractor().extract(List.of(new RepoBuild(repo, head.out(), null)), 1), 1);
    Fixtures.checkEnvelope(
        facts, List.of(repo), 1, WebSocketExtractor.NAME, WebSocketExtractor.VERSION);
    List<String> lines = Fixtures.render(facts).lines().toList();
    String config = "method:com.crm.notification.notification_service.config.WebSocketsConfig#";
    String sender = "method:com.crm.notification.notification_service.service.NotificationSender#";

    assertThat(lines)
        // /ws-notifications, plain and with SockJS, is one endpoint.
        .anyMatch(
            l ->
                l.startsWith(config + "registerStompEndpoints(")
                    && l.contains(" websocket-endpoint /ws-notifications ")
                    && l.contains("\"sockjs\":true")
                    && l.contains("\"nativeWebSocket\":true"))
        .anyMatch(l -> l.startsWith(config) && l.contains(" stomp-prefix /topic "))
        .anyMatch(l -> l.startsWith(config) && l.contains(" stomp-prefix /app "))
        .anyMatch(l -> l.startsWith(config) && l.contains(" stomp-prefix /user "))
        // The interceptor that checks CONNECT and SUBSCRIBE.
        .anyMatch(
            l ->
                l.startsWith(config + "configureClientInboundChannel(")
                    && l.contains(
                        " stomp-interceptor com.crm.notification.notification_service.websocket"
                            + ".StompAuthChannelInterceptor ")
                    && l.contains("\"intercepts\":[\"preSend\"]"))
        // The interceptor class itself, at its declaration, with what its code reads (the header's
        // name is a library constant, folded only when the code is read through javac).
        .anyMatch(
            l ->
                l.startsWith(
                        "type:com.crm.notification.notification_service.websocket"
                            + ".StompAuthChannelInterceptor stomp-channel-interceptor ")
                    && l.contains("\"readsDestination\":true")
                    && l.contains("\"setsUser\":true")
                    && l.contains("/topic/organizations/")
                    && l.contains("StompAuthChannelInterceptor.java#27-30 "))
        // The pushes: to one user, and to an organization's topic.
        .anyMatch(
            l ->
                l.startsWith(sender + "sendMessageToUser(")
                    && l.contains(" stomp-send /topic/notifications ")
                    && l.contains("\"toUser\":true"))
        .anyMatch(
            l ->
                l.startsWith(sender + "sendMessageToOrganization(")
                    && l.contains(" stomp-send /topic/organizations/{}/notifications "));
  }
}

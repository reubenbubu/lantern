package io.lantern.link;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.repo.Git;
import io.lantern.core.repo.Repo;
import io.lantern.core.repo.RepoSettings;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.gateway.GatewayExtractor;
import io.lantern.extract.http.HttpClientExtractor;
import io.lantern.extract.java.JavaAnalysis;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.extract.maven.MavenExtractor;
import io.lantern.extract.spring.SpringExtractor;
import io.lantern.extract.websocket.WebSocketExtractor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The gateway routes of the development test sets project-1, project-2 and project-4 (#251), read
 * without builds (degraded mode), against their references: every route the reference draws is a
 * gateway-to-service line with its protocol, and project-4's unrouted endpoint is recorded (#257),
 * and its WebSocket route names the STOMP endpoint it reaches (#262). Runs only when {@code
 * lantern.test.projects} names the folder the sets were set up in ({@code
 * testsets/project-N/setup.sh}, each repository at its pin). Writes the links it finds to {@code
 * target/gateway-routes/}.
 */
@EnabledIfSystemProperty(named = "lantern.test.projects", matches = ".+")
class GatewayRoutesIT {

  @Test
  void project1RoutesByPort() throws IOException {
    List<String> lines = routes("project-1");

    assertThat(lines)
        .contains(
            "service:com.example:gateway CALLS_HTTP service:com.example:profile-service"
                + " route=profile-service HTTP port",
            "service:com.example:gateway CALLS_HTTP service:com.example:order-service"
                + " route=order-service HTTP port",
            "service:com.example:gateway CALLS_HTTP service:com.example:keycloak-admin-service"
                + " route=admin-service HTTP port");
    // The blocking route answers itself.
    assertThat(lines).noneMatch(l -> l.contains("route=block-internal-registration"));
  }

  @Test
  void project2RoutesByPort() throws IOException {
    List<String> lines = routes("project-2");

    for (String[] route :
        List.of(
            new String[] {"auth-service", "auth-service"},
            new String[] {"auth-service-oauth2", "auth-service"},
            new String[] {"event-service", "event-service"},
            new String[] {"venue-service", "venue-service"},
            new String[] {"attendee-service", "attendee-service"},
            new String[] {"payment-service", "payment-service"},
            new String[] {"notification-service", "notification-service"})) {
      assertThat(lines)
          .anyMatch(
              l ->
                  l.startsWith("service:com.eventplatform:api-gateway CALLS_HTTP")
                      && l.contains(":" + route[1] + " route=" + route[0] + " HTTP port"));
    }
  }

  @Test
  void project4RoutesByServiceNameAndItsUnroutedPreview() throws IOException {
    List<String> lines = routes("project-4");

    // The units by their Maven coordinates; file-service's artifact is file.
    for (String[] route :
        List.of(
            new String[] {"notifications-service-web-sockets", "notification-service", "WebSocket"},
            new String[] {"main-service-authorize", "main-service", "HTTP"},
            new String[] {"auth-service-authorize", "auth-service", "HTTP"},
            new String[] {"main-service-check-access", "main-service", "HTTP"},
            new String[] {"file-service-check-access-and-file-existence", "file", "HTTP"},
            new String[] {"file-service-check-access", "file", "HTTP"},
            new String[] {"authorization-api", "auth-service", "HTTP"})) {
      assertThat(lines)
          .anyMatch(
              l ->
                  l.contains("api-gateway CALLS_HTTP ")
                      && l.contains(
                          ":"
                              + route[1]
                              + " route="
                              + route[0]
                              + " "
                              + route[2]
                              + " service-name"));
    }
    // The WebSocket route reaches notification-service's STOMP endpoint (#262).
    assertThat(lines)
        .anyMatch(
            l ->
                l.contains(":notification-service route=notifications-service-web-sockets")
                    && l.endsWith(" endpoint=/ws-notifications"));
    assertThat(lines)
        .anyMatch(
            l ->
                l.contains(
                        "api-gateway unrouted-endpoint GET /api/organizations/{organizationId}/preview")
                    && l.contains("main-service"));
  }

  /**
   * The gateway lines of a test set (each route's link, those left open, and the endpoints no route
   * reaches), as {@code subject predicate object route=<id> <protocol> <matchedBy>}, written to
   * {@code target/gateway-routes/<set>.txt} with the gateway's other lines for comparison.
   */
  private static List<String> routes(String set) throws IOException {
    List<RepoBuild> repos = repos(set);
    List<Fact> facts = new ArrayList<>(new MavenExtractor().extract(repos, 1));
    JavaAnalysis code = JavaExtractor.analyze(repos);
    facts.addAll(new JavaExtractor().extract(code, 1));
    facts.addAll(new ConfigExtractor().extract(repos, 1));
    facts.addAll(new SpringExtractor().extract(code, 1));
    facts.addAll(new HttpClientExtractor().extract(code, 1));
    facts.addAll(new GatewayExtractor().extract(code, 1));
    facts.addAll(new WebSocketExtractor().extract(code, 1));
    facts.addAll(new DeployableUnits().derive(facts, 1));
    List<Fact> links = new CrossRepoLinks().derive(facts, 1);
    List<String> lines = new ArrayList<>();
    List<String> others = new ArrayList<>();
    for (Fact f : links) {
      String route = LinkIndex.text(f, "route");
      String line = f.subject() + " " + f.predicate() + " " + f.object().value();
      if (route != null) {
        lines.add(
            line
                + " route="
                + route
                + " "
                + LinkIndex.text(f, "protocol")
                + " "
                + LinkIndex.text(f, "matchedBy")
                + (LinkIndex.text(f, "endpoint") == null
                    ? ""
                    : " endpoint=" + LinkIndex.text(f, "endpoint")));
      } else if (f.predicate().equals(CrossRepoLinks.UNROUTED_ENDPOINT)) {
        lines.add(line + " on " + LinkIndex.text(f, "target") + calledBy(f));
      } else if (f.predicate().equals(CrossRepoLinks.UNRESOLVED_LINK)
          && "route".equals(LinkIndex.text(f, "kind"))) {
        lines.add(line + " " + LinkIndex.text(f, "reason"));
      } else if (f.predicate().equals(CrossRepoLinks.CALLS_HTTP)) {
        others.add(line + " " + LinkIndex.text(f, "protocol") + " " + f.attributes().get("path"));
      }
    }
    Path out = Path.of("target", "gateway-routes");
    Files.createDirectories(out);
    List<String> written = new ArrayList<>(lines);
    written.add("# Other HTTP links");
    written.addAll(others);
    Files.write(out.resolve(set + ".txt"), written);
    return lines;
  }

  private static String calledBy(Fact f) {
    List<String> callers = LinkIndex.texts(f, "calledBy");
    return callers.isEmpty() ? "" : " calledBy=" + String.join(",", callers);
  }

  /** The set's repositories at their pins, without builds. */
  private static List<RepoBuild> repos(String set) throws IOException {
    Path folder = Path.of(System.getProperty("lantern.test.projects")).resolve(set);
    List<RepoBuild> repos = new ArrayList<>();
    for (String line : Files.readAllLines(Path.of("..", "testsets", set, "pins.txt"))) {
      line = line.strip();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      String[] parts = line.split("\\s+");
      Path path = folder.resolve(parts[0]).toRealPath();
      Repo repo = new Repo(parts[0], path, RepoSettings.DEFAULT);
      Git.Result head = Git.run(path, "rev-parse", "HEAD");
      assertThat(head.out())
          .as("%s at its pin (run testsets/%s/setup.sh)", parts[0], set)
          .isEqualTo(parts[2]);
      repos.add(new RepoBuild(repo, parts[2], null));
    }
    return repos;
  }
}

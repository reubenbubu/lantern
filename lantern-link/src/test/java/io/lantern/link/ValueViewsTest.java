package io.lantern.link;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.repo.Repo;
import io.lantern.core.repo.RepoSettings;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.http.HttpClientExtractor;
import io.lantern.extract.messaging.MessagingExtractor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The first link step gives the facts the extractors recorded before they kept values as written
 * (ADR 0094): on the extractors' {@code http} fixture, the {@code http-call} facts it derives from
 * the requests are those the HTTP client extractor resolved itself until #557, its golden file of
 * then.
 */
class ValueViewsTest {

  private static final long SNAPSHOT = 5;

  @TempDir Path temp;

  @Test
  void theRequestsResolveToTheCallsTheHttpClientExtractorRecordedBefore() throws IOException {
    List<RepoBuild> repos = List.of(fixture("http"));
    List<Fact> facts = new ArrayList<>(new ConfigExtractor().extract(repos, SNAPSHOT));
    facts.addAll(new HttpClientExtractor().extract(repos, SNAPSHOT));

    List<Fact> calls = new ValueViews().derive(facts, SNAPSHOT);

    assertThat(calls).allMatch(f -> f.predicate().equals(HttpClientExtractor.HTTP_CALL));
    assertThat(sorted(render(calls))).isEqualTo(sorted(golden("http-calls-parsed.txt")));
    // Run again, the same facts.
    assertThat(new ValueViews().derive(facts, SNAPSHOT)).isEqualTo(calls);
  }

  @Test
  void theGatewayRoutesAreThoseTheGatewayExtractorRecordedBefore() throws IOException {
    List<RepoBuild> repos = List.of(fixture("gateway"));
    List<Fact> facts = new ArrayList<>(new ConfigExtractor().extract(repos, SNAPSHOT));
    facts.addAll(new io.lantern.extract.gateway.GatewayExtractor().extract(repos, SNAPSHOT));

    List<Fact> routes = new ValueViews().derive(facts, SNAPSHOT);

    assertThat(sorted(render(routes))).isEqualTo(sorted(golden("gateway-routes-parsed.txt")));
  }

  @Test
  void theMessagingFactsAreThoseTheMessagingExtractorRecordedBefore() throws IOException {
    List<RepoBuild> repos = List.of(fixture("messaging"));
    List<Fact> facts = new ArrayList<>(new ConfigExtractor().extract(repos, SNAPSHOT));
    facts.addAll(new MessagingExtractor().extract(repos, SNAPSHOT));

    List<Fact> messages = new ValueViews().derive(facts, SNAPSHOT);

    assertThat(sorted(render(messages))).isEqualTo(sorted(golden("messages-parsed.txt")));
    assertThat(String.join("\n", render(messages))).doesNotContain("s3cret");
  }

  @Test
  void theSpringCloudStreamBindingsAreThoseTheMessagingExtractorRecordedBefore()
      throws IOException {
    List<RepoBuild> repos = List.of(fixture("messaging-stream", "stream"));
    List<Fact> facts = new ArrayList<>(new ConfigExtractor().extract(repos, SNAPSHOT));
    facts.addAll(new MessagingExtractor().extract(repos, SNAPSHOT));

    List<Fact> messages = new ValueViews().derive(facts, SNAPSHOT);

    assertThat(sorted(render(messages))).isEqualTo(sorted(golden("stream-messages-parsed.txt")));
    assertThat(String.join("\n", render(messages))).doesNotContain("s3cret");
  }

  @Test
  void theStompSendsAreThoseTheWebSocketExtractorRecordedBefore() throws IOException {
    List<RepoBuild> repos = List.of(fixture("websocket"));
    List<Fact> facts = new ArrayList<>(new ConfigExtractor().extract(repos, SNAPSHOT));
    facts.addAll(new io.lantern.extract.websocket.WebSocketExtractor().extract(repos, SNAPSHOT));

    List<Fact> sends = new ValueViews().derive(facts, SNAPSHOT);

    assertThat(sorted(render(sends))).isEqualTo(sorted(golden("stomp-sends-parsed.txt")));
  }

  @Test
  void datasourcesNameTheirFilesByBlobAsStoredConfigFactsDo() throws IOException {
    List<RepoBuild> repos = List.of(fixture("persistence"));
    String blob = "0123456789abcdef0123456789abcdef01234567";
    List<Fact> facts = new ArrayList<>();
    for (Fact f : new ConfigExtractor().extract(repos, SNAPSHOT)) {
      // As the store keeps them: the file's blob, no content hash.
      facts.add(
          Fact.create(
              f.subject(),
              f.predicate(),
              f.object(),
              f.attributes(),
              f.origin(),
              f.evidence().map(l -> l.withBlob(blob)),
              f.confidence(),
              f.validity()));
    }
    facts.addAll(
        new io.lantern.extract.persistence.PersistenceExtractor().extract(repos, SNAPSHOT));

    List<Fact> datasources = new ValueViews().derive(facts, SNAPSHOT);

    assertThat(datasources).hasSize(golden("datasources-parsed.txt").size());
    assertThat(datasources)
        .allMatch(f -> blob.equals(f.evidence().blob()) && f.evidence().contentHash() == null);
  }

  @Test
  void theDatasourcesAreThoseThePersistenceExtractorRecordedBefore() throws IOException {
    List<RepoBuild> repos = List.of(fixture("persistence"));
    List<Fact> facts = new ArrayList<>(new ConfigExtractor().extract(repos, SNAPSHOT));
    facts.addAll(
        new io.lantern.extract.persistence.PersistenceExtractor().extract(repos, SNAPSHOT));

    List<Fact> datasources = new ValueViews().derive(facts, SNAPSHOT);

    // The facts of before, and the datasource entities with their addresses (#560).
    assertThat(sorted(render(datasources))).isEqualTo(sorted(golden("datasources-parsed.txt")));
    assertThat(String.join("\n", render(datasources)))
        .doesNotContain("s3cret", "hunter2", "tiger", "scott", "user=sa", "shop@");
    // The evidence names the config files by blob, as the stored config facts do.
    assertThat(datasources)
        .allMatch(
            f ->
                f.evidence().declared().contentHash() != null
                    || f.evidence().declared().blob() != null);
  }

  /** Moved from the gateway extractor's tests with #558, unchanged. */
  @Test
  void zuulRoutesAndTheDiscoveryLocator() throws IOException {
    List<RepoBuild> repos = List.of(fixture("gateway"));
    List<Fact> facts = new ArrayList<>(new ConfigExtractor().extract(repos, SNAPSHOT));
    facts.addAll(new io.lantern.extract.gateway.GatewayExtractor().extract(repos, SNAPSHOT));
    List<String> lines = render(new ValueViews().derive(facts, SNAPSHOT));
    String legacy = "module:com.example.edge:legacy gateway-route ";
    assertThat(lines)
        // A service ID: load balanced, behind the global prefix, both prefixes stripped.
        .anyMatch(
            l ->
                l.startsWith(legacy + "accounts ")
                    && l.contains("\"framework\":\"zuul\"")
                    && l.contains("\"uri\":\"lb://account-service\"")
                    && l.contains("\"paths\":[\"/api/accounts/**\"]")
                    && l.contains("\"filters\":[\"StripPrefix=2\"]"))
        // A URL, keeping its own prefix (strip-prefix: false) but not the global one.
        .anyMatch(
            l ->
                l.startsWith(legacy + "auth ")
                    && !l.contains("profile")
                    && l.contains("\"uri\":\"http://auth-service:5000\"")
                    && l.contains("\"filters\":[\"StripPrefix=1\"]"))
        .anyMatch(
            l ->
                l.startsWith(legacy + "auth ")
                    && l.contains("\"profile\":\"local\"")
                    && l.contains("\"uri\":\"http://localhost:5000\""))
        // The shorthand form: the route's name is its service.
        .anyMatch(
            l ->
                l.startsWith(legacy + "statistics ")
                    && l.contains("\"uri\":\"lb://statistics\"")
                    && l.contains("\"paths\":[\"/api/stats/v1/**\"]")
                    && l.contains("\"filters\":[\"StripPrefix=3\"]"))
        // No path: /<name>/**.
        .anyMatch(
            l ->
                l.startsWith(legacy + "notifications ")
                    && l.contains("\"uri\":\"lb://notification-service\"")
                    && l.contains("\"paths\":[\"/api/notifications/**\"]"))
        // The discovery locator under the server.webflux prefix, with its settings.
        .anyMatch(
            l ->
                l.startsWith(
                        "module:com.example.edge:finder gateway-discovery-locator 'lb://'+serviceId ")
                    && l.contains("\"lowerCaseServiceId\":true")
                    && l.contains("\"includeExpression\":\"serviceId != 'finder'\"")
                    && l.contains("finder/src/main/resources/application.yml#10-12 "))
        // A profile that replaces its URL expression and filters.
        .anyMatch(
            l ->
                l.startsWith(
                        "module:com.example.edge:finder gateway-discovery-locator 'http://'+serviceId ")
                    && l.contains("\"filters\":[\"StripPrefix=1\"]")
                    && l.contains("\"profile\":\"custom\""))
        // A path without a wildcard strips nothing, and the global prefix is kept.
        .anyMatch(
            l ->
                l.startsWith("module:com.example.edge:classic gateway-route health ")
                    && l.contains("\"uri\":\"lb://health-service\"")
                    && l.contains("\"paths\":[\"/v2/health\"]")
                    && !l.contains("StripPrefix"))
        // url wins over serviceId; only the route's own prefix is stripped.
        .anyMatch(
            l ->
                l.startsWith("module:com.example.edge:classic gateway-route reports ")
                    && l.contains("\"uri\":\"http://reports:8080\"")
                    && l.contains("\"filters\":[\"StripPrefix=1\"]"))
        // The locator under the old prefix, its predicate in name and args form.
        .anyMatch(
            l ->
                l.startsWith("module:com.example.edge:classic gateway-discovery-locator ")
                    && !l.contains("profile")
                    && l.contains("\"predicates\":[\"Path='/svc/'+serviceId+'/**'\"]"))
        // A profile that turns it off says so.
        .anyMatch(
            l ->
                l.startsWith("module:com.example.edge:classic gateway-discovery-locator ")
                    && l.contains("{\"enabled\":false,\"profile\":\"prod\"}"))
        // A gateway without the locator has no such fact.
        .noneMatch(l -> l.startsWith("module:com.example.edge:edge gateway-discovery-locator"));
  }

  @Test
  void configRoutesNameTheirFilesByBlobAsStoredConfigFactsDo() throws IOException {
    List<RepoBuild> repos = List.of(fixture("gateway"));
    String blob = "0123456789abcdef0123456789abcdef01234567";
    List<Fact> facts = new ArrayList<>();
    for (Fact f : new ConfigExtractor().extract(repos, SNAPSHOT)) {
      // As the store keeps them: the file's blob, no content hash.
      facts.add(
          Fact.create(
              f.subject(),
              f.predicate(),
              f.object(),
              f.attributes(),
              f.origin(),
              f.evidence().map(l -> l.withBlob(blob)),
              f.confidence(),
              f.validity()));
    }
    facts.addAll(new io.lantern.extract.gateway.GatewayExtractor().extract(repos, SNAPSHOT));

    List<Fact> routes =
        new ValueViews()
            .derive(facts, SNAPSHOT).stream()
                .filter(f -> f.attributes().path("definedIn").asString("").equals("config"))
                .toList();

    assertThat(routes).isNotEmpty();
    assertThat(routes)
        .allMatch(f -> blob.equals(f.evidence().blob()) && f.evidence().contentHash() == null);
  }

  @Test
  void aConfigRouteUsingASecretSeveralValuesDeepIsMaskedAsBefore() throws IOException {
    RepoBuild repo =
        repo(
            "gateway-deep",
            java.util.Map.of(
                "pom.xml",
                "<project><modelVersion>4.0.0</modelVersion><groupId>com.example</groupId>"
                    + "<artifactId>edge</artifactId><version>1</version></project>\n",
                "src/main/resources/application.properties",
                "svc.token=deep-s3cret-value\n"
                    + "backend.url=http://h/${svc.token}\n"
                    + "spring.cloud.gateway.routes[0].id=deep\n"
                    + "spring.cloud.gateway.routes[0].uri=${backend.url}\n"
                    + "spring.cloud.gateway.routes[0].predicates[0]=Path=/deep/**\n"));

    List<String> routes =
        render(
            new ValueViews()
                .derive(new ConfigExtractor().extract(List.of(repo), SNAPSHOT), SNAPSHOT));

    assertThat(routes)
        .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
        .contains(" gateway-route deep ")
        .contains("\"masked\":true")
        .contains("\"written\":\"${backend.url}\"")
        .doesNotContain("deep-s3cret-value");
  }

  @Test
  void aConfigRouteUsingAKnownSecretKeyIsMasked() throws IOException {
    RepoBuild repo =
        repo(
            "gateway-jwt",
            java.util.Map.of(
                "pom.xml",
                "<project><modelVersion>4.0.0</modelVersion><groupId>com.example</groupId>"
                    + "<artifactId>shop</artifactId><version>1</version></project>\n",
                "src/main/java/com/example/Shop.java",
                "package com.example;\n\npublic class Shop {}\n",
                "src/main/resources/application.properties",
                "jwt.key=literal-test-value-0123456789abcdef\n"
                    + "spring.cloud.gateway.server.webmvc.routes[0].id=tokened\n"
                    + "spring.cloud.gateway.server.webmvc.routes[0].uri=http://edge/${jwt.key}\n"
                    + "spring.cloud.gateway.server.webmvc.routes[0].predicates[0]=Path=/tokened/${jwt.key}/**\n"
                    + "spring.cloud.gateway.server.webmvc.routes[0].filters[0]=AddRequestHeader=X-Token, ${jwt.key}\n"));

    // Without the code's keys the name alone does not show jwt.key is a secret.
    List<Fact> plain = new ConfigExtractor().extract(List.of(repo), SNAPSHOT);
    assertThat(render(new ValueViews().derive(plain, SNAPSHOT)))
        .anyMatch(l -> l.contains("literal-test-value"));
    // With the identity extractor's finding, the config facts and the routes mask it.
    List<Fact> facts =
        new ArrayList<>(
            new ConfigExtractor().extract(List.of(repo), SNAPSHOT, java.util.Set.of("jwt.key")));
    facts.add(jwtSecretKey("jwt.key"));
    assertThat(render(new ValueViews().derive(facts, SNAPSHOT)))
        .noneMatch(l -> l.contains("literal-test-value"))
        .anyMatch(l -> l.contains(" gateway-route tokened "));
  }

  @Test
  void aUrlUsingAKnownSecretKeyIsKeptAsWrittenAndMasked() throws IOException {
    RepoBuild repo =
        repo(
            "jwt",
            java.util.Map.of(
                "pom.xml",
                "<project><modelVersion>4.0.0</modelVersion><groupId>com.example</groupId>"
                    + "<artifactId>shop</artifactId><version>1</version></project>\n",
                "src/main/java/com/example/Remote.java",
                """
                package com.example;

                import org.springframework.cloud.openfeign.FeignClient;
                import org.springframework.web.bind.annotation.GetMapping;

                @FeignClient(name = "remote", url = "http://remote/${jwt.key}")
                public interface Remote {
                  @GetMapping("/things")
                  String things();
                }
                """,
                "src/main/resources/application.properties",
                "jwt.key=literal-test-value-0123456789abcdef\n"));
    List<Fact> facts = new ArrayList<>(new ConfigExtractor().extract(List.of(repo), SNAPSHOT));
    facts.addAll(new HttpClientExtractor().extract(List.of(repo), SNAPSHOT));

    // Without the code's keys the name alone does not show jwt.key is a secret.
    assertThat(render(new ValueViews().derive(facts, SNAPSHOT)))
        .anyMatch(l -> l.contains("literal-test-value"));
    // With the identity extractor's finding that the code signs JWTs with it, it is masked.
    facts.add(jwtSecretKey("jwt.key"));
    assertThat(render(new ValueViews().derive(facts, SNAPSHOT)))
        .noneMatch(l -> l.contains("literal-test-value"))
        .anyMatch(
            l ->
                l.contains("GET http://remote/${jwt.key}/things") && l.contains("\"masked\":true"));
  }

  /** The identity extractor's finding that the code signs JWTs with a config key's value. */
  private static Fact jwtSecretKey(String key) {
    tools.jackson.databind.node.ObjectNode keys = io.lantern.core.fact.FactJson.newObject();
    keys.putArray("keys").add(key);
    return Fact.create(
        io.lantern.core.fact.EntityId.parse("type:com.example.Jwt"),
        "jwt-secret-key",
        io.lantern.core.fact.FactObject.string("hmac"),
        keys,
        new io.lantern.core.fact.Origin(io.lantern.core.fact.Origin.Kind.STATIC, "identity", "1"),
        io.lantern.core.fact.Evidence.of("jwt", "src/main/java/com/example/Jwt.java", null, null),
        1.0,
        io.lantern.core.fact.Validity.from(SNAPSHOT));
  }

  private static List<String> sorted(List<String> lines) {
    return lines.stream().sorted().toList();
  }

  /** The facts as the extractors' golden files write them, one line each. */
  static List<String> render(List<Fact> facts) {
    List<String> lines = new ArrayList<>();
    for (Fact f : facts) {
      String where = f.evidence().repo() + ":" + f.evidence().path();
      if (f.evidence().lines() != null) {
        where += "#" + f.evidence().lines().start() + "-" + f.evidence().lines().end();
      }
      lines.add(
          f.subject()
              + " "
              + f.predicate()
              + " "
              + f.object().value()
              + (f.attributes().isEmpty() ? "" : " " + f.attributes())
              + " @"
              + where
              + " "
              + f.confidence());
    }
    return lines;
  }

  private static List<String> golden(String name) throws IOException {
    try (InputStream in = ValueViewsTest.class.getResourceAsStream("/golden/" + name)) {
      assertThat(in).as(name).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
    }
  }

  /** A git repository holding the given files, committed. */
  private RepoBuild repo(String name, java.util.Map<String, String> files) throws IOException {
    Path target = temp.resolve(name);
    for (java.util.Map.Entry<String, String> file : files.entrySet()) {
      Path path = target.resolve(file.getKey());
      Files.createDirectories(path.getParent());
      Files.writeString(path, file.getValue());
    }
    return commit(target, name);
  }

  /** A git repository holding a copy of one of the extractors' fixtures, committed. */
  private RepoBuild fixture(String name) throws IOException {
    return fixture(name, name);
  }

  /** The extractors' fixture of that name, as a repository of the given name. */
  private RepoBuild fixture(String name, String repo) throws IOException {
    Path source = Path.of(System.getProperty("lantern.test.fixtures")).resolve(name);
    Path target = temp.resolve(repo);
    try (Stream<Path> paths = Files.walk(source)) {
      for (Path from : paths.toList()) {
        Path to = target.resolve(source.relativize(from).toString());
        if (Files.isDirectory(from)) {
          Files.createDirectories(to);
        } else {
          Files.copy(from, to);
        }
      }
    }
    return commit(target, repo);
  }

  private static RepoBuild commit(Path target, String name) throws IOException {
    git(target, "init", "--quiet");
    git(target, "add", "--all");
    git(
        target,
        "-c",
        "user.name=Lantern Test",
        "-c",
        "user.email=test@lantern.invalid",
        "commit",
        "--quiet",
        "--message",
        "Fixture");
    String head = git(target, "rev-parse", "HEAD");
    return new RepoBuild(new Repo(name, target.toRealPath(), RepoSettings.DEFAULT), head, null);
  }

  private static String git(Path dir, String... args) throws IOException {
    List<String> command = new ArrayList<>(List.of("git", "-C", dir.toString()));
    command.addAll(List.of(args));
    Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
    try {
      String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
      if (p.waitFor() != 0) {
        throw new IOException("git " + String.join(" ", args) + ": " + out);
      }
      return out;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(e);
    }
  }
}

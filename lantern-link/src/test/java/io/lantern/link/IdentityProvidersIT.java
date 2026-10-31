package io.lantern.link;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.repo.Git;
import io.lantern.core.repo.Repo;
import io.lantern.core.repo.RepoSettings;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.deployment.DeploymentExtractor;
import io.lantern.extract.identity.IdentityExtractor;
import io.lantern.extract.java.JavaAnalysis;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.extract.maven.MavenExtractor;
import io.lantern.extract.spring.SpringExtractor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The identity providers and shared JWT secrets of the development test sets project-1, project-2
 * and project-4 (#261), read without builds (degraded mode), against their references: project-1's
 * Keycloak is an external system with the lines of the units that use it, project-2's gateway and
 * services fetch auth-service's JWK set, and project-4's gateway and auth-service share their HMAC
 * secret. Runs only when {@code lantern.test.projects} names the folder the sets were set up in
 * ({@code testsets/project-N/setup.sh}, each repository at its pin). Writes what it finds to {@code
 * target/identity-providers/}.
 */
@EnabledIfSystemProperty(named = "lantern.test.projects", matches = ".+")
class IdentityProvidersIT {

  @Test
  void project1UsesKeycloakAsAnExternalSystem() throws IOException {
    List<String> lines = identity("project-1");

    assertThat(lines)
        .contains(
            "service:com.example:bff USES_IDENTITY_PROVIDER external-system:keycloak"
                + " signs-in-users OIDC over HTTP published-port",
            "service:com.example:gateway USES_IDENTITY_PROVIDER external-system:keycloak"
                + " validates-tokens OIDC over HTTP published-port",
            // The issuer comes from common-security's EnvironmentPostProcessor (#333).
            "service:com.example:order-service USES_IDENTITY_PROVIDER external-system:keycloak"
                + " validates-tokens OIDC over HTTP published-port",
            "service:com.example:profile-service USES_IDENTITY_PROVIDER external-system:keycloak"
                + " gets-tokens OAuth2 over HTTP published-port",
            "service:com.example:keycloak-admin-service USES_IDENTITY_PROVIDER"
                + " external-system:keycloak manages-users Keycloak Admin REST over HTTP"
                + " published-port",
            "external-system:keycloak display-name Keycloak",
            "external-system:keycloak deployed-in Docker Compose (default)");
  }

  @Test
  void project2ServicesFetchAuthServicesJwkSet() throws IOException {
    List<String> lines = identity("project-2");

    for (String unit :
        List.of(
            "api-gateway",
            "event-service",
            "venue-service",
            "attendee-service",
            "payment-service",
            "notification-service")) {
      assertThat(lines)
          .anyMatch(
              l ->
                  l.startsWith("service:com.eventplatform:" + unit + " USES_IDENTITY_PROVIDER")
                      && l.contains(
                          ":auth-service validates-tokens JWKS over HTTP port target=method:"));
    }
    assertThat(lines).noneMatch(l -> l.contains("external-system"));
  }

  @Test
  void project4GatewayAndAuthServiceShareTheirSecret() throws IOException {
    List<String> lines = identity("project-4");

    assertThat(lines)
        .anyMatch(
            l ->
                l.contains("auth-service shared-secret app.token.signing.key")
                    && l.contains("basis=placeholder")
                    && l.contains("api-gateway")
                    && l.contains("uses=[sign, verify]"))
        .anyMatch(
            l ->
                l.contains("api-gateway shared-secret app.token.signing.key")
                    && l.contains("basis=placeholder")
                    && l.contains("auth-service")
                    && l.contains("uses=[verify]"));
    assertThat(lines).noneMatch(l -> l.contains("USES_IDENTITY_PROVIDER"));
  }

  /**
   * The identity lines of a test set, as {@code subject predicate object purpose protocol
   * matchedBy}, written to {@code target/identity-providers/<set>.txt}.
   */
  private static List<String> identity(String set) throws IOException {
    List<RepoBuild> repos = repos(set);
    List<Fact> facts = new ArrayList<>(new MavenExtractor().extract(repos, 1));
    JavaAnalysis code = JavaExtractor.analyze(repos);
    facts.addAll(new JavaExtractor().extract(code, 1));
    facts.addAll(new ConfigExtractor().extract(repos, 1));
    facts.addAll(new SpringExtractor().extract(code, 1));
    facts.addAll(new IdentityExtractor().extract(code, repos, 1));
    facts.addAll(new DeploymentExtractor().extract(repos, 1));
    facts.addAll(new DeployableUnits().derive(facts, 1));
    List<String> lines = new ArrayList<>();
    for (Fact f : facts) {
      if (f.predicate().equals(IdentityExtractor.JWT_SECRET_KEY)
          || f.predicate().equals(IdentityExtractor.IDENTITY_ADMIN_CLIENT)) {
        lines.add(f.subject() + " " + f.predicate() + " " + f.object().value());
      }
    }
    for (Fact f : new CrossRepoLinks().derive(facts, 1)) {
      String line = f.subject() + " " + f.predicate() + " " + f.object().value();
      if (f.predicate().equals(CrossRepoLinks.USES_IDENTITY_PROVIDER)) {
        String target = TestAttributes.text(f, "target");
        lines.add(
            line
                + " "
                + TestAttributes.text(f, "purpose")
                + " "
                + TestAttributes.text(f, "protocol")
                + " "
                + TestAttributes.text(f, "matchedBy")
                + (target == null ? "" : " target=" + target)
                + (f.attributes().has("profile")
                    ? " profile=" + TestAttributes.text(f, "profile")
                    : ""));
      } else if (f.predicate().equals(CrossRepoLinks.SHARED_SECRET)) {
        lines.add(
            line
                + " basis="
                + TestAttributes.text(f, "basis")
                + " with="
                + TestAttributes.texts(f, "with")
                + " uses="
                + TestAttributes.texts(f, "uses"));
      } else if (f.subject().type().equals(CrossRepoLinks.EXTERNAL_SYSTEM)) {
        lines.add(line);
      }
    }
    Path out = Path.of("target", "identity-providers");
    Files.createDirectories(out);
    Files.write(out.resolve(set + ".txt"), lines);
    return lines;
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

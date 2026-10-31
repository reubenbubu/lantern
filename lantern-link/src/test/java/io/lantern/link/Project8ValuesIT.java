package io.lantern.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import io.lantern.core.fact.Fact;
import io.lantern.core.repo.Git;
import io.lantern.core.repo.Repo;
import io.lantern.core.repo.RepoSettings;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.deployment.DeploymentExtractor;
import io.lantern.extract.gateway.GatewayExtractor;
import io.lantern.extract.http.HttpClientExtractor;
import io.lantern.extract.java.JavaAnalysis;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.extract.maven.MavenExtractor;
import io.lantern.extract.messaging.MessagingExtractor;
import io.lantern.extract.persistence.PersistenceExtractor;
import io.lantern.extract.spring.SpringExtractor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Value resolution on the test set project-8 (FleetCare), read without builds from its repositories
 * as {@code testsets/project-8/setup.sh} makes them (#588): a deployment's environment is a view,
 * and what #516 fixed holds in every view. Runs only when {@code lantern.test.projects} names the
 * folder that holds {@code project-8}, such as {@code ~/workspace/lantern-test-projects}; needs no
 * Docker.
 */
@EnabledIfSystemProperty(named = "lantern.test.projects", matches = ".+")
class Project8ValuesIT {

  private static final String DEFAULT_COMPOSE =
      "fleetcare-deploy|compose|default|docker-compose.yml|";
  private static final String LOCAL_COMPOSE =
      "fleetcare-deploy|compose|local|docker-compose.local.yml|";

  private static List<Fact> derived;

  @BeforeAll
  static void link() throws IOException {
    Path set = Path.of(System.getProperty("lantern.test.projects")).resolve("project-8");
    List<RepoBuild> repos = new ArrayList<>();
    try (Stream<Path> folders = Files.list(set)) {
      for (Path path :
          folders.filter(p -> Files.isDirectory(p.resolve(".git"))).sorted().toList()) {
        Repo repo =
            new Repo(path.getFileName().toString(), path.toRealPath(), RepoSettings.DEFAULT);
        Git.Result head = Git.run(repo.path(), "rev-parse", "HEAD");
        repos.add(new RepoBuild(repo, head.out().strip(), null));
      }
    }
    List<Fact> facts = new ArrayList<>(new MavenExtractor().extract(repos, 1));
    JavaAnalysis code = JavaExtractor.analyze(repos);
    facts.addAll(new JavaExtractor().extract(code, 1));
    facts.addAll(new ConfigExtractor().extract(repos, 1));
    facts.addAll(new SpringExtractor().extract(code, 1));
    facts.addAll(new HttpClientExtractor().extract(code, 1));
    facts.addAll(new MessagingExtractor().extract(code, 1));
    facts.addAll(new GatewayExtractor().extract(code, 1));
    facts.addAll(new PersistenceExtractor().extract(code, 1));
    facts.addAll(new DeploymentExtractor().extract(repos, 1));
    facts.addAll(new DeployableUnits().derive(facts, 1));
    derived = new ValueResolution().derive(facts, 1);
  }

  @Test
  void aPlaceholderADeploymentSetsResolvesInThatDeploymentsView() {
    // The compose file sets DB_HOST; no config does, so only the deployment resolves it.
    assertThat(resolutions("address:io.fleetcare:workorder-service:${DB_HOST}:5432"))
        .extracting(f -> f.object().value(), f -> TestAttributes.text(f, "deployment"))
        .containsExactly(tuple("address:postgres:5432", DEFAULT_COMPOSE + "workorder-service"));
    // The config's own URL in the default view, the compose service's name in the deployment.
    assertThat(
            resolutions(
                "address:io.fleetcare:customer-portal-bff:${fleetcare.services.technicians-url}"))
        .extracting(f -> f.object().value(), f -> TestAttributes.text(f, "deployment"))
        .containsExactlyInAnyOrder(
            tuple("address:localhost:8082", null),
            tuple("address:technician-service:8082", DEFAULT_COMPOSE + "customer-portal-bff"));
    // A deployment's environment over the profile it makes active: the local compose file runs
    // billing-service with the local profile, whose sandbox differs from the default view's.
    assertThat(resolutions("address:io.fleetcare:billing-service:${paygrid.base-url}"))
        .extracting(
            f -> f.object().value(),
            f -> TestAttributes.text(f, "profile"),
            f -> TestAttributes.text(f, "deployment"))
        .containsExactlyInAnyOrder(
            tuple("address:sandbox.paygrid.example", null, null),
            tuple("address:api.paygrid.example", "prod", null),
            tuple("address:localhost:9443", "local", null),
            tuple("address:localhost:9443", null, LOCAL_COMPOSE + "billing-service"));
    assertThat(resolutions("address:io.fleetcare:billing-service:${paygrid.base-url}"))
        .filteredOn(
            f ->
                TestAttributes.text(f, "deployment") == null
                    && TestAttributes.text(f, "profile") == null)
        .singleElement()
        .satisfies(
            f ->
                assertThat(TestAttributes.texts(f, "exceptDeployments"))
                    .containsExactly(LOCAL_COMPOSE + "billing-service"));
  }

  @Test
  void aProfilesListOfRoutesReplacesTheDefaultsWholeInEveryView() {
    // #516, item 2: the staging profile does not give customer-bff another route's filters.
    List<Fact> routes =
        derived.stream()
            .filter(f -> f.predicate().equals(GatewayExtractor.ROUTE))
            .filter(f -> f.object().value().equals("customer-bff"))
            .toList();
    assertThat(routes).isNotEmpty();
    for (Fact route : routes) {
      assertThat(TestAttributes.texts(route, "filters"))
          .as(TestAttributes.text(route, "profile"))
          .contains("StripPrefix=1")
          .noneMatch(filter -> filter.contains("RequestRateLimiter"));
    }
  }

  @Test
  void anExpressionThatIsNotConfigsResolvesInNoView() {
    // A URL whose start the code computes ({basePath}, #516 item 8) is no deployment's to fill.
    assertThat(derived)
        .filteredOn(f -> f.predicate().equals(ValueResolution.RESOLVES_TO))
        .filteredOn(f -> !f.subject().toString().contains("${"))
        .isEmpty();
  }

  private static List<Fact> resolutions(String entity) {
    return derived.stream()
        .filter(f -> f.predicate().equals(ValueResolution.RESOLVES_TO))
        .filter(f -> f.subject().toString().equals(entity))
        .toList();
  }
}

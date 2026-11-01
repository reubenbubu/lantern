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

  private static List<Fact> extracted;

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
    extracted = facts;
    derived = new ValueResolution().derive(facts, 1);
  }

  @Test
  void aPlaceholderADeploymentSetsResolvesInThatDeploymentsView() {
    // The compose file sets DB_HOST; no config does, so only the deployment resolves it.
    assertThat(resolutions("address:io.fleetcare:workorder-service:${DB_HOST}:5432"))
        .extracting(f -> f.object().value(), f -> TestAttributes.text(f, "view"))
        .containsExactly(tuple("address:postgres:5432", DEFAULT_COMPOSE + "workorder-service"));
    // The config's own URL in the default view, the compose service's name in the deployment.
    assertThat(
            resolutions(
                "address:io.fleetcare:customer-portal-bff:${fleetcare.services.technicians-url}"))
        .extracting(f -> f.object().value(), f -> TestAttributes.text(f, "view"))
        .containsExactlyInAnyOrder(
            tuple("address:localhost:8082", null),
            tuple("address:technician-service:8082", DEFAULT_COMPOSE + "customer-portal-bff"));
    // A deployment's environment over the profile it makes active: the local compose file runs
    // billing-service with the local profile, whose sandbox differs from the default view's.
    assertThat(resolutions("address:io.fleetcare:billing-service:${paygrid.base-url}"))
        .extracting(f -> f.object().value(), f -> TestAttributes.text(f, "view"))
        .containsExactlyInAnyOrder(
            tuple("address:sandbox.paygrid.example", null),
            tuple("address:api.paygrid.example", "prod"),
            tuple("address:localhost:9443", "local"),
            tuple("address:localhost:9443", LOCAL_COMPOSE + "billing-service"));
    // The default view's claim lists every other view, profiles first (#590).
    assertThat(resolutions("address:io.fleetcare:billing-service:${paygrid.base-url}"))
        .filteredOn(f -> TestAttributes.text(f, "view") == null)
        .singleElement()
        .satisfies(
            f ->
                assertThat(TestAttributes.texts(f, "absentIn"))
                    .containsExactly("local", "prod", LOCAL_COMPOSE + "billing-service"));
    // The deployment's literal comes from the profile it activates, and cites its container too.
    Fact local =
        resolutions("address:io.fleetcare:billing-service:${paygrid.base-url}").stream()
            .filter(f -> deployment(f))
            .findFirst()
            .get();
    assertThat(TestAttributes.texts(local, "facts"))
        .anyMatch(
            id ->
                extracted.stream()
                    .anyMatch(
                        f ->
                            f.id().equals(id)
                                && f.predicate().equals(DeploymentExtractor.DEPLOYMENT_CONTAINER)));
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
  void onlyExpressionsWithPlaceholdersResolve() {
    assertThat(derived)
        .filteredOn(f -> f.predicate().equals(ValueResolution.RESOLVES_TO))
        .filteredOn(f -> !f.subject().toString().contains("${"))
        .isEmpty();
  }

  @Test
  void everyDatasourceADeploymentRunsResolvesToItsServerThere() {
    List<String> addresses =
        extracted.stream()
            .filter(f -> f.predicate().equals(PersistenceExtractor.CONNECTS_TO))
            .map(f -> f.object().value())
            .filter(a -> a.contains("${"))
            .distinct()
            .toList();
    List<String> scoped =
        derived.stream()
            .filter(f -> f.predicate().equals(PersistenceExtractor.CONNECTS_TO))
            .map(f -> f.object().value())
            .filter(a -> a.contains("${"))
            .distinct()
            .toList();
    List<String> all = new ArrayList<>(addresses);
    scoped.stream().filter(a -> !all.contains(a)).forEach(all::add);
    assertThat(all).isNotEmpty();
    for (String address : all) {
      // In the compose deployment, a container of the set's own: never this machine.
      assertThat(resolutions(address))
          .as(address)
          .filteredOn(f -> deployment(f))
          .isNotEmpty()
          .allMatch(f -> !f.object().value().startsWith("address:localhost"));
    }
  }

  @Test
  void theBindingsPickedByQualifierHoldInEveryView() {
    // #516, item 1: the direct, fanout and headers exchanges' queues, bound by @Qualifier.
    List<String> bindings =
        derived.stream()
            .filter(f -> f.predicate().equals(MessagingExtractor.BINDING))
            .filter(f -> f.subject().toString().contains("NotificationRabbitConfiguration"))
            .map(
                f ->
                    f.object().value()
                        + " "
                        + TestAttributes.text(f, "exchange")
                        + " "
                        + TestAttributes.text(f, "profile"))
            .toList();
    assertThat(bindings)
        .contains(
            "notification.commands notification.commands null",
            "notification.broadcast.all notification.broadcast null",
            "notification.priority notification.headers null")
        .allMatch(b -> b.endsWith(" null"));
  }

  @Test
  void aUrlFollowedFromAUriFieldKeepsItsPlaceholdersInEveryView() {
    // #516, item 7: PartnerStatusClient's "https://" + host, followed from its URI field; no
    // config or deployment sets the host, so no view resolves it.
    String address = "address:io.fleetcare:billing-service:https://${accounting.status-host}";
    assertThat(extracted)
        .anyMatch(
            f ->
                f.predicate().equals(HttpClientExtractor.ADDRESSED_TO)
                    && f.object().value().equals(address));
    assertThat(
            derived.stream()
                .filter(f -> f.predicate().equals(HttpClientExtractor.HTTP_CALL))
                .filter(f -> f.subject().toString().contains("PartnerStatusClient#fetch"))
                .map(f -> f.object().value()))
        .isNotEmpty()
        .allMatch(call -> call.startsWith("GET https://${accounting.status-host}/"));
    assertThat(resolutions(address)).isEmpty();
  }

  private static List<Fact> resolutions(String entity) {
    return derived.stream()
        .filter(f -> f.predicate().equals(ValueResolution.RESOLVES_TO))
        .filter(f -> f.subject().toString().equals(entity))
        .toList();
  }

  /** Whether a claim's view is a deployment's, whose key names its environment (#590). */
  private static boolean deployment(Fact f) {
    String view = TestAttributes.text(f, "view");
    return view != null && view.contains("|");
  }
}

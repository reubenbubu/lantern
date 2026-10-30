package io.lantern.link;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.repo.Git;
import io.lantern.core.repo.Repo;
import io.lantern.core.repo.RepoSettings;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.deployment.DeploymentExtractor;
import io.lantern.extract.java.JavaAnalysis;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.extract.maven.MavenExtractor;
import io.lantern.extract.persistence.PersistenceExtractor;
import io.lantern.extract.redis.RedisExtractor;
import io.lantern.extract.spring.SpringExtractor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The deployment of the test set project-4 (docs/adr/0029, ADR 0082), read without builds from the
 * repositories at their pinned commits (set up by {@code testsets/project-4/setup.sh}): its
 * Deployment repository's compose files, kustomize overlay and Helm chart fill in the datasources
 * the services leave to the environment. Runs only when {@code lantern.test.projects} names the
 * folder that holds {@code project-4}, such as {@code ~/workspace/lantern-test-projects}; needs no
 * Docker. Reference: {@code testsets/project-4/reference}, whose data stores auth_db, main_db and
 * file_db (PostgreSQL 17.5) these are.
 */
@EnabledIfSystemProperty(named = "lantern.test.projects", matches = ".+")
class Project4DeploymentIT {

  /** The derived facts of project-4, one line each, without fact IDs. */
  private static List<String> lines() throws IOException {
    Path projects = Path.of(System.getProperty("lantern.test.projects"));
    List<RepoBuild> repos = new ArrayList<>();
    for (String line : Files.readAllLines(Path.of("..", "testsets", "project-4", "pins.txt"))) {
      if (line.isBlank() || line.startsWith("#")) {
        continue;
      }
      String name = line.trim().split("\\s+")[0];
      Path path = projects.resolve("project-4").resolve(name);
      Repo repo = new Repo(name, path.toRealPath(), RepoSettings.DEFAULT);
      Git.Result head = Git.run(repo.path(), "rev-parse", "HEAD");
      repos.add(new RepoBuild(repo, head.out().strip(), null));
    }
    List<Fact> facts = new ArrayList<>(new MavenExtractor().extract(repos, 1));
    JavaAnalysis code = JavaExtractor.analyze(repos);
    facts.addAll(new JavaExtractor().extract(code, 1));
    facts.addAll(new ConfigExtractor().extract(repos, 1));
    facts.addAll(new SpringExtractor().extract(code, 1));
    facts.addAll(new PersistenceExtractor().extract(code, 1));
    facts.addAll(new RedisExtractor().extract(code, repos, 1));
    facts.addAll(new DeploymentExtractor().extract(repos, 1));
    facts.addAll(new DeployableUnits().derive(facts, 1));
    List<String> lines = new ArrayList<>();
    for (Fact f : new CrossRepoLinks().derive(facts, 1)) {
      var attributes = f.attributes().deepCopy();
      attributes.remove("facts");
      attributes.remove("fact");
      attributes.remove("link");
      lines.add(
          f.subject()
              + " "
              + f.predicate()
              + " "
              + f.object().value()
              + (attributes.isEmpty() ? "" : " " + attributes)
              + " "
              + f.confidence());
    }
    lines.sort(String::compareTo);
    return lines;
  }

  @Test
  void theServicesDatabasesAreNamedAndVersionedFromTheDeployment() throws IOException {
    List<String> lines = lines();
    lines.stream()
        .filter(
            l ->
                l.contains(" display-name ")
                    || l.contains("database:")
                    || l.contains(" deployed-in "))
        .forEach(System.out::println);

    assertThat(lines)
        .filteredOn(l -> l.startsWith("database:") && l.contains(" display-name "))
        .extracting(l -> l.substring(l.indexOf(" display-name ") + 14, l.lastIndexOf(' ')))
        .contains("auth_db", "main_db", "file_db")
        .doesNotContain("auth-service database", "main-service database", "file-service database");
    for (String store : List.of("auth_db", "main_db", "file_db")) {
      String database =
          lines.stream()
              .filter(l -> l.endsWith(" display-name " + store + " 1.0"))
              .map(l -> l.substring(0, l.indexOf(' ')))
              .findFirst()
              .orElseThrow();
      assertThat(lines).contains(database + " technology PostgreSQL 17.5 1.0");
      // The compose files run it in a container of its own, Kubernetes in the shared one.
      assertThat(lines)
          .anySatisfy(
              l -> assertThat(l).startsWith(database + " deployed-in Docker Compose (local)"))
          .anySatisfy(l -> assertThat(l).startsWith(database + " deployed-in Kubernetes (dev)"));
    }
    // Redis runs as 8.4.0 locally and in Kubernetes, 7.4.4 in the dev compose file.
    assertThat(lines)
        .anySatisfy(l -> assertThat(l).contains("/redis technology Redis 7.4.4 / 8.4.0"));
    // Every service runs in the compose files and in Kubernetes.
    for (String unit : List.of(":auth-service ", ":main-service ", ":file ", ":api-gateway ")) {
      assertThat(lines)
          .anySatisfy(l -> assertThat(l).contains(unit + "deployed-in Docker Compose (dev)"))
          .anySatisfy(l -> assertThat(l).contains(unit + "deployed-in Kubernetes (dev)"))
          .anySatisfy(l -> assertThat(l).contains(unit + "deployed-in Helm (apa-crm)"));
    }
    assertThat(String.join("\n", lines)).doesNotContain("PASSWORD\":", "SIGNING_KEY\":");
  }
}

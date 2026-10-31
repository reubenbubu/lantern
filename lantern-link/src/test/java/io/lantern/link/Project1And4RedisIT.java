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
 * The Redis data stores of the test sets project-1 and project-4 (docs/adr/0029, ADR 0073), read
 * without builds from the repositories at their pinned commits (their folders are set up by {@code
 * testsets/project-N/setup.sh}). Runs only when {@code lantern.test.projects} names the folder that
 * holds {@code project-1} and {@code project-4}, such as {@code ~/workspace/lantern-test-projects};
 * needs no Docker. Reference: {@code testsets/project-1/reference} and {@code
 * testsets/project-4/reference}, whose Redis data store and unit lines these are.
 */
@EnabledIfSystemProperty(named = "lantern.test.projects", matches = ".+")
class Project1And4RedisIT {

  private static Path projects() {
    return Path.of(System.getProperty("lantern.test.projects"));
  }

  /** The repositories a set pins, by name (testsets/project-N/pins.txt). */
  private static List<String> pinned(String project) throws IOException {
    Path pins = Path.of("..", "testsets", project, "pins.txt");
    List<String> names = new ArrayList<>();
    for (String line : Files.readAllLines(pins)) {
      if (!line.isBlank() && !line.startsWith("#")) {
        names.add(line.trim().split("\\s+")[0]);
      }
    }
    return names;
  }

  /** The Redis lines a set of repositories gives: the links, and the data stores' facts. */
  private static List<String> redis(String project) throws IOException {
    List<RepoBuild> repos = new ArrayList<>();
    for (String name : pinned(project)) {
      Path path = projects().resolve(project).resolve(name);
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
    facts.addAll(new ValueResolution().derive(facts, 1));
    facts.addAll(new DeployableUnits().derive(facts, 1));
    facts.addAll(new CrossRepoLinks().derive(facts, 1));
    List<String> lines = new ArrayList<>();
    for (Fact f : facts) {
      boolean redisLink =
          f.predicate().equals(CrossRepoLinks.USES_DATABASE)
              && f.attributes().path("protocol").asString("").equals("RESP");
      boolean store =
          f.subject().type().equals(CrossRepoLinks.DATABASE)
              && f.subject().key().endsWith("/redis");
      if (redisLink || store) {
        var attributes = f.attributes().deepCopy();
        attributes.remove("facts");
        attributes.remove("link");
        lines.add(
            f.subject()
                + " "
                + f.predicate()
                + " "
                + f.object().value()
                + (attributes.isEmpty() ? "" : " " + attributes)
                + " @"
                + f.evidence().repo()
                + ":"
                + f.evidence().path()
                + " "
                + f.confidence());
      }
    }
    lines.sort(String::compareTo);
    return lines;
  }

  @Test
  void project1Redis() throws IOException {
    List<String> lines = redis("project-1");
    lines.forEach(System.out::println);

    assertThat(database(link(lines, ":bff USES_DATABASE ")))
        .isEqualTo(database(link(lines, ":gateway USES_DATABASE ")));
    assertThat(lines)
        .anySatisfy(
            l ->
                assertThat(l)
                    .contains(":bff USES_DATABASE database:localhost:6379/redis")
                    .contains("\"protocol\":\"RESP\"")
                    .contains("\"technology\":\"Redis 8\"")
                    .contains("bff:session:*"))
        .anySatisfy(
            l ->
                assertThat(l)
                    .contains(":gateway USES_DATABASE database:localhost:6379/redis")
                    .contains("\"protocol\":\"RESP\"")
                    .contains("rate-limiter"));
  }

  @Test
  void project4Redis() throws IOException {
    List<String> lines = redis("project-4");
    lines.forEach(System.out::println);

    // Both units reach the same store, whose host and port the config leaves to the environment.
    String auth = link(lines, ":auth-service USES_DATABASE ");
    String gateway = link(lines, ":api-gateway USES_DATABASE ");
    assertThat(database(auth)).isEqualTo(database(gateway));
    assertThat(auth).contains("\"protocol\":\"RESP\"").contains("\"technology\":\"Redis");
    assertThat(gateway).contains("\"protocol\":\"RESP\"").contains("\"technology\":\"Redis");
    assertThat(lines).anySatisfy(l -> assertThat(l).contains("/redis technology Redis"));
    assertThat(lines).allSatisfy(l -> assertThat(l).doesNotContain("password"));
  }

  private static String link(List<String> lines, String marker) {
    return lines.stream()
        .filter(l -> l.contains(marker))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No link " + marker + " in " + lines));
  }

  /** The data store a link line ends at. */
  private static String database(String line) {
    int from = line.indexOf(" USES_DATABASE ") + " USES_DATABASE ".length();
    return line.substring(from, line.indexOf(' ', from));
  }
}

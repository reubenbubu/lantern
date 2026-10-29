package io.lantern.compose;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.document.Diagram;
import io.lantern.core.document.Document;
import io.lantern.core.document.Section;
import io.lantern.core.document.TemplateRef;
import io.lantern.core.fact.Fact;
import io.lantern.core.repo.Git;
import io.lantern.core.repo.Repo;
import io.lantern.core.repo.RepoSettings;
import io.lantern.core.snapshot.Snapshot;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.gateway.GatewayExtractor;
import io.lantern.extract.http.HttpClientExtractor;
import io.lantern.extract.java.JavaAnalysis;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.extract.maven.MavenExtractor;
import io.lantern.extract.messaging.MessagingExtractor;
import io.lantern.extract.persistence.PersistenceExtractor;
import io.lantern.extract.spring.SpringExtractor;
import io.lantern.extract.sql.SqlExtractor;
import io.lantern.link.CrossRepoLinks;
import io.lantern.link.DeployableUnits;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The gateway of the development test set project-4 (#251) in the system's C4 report, composed from
 * its repositories read without builds (degraded mode) and without the LLM: its component diagram
 * shows the routes and the filters they apply, and the container diagram its route lines with their
 * protocols (#257). Runs only when {@code lantern.test.projects} names the folder the sets were set
 * up in ({@code testsets/project-4/setup.sh}). Writes the gateway's diagrams to {@code
 * target/gateway-routes/project-4.c4.txt}.
 */
@EnabledIfSystemProperty(named = "lantern.test.projects", matches = ".+")
class GatewayComponentsIT {

  private static final TemplateRef TEMPLATE = new TemplateRef("c4-model-sequence-diagrams", "1");

  @Test
  void project4sGatewayShowsItsRoutesAndFilters() throws IOException {
    Map<String, String> commits = new TreeMap<>();
    List<RepoBuild> repos = repos("project-4", commits);
    List<Fact> facts = new ArrayList<>(new MavenExtractor().extract(repos, 1));
    JavaAnalysis code = JavaExtractor.analyze(repos);
    facts.addAll(new JavaExtractor().extract(code, 1));
    facts.addAll(new ConfigExtractor().extract(repos, 1));
    facts.addAll(new SqlExtractor().extract(repos, 1));
    facts.addAll(new SpringExtractor().extract(code, 1));
    facts.addAll(new PersistenceExtractor().extract(code, 1));
    facts.addAll(new HttpClientExtractor().extract(code, 1));
    facts.addAll(new MessagingExtractor().extract(code, 1));
    facts.addAll(new GatewayExtractor().extract(code, 1));
    facts.addAll(new DeployableUnits().derive(facts, 1));
    facts.addAll(new CrossRepoLinks().derive(facts, 1));
    Document document =
        new Composer("1")
            .compose(
                ViewDefinition.c4System("system:c4", "APA-CRM", TEMPLATE),
                new Snapshot(1, commits, 1, "test", Instant.EPOCH),
                facts,
                Map.of());

    List<String> lines = new ArrayList<>();
    List<String> routeLines = new ArrayList<>();
    for (Diagram containers : diagrams(section(document.sections(), "containers"))) {
      edges(containers).stream()
          .filter(e -> e.startsWith("service:com.crm:api-gateway "))
          .forEach(routeLines::add);
    }
    lines.add("# Container diagram, lines from the gateway");
    lines.addAll(routeLines);
    Section gateway =
        section(document.sections(), "components").sections().stream()
            .filter(s -> s.title().equals("api-gateway"))
            .findFirst()
            .orElseThrow();
    Diagram components = diagram(gateway);
    lines.add("# Component diagram of the gateway: nodes");
    lines.addAll(nodes(components));
    lines.add("# Component diagram of the gateway: lines");
    lines.addAll(edges(components));
    Path out = Path.of("target", "gateway-routes");
    Files.createDirectories(out);
    Files.write(out.resolve("project-4.c4.txt"), lines);

    String routes = "type:com.crm.gateway.config.RouteConfig";
    assertThat(nodes(components))
        .contains(
            routes + " \"RouteConfig\" [Gateway routes]",
            "type:com.crm.gateway.filters.AuthFilter \"AuthFilter\" [Gateway filter]",
            "type:com.crm.gateway.filters.AuthorizeAndCheckAccessFilter"
                + " \"AuthorizeAndCheckAccessFilter\" [Gateway filter]",
            "type:com.crm.gateway.filters.OrganizationFilesFilter"
                + " \"OrganizationFilesFilter\" [Gateway filter]");
    assertThat(edges(components))
        .anyMatch(e -> e.startsWith(routes + " -> type:com.crm.gateway.filters.AuthFilter:"))
        .anyMatch(
            e ->
                e.startsWith(routes + " -> service:com.crm.notification:notification-service:")
                    && e.endsWith("[WebSocket]"));
    // The gateway also calls auth-service and main-service from its own code, so those lines say
    // both.
    assertThat(routeLines)
        .anyMatch(e -> e.contains("notification-service: Routes /ws-notifications/**"))
        .anyMatch(e -> e.contains("auth-service: Calls and routes"))
        .anyMatch(e -> e.contains("main-service: Calls and routes"))
        .anyMatch(e -> e.contains(":file: Routes"));
  }

  private static Section section(List<Section> sections, String id) {
    return sections.stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
  }

  /** The diagrams of a section and its subsections, in order. */
  private static List<Diagram> diagrams(Section section) {
    List<Diagram> result = new ArrayList<>();
    section.blocks().stream()
        .filter(Diagram.class::isInstance)
        .map(Diagram.class::cast)
        .forEach(result::add);
    section.sections().forEach(s -> result.addAll(diagrams(s)));
    return result;
  }

  private static Diagram diagram(Section section) {
    return section.blocks().stream()
        .filter(Diagram.class::isInstance)
        .map(Diagram.class::cast)
        .findFirst()
        .orElseThrow();
  }

  private static List<String> nodes(Diagram diagram) {
    return diagram.nodes().stream()
        .map(
            n ->
                n.id()
                    + " \""
                    + n.label()
                    + "\""
                    + (n.technology() == null ? "" : " [" + n.technology() + "]"))
        .toList();
  }

  private static List<String> edges(Diagram diagram) {
    return diagram.edges().stream()
        .map(
            e ->
                e.from()
                    + " -> "
                    + e.to()
                    + ": "
                    + e.label()
                    + (e.technology() == null ? "" : " [" + e.technology() + "]"))
        .toList();
  }

  /** The set's repositories at their pins, without builds; their commits go to the map given. */
  private static List<RepoBuild> repos(String set, Map<String, String> commits) throws IOException {
    Path folder = Path.of(System.getProperty("lantern.test.projects")).resolve(set);
    List<RepoBuild> repos = new ArrayList<>();
    for (String line : Files.readAllLines(Path.of("..", "testsets", set, "pins.txt"))) {
      line = line.strip();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      String[] parts = line.split("\\s+");
      Path path = folder.resolve(parts[0]).toRealPath();
      Git.Result head = Git.run(path, "rev-parse", "HEAD");
      assertThat(head.out())
          .as("%s at its pin (run testsets/%s/setup.sh)", parts[0], set)
          .isEqualTo(parts[2]);
      commits.put(parts[0], parts[2]);
      repos.add(new RepoBuild(new Repo(parts[0], path, RepoSettings.DEFAULT), parts[2], null));
    }
    return repos;
  }
}

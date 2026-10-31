package io.lantern.link;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.extract.attributes.DeploymentContainerAttributes;
import io.lantern.extract.deployment.DeploymentExtractor;
import io.lantern.link.LinkIndex.Unit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The containers the deployment descriptors of a snapshot run (the deployment extractor's {@code
 * deployment-container} facts), tied to the units they run, whose environments value resolution
 * applies to the units' config ({@link EnvironmentValues}). See {@code
 * docs/adr/0082-deployment-descriptors.md}.
 *
 * <p>A container runs a unit when one of its names (compose service, container name, workload,
 * Service, app label or the image's name) is the unit's {@code spring.application.name} or artifact
 * ID, compared without case, dashes or underscores, and no other unit has that name. In a container
 * that runs a unit, an environment variable sets the config key it binds to as Spring Boot's
 * relaxed binding does ({@code AUTH_DATASOURCE_HOST} sets {@code auth.datasource.host}).
 */
final class Deployments {

  /** Attribute the linker gives a unit or data store: a container that runs it. */
  static final String DEPLOYED_IN = "deployed-in";

  /** A container of one environment, read from its fact. */
  record Container(
      Fact fact,
      String environment,
      String name,
      Set<String> names,
      Map<String, String> env,
      String product,
      String version,
      List<String> databases) {

    /** Whether others in its environment reach it by a host name. */
    boolean answers(String host) {
      String wanted = host.toLowerCase(Locale.ROOT);
      // A Kubernetes DNS name (postgres.ns.svc.cluster.local) names the Service by its first label.
      String first = wanted.contains(".svc") ? wanted.substring(0, wanted.indexOf('.')) : wanted;
      return Claims.list(container(fact).hosts()).stream()
          .map(h -> h.toLowerCase(Locale.ROOT))
          .anyMatch(h -> h.equals(wanted) || h.equals(first));
    }

    /** The environment's name as documents show it, such as "Docker Compose (local)". */
    String label() {
      return label(fact);
    }

    static String label(Fact fact) {
      return DeploymentExtractor.environmentLabel(
          container(fact).platform(), container(fact).environment());
    }
  }

  /**
   * What a datasource of a unit is in one environment: the container that runs the unit, the one
   * its database is on (or null), the server as the environment writes it, and the database name
   * (null when the environment does not say).
   */
  record Placed(
      Container unit, Container server, String address, String database, boolean guessed) {}

  /** Placeholders in config as written: {@code ${key}} or {@code ${key:default}}. */

  /** Words a database name shares with any service's config, so they tie nothing. */
  private static final Set<String> GENERIC =
      Set.of(
          "db",
          "database",
          "datasource",
          "data",
          "source",
          "service",
          "app",
          "spring",
          "jdbc",
          "url",
          "name",
          "server",
          "host",
          "port",
          "sql",
          "schema");

  /** The roles of products that run infrastructure, never a unit; runtimes and proxies may. */
  private static final Set<String> INFRASTRUCTURE =
      Set.of(
          "database",
          "cache",
          "broker",
          "identity",
          "discovery",
          "observability",
          "mail",
          "storage");

  private final List<Container> containers = new ArrayList<>();
  private final Map<EntityId, List<Container>> byUnit = new HashMap<>();

  Deployments(LinkIndex index) {
    for (Fact f : index.of(DeploymentExtractor.DEPLOYMENT_CONTAINER)) {
      Set<String> names = new LinkedHashSet<>();
      names.add(normalized(f.object().value()));
      Claims.list(container(f).hosts()).forEach(h -> names.add(normalized(h)));
      Claims.list(container(f).aliases()).forEach(a -> names.add(normalized(a)));
      String image = container(f).image();
      if (image != null && !image.contains("${")) {
        String plain = image.contains("@") ? image.substring(0, image.indexOf('@')) : image;
        String last = plain.substring(plain.lastIndexOf('/') + 1);
        names.add(normalized(last.contains(":") ? last.substring(0, last.indexOf(':')) : last));
      }
      names.remove("");
      Map<String, String> env = new TreeMap<>();
      JsonNode node = container(f).env();
      if (node != null) {
        node.properties().forEach(e -> env.put(e.getKey(), e.getValue().asString("")));
      }
      containers.add(
          new Container(
              f,
              f.evidence().repo()
                  + "|"
                  + container(f).platform()
                  + "|"
                  + container(f).environment()
                  + ("compose".equals(container(f).platform()) ? "|" + f.evidence().path() : ""),
              f.object().value(),
              names,
              env,
              container(f).product(),
              container(f).version(),
              Claims.list(container(f).databases())));
    }
    // Ties by name: a container names one unit, or none if it could be several.
    Map<String, Set<Unit>> unitsByName = new HashMap<>();
    for (Unit unit : index.units.values()) {
      for (String name : unitNames(index, unit)) {
        unitsByName.computeIfAbsent(name, n -> new LinkedHashSet<>()).add(unit);
      }
    }
    for (Container c : containers) {
      String role = container(c.fact()).role();
      if (role != null && INFRASTRUCTURE.contains(role)) {
        continue;
      }
      Set<Unit> matched = new LinkedHashSet<>();
      for (String name : c.names()) {
        matched.addAll(unitsByName.getOrDefault(name, Set.of()));
      }
      if (matched.size() == 1) {
        byUnit.computeIfAbsent(matched.iterator().next().id(), u -> new ArrayList<>()).add(c);
      }
    }
  }

  private static Set<String> unitNames(LinkIndex index, Unit unit) {
    Set<String> names = new LinkedHashSet<>();
    Fact application = index.name(unit.id(), null);
    if (application != null) {
      names.add(normalized(application.object().value()));
    }
    names.add(normalized(unit.artifactId()));
    names.remove("");
    return names;
  }

  static String normalized(String name) {
    return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
  }

  /** Every container, in fact ID order. */
  List<Container> all() {
    return containers;
  }

  /** The containers that run a unit, in fact ID order. */
  List<Container> running(Unit unit) {
    return byUnit.getOrDefault(unit.id(), List.of());
  }

  /**
   * Where a datasource of a unit points in each environment that runs the unit: its server and
   * database filled in from the container's environment, the container the server is (by host name,
   * or by the compose variable that names the database in both), and the database's name when the
   * environment, the server's image variable or its init scripts give it.
   *
   * @param server the datasource's server as the config writes it, placeholders and all
   * @param database the datasource's database as the config writes it
   * @param words names that tell the unit's database among a server's several: the unit's names and
   *     the datasource's placeholder keys
   */
  List<Placed> place(
      Unit unit, String server, String database, String technology, Collection<String> words) {
    List<Placed> placed = new ArrayList<>();
    for (Container c : running(unit)) {
      String address = EnvironmentValues.fill(c, server);
      String name = EnvironmentValues.fill(c, database);
      if (address == null && name == null) {
        continue;
      }
      Container host = null;
      if (address != null && !address.contains("${") && !address.isBlank()) {
        String hostName =
            address.contains(":") ? address.substring(0, address.lastIndexOf(':')) : address;
        host = only(c, technology, o -> o.answers(hostName));
      }
      if (host == null && name != null && name.matches("\\$\\{[A-Za-z_][A-Za-z0-9_]*}")) {
        // A compose variable that names the database for both the unit and a database container.
        String variable = name;
        host =
            only(
                c,
                technology,
                o ->
                    DeploymentExtractor.DATABASE_VARIABLES.stream()
                        .anyMatch(v -> variable.equals(o.env().get(v))));
      }
      String known = name == null || name.contains("${") || name.isBlank() ? null : name;
      boolean guessed = false;
      if (known == null && host != null) {
        List<String> candidates = host.databases();
        if (candidates.size() == 1) {
          known = candidates.getFirst();
        } else {
          known = byWords(candidates, words);
          guessed = known != null;
        }
      }
      if (address != null && (address.contains("${") || address.isBlank())) {
        address = host == null ? null : host.name();
      }
      if (host != null || known != null || address != null) {
        placed.add(new Placed(c, host, address, known, guessed));
      }
    }
    return placed;
  }

  /**
   * The one other container of an environment that matches and does not run another product than
   * the datasource's, or null when none or several do.
   */
  private Container only(
      Container c, String technology, java.util.function.Predicate<Container> matches) {
    List<Container> found =
        sameEnvironment(c).stream()
            .filter(o -> o != c)
            .filter(
                o ->
                    technology == null
                        || o.product() == null
                        || o.product().equalsIgnoreCase(technology))
            .filter(matches)
            .toList();
    return found.size() == 1 ? found.getFirst() : null;
  }

  private List<Container> sameEnvironment(Container c) {
    return containers.stream().filter(o -> o.environment().equals(c.environment())).toList();
  }

  /** The one candidate that shares a word with the given names, or null. */
  private static String byWords(List<String> candidates, Collection<String> names) {
    Set<String> wanted = new TreeSet<>();
    for (String name : names) {
      for (String word : name.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
        if (!word.isEmpty() && !GENERIC.contains(word)) {
          wanted.add(word);
        }
      }
    }
    String found = null;
    for (String candidate : candidates) {
      for (String word : candidate.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
        if (wanted.contains(word)) {
          if (found != null && !found.equals(candidate)) {
            return null;
          }
          found = candidate;
        }
      }
    }
    return found;
  }

  /**
   * A product with the versions its containers run: the version when they agree, the versions from
   * oldest to newest when they differ ({@code Redis 7.4.4 / 8.4.0}), the product alone when none
   * names one.
   */
  static String technology(String product, Collection<Container> running) {
    Set<String> versions = new TreeSet<>(Deployments::compareVersions);
    for (Container c : running) {
      if (c.version() != null && product.equalsIgnoreCase(c.product())) {
        versions.add(c.version());
      }
    }
    return versions.isEmpty() ? product : product + " " + String.join(" / ", versions);
  }

  private static int compareVersions(String a, String b) {
    String[] x = a.split("\\.");
    String[] y = b.split("\\.");
    for (int i = 0; i < Math.max(x.length, y.length); i++) {
      java.math.BigInteger p =
          i < x.length ? new java.math.BigInteger(x[i]) : java.math.BigInteger.ONE.negate();
      java.math.BigInteger q =
          i < y.length ? new java.math.BigInteger(y[i]) : java.math.BigInteger.ONE.negate();
      if (!p.equals(q)) {
        return p.compareTo(q);
      }
    }
    return 0;
  }

  /** A {@code deployed-in} fact: the subject runs in the container. */
  static Fact deployedIn(EntityId subject, Container c, Origin origin, Validity validity) {
    ObjectNode attributes = FactJson.newObject();
    attributes.put("container", c.name());
    attributes.put("platform", container(c.fact()).platform());
    attributes.put("environment", container(c.fact()).environment());
    attributes.put("fact", c.fact().id());
    return Fact.create(
        subject,
        DEPLOYED_IN,
        FactObject.string(c.label()),
        attributes,
        origin,
        c.fact().evidence(),
        c.fact().confidence(),
        validity);
  }

  /** Records which containers run each unit. */
  void link(LinkIndex index, FoundLinks out, Origin origin, Validity validity) {
    for (Unit unit : index.units.values()) {
      for (Container c : running(unit)) {
        out.add(deployedIn(unit.id(), c, origin, validity));
      }
    }
  }

  /** A {@code deployment-container} fact's attributes. */
  static DeploymentContainerAttributes container(Fact fact) {
    return Claims.of(fact, DeploymentContainerAttributes.class);
  }
}

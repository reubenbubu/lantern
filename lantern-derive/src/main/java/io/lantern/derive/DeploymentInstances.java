package io.lantern.derive;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.types.Types;
import io.lantern.core.vocabulary.VocabularyType;
import io.lantern.link.LinkRule;
import io.lantern.link.RuleInfo;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The deployment instances of the architecture model (#617): one per container a deployment
 * descriptor runs in an environment, {@code deployment-instance:<platform>:<environment>/<name>}
 * keyed on the name the environment gives it (#615), with its image or build, technology and ports
 * ({@code deployed-as}), and what it {@code RUNS}: the units and data stores the linker placed in
 * it. Each fact cites the facts it rests on.
 */
public final class DeploymentInstances implements LinkRule.OverFacts {

  public static final String NAME = "deployment-instances";

  /** Raised whenever the instances it gives for the same facts change. */
  public static final String VERSION = "1";

  private static final Origin ORIGIN = new Origin(Origin.Kind.STATIC, NAME, VERSION);
  private static final List<String> COPIED =
      List.of("platform", "environment", "image", "build", "product", "version");

  @Override
  public RuleInfo info() {
    return new RuleInfo(NAME, VERSION, Origin.Kind.STATIC);
  }

  @Override
  public Set<String> reads() {
    return Set.of(EXTRACTED, LINKED);
  }

  @Override
  public Set<String> writes() {
    return Set.of(Types.DEPLOYED_AS, Types.RUNS);
  }

  /** The vocabulary types of the instances' facts besides the built-in ones. */
  public static List<VocabularyType> vocabulary() {
    return Derivation.load("/vocabulary/deployment-instances-types.json");
  }

  /**
   * A container a deployment descriptor defines, in one environment, with what runs in it (ADR
   * 0082).
   *
   * @param container the container's fact
   * @param environment the environment as documents name it, such as {@code Docker Compose (local)}
   * @param image the image it runs, or {@code null}
   * @param build the path it is built from, or {@code null}
   * @param product the product the image is, or {@code null}
   * @param version the product's version, or {@code null}
   * @param runs the units and data stores the linker found running in it, in ID order
   * @param ports the ports it publishes, as stored
   * @param facts the facts the row rests on: the container's and those of what runs in it
   */
  public record Deployed(
      Fact container,
      String environment,
      String image,
      String build,
      String product,
      String version,
      List<EntityId> runs,
      List<String> ports,
      List<Fact> facts) {

    /** The name the descriptor gives the container. */
    public String name() {
      return container.object().value();
    }
  }

  /**
   * The containers of the deployment descriptors in the repositories (all, when none is given),
   * sorted by environment, then container, then fact ID. A descriptor entry several instances rest
   * on is listed once for each.
   *
   * @param repos the repositories in scope, or none for all of them
   */
  public static List<Deployed> deployed(ArchitectureModel model, Set<String> repos) {
    Map<String, SortedSet<EntityId>> running = new TreeMap<>();
    Map<String, List<Fact>> runningFacts = new TreeMap<>();
    List<Fact> containers = new ArrayList<>();
    Set<EntityId> instances = new HashSet<>();
    for (Fact instance : model.withPredicate(Types.DEPLOYED_AS)) {
      for (Fact container : model.derivedFrom(instance)) {
        if (repos.isEmpty() || repos.contains(container.evidence().repo())) {
          containers.add(container);
        }
      }
      if (!instances.add(instance.subject())) {
        continue;
      }
      for (Fact runs : model.relations(instance.subject(), Types.RUNS)) {
        for (Fact fact : model.derivedFrom(runs)) {
          String container = Elements.attribute(fact, "fact", null);
          if (container != null) {
            running
                .computeIfAbsent(container, c -> new TreeSet<>(ArchitectureModel.BY_ID))
                .add(ArchitectureModel.object(runs));
            runningFacts.computeIfAbsent(container, c -> new ArrayList<>()).add(fact);
          }
        }
      }
    }
    containers.sort(
        Comparator.comparing(DeploymentInstances::environmentName)
            .thenComparing(f -> f.object().value())
            .thenComparing(Fact::id));
    List<Deployed> result = new ArrayList<>();
    for (Fact fact : containers) {
      List<Fact> cited = new ArrayList<>(List.of(fact));
      cited.addAll(runningFacts.getOrDefault(fact.id(), List.of()));
      result.add(
          new Deployed(
              fact,
              environmentName(fact),
              Elements.attribute(fact, "image", null),
              Elements.attribute(fact, "build", null),
              Elements.attribute(fact, "product", null),
              Elements.attribute(fact, "version", null),
              List.copyOf(running.getOrDefault(fact.id(), new TreeSet<>(ArchitectureModel.BY_ID))),
              Elements.strings(fact, "ports"),
              List.copyOf(cited)));
    }
    return List.copyOf(result);
  }

  /** A container's environment as documents name it, such as "Docker Compose (local)". */
  static String environmentName(Fact container) {
    // As the linker names it on deployed-in facts; the composer does not depend on the extractor.
    String platform = Elements.attribute(container, "platform", null);
    String environment = Elements.attribute(container, "environment", null);
    String name =
        switch (platform == null ? "" : platform) {
          case "compose" -> "Docker Compose";
          case "kubernetes" -> "Kubernetes";
          case "helm" -> "Helm";
          default -> platform;
        };
    return environment == null ? name : name + " (" + environment + ")";
  }

  /**
   * The instance a {@code deployment-container} fact describes. Its parts are percent-encoded where
   * a key cannot hold a character (whitespace) or a separator would be ambiguous, so an environment
   * named "fleetcare prod" gives {@code helm:fleetcare%20prod/notification-service}. A descriptor
   * naming no environment stands for its own, {@code @} and its repository and path: {@code
   * compose:@shop%2Fdocker-compose.yml/postgres}.
   */
  public static EntityId id(Fact container) {
    String platform = attribute(container, "platform");
    String environment = attribute(container, "environment");
    return new EntityId(
        Types.DEPLOYMENT_INSTANCE,
        (platform == null ? "-" : encoded(platform, ":/"))
            + ":"
            + (environment != null
                ? encoded(environment, ":/@")
                // A descriptor naming no environment is one of its own: two repositories' compose
                // files that both run a postgres are two instances.
                : "@"
                    + encoded(
                        container.evidence().repo() + "/" + container.evidence().path(), ":/@"))
            + "/"
            + encoded(container.object().value().toString(), ""));
  }

  /** The text with '%', whitespace, control characters and the given separators percent-encoded. */
  static String encoded(String text, String separators) {
    if (text.equals("-")) {
      return "%2D";
    }
    StringBuilder out = new StringBuilder();
    text.codePoints()
        .forEach(
            c -> {
              boolean plain =
                  c != '%'
                      && separators.indexOf(c) < 0
                      && !Character.isWhitespace(c)
                      && !Character.isSpaceChar(c)
                      && !Character.isISOControl(c)
                      && !(c >= 0x7f && c <= 0xa0)
                      && c != 0xfeff;
              if (plain) {
                out.appendCodePoint(c);
              } else {
                for (byte b :
                    new String(Character.toChars(c))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                  out.append('%').append(String.format("%02X", b & 0xff));
                }
              }
            });
    return out.toString();
  }

  @Override
  public List<Fact> derive(List<Fact> facts, long snapshot) {
    Validity validity = Validity.from(snapshot);
    Map<String, List<Fact>> deployedIn = new LinkedHashMap<>();
    for (Fact fact : facts) {
      String container = attribute(fact, "fact");
      if (fact.predicate().equals(Types.DEPLOYED_IN) && container != null) {
        deployedIn.computeIfAbsent(container, c -> new ArrayList<>()).add(fact);
      }
    }
    List<Fact> derived = new ArrayList<>();
    for (Fact container : facts) {
      if (!container.predicate().equals(Types.DEPLOYMENT_CONTAINER)) {
        continue;
      }
      EntityId instance = id(container);
      ObjectNode attributes = FactJson.newObject();
      for (String name : COPIED) {
        String value = attribute(container, name);
        if (value != null) {
          attributes.put(name, value);
        }
      }
      JsonNode ports = container.attributes().get("ports");
      if (ports != null && ports.isArray()) {
        attributes.set("ports", ports.deepCopy());
      }
      attributes.putArray("facts").add(container.id());
      derived.add(
          Fact.create(
              instance,
              Types.DEPLOYED_AS,
              container.object(),
              attributes,
              ORIGIN,
              container.evidence(),
              1.0,
              validity));
      for (Fact runs : deployedIn.getOrDefault(container.id(), List.of())) {
        ObjectNode cites = FactJson.newObject();
        ArrayNode ids = cites.putArray("facts");
        ids.add(runs.id());
        derived.add(
            Fact.create(
                instance,
                Types.RUNS,
                FactObject.entity(runs.subject()),
                cites,
                ORIGIN,
                container.evidence(),
                1.0,
                validity));
      }
    }
    return Derivation.merged(derived);
  }

  private static String attribute(Fact fact, String name) {
    JsonNode value = fact.attributes().get(name);
    return value == null || value.isNull() ? null : value.asString();
  }
}

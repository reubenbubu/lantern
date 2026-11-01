package io.lantern.derive;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.vocabulary.VocabularyType;
import io.lantern.link.LinkRule;
import io.lantern.link.RuleInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

  public static final String DEPLOYMENT_INSTANCE = "deployment-instance";
  public static final String DEPLOYED_AS = "deployed-as";
  public static final String RUNS = "RUNS";

  private static final Origin ORIGIN = new Origin(Origin.Kind.STATIC, NAME, VERSION);
  private static final String DEPLOYMENT_CONTAINER = "deployment-container";
  private static final String DEPLOYED_IN = "deployed-in";
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
    return Set.of(DEPLOYED_AS, RUNS);
  }

  /** The vocabulary types of the instances' facts besides the built-in ones. */
  public static List<VocabularyType> vocabulary() {
    return Derivation.load("/vocabulary/deployment-instances-types.json");
  }

  /** The instance a {@code deployment-container} fact describes. */
  public static EntityId id(Fact container) {
    String platform = attribute(container, "platform");
    String environment = attribute(container, "environment");
    return new EntityId(
        DEPLOYMENT_INSTANCE,
        (platform == null ? "-" : platform)
            + ":"
            + (environment == null ? "-" : environment)
            + "/"
            + container.object().value());
  }

  @Override
  public List<Fact> derive(List<Fact> facts, long snapshot) {
    Validity validity = Validity.from(snapshot);
    Map<String, List<Fact>> deployedIn = new LinkedHashMap<>();
    for (Fact fact : facts) {
      String container = attribute(fact, "fact");
      if (fact.predicate().equals(DEPLOYED_IN) && container != null) {
        deployedIn.computeIfAbsent(container, c -> new ArrayList<>()).add(fact);
      }
    }
    List<Fact> derived = new ArrayList<>();
    for (Fact container : facts) {
      if (!container.predicate().equals(DEPLOYMENT_CONTAINER)) {
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
              DEPLOYED_AS,
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
                RUNS,
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

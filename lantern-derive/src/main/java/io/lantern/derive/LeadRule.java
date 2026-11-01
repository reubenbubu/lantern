package io.lantern.derive;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.core.vocabulary.VocabularyType;
import io.lantern.link.LinkRule;
import io.lantern.link.RuleInfo;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
 * A rule of the Investigate lead catalog (knowledge-build epic, section 11.2; #524): a query over
 * the facts that finds candidate risks of one kind and stores each as a lead, {@code
 * lead:<kind>/<key>}, for Investigate to confirm or refute. A lead names the units it concerns, the
 * code to read, why it fired, and a {@code content} hash of the claims and code it rests on, so a
 * lead is asked again only when what it points at changes (#615). Its lineage is the facts that
 * triggered it, one level.
 */
public abstract class LeadRule implements LinkRule.OverFacts {

  /** The entity type of a lead. */
  public static final String LEAD = "lead";

  /** The fact that a lead is raised: its value is the lead's kind. */
  public static final String LEAD_TRIGGER = "lead-trigger";

  static final String DEPLOYABLE_UNIT = "deployable-unit";
  static final String METHOD = "method";

  private final String kind;
  private final String version;

  protected LeadRule(String kind, String version) {
    this.kind = kind;
    this.version = version;
  }

  /** The kind of lead the rule finds, as the catalog names it. */
  public String kind() {
    return kind;
  }

  @Override
  public RuleInfo info() {
    return new RuleInfo("lead-" + kind, version, Origin.Kind.STATIC);
  }

  @Override
  public Set<String> reads() {
    Set<String> reads = new TreeSet<>(Set.of(EXTRACTED, LINKED));
    reads.addAll(alsoReads());
    return reads;
  }

  /** What the rule reads besides the extracted and linked facts, such as other derived facts. */
  protected Set<String> alsoReads() {
    return Set.of();
  }

  @Override
  public Set<String> writes() {
    return Set.of(LEAD_TRIGGER);
  }

  /** The vocabulary types of leads. */
  public static List<VocabularyType> vocabulary() {
    return Derivation.load("/vocabulary/leads-types.json");
  }

  @Override
  public List<Fact> derive(List<Fact> facts, long snapshot) {
    Knowledge knowledge = new Knowledge(facts);
    Leads leads = new Leads(knowledge);
    find(knowledge, leads);
    Origin origin = new Origin(Origin.Kind.STATIC, info().name(), version);
    Validity validity = Validity.from(snapshot);
    List<Fact> derived = new ArrayList<>();
    for (Lead lead : leads.byKey.values()) {
      derived.add(lead.fact(kind, origin, validity));
    }
    return derived;
  }

  /** Finds the rule's leads among the facts. */
  protected abstract void find(Knowledge knowledge, Leads leads);

  /** The leads a rule is gathering, by key, and what they need to know of the units. */
  protected final class Leads {
    private final Knowledge knowledge;
    private final Map<String, Lead> byKey = new TreeMap<>();
    private Map<EntityId, SortedSet<EntityId>> unitsOfType;

    Leads(Knowledge knowledge) {
      this.knowledge = knowledge;
    }

    /** The lead with this key, started with the reason it fired if it is new. */
    Lead lead(String key, String trigger) {
      return byKey.computeIfAbsent(key, k -> new Lead(k, trigger));
    }

    /** The deployable units whose code holds the entity: a method, a type or a module. */
    SortedSet<EntityId> unitsOf(EntityId entity) {
      if (unitsOfType == null) {
        unitsOfType = new TreeMap<>(Knowledge.BY_ID);
        for (EntityId unit : units()) {
          for (EntityId module : Components.codeModules(knowledge, unit)) {
            unitsOfType.computeIfAbsent(module, m -> new TreeSet<>(Knowledge.BY_ID)).add(unit);
            SortedSet<EntityId> types = new TreeSet<>(knowledge.typesWithin(module));
            for (EntityId pkg : knowledge.parts(module, BuiltInTypes.PACKAGE)) {
              types.addAll(knowledge.typesWithin(pkg));
            }
            for (EntityId type : types) {
              unitsOfType.computeIfAbsent(type, t -> new TreeSet<>(Knowledge.BY_ID)).add(unit);
            }
          }
        }
      }
      EntityId owner =
          entity.type().equals(BuiltInTypes.TYPE) || entity.type().equals(BuiltInTypes.MODULE)
              ? entity
              : Components.ownerType(knowledge, entity);
      SortedSet<EntityId> found = unitsOfType.get(owner);
      if (found == null && units().contains(entity)) {
        found = new TreeSet<>(Set.of(entity));
      }
      return found == null ? new TreeSet<>(Knowledge.BY_ID) : found;
    }

    /** The types a module's code declares. */
    SortedSet<EntityId> typesOf(EntityId module) {
      SortedSet<EntityId> types = new TreeSet<>(Knowledge.BY_ID);
      types.addAll(knowledge.typesWithin(module));
      for (EntityId pkg : knowledge.parts(module, BuiltInTypes.PACKAGE)) {
        types.addAll(knowledge.typesWithin(pkg));
      }
      return types;
    }

    /** The deployable units. */
    SortedSet<EntityId> units() {
      SortedSet<EntityId> units = new TreeSet<>(Knowledge.BY_ID);
      knowledge.withPredicate(DEPLOYABLE_UNIT).forEach(f -> units.add(f.subject()));
      return units;
    }
  }

  /** A lead being gathered: the units it concerns, the code to read and the facts behind it. */
  protected static final class Lead {
    private final String key;
    private final String trigger;
    private final SortedSet<String> units = new TreeSet<>();
    private final SortedSet<String> code = new TreeSet<>();
    private final Map<String, Fact> facts = new TreeMap<>();

    Lead(String key, String trigger) {
      this.key = key;
      this.trigger = trigger;
    }

    Lead unit(EntityId unit) {
      units.add(unit.toString());
      return this;
    }

    Lead units(Iterable<EntityId> all) {
      all.forEach(this::unit);
      return this;
    }

    Lead code(EntityId entity) {
      code.add(entity.toString());
      return this;
    }

    Lead cite(Fact fact) {
      facts.putIfAbsent(fact.id(), fact);
      return this;
    }

    Fact fact(String kind, Origin origin, Validity validity) {
      ObjectNode attributes = FactJson.newObject();
      attributes.put("trigger", trigger);
      if (!units.isEmpty()) {
        ArrayNode list = attributes.putArray("units");
        units.forEach(list::add);
      }
      ArrayNode read = attributes.putArray("code");
      code.forEach(read::add);
      attributes.put("content", content());
      ArrayNode cites = attributes.putArray("facts");
      facts.keySet().forEach(cites::add);
      Fact first = facts.values().iterator().next();
      return Fact.create(
          new EntityId(LEAD, kind + "/" + DeploymentInstances.encoded(key, "")),
          LEAD_TRIGGER,
          FactObject.string(kind),
          attributes,
          origin,
          first.evidence(),
          1.0,
          validity);
    }

    /**
     * A hash of what the lead points at: the claims behind it and the content of the code they were
     * read from, so a change to either asks Investigate again (#615).
     */
    private String content() {
      SortedSet<String> parts = new TreeSet<>(code);
      for (Fact fact : facts.values()) {
        StringBuilder part = new StringBuilder(fact.claimId() == null ? fact.id() : fact.claimId());
        for (Evidence.Location location : fact.evidence().locations()) {
          String content =
              location.contentHash() != null ? location.contentHash() : location.blob();
          part.append(' ').append(location.path()).append('@').append(content);
        }
        parts.add(part.toString());
      }
      try {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        // In base64url, so it is never read as a claim or fact ID, which are hex.
        return "sha256:"
            + java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                    digest.digest(String.join("\n", parts).getBytes(StandardCharsets.UTF_8)));
      } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  // --- Helpers the rules share ----------------------------------------------------------------

  static String attribute(Fact fact, String name) {
    JsonNode value = fact.attributes().get(name);
    return value == null || value.isNull() || value.isContainer() ? null : value.asString();
  }

  static List<String> strings(Fact fact, String name) {
    JsonNode value = fact.attributes().get(name);
    List<String> result = new ArrayList<>();
    if (value != null && value.isArray()) {
      value.forEach(v -> result.add(v.asString()));
    } else if (value != null && !value.isNull() && !value.isContainer()) {
      result.add(value.asString());
    }
    return result;
  }

  static EntityId parse(String id) {
    if (id == null) {
      return null;
    }
    try {
      return EntityId.parse(id);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** The methods of a type and the type itself. */
  static Set<EntityId> withMethods(Knowledge knowledge, EntityId type) {
    Set<EntityId> members = new LinkedHashSet<>(List.of(type));
    members.addAll(knowledge.parts(type, METHOD));
    return members;
  }
}

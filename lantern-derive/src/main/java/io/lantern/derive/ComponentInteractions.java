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
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * How the components of each unit interact, stored as part of the architecture model (#617): a
 * {@code COMPONENT_LINK} from a component to another for each route a gateway applies a filter of
 * another component on, each call into another component's code and each entry point an aspect
 * advises; and, for each link between containers whose code end is in a component, a {@code
 * COMPONENT_LINK} of kind {@code code-end} from that component to the link's other end. A component
 * that reads or writes tables, or holds a Spring Data repository, has its {@code data-access}. Each
 * fact cites the facts it rests on, its lineage.
 */
public final class ComponentInteractions implements LinkRule.OverFacts {

  public static final String NAME = "component-interactions";

  /** Raised whenever the interactions it gives for the same facts change. */
  public static final String VERSION = "1";

  public static final String COMPONENT_LINK = "COMPONENT_LINK";
  public static final String DATA_ACCESS = "data-access";

  /** The kinds of component link. */
  public static final String APPLIES = "applies";

  public static final String CALLS = "calls";
  public static final String ADVISES_KIND = "advises";
  public static final String CODE_END = "code-end";

  private static final Origin ORIGIN = new Origin(Origin.Kind.STATIC, NAME, VERSION);
  private static final String METHOD = "method";
  private static final String GATEWAY_ROUTE = "gateway-route";
  private static final String ADVISES = "ADVISES";
  private static final String ASPECT = "aspect";
  private static final String REPOSITORY_FOR = "REPOSITORY_FOR";
  private static final String READS_TABLE = "READS_TABLE";
  private static final String WRITES_TABLE = "WRITES_TABLE";
  private static final String DEPLOYABLE_UNIT = "deployable-unit";

  private static final Set<String> READS =
      Set.of(LINKED, Components.PART_OF, Components.ANCHORED_ON, Components.HAS_MEMBER);

  @Override
  public RuleInfo info() {
    return new RuleInfo(NAME, VERSION, Origin.Kind.STATIC);
  }

  @Override
  public Set<String> reads() {
    return READS;
  }

  @Override
  public Set<String> writes() {
    return Set.of(COMPONENT_LINK, DATA_ACCESS);
  }

  /** The vocabulary types of the interactions' facts besides the built-in ones. */
  public static List<VocabularyType> vocabulary() {
    return Derivation.load("/vocabulary/component-interactions-types.json");
  }

  @Override
  public List<Fact> derive(List<Fact> facts, long snapshot) {
    Knowledge knowledge = new Knowledge(facts);
    Validity validity = Validity.from(snapshot);
    // One fact per claim: the facts it rests on gathered in the order found.
    Map<String, Line> lines = new LinkedHashMap<>();
    List<Fact> derived = new ArrayList<>();
    SortedSet<EntityId> units = new TreeSet<>(Knowledge.BY_ID);
    knowledge.withPredicate(DEPLOYABLE_UNIT).forEach(f -> units.add(f.subject()));
    Map<EntityId, Map<EntityId, EntityId>> componentsOf = new TreeMap<>(Knowledge.BY_ID);
    for (EntityId unit : units) {
      // The unit's components and which holds each member type.
      Map<EntityId, SortedSet<EntityId>> members = new TreeMap<>(Knowledge.BY_ID);
      Map<EntityId, EntityId> mainOf = new TreeMap<>(Knowledge.BY_ID);
      Map<EntityId, EntityId> componentOf = new TreeMap<>(Knowledge.BY_ID);
      for (Fact part : knowledge.withPredicate(Components.PART_OF)) {
        if (!Knowledge.isEntity(part) || !Knowledge.object(part).equals(unit)) {
          continue;
        }
        EntityId component = part.subject();
        SortedSet<EntityId> types = new TreeSet<>(Knowledge.BY_ID);
        knowledge
            .relations(component, Components.HAS_MEMBER)
            .forEach(f -> types.add(Knowledge.object(f)));
        members.put(component, types);
        knowledge
            .relations(component, Components.ANCHORED_ON)
            .forEach(f -> mainOf.put(component, Knowledge.object(f)));
        types.forEach(t -> componentOf.put(t, component));
      }
      for (Map.Entry<EntityId, SortedSet<EntityId>> entry : members.entrySet()) {
        EntityId component = entry.getKey();
        EntityId main = mainOf.get(component);
        if (main == null) {
          continue;
        }
        // Routes to the filters they apply.
        for (EntityId member : withMethods(knowledge, main)) {
          for (Fact route : knowledge.attributes(member)) {
            if (!route.predicate().equals(GATEWAY_ROUTE)) {
              continue;
            }
            for (String filter : strings(route, "filterTypes")) {
              EntityId to = componentOf.get(new EntityId(BuiltInTypes.TYPE, filter));
              if (to != null && !to.equals(component)) {
                line(
                        lines,
                        component,
                        to,
                        APPLIES,
                        BuiltInTypes.CALLS,
                        member,
                        filter + " " + route.object().value())
                    .add(route, route.object().value().toString());
              }
            }
          }
        }
        // Calls into other components' code.
        for (EntityId type : entry.getValue()) {
          for (EntityId member : withMethods(knowledge, type)) {
            for (Fact call : knowledge.relations(member, BuiltInTypes.CALLS)) {
              EntityId called = Knowledge.object(call);
              for (EntityId target :
                  implementers(knowledge, Components.ownerType(knowledge, called))) {
                EntityId to = componentOf.get(target);
                if (to != null && !to.equals(component)) {
                  line(
                          lines,
                          component,
                          to,
                          CALLS,
                          BuiltInTypes.CALLS,
                          member,
                          called + " " + target)
                      .add(call, methodName(called));
                }
              }
            }
          }
        }
        // An aspect to the components whose entry points it advises.
        for (Fact advice : knowledge.relations(main, ADVISES)) {
          EntityId advised = Knowledge.object(advice);
          EntityId to = componentOf.get(Components.ownerType(knowledge, advised));
          if (to != null && !to.equals(component)) {
            line(lines, component, to, ADVISES_KIND, ADVISES, main, advised.toString())
                .add(advice, advisedBy(knowledge, main));
          }
        }
        dataAccess(knowledge, component, entry.getValue(), main, validity).ifPresent(derived::add);
      }
      componentsOf.put(unit, componentOf);
    }
    // The code end of each link between containers that is in a component of the unit at that
    // end: the source for the unit the link is from, the target for the unit it goes to.
    for (Fact link : facts) {
      if (attribute(link, "source") == null && attribute(link, "target") == null) {
        continue;
      }
      List<Object[]> ends = new ArrayList<>();
      ends.add(new Object[] {link.subject(), "source"});
      if (Knowledge.isEntity(link) && !Knowledge.object(link).equals(link.subject())) {
        ends.add(new Object[] {Knowledge.object(link), "target"});
      }
      for (Object[] end : ends) {
        EntityId unit = (EntityId) end[0];
        String side = (String) end[1];
        Map<EntityId, EntityId> componentOf = componentsOf.get(unit);
        EntityId at = parse(attribute(link, side));
        if (componentOf == null || at == null) {
          continue;
        }
        EntityId component = componentOf.get(Components.ownerType(knowledge, at));
        if (component == null) {
          continue;
        }
        EntityId other =
            side.equals("source") && Knowledge.isEntity(link)
                ? Knowledge.object(link)
                : link.subject();
        line(lines, component, other, CODE_END, link.predicate(), at, side + " " + other)
            .add(link, null);
      }
    }
    for (Line line : lines.values()) {
      derived.add(line.fact(validity));
    }
    return derived;
  }

  private static Line line(
      Map<String, Line> lines,
      EntityId from,
      EntityId to,
      String kind,
      String relationship,
      EntityId at,
      String target) {
    String key = from + " " + to + " " + kind + " " + relationship + " " + at + " " + target;
    return lines.computeIfAbsent(
        key, k -> new Line(from, to, kind, relationship, at.toString(), target));
  }

  /** A component link being gathered: what it rests on and the detail its line shows. */
  private static final class Line {
    final EntityId from;
    final EntityId to;
    final String kind;
    final String relationship;
    final String at;
    final String target;
    String detail;
    final List<Fact> facts = new ArrayList<>();

    Line(EntityId from, EntityId to, String kind, String relationship, String at, String target) {
      this.from = from;
      this.to = to;
      this.kind = kind;
      this.relationship = relationship;
      this.at = at;
      this.target = target;
    }

    Line add(Fact fact, String shown) {
      if (facts.stream().noneMatch(f -> f.id().equals(fact.id()))) {
        facts.add(fact);
      }
      if (shown != null) {
        detail = shown;
      }
      return this;
    }

    Fact fact(Validity validity) {
      ObjectNode attributes = FactJson.newObject();
      attributes.put("kind", kind);
      attributes.put("relationship", relationship);
      attributes.put("at", at);
      attributes.put("target", target);
      if (detail != null) {
        attributes.put("detail", detail);
      }
      ArrayNode cites = attributes.putArray("facts");
      facts.forEach(f -> cites.add(f.id()));
      Evidence evidence = facts.getFirst().evidence();
      return Fact.create(
          from, COMPONENT_LINK, FactObject.entity(to), attributes, ORIGIN, evidence, 1.0, validity);
    }
  }

  /** How a component reaches its unit's databases, if it does: its {@code data-access} fact. */
  private static java.util.Optional<Fact> dataAccess(
      Knowledge knowledge,
      EntityId component,
      Set<EntityId> types,
      EntityId main,
      Validity validity) {
    boolean reads = false;
    boolean writes = false;
    List<Fact> basis = new ArrayList<>();
    String access = null;
    for (EntityId type : types) {
      List<Fact> repository = knowledge.relations(type, REPOSITORY_FOR);
      if (!repository.isEmpty()) {
        access = "Reads and writes";
        basis = new ArrayList<>(repository);
        break;
      }
      for (EntityId member : withMethods(knowledge, type)) {
        List<Fact> read = knowledge.relations(member, READS_TABLE);
        List<Fact> written = knowledge.relations(member, WRITES_TABLE);
        reads |= !read.isEmpty();
        writes |= !written.isEmpty();
        basis.addAll(read);
        basis.addAll(written);
      }
    }
    if (access == null) {
      access = reads && writes ? "Reads and writes" : reads ? "Reads" : writes ? "Writes" : null;
    }
    if (access == null || basis.isEmpty()) {
      return java.util.Optional.empty();
    }
    ObjectNode attributes = FactJson.newObject();
    ArrayNode cites = attributes.putArray("facts");
    basis.forEach(f -> cites.add(f.id()));
    return java.util.Optional.of(
        Fact.create(
            component,
            DATA_ACCESS,
            FactObject.string(access),
            attributes,
            ORIGIN,
            basis.getFirst().evidence(),
            1.0,
            validity));
  }

  /** The annotations an aspect's pointcuts select by, as written in code: {@code @Name}. */
  private static String advisedBy(Knowledge knowledge, EntityId aspect) {
    List<String> names = new ArrayList<>();
    for (Fact fact : knowledge.attributes(aspect)) {
      if (fact.predicate().equals(ASPECT)) {
        for (String type : strings(fact, "annotations")) {
          names.add(
              "@" + type.substring(Math.max(type.lastIndexOf('.'), type.lastIndexOf('$')) + 1));
        }
      }
    }
    return names.isEmpty() ? null : String.join(", ", names);
  }

  /** The type and the types implementing it, if it is an interface. */
  private static Set<EntityId> implementers(Knowledge knowledge, EntityId type) {
    Set<EntityId> result = new LinkedHashSet<>(List.of(type));
    for (Fact fact : knowledge.withPredicate(BuiltInTypes.IMPLEMENTS)) {
      if (Knowledge.isEntity(fact) && Knowledge.object(fact).equals(type)) {
        result.add(fact.subject());
      }
    }
    return result;
  }

  private static List<EntityId> withMethods(Knowledge knowledge, EntityId type) {
    List<EntityId> members = new ArrayList<>(List.of(type));
    members.addAll(knowledge.parts(type, METHOD));
    return members;
  }

  /** A method's name without its parameters. */
  public static String methodName(EntityId method) {
    String key = method.key();
    String name = key.substring(key.indexOf('#') + 1);
    int paren = name.indexOf('(');
    return paren < 0 ? name : name.substring(0, paren);
  }

  private static EntityId parse(String code) {
    if (code == null) {
      return null;
    }
    try {
      return EntityId.parse(code);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static String attribute(Fact fact, String name) {
    JsonNode value = fact.attributes().get(name);
    return value == null || value.isNull() ? null : value.asString();
  }

  private static List<String> strings(Fact fact, String name) {
    JsonNode value = fact.attributes().get(name);
    List<String> result = new ArrayList<>();
    if (value != null && value.isArray()) {
      value.forEach(v -> result.add(v.asString()));
    }
    return result;
  }
}

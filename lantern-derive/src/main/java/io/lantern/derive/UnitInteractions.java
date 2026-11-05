package io.lantern.derive;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.types.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * The interactions each unit or frontend holds: those Describe asks its item about, and the
 * unknowns page lists when no intent is stored for them (ADR 0103, amendments for #738 and #741).
 * Every interaction a view can draw is held by one unit, apart from a component's use of a store,
 * which takes its unit's intent for the store (#739) and is listed here apart ({@link
 * #dataAccess}).
 */
public final class UnitInteractions {

  /** The links a unit's relationships are drawn from, as the container diagram draws them. */
  public static final List<String> LINKS =
      List.of(
          Types.CALLS_HTTP,
          Types.SENDS_MAIL,
          Types.USES_IDENTITY_PROVIDER,
          Types.PUBLISHES,
          Types.CONSUMES,
          Types.USES_DATABASE,
          Types.ACCESSES_DATA_OF);

  /** What kind of interaction a unit holds, in the order a unit's item shows them. */
  public enum Kind {
    /** One of the {@link #LINKS} from the unit. */
    LINK,
    /** Another line of the container diagrams, such as what Investigate found or a route. */
    LINE,
    /** Another interaction with a unit or frontend, which the flows draw. */
    UNIT_LINK,
    /**
     * A message the unit pushes to the browsers subscribed to a STOMP destination, which a flow
     * draws as a message to them (#745, #738).
     */
    PUSH,
    /** A line between two of the unit's components. */
    COMPONENT_LINK,
    /** A person's use of the frontend. */
    PERSON,
    /** A component's use of a store the unit uses. */
    DATA_ACCESS
  }

  /**
   * An interaction a unit holds.
   *
   * @param kind what kind it is
   * @param unit the unit or frontend that holds it
   * @param fact its fact: the link, the line's fact, the push, the component link, the person's
   *     basis or the component's data access
   * @param line the diagram line it is drawn as, for a {@link Kind#LINE}; else null
   * @param from where it starts: the unit, the line's start, the component, or the person
   * @param to where it ends: the other element, the subscribed browsers, the component or the store
   * @param through for a {@link Kind#DATA_ACCESS}, the unit's link to the store, whose intent it
   *     takes; else null
   */
  public record Held(
      Kind kind,
      EntityId unit,
      Fact fact,
      Elements.Line line,
      EntityId from,
      EntityId to,
      Fact through) {}

  private final Knowledge knowledge;
  private final Elements elements;
  private final UnaryOperator<List<Fact>> order;
  private final Set<Fact> alternatives;
  private final Set<EntityId> units;
  private Map<EntityId, List<Elements.Line>> otherLines;
  private Map<EntityId, List<Fact>> pushes;

  /**
   * @param knowledge the facts
   * @param elements what the diagrams draw
   * @param order how facts of one kind are ordered, as a unit's item shows them
   */
  public UnitInteractions(Knowledge knowledge, Elements elements, UnaryOperator<List<Fact>> order) {
    this.knowledge = Objects.requireNonNull(knowledge, "knowledge");
    this.elements = Objects.requireNonNull(elements, "elements");
    this.order = Objects.requireNonNull(order, "order");
    this.alternatives = elements.alternativeLinks().keySet();
    Set<EntityId> all = new TreeSet<>(Knowledge.BY_ID);
    all.addAll(Elements.units(knowledge));
    all.addAll(Elements.frontends(knowledge));
    this.units = all;
  }

  /** The units and frontends, each of which holds its interactions. */
  public Set<EntityId> units() {
    return units;
  }

  /**
   * The interactions the unit holds, other than data access, in the order its item shows them: its
   * links to other containers, every other line of the diagrams it holds, its other interactions
   * with units, its pushes to STOMP subscribers, the lines between its components and the people
   * who use it.
   */
  public List<Held> of(EntityId unit) {
    List<Held> result = new ArrayList<>();
    Set<Fact> held = new HashSet<>();
    for (String predicate : LINKS) {
      for (Fact link : order.apply(knowledge.relations(unit, predicate))) {
        if (!alternatives.contains(link) && held.add(link)) {
          result.add(new Held(Kind.LINK, unit, link, null, unit, Knowledge.object(link), null));
        }
      }
    }
    for (Elements.Line line : otherLines().getOrDefault(unit, List.of())) {
      // Every interaction a line draws, not only its first: each is counted on its own.
      for (Fact fact : line.facts()) {
        if ((fact == line.facts().getFirst()
                || elements.vocabulary().isA(fact.predicate(), Types.INTERACTS_WITH))
            && held.add(fact)) {
          result.add(new Held(Kind.LINE, unit, fact, line, line.from(), line.to(), null));
        }
      }
    }
    for (Fact link : order.apply(knowledge.about(unit))) {
      if (Knowledge.isEntity(link)
          && elements.vocabulary().isA(link.predicate(), Types.INTERACTS_WITH)
          && !alternatives.contains(link)
          && units.contains(Knowledge.object(link))
          && held.add(link)) {
        result.add(new Held(Kind.UNIT_LINK, unit, link, null, unit, Knowledge.object(link), null));
      }
    }
    for (Fact push : order.apply(pushes().getOrDefault(unit, List.of()))) {
      result.add(new Held(Kind.PUSH, unit, push, null, unit, ScopeFlows.SUBSCRIBERS, null));
    }
    Set<EntityId> components = new java.util.LinkedHashSet<>();
    elements.components(unit).forEach(c -> components.add(Components.id(unit, c.main())));
    for (EntityId component : components) {
      for (Fact line : order.apply(knowledge.relations(component, Types.COMPONENT_LINK))) {
        if (!"code-end".equals(Elements.attribute(line, "kind", ""))
            && components.contains(Knowledge.object(line))) {
          result.add(
              new Held(
                  Kind.COMPONENT_LINK, unit, line, null, component, Knowledge.object(line), null));
        }
      }
    }
    // A person who uses the frontend: by its name in the person's basis, or by a line the
    // diagrams draw from the person to it, whose basis may name it otherwise.
    String name = elements.names().name(unit);
    Set<EntityId> drawn = new HashSet<>();
    for (Elements.Line line : elements.lines()) {
      if (line.to().equals(unit) && line.from().type().equals(Types.PERSON)) {
        drawn.add(line.from());
      }
    }
    for (Map.Entry<EntityId, Fact> person : elements.people().entrySet()) {
      String key = unit.key().substring(unit.key().lastIndexOf(':') + 1);
      if (Elements.strings(person.getValue(), "frontends").stream()
              .anyMatch(n -> n.equalsIgnoreCase(name) || n.equalsIgnoreCase(key))
          || drawn.contains(person.getKey())) {
        result.add(
            new Held(Kind.PERSON, unit, person.getValue(), null, person.getKey(), unit, null));
      }
    }
    return result;
  }

  /**
   * The unit's components' uses of its stores: one per component with a data access and store the
   * unit uses, drawn as a line of the component diagram that takes the unit's intent for the store.
   */
  public List<Held> dataAccess(EntityId unit) {
    List<Held> result = new ArrayList<>();
    List<Fact> stores =
        order.apply(knowledge.relations(unit, Types.USES_DATABASE)).stream()
            .filter(f -> !alternatives.contains(f))
            .toList();
    for (Elements.Component component : elements.components(unit)) {
      EntityId id = Components.id(unit, component.main());
      for (Fact access : knowledge.attributes(id)) {
        if (!access.predicate().equals(Types.DATA_ACCESS)) {
          continue;
        }
        for (Fact store : stores) {
          result.add(
              new Held(Kind.DATA_ACCESS, unit, access, null, id, Knowledge.object(store), store));
        }
      }
    }
    return result;
  }

  /**
   * The {@code stomp-send} facts a stored flow pushes from each unit (#738): each step of kind
   * {@link FlowModel#STOMP_PUSH}, by the unit it stands over, follows its push first. A push no
   * flow reaches is drawn by no view. The scorer reads the same steps (`interactionsOf`,
   * lantern-web/scripts/roles.mjs).
   */
  private Map<EntityId, List<Fact>> pushes() {
    if (pushes != null) {
      return pushes;
    }
    Map<EntityId, Set<Fact>> result = new LinkedHashMap<>();
    for (FlowModel.Flow flow : FlowModel.read(knowledge)) {
      pushes(flow.steps(), result);
    }
    Map<EntityId, List<Fact>> lists = new LinkedHashMap<>();
    result.forEach((unit, facts) -> lists.put(unit, List.copyOf(facts)));
    pushes = lists;
    return lists;
  }

  private static void pushes(List<FlowModel.Step> steps, Map<EntityId, Set<Fact>> result) {
    for (FlowModel.Step step : steps) {
      if (step.action().equals(FlowModel.STOMP_PUSH)
          && !step.over().isEmpty()
          && !step.facts().isEmpty()) {
        result
            .computeIfAbsent(step.over().getFirst(), u -> new java.util.LinkedHashSet<>())
            .add(step.facts().getFirst());
      }
      for (FlowModel.Operand operand : step.operands()) {
        pushes(operand.steps(), result);
      }
    }
  }

  /**
   * Every line of the diagrams that the {@link #LINKS} leave out, other than a person's, by the
   * unit or frontend that holds it (#738): what Investigate found, a unit's other interactions, the
   * unknown caller's lines and routes between exchanges and queues. A line is held by the unit its
   * link starts from, else by the unit or frontend at either end; a route between two destinations
   * by the first unit, in ID order, with a line to either of its ends. The scorer gives each
   * interaction its owner by the same rule (`interactionsOf`, lantern-web/scripts/roles.mjs), so
   * this page's "Intent not known" and its "don't know" count the same: the two must match (#741).
   */
  private Map<EntityId, List<Elements.Line>> otherLines() {
    if (otherLines != null) {
      return otherLines;
    }
    Map<EntityId, List<Elements.Line>> result = new LinkedHashMap<>();
    List<Elements.Line> lines = elements.lines();
    for (Elements.Line line : lines) {
      if (LINKS.contains(line.relationship())
          || line.from().type().equals(Types.PERSON)
          || line.facts().isEmpty()) {
        continue;
      }
      EntityId owner =
          units.contains(line.facts().getFirst().subject())
              ? line.facts().getFirst().subject()
              : units.contains(line.from())
                  ? line.from()
                  : units.contains(line.to()) ? line.to() : null;
      if (owner == null) {
        Set<EntityId> ends = Set.of(line.from(), line.to());
        owner =
            lines.stream()
                .filter(l -> ends.contains(l.to()) || ends.contains(l.from()))
                .flatMap(l -> Stream.of(l.from(), l.to()))
                .filter(units::contains)
                .min(Knowledge.BY_ID)
                .orElse(null);
      }
      if (owner != null) {
        result.computeIfAbsent(owner, u -> new ArrayList<>()).add(line);
      }
    }
    otherLines = result;
    return result;
  }
}

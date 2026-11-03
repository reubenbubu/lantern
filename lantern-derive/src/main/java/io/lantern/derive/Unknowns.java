package io.lantern.derive;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.derive.attributes.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * What the model of a scope holds as open or unanswered, and what its elements lack
 * (knowledge-build epic, section 12; #700): the links the linker left open and nothing resolved,
 * the leads Investigate could not decide or was not asked about, the units it did not look at, the
 * containers and components without a technology, the elements without a responsibility sentence
 * and the lines between containers without a protocol. The composer lists the first ones on the
 * unknowns page and checks the quality bar with the others, so neither depends on how a diagram
 * happens to be drawn. The unknown caller is not in the model yet (#365).
 */
public final class Unknowns {

  /** A lead's verdict when Investigate could not decide it, or was not asked. */
  public static final String UNCLEAR = "unclear";

  /**
   * A lead Investigate left unclear.
   *
   * @param kind what the lead is about, such as {@code missing-auth}
   * @param units the units it concerns, in scope
   * @param reason why it is unclear: not answered, rejected, not asked; or null
   * @param verdict the verdict fact
   */
  public record Lead(String kind, List<EntityId> units, String reason, Fact verdict) {}

  /** A component of a unit in scope. */
  public record Part(EntityId unit, Elements.Component component) {}

  private final ArchitectureModel model;
  private final Knowledge knowledge;
  private final Elements elements;
  private final SortedSet<EntityId> units;
  private final boolean system;
  private final Predicate<EntityId> summarized;

  /**
   * @param elements what the scope's diagrams show
   * @param units the deployable units and frontends in scope
   * @param system whether the scope is the whole system, whose own facts (what Investigate did not
   *     look at in it) are in scope too
   * @param summarized whether a container's code has a summary (section 11.3), which describes it
   *     where no responsibility sentence does
   */
  public Unknowns(
      ArchitectureModel model,
      Elements elements,
      Set<EntityId> units,
      boolean system,
      Predicate<EntityId> summarized) {
    this.summarized = Objects.requireNonNull(summarized, "summarized");
    this.model = Objects.requireNonNull(model, "model");
    this.knowledge = model.knowledge();
    this.elements = Objects.requireNonNull(elements, "elements");
    this.units = new TreeSet<>(Knowledge.BY_ID);
    this.units.addAll(units);
    this.units.addAll(elements.frontends());
    this.system = system;
  }

  /**
   * The links of the scope's units the linker left open and the LLM resolver did not resolve, by
   * unit, then what the code calls or sends to, the code that does it, the reason, the keys left
   * unresolved, the call site and the profile, so they read the same on every run whatever the
   * facts' IDs (#605).
   */
  public List<Fact> openLinks() {
    List<Fact> result = new ArrayList<>();
    for (EntityId unit : units) {
      knowledge.attributes(unit).stream()
          .filter(f -> f.predicate().equals(Types.UNRESOLVED_LINK) && !knowledge.resolved(f))
          .sorted(
              Comparator.comparing((Fact f) -> f.object().value())
                  .thenComparing(f -> Elements.attribute(f, "source", ""))
                  .thenComparing(f -> Elements.attribute(f, "reason", ""))
                  .thenComparing(f -> String.join(",", Elements.strings(f, "unresolved")))
                  .thenComparing(f -> Elements.attribute(f, "site", ""))
                  .thenComparing(f -> Elements.attribute(f, "view", ""))
                  .thenComparing(Fact::id))
          .forEach(result::add);
    }
    return result;
  }

  /**
   * The leads about the scope's units that Investigate left unclear, a lead it was not asked about
   * included, by kind, then unit, then reason.
   */
  public List<Lead> unclearLeads() {
    List<Lead> result = new ArrayList<>();
    for (Fact verdict : knowledge.withPredicate(Types.LEAD_VERDICT)) {
      if (!verdict.object().value().equals(UNCLEAR)) {
        continue;
      }
      EntityId lead = verdict.subject();
      List<EntityId> concerned = new ArrayList<>();
      String kind = null;
      for (Fact trigger : knowledge.about(lead)) {
        if (trigger.predicate().equals(Types.LEAD_TRIGGER)) {
          kind = trigger.object().value();
          for (String unit : Elements.strings(trigger, "units")) {
            EntityId id = EntityId.parse(unit);
            if (units.contains(id) && !concerned.contains(id)) {
              concerned.add(id);
            }
          }
        }
      }
      if (kind != null && !concerned.isEmpty()) {
        concerned.sort(Knowledge.BY_ID);
        result.add(
            new Lead(
                kind,
                List.copyOf(concerned),
                Elements.attribute(verdict, "reason", null),
                verdict));
      }
    }
    result.sort(
        Comparator.comparing(Lead::kind)
            .thenComparing(l -> l.units().getFirst().toString())
            .thenComparing(l -> Objects.toString(l.reason(), ""))
            .thenComparing(l -> l.verdict().id()));
    return result;
  }

  /** The scope's units, and the system for the system's scope, Investigate did not look at. */
  public SortedSet<EntityId> notInvestigated() {
    SortedSet<EntityId> result = new TreeSet<>(Knowledge.BY_ID);
    for (Fact fact : knowledge.withPredicate(Types.NOT_INVESTIGATED)) {
      if (units.contains(fact.subject())
          || (system && fact.subject().equals(ArchitectureModel.SYSTEM))) {
        result.add(fact.subject());
      }
    }
    return result;
  }

  /**
   * The containers in scope with no technology: units, frontends and the data stores they use; a
   * topic, queue or exchange is named by its broker, and an external system or person needs none.
   */
  public SortedSet<EntityId> containersWithoutTechnology() {
    SortedSet<EntityId> result = new TreeSet<>(Knowledge.BY_ID);
    for (EntityId container : containers()) {
      if (!isOutside(container) && elements.technology(container) == null) {
        result.add(container);
      }
    }
    return result;
  }

  /** The components the scope's component diagrams draw with no technology, by unit. */
  public List<Part> componentsWithoutTechnology() {
    List<Part> result = new ArrayList<>();
    for (EntityId unit : units) {
      for (Elements.Component component : drawn(unit)) {
        if (component.technology() == null) {
          result.add(new Part(unit, component));
        }
      }
    }
    return result;
  }

  /**
   * The containers in scope described by no responsibility sentence or summary: the units,
   * frontends and what the container diagrams draw them with, and the people who use them.
   */
  public SortedSet<EntityId> containersWithoutResponsibility() {
    SortedSet<EntityId> result = new TreeSet<>(Knowledge.BY_ID);
    for (EntityId container : containers()) {
      if (model.responsibility(container).isEmpty() && !summarized.test(container)) {
        result.add(container);
      }
    }
    return result;
  }

  /** The components the scope's component diagrams draw without a responsibility sentence. */
  public List<Part> componentsWithoutResponsibility() {
    List<Part> result = new ArrayList<>();
    for (EntityId unit : units) {
      for (Elements.Component component : drawn(unit)) {
        if (model.responsibility(component.main()).isEmpty()) {
          result.add(new Part(unit, component));
        }
      }
    }
    return result;
  }

  /**
   * The lines of the scope's container diagrams between two containers that name no protocol: a
   * line to or from an external system or a person needs none.
   */
  public List<Elements.Line> linesWithoutProtocol() {
    List<Elements.Line> result = new ArrayList<>();
    for (Elements.Line line : elements.lines()) {
      if ((units.contains(line.from()) || units.contains(line.to()))
          && !isOutside(line.from())
          && !isOutside(line.to())
          && line.technology() == null) {
        result.add(line);
      }
    }
    return result;
  }

  /**
   * The scope's containers, in ID order: its units and frontends, then the stores, destinations,
   * external systems and people at the other end of their lines.
   */
  private SortedSet<EntityId> containers() {
    SortedSet<EntityId> result = new TreeSet<>(Knowledge.BY_ID);
    result.addAll(units);
    Set<EntityId> destinations = new LinkedHashSet<>(elements.destinations());
    for (Elements.Line line : elements.lines()) {
      for (EntityId end : List.of(line.from(), line.to())) {
        EntityId other = end.equals(line.from()) ? line.to() : line.from();
        if (units.contains(other) && (destinations.contains(end) || isOutside(end))) {
          result.add(end);
        }
      }
    }
    return result;
  }

  /** A unit's components, when its component diagrams draw them: when it has more than one. */
  private List<Elements.Component> drawn(EntityId unit) {
    List<Elements.Component> components = elements.components(unit);
    return components.size() > 1 ? components : List.of();
  }

  private static boolean isOutside(EntityId element) {
    return element.type().equals(Types.EXTERNAL_SYSTEM) || element.type().equals(Types.PERSON);
  }
}

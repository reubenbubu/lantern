package io.lantern.derive;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.types.Types;
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
 * happens to be drawn. The Unknown caller's lines are among them (#766).
 */
public final class Unknowns {

  /** A lead's verdict when Investigate could not decide it, or was not asked. */
  public static final String UNCLEAR = "unclear";

  /**
   * A lead Investigate left unclear.
   *
   * @param kind what the lead is about, such as {@code missing-auth}
   * @param about the units it concerns, in scope; for a lead about no unit, such as a shared store
   *     that runs as one instance or a Kubernetes Secret, what it points at
   * @param reason why it is unclear: not answered, rejected, not asked; or null
   * @param verdict the verdict fact
   */
  public record Lead(String kind, List<EntityId> about, String reason, Fact verdict) {}

  /**
   * What is wrong with the Unknown caller's lines to the scope's units, checked apart from how they
   * are found ({@link UnknownCallers#violations}); empty when every line holds.
   */
  public List<String> unknownCallerViolations() {
    return UnknownCallers.violations(knowledge).stream()
        .filter(v -> units.stream().anyMatch(u -> v.startsWith("The line to " + u + " ")))
        .toList();
  }

  /**
   * A lead Investigate refuted (#741): what it is about, the finding that refutes it and the file
   * ranges it cites.
   */
  public record Refuted(
      String kind, List<EntityId> about, String finding, List<String> cites, Fact verdict) {}

  /**
   * An item a role did not ask about, or got no answer for (#741).
   *
   * @param role the role
   * @param subject what the item is about
   * @param item the item's subject as the role names it, when it is not an entity
   * @param reason why
   */
  public record NotAsked(String role, EntityId subject, String item, String reason, Fact fact) {}

  /** A line of the Unknown caller: the unit, the line's fact and the entry points it cites. */
  public record Caller(EntityId unit, Fact line, List<Fact> entries) {

    /** Each entry point as its fact states it: an endpoint, a destination or a route. */
    public List<String> entryTexts() {
      return entries.stream().map(e -> e.object().value()).toList();
    }
  }

  /** What an open link's call, send or datasource reads as, as stored. */
  public static String call(Fact openLink) {
    return openLink.object().value();
  }

  /** A component of a unit in scope. */
  public record Part(EntityId unit, Elements.Component component) {}

  private final ArchitectureModel model;
  private final Knowledge knowledge;
  private final Elements elements;
  private final SortedSet<EntityId> units;
  private final boolean system;
  private final Set<String> repos;
  private final Predicate<EntityId> summarized;

  /**
   * @param elements what the scope's diagrams show
   * @param units the deployable units and frontends in scope
   * @param system whether the scope is the whole system, whose own facts (what Investigate did not
   *     look at in it) are in scope too
   * @param repos the repositories in scope, whose leads about no unit are the scope's
   * @param summarized whether a container's code has a summary (section 11.3), which describes it
   *     where no responsibility sentence does
   */
  public Unknowns(
      ArchitectureModel model,
      Elements elements,
      Set<EntityId> units,
      boolean system,
      Set<String> repos,
      Predicate<EntityId> summarized) {
    this.repos = Set.copyOf(repos);
    this.summarized = Objects.requireNonNull(summarized, "summarized");
    this.model = Objects.requireNonNull(model, "model");
    this.knowledge = model.index();
    this.elements = Objects.requireNonNull(elements, "elements");
    this.units = new TreeSet<>(Knowledge.BY_ID);
    this.units.addAll(units);
    this.units.addAll(elements.frontends());
    this.system = system;
  }

  /** Why a link the linker left open was left open, as stored, or {@code null}. */
  public static String reason(Fact openLink) {
    return Elements.attribute(openLink, "reason", null);
  }

  /** The keys a link the linker left open did not resolve, as stored. */
  public static List<String> unresolvedKeys(Fact openLink) {
    return Elements.strings(openLink, "unresolved");
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
   * The leads Investigate left unclear, a lead it was not asked about included: those about the
   * scope's units, and those about no unit that the scope holds (in the system's scope every one,
   * else those found in its repositories). They come by kind, then what they are about, then
   * reason, then the lead, never by a verdict's ID, which names the model asked.
   */
  public List<Lead> unclearLeads() {
    return leads(UNCLEAR).stream()
        .map(
            l ->
                new Lead(
                    l.kind(),
                    l.about(),
                    Elements.attribute(l.verdict(), "reason", null),
                    l.verdict()))
        .toList();
  }

  /**
   * The leads Investigate refuted, of the scope as {@link #unclearLeads()} says, each with the
   * finding that refutes it and what it cites (#741), in the same order.
   */
  public List<Refuted> refutedLeads() {
    return leads("refuted").stream()
        .map(
            l ->
                new Refuted(
                    l.kind(),
                    l.about(),
                    Elements.attribute(l.verdict(), "finding", null),
                    Elements.strings(l.verdict(), "cites"),
                    l.verdict()))
        .toList();
  }

  /**
   * Every item a role did not ask about, or got no answer for (#741): in the system's scope all of
   * them, else those about the scope's units. By role, then subject.
   */
  public List<NotAsked> notAsked() {
    List<NotAsked> result = new ArrayList<>();
    for (Fact fact : knowledge.withPredicate(Types.NOT_ASKED)) {
      if (system || units.contains(fact.subject())) {
        result.add(
            new NotAsked(
                fact.object().value(),
                fact.subject(),
                Elements.attribute(fact, "item", null),
                Elements.attribute(fact, "reason", null),
                fact));
      }
    }
    result.sort(
        Comparator.comparing(NotAsked::role)
            .thenComparing(n -> n.subject().toString())
            .thenComparing(n -> Objects.toString(n.item(), "")));
    return result;
  }

  /** The leads Investigate gave a verdict of this value, of the scope, sorted. */
  private List<Lead> leads(String value) {
    List<Lead> result = new ArrayList<>();
    for (Fact verdict : knowledge.withPredicate(Types.LEAD_VERDICT)) {
      if (!verdict.object().value().equals(value)) {
        continue;
      }
      EntityId lead = verdict.subject();
      List<EntityId> concerned = new ArrayList<>();
      List<EntityId> pointed = new ArrayList<>();
      boolean unitless = true;
      boolean held = system;
      String kind = null;
      for (Fact trigger : knowledge.about(lead)) {
        if (!trigger.predicate().equals(Types.LEAD_TRIGGER)) {
          continue;
        }
        kind = trigger.object().value();
        held |= repos.contains(trigger.evidence().repo());
        for (String unit : Elements.strings(trigger, "units")) {
          unitless = false;
          EntityId id = EntityId.parse(unit);
          if (units.contains(id) && !concerned.contains(id)) {
            concerned.add(id);
          }
        }
        for (String code : Elements.strings(trigger, "code")) {
          EntityId id = EntityId.parse(code);
          if (!pointed.contains(id)) {
            pointed.add(id);
          }
        }
      }
      List<EntityId> about = unitless && held ? pointed : concerned;
      if (kind != null && !about.isEmpty()) {
        about.sort(Knowledge.BY_ID);
        result.add(
            new Lead(
                kind, List.copyOf(about), Elements.attribute(verdict, "reason", null), verdict));
      }
    }
    result.sort(
        Comparator.comparing(Lead::kind)
            .thenComparing(
                l -> String.join("\u0000", l.about().stream().map(EntityId::toString).toList()))
            .thenComparing(l -> Objects.toString(l.reason(), ""))
            .thenComparing(l -> l.verdict().subject().toString()));
    return result;
  }

  /**
   * The Unknown caller's lines to the scope's units (#766): the units whose entry points meant to
   * be called nothing in the set reaches and no person uses, each line citing them.
   */
  public List<Caller> unknownCallers() {
    List<Caller> result = new ArrayList<>();
    for (Fact fact : knowledge.withPredicate(Types.CALLS_UNREACHED)) {
      if (Knowledge.isEntity(fact) && units.contains(Knowledge.object(fact))) {
        List<Fact> entries = new ArrayList<>();
        for (String id : Screens.strings(fact, "facts")) {
          Fact entry = knowledge.fact(id);
          if (entry != null) {
            entries.add(entry);
          }
        }
        result.add(new Caller(Knowledge.object(fact), fact, List.copyOf(entries)));
      }
    }
    result.sort(Comparator.comparing(c -> c.unit().toString()));
    return result;
  }

  /**
   * Every interaction the scope's units hold that has no stored intent (#741): each one Describe
   * asks about ({@link UnitInteractions#of}) without an intent of its own, a person's use of a
   * frontend without the intent stored for that frontend, and a component's use of a store whose
   * unit holds no intent for the store. They come by unit, then in the order each unit holds them.
   */
  public List<UnitInteractions.Held> interactionsWithoutIntent() {
    UnitInteractions held =
        new UnitInteractions(
            knowledge,
            elements,
            facts -> facts.stream().sorted(Comparator.comparing(Fact::id)).toList());
    List<UnitInteractions.Held> result = new ArrayList<>();
    for (EntityId unit : units) {
      if (!held.units().contains(unit)) {
        continue;
      }
      List<UnitInteractions.Held> all = new ArrayList<>(held.of(unit));
      all.addAll(held.dataAccess(unit));
      for (UnitInteractions.Held interaction : all) {
        boolean known =
            switch (interaction.kind()) {
              case PERSON -> model.personIntent(interaction.fact(), unit).isPresent();
              case DATA_ACCESS -> model.intent(interaction.through()).isPresent();
              default -> model.intent(interaction.fact()).isPresent();
            };
        if (!known) {
          result.add(interaction);
        }
      }
    }
    return result;
  }

  /**
   * The container lines Describe words as a whole that hold no intent of their own (ADR 0103,
   * amendment for #810): they merge links whose intents differ, none said by most of them, and the
   * line's own item was not asked, was left out or was rejected.
   */
  public List<MergedLines.Merged> linesWithoutIntent() {
    List<MergedLines.Merged> result = new ArrayList<>();
    for (MergedLines.Merged line : MergedLines.of(elements.lines(), units)) {
      if (MergedLines.needsWording(line, link -> model.intent(link).map(t -> t.text()))
          && model.lineIntent(line.from(), line.to(), line.relationship()).isEmpty()) {
        result.add(line);
      }
    }
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
    SortedSet<EntityId> drawn = containers();
    for (Elements.Line line : elements.lines()) {
      // A line of a unit, or one between two elements the views draw, such as a route from an
      // exchange to a queue.
      if ((units.contains(line.from())
              || units.contains(line.to())
              || (drawn.contains(line.from()) && drawn.contains(line.to())))
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

  private boolean isOutside(EntityId element) {
    return elements.isType(element.type(), Types.EXTERNAL_SYSTEM)
        || elements.isType(element.type(), Types.PERSON);
  }
}

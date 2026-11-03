package io.lantern.compose;

import io.lantern.core.document.Block;
import io.lantern.core.document.Callout;
import io.lantern.core.document.Diagram;
import io.lantern.core.document.Inline;
import io.lantern.core.document.Metrics;
import io.lantern.core.document.Section;
import io.lantern.core.document.Table;
import io.lantern.core.document.TextRun;
import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.derive.ComponentInteractions;
import io.lantern.derive.Components;
import io.lantern.derive.DeploymentInstances;
import io.lantern.derive.Elements;
import io.lantern.derive.Elements.Line;
import io.lantern.derive.Groups;
import io.lantern.derive.Knowledge;
import io.lantern.derive.attributes.Types;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The C4 context, container and component diagrams of a scope, by the definitions of {@code
 * docs/design.md} (Composition, Diagrams): the software system is the scope's system, its
 * containers the deployable units the linker found (ADR 0039) with the databases, topics, queues
 * and exchanges they use, and the lines between them the linker's links (ADR 0042). Components are
 * found by role inside a unit, from Spring stereotypes and the call graph.
 *
 * <p>Nothing is invented: a line is drawn for a link, labelled with the LLM's intent phrase for it
 * when every link of the line has one (ADR 0050), else with what the link records (the endpoints
 * called, the routing keys sent), its protocol as technology and the Spring profiles it alone holds
 * under. People are drawn only when the code shows them (ADR 0074): the linker infers them from
 * roles, memberships, invitations and sign-in, and they are marked inferred; otherwise a note says
 * who uses the system is unknown.
 */
final class C4Views {

  static final String ROUTE = "route";

  /** A decision record's rationale when it has no context section. */
  static final String NOT_RECORDED = "Not recorded.";

  /** The most elements a diagram shows before it is split (docs/design.md, Quality bar). */
  static final int MAX_ELEMENTS = 15;

  /** How many details an edge label names before it counts the rest. */
  private static final int NAMED = 2;

  private final Knowledge knowledge;
  private final Assembler assembler;
  private final EntityId system;
  private final SortedSet<EntityId> units;

  private final Set<String> repos;

  /** What the diagrams show, read from the model (#717). */
  private final Elements elements;

  private final List<Line> links;

  /** Each profile-only link that replaces a default one under its profile, with that default. */
  private final Map<Fact, Fact> alternatives;

  /** The frontends in scope: those of the scope's repositories (ADR 0083). */
  private final SortedSet<EntityId> frontends;

  /**
   * @param system the software system the scope's containers belong to
   * @param units the deployable units in scope
   * @param repos the repositories in scope, whose decision records are listed
   */
  C4Views(
      Knowledge knowledge,
      Assembler assembler,
      EntityId system,
      SortedSet<EntityId> units,
      Set<String> repos) {
    this.knowledge = knowledge;
    this.assembler = assembler;
    this.system = system;
    this.units = units;
    this.repos = repos;
    this.elements = new Elements(knowledge, assembler.vocabulary(), assembler.names(), repos);
    this.links = elements.lines();
    this.alternatives = elements.alternativeLinks();
    this.frontends = elements.frontends();
  }

  /** What the diagrams show, read from the model. */
  Elements elements() {
    return elements;
  }

  /**
   * A note listing the alternatives of the units' default links under profiles, those to external
   * systems only if asked.
   */
  private Optional<Callout> alternativesNote(String id, Set<EntityId> of, boolean externalOnly) {
    List<Inline> content = new ArrayList<>();
    List<Fact> facts = new ArrayList<>();
    for (Map.Entry<Fact, Fact> entry : alternatives.entrySet()) {
      Fact profiled = entry.getKey();
      Fact fallback = entry.getValue();
      EntityId target = Knowledge.object(profiled);
      if (!of.contains(profiled.subject())
          || (externalOnly && !target.type().equals(Types.EXTERNAL_SYSTEM))) {
        continue;
      }
      String verb =
          switch (profiled.predicate()) {
            case Types.CALLS_HTTP -> "calls";
            case Types.SENDS_MAIL -> "sends mail through";
            case Types.USES_IDENTITY_PROVIDER -> "uses as its identity provider";
            case Types.PUBLISHES -> "sends to";
            case Types.CONSUMES -> "listens on";
            case Types.USES_DATABASE -> "connects to";
            default -> "reads tables of";
          };
      assembler.entity(target);
      content.add(
          TextRun.of(
              (content.isEmpty() ? "" : " ")
                  + "Under the Spring profile "
                  + Knowledge.view(profiled)
                  + ", "
                  + assembler.name(profiled.subject())
                  + " "
                  + verb
                  + " "
                  + assembler.name(target)
                  + " instead of "
                  + assembler.name(Knowledge.object(fallback))
                  + "."));
      facts.add(profiled);
      facts.add(fallback);
    }
    if (content.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new Callout(
            id,
            Callout.Variant.NOTE,
            null,
            "Under other profiles",
            content,
            assembler.facts(facts)));
  }

  private static String attribute(Fact fact, String name) {
    return RepoSections.attribute(fact, name, null);
  }

  /** An edge being built from links: its facts, details, technologies and profiles. */
  private static final class EdgeBuilder {
    final EntityId from;
    final String relationship;
    final EntityId to;
    String plain;
    String prefix;
    final Set<String> details = new TreeSet<>();
    final Set<String> technologies = new TreeSet<>();
    final Set<String> vias = new TreeSet<>();
    final Set<String> profiles = new TreeSet<>();
    boolean always;
    final List<Fact> facts = new ArrayList<>();

    /**
     * The intent phrases of the edge's links with how many links say each, used as its label only
     * if every link has one.
     */
    final SortedMap<String, Integer> phrases = new TreeMap<>();

    boolean unphrased;

    /** The intent Name groups stored for a line leaving a group, which labels it; or null. */
    String intent;

    EdgeBuilder(EntityId from, String relationship, EntityId to) {
      this.from = from;
      this.relationship = relationship;
      this.to = to;
    }

    void add(Line link, Assembler.Phrased phrase) {
      add(link.plain(), link.prefix(), link.detail(), link.technology(), link.profile());
      if (link.via() != null) {
        vias.add(link.via());
      }
      facts.addAll(link.facts());
      phrased(phrase);
    }

    /**
     * Adds the intent phrase of a link of the edge, with the facts it cites, or marks the edge as
     * having a link without one, which keeps its static label.
     */
    void phrased(Assembler.Phrased phrase) {
      if (phrase == null) {
        unphrased = true;
      } else {
        phrases.merge(phrase.text(), 1, Integer::sum);
        facts.addAll(phrase.facts());
      }
    }

    /** The technology line: the protocols, then what the line goes through. */
    String technology() {
      String protocols = String.join(", ", technologies);
      if (vias.isEmpty()) {
        return protocols.isEmpty() ? null : protocols;
      }
      String through = String.join(", ", vias);
      return protocols.isEmpty() ? through : protocols + " · " + through;
    }

    void add(String plain, String prefix, String detail, String technology, String profile) {
      if (this.plain == null) {
        this.plain = plain;
        this.prefix = prefix;
      } else if (prefix != null
          && this.prefix != null
          && !this.prefix.toLowerCase(Locale.ROOT).contains(prefix.toLowerCase(Locale.ROOT))) {
        // A line that both routes requests and calls (a gateway that also calls a service it
        // routes to) says both, in a stable order.
        List<String> both = new ArrayList<>(List.of(this.prefix, prefix));
        both.sort(null);
        this.prefix = both.get(0) + " and " + both.get(1).toLowerCase(Locale.ROOT);
        List<String> plains = new ArrayList<>(List.of(this.plain, plain));
        plains.sort(null);
        this.plain = plains.get(0) + " and " + lowerFirst(plains.get(1));
      }
      if (detail != null && prefix != null) {
        details.add(detail);
      }
      if (technology != null) {
        technologies.add(technology);
      }
      if (profile == null) {
        always = true;
      } else {
        profiles.add(profile);
      }
    }

    /**
     * The intent phrase of its links when every link has one: one intent per line, the one most of
     * its links say (the first alphabetically on a tie); the rest stay with the line's facts. Else
     * what the links record.
     */
    String label() {
      if (intent != null) {
        return intent;
      }
      if (!unphrased && !phrases.isEmpty()) {
        return phrases.entrySet().stream()
            .max(
                Map.Entry.<String, Integer>comparingByValue()
                    .thenComparing(Map.Entry.<String, Integer>comparingByKey().reversed()))
            .orElseThrow()
            .getKey();
      }
      if (details.isEmpty()) {
        return plain;
      }
      List<String> named = new ArrayList<>(details);
      String text = String.join(", ", named.subList(0, Math.min(NAMED, named.size())));
      if (named.size() > NAMED) {
        text += " and " + (named.size() - NAMED) + " more";
      }
      return prefix + " " + text;
    }
  }

  /** A phrase's first letter in lower case, unless it starts an acronym or a name in capitals. */
  static String lowerFirst(String phrase) {
    if (phrase.length() > 1
        && Character.isUpperCase(phrase.charAt(0))
        && Character.isLowerCase(phrase.charAt(1))) {
      return Character.toLowerCase(phrase.charAt(0)) + phrase.substring(1);
    }
    return phrase;
  }

  /** Edges by their ends and relationship, sorted so diagrams do not depend on fact order. */
  private final class Edges {
    private final SortedMap<String, EdgeBuilder> byKey = new TreeMap<>();

    EdgeBuilder get(EntityId from, String relationship, EntityId to) {
      return byKey.computeIfAbsent(
          from + " " + relationship + " " + to, k -> new EdgeBuilder(from, relationship, to));
    }

    void add(Line link) {
      add(link.from(), link.to(), link);
    }

    /**
     * Adds a link drawn between the given ends, which a view lifted it to: a group, or the element
     * itself. A line leaving a group takes the intent Name groups stored for it (ADR 0107).
     */
    void add(EntityId from, EntityId to, Line link) {
      EdgeBuilder edge = get(from, link.relationship(), to);
      edge.add(link, assembler.phrase(link.facts().getFirst()));
      if (from.type().equals(Types.GROUP) && edge.intent == null) {
        Assembler.Phrased intent = assembler.groupIntent(from, to + " " + link.relationship());
        if (intent != null) {
          edge.intent = intent.text();
          edge.facts.addAll(intent.facts());
        }
      }
    }

    List<Diagram.Edge> build(Assembler assembler, boolean withTechnology) {
      List<Diagram.Edge> result = new ArrayList<>();
      Set<String> ids = new HashSet<>();
      for (EdgeBuilder edge : byKey.values()) {
        String technology = withTechnology ? edge.technology() : null;
        result.add(
            new Diagram.Edge(
                Assembler.edgeId(ids, edge.from, edge.relationship, edge.to),
                edge.from,
                edge.to,
                edge.label(),
                technology,
                edge.relationship,
                edge.always ? List.of() : List.copyOf(edge.profiles),
                assembler.facts(edge.facts)));
      }
      return result;
    }
  }

  // System context.

  /**
   * The system context: the scope's system, the external systems its units call and the lines to
   * them, without technology; then a note that its users are unknown.
   */
  List<Block> context(String section) {
    Map<EntityId, Diagram.Node> nodes = new TreeMap<>(Knowledge.BY_ID);
    nodes.put(system, node(system, null, null, List.of()));
    Edges edges = new Edges();
    for (Line link : links) {
      if ((units.contains(link.from()) || frontends.contains(link.from()))
          && link.to().type().equals(Types.EXTERNAL_SYSTEM)) {
        nodes.computeIfAbsent(link.to(), e -> node(e, null, null, List.of()));
        EdgeBuilder edge = edges.get(system, link.relationship(), link.to());
        edge.add(link.plain(), link.prefix(), link.detail(), null, link.profile());
        edge.facts.addAll(link.facts());
        edge.phrased(assembler.phrase(link.facts().getFirst()));
      }
    }
    SortedMap<EntityId, Fact> people = elements.people();
    for (Map.Entry<EntityId, Fact> person : people.entrySet()) {
      List<Fact> cited = cited(person.getValue());
      nodes.put(person.getKey(), node(person.getKey(), null, null, cited));
      EdgeBuilder edge = edges.get(person.getKey(), Elements.USES, system);
      List<String> frontends = Elements.strings(person.getValue(), "frontends");
      edge.add(
          frontends.isEmpty() ? "Uses" : "Uses, through " + String.join(", ", frontends),
          null,
          null,
          null,
          null);
      edge.facts.addAll(cited);
      edge.phrased(null);
    }
    List<Block> blocks = new ArrayList<>();
    blocks.add(counts(section, people));
    blocks.add(
        new Diagram(
            assembler.id(section, "diagram"),
            Diagram.Kind.C4_CONTEXT,
            "System context of " + assembler.name(system),
            List.copyOf(nodes.values()),
            edges.build(assembler, false),
            List.of()));
    alternativesNote(assembler.id(section, "profiles"), units, true).ifPresent(blocks::add);
    blocks.add(
        new Callout(
            assembler.id(section, "people"),
            Callout.Variant.NOTE,
            null,
            null,
            List.of(
                TextRun.of(
                    people.isEmpty()
                        ? "The code names no people or user roles, so who uses "
                            + assembler.name(system)
                            + " is not known."
                        : "These people are inferred from the code. Who else uses "
                            + assembler.name(system)
                            + " is not known.")),
            List.of()));
    return blocks;
  }

  /** The facts a derived fact was made from, named by its {@code facts} attribute. */
  private List<Fact> basis(Fact derived) {
    List<Fact> facts = new ArrayList<>();
    for (String id : Elements.strings(derived, "facts")) {
      Fact fact = knowledge.fact(id);
      if (fact != null) {
        facts.add(fact);
      }
    }
    return facts;
  }

  /** The facts a person rests on, with its own. */
  private List<Fact> cited(Fact basis) {
    List<Fact> facts = new ArrayList<>(List.of(basis));
    for (String id : Elements.strings(basis, "cites")) {
      Fact fact = knowledge.fact(id);
      if (fact != null) {
        facts.add(fact);
      }
    }
    return facts;
  }

  /**
   * How many deployable units the scope has, and how many data stores, queues and external systems
   * they use, each citing the facts it counts.
   */
  private Metrics counts(String section, SortedMap<EntityId, Fact> people) {
    List<Fact> unitFacts = new ArrayList<>();
    units.forEach(u -> unitFacts.addAll(elements.containerFacts(u)));
    Map<String, SortedMap<EntityId, List<Fact>>> used = new TreeMap<>();
    for (String kind : List.of(Types.DATABASE, Types.QUEUE, Types.EXTERNAL_SYSTEM)) {
      used.put(kind, new TreeMap<>(Knowledge.BY_ID));
    }
    for (Line link : links) {
      for (EntityId end : List.of(link.from(), link.to())) {
        EntityId other = end.equals(link.from()) ? link.to() : link.from();
        String kind =
            switch (end.type()) {
              case Types.DATABASE -> Types.DATABASE;
              case Types.TOPIC, Types.QUEUE, Types.EXCHANGE -> Types.QUEUE;
              case Types.EXTERNAL_SYSTEM -> Types.EXTERNAL_SYSTEM;
              default -> null;
            };
        if (kind != null && (units.contains(other) || frontends.contains(other))) {
          used.get(kind).computeIfAbsent(end, e -> new ArrayList<>()).addAll(link.facts());
        }
      }
    }
    List<Metrics.Item> items = new ArrayList<>();
    items.add(metric("Deployable units", units.size(), unitFacts));
    if (!frontends.isEmpty()) {
      List<Fact> apps = new ArrayList<>();
      frontends.forEach(f -> elements.frontendApp(f).ifPresent(apps::add));
      items.add(metric("Frontends", frontends.size(), apps));
    }
    items.add(metric("Databases", used.get(Types.DATABASE)));
    items.add(metric("Topics, queues and exchanges", used.get(Types.QUEUE)));
    items.add(metric("External systems", used.get(Types.EXTERNAL_SYSTEM)));
    if (!people.isEmpty()) {
      items.add(metric("People", people.size(), List.copyOf(people.values())));
    }
    return new Metrics(assembler.id(section, "counts"), items, List.of());
  }

  private Metrics.Item metric(String label, SortedMap<EntityId, List<Fact>> counted) {
    List<Fact> facts = new ArrayList<>();
    counted.values().forEach(facts::addAll);
    return metric(label, counted.size(), facts);
  }

  private Metrics.Item metric(String label, int value, List<Fact> facts) {
    return new Metrics.Item(label, BigDecimal.valueOf(value), null, null, assembler.facts(facts));
  }

  // Containers.

  /**
   * A concern a container view is split by when the whole system's view would show more than {@link
   * #MAX_ELEMENTS} elements (design, Quality bar): its title and the relationships its lines draw.
   */
  private record Concern(String title, Set<String> relationships) {

    Concern and(Concern other) {
      Set<String> both = new HashSet<>(relationships);
      both.addAll(other.relationships);
      return new Concern(title + " and " + other.title.toLowerCase(Locale.ROOT), both);
    }
  }

  private static final Concern REQUESTS =
      new Concern(
          "Requests",
          Set.of(Types.CALLS_HTTP, Elements.USES, Types.SENDS_MAIL, Types.USES_IDENTITY_PROVIDER));
  private static final Concern DATA =
      new Concern("Data", Set.of(Types.USES_DATABASE, Types.ACCESSES_DATA_OF));
  private static final Concern MESSAGING =
      new Concern("Messaging", Set.of(Types.PUBLISHES, Types.CONSUMES, Types.ROUTES_TO));

  /**
   * The container view of the whole system: one diagram of the scope's units and frontends and what
   * they connect to, inside the system. Above {@link #MAX_ELEMENTS} elements it is split by
   * concern, one page each, from the entry inward: requests and data, then messaging; a view still
   * above splits requests from data, and one still above that is drawn per unit with its neighbours
   * in that concern, from the entry inward, leaving out a unit whose view shows nothing a view
   * before it does not. A per-unit view still above is drawn whole and fails "At most 15 elements"
   * in the document's quality report (ADR 0046). What is not at a C4 level, the deployment table,
   * the decision records and the links not resolved, is in sections of its own (ADR 0086).
   *
   * @param section the containers section's ID
   * @param blocks receives the blocks of the section itself
   * @param subsections receives one subsection per view when the diagram is split
   */
  void containers(String section, List<Block> blocks, List<Section> subsections) {
    SortedSet<EntityId> shown = new TreeSet<>(entryFirst());
    shown.addAll(units);
    shown.addAll(frontends);
    Optional<Callout> profiles = alternativesNote(assembler.id(section, "profiles"), units, false);
    if (shown.isEmpty()) {
      // A note: what the code does not show cites no fact (Quality bar, ADR 0046).
      blocks.add(
          new Callout(
              assembler.id(section, "none"),
              Callout.Variant.NOTE,
              null,
              null,
              List.of(TextRun.of("No deployable unit was found in the code.")),
              List.of()));
      profiles.ifPresent(blocks::add);
      return;
    }
    Diagram whole =
        containerDiagram(assembler.id(section, "diagram"), shown, "Containers", null, true);
    if (fits(whole) || shown.size() == 1) {
      blocks.add(whole);
      profiles.ifPresent(blocks::add);
      return;
    }
    Map<EntityId, EntityId> domainOf = domainOf(shown);
    if (!domainOf.isEmpty()) {
      domains(section, shown, domainOf, blocks, subsections);
      profiles.ifPresent(blocks::add);
      return;
    }
    List<Diagram> drawn = new ArrayList<>();
    split(section, shown, REQUESTS.and(DATA), List.of(REQUESTS, DATA), true, drawn, subsections);
    split(section, shown, MESSAGING, List.of(), false, drawn, subsections);
    // The note goes with the first view, so it stays on a container page.
    profiles.ifPresent(
        note -> {
          Section first = subsections.getFirst();
          List<Block> with = new ArrayList<>(first.blocks());
          with.add(note);
          subsections.set(
              0,
              new Section(
                  first.id(),
                  first.title(),
                  first.levels(),
                  first.entity(),
                  first.facts(),
                  with,
                  first.sections()));
        });
  }

  /**
   * The container view at domain level (ADR 0117): one overview of the system showing each domain
   * instead of its units, units in no domain drawn as themselves, and then one page per domain,
   * from the entry inward, with its units and their neighbours outside it, lifted to their domains.
   * A domain page above {@link #MAX_ELEMENTS} elements is split by concern.
   */
  private void domains(
      String section,
      SortedSet<EntityId> shown,
      Map<EntityId, EntityId> domainOf,
      List<Block> blocks,
      List<Section> subsections) {
    blocks.add(
        containerDiagram(
            assembler.id(section, "diagram"),
            shown,
            "Containers of " + assembler.name(system) + ", by domain",
            null,
            true,
            domainOf));
    // Each domain's units, from the entry inward; the domains in the order of their first unit.
    Map<EntityId, SortedSet<EntityId>> members = new java.util.LinkedHashMap<>();
    for (EntityId unit : shown) {
      EntityId domain = domainOf.get(unit);
      if (domain != null) {
        members.computeIfAbsent(domain, d -> new TreeSet<>(entryFirst())).add(unit);
      }
    }
    members.forEach(
        (domain, units) -> {
          Map<EntityId, EntityId> others = new HashMap<>(domainOf);
          others.keySet().removeAll(units);
          String id = assembler.id(section, domain.toString());
          String name = assembler.name(domain);
          Diagram view =
              containerDiagram(
                  assembler.id(id, "diagram"),
                  units,
                  "Containers of " + assembler.name(system) + " in " + name,
                  null,
                  true,
                  others);
          if (fits(view)) {
            subsections.add(subsection(id, name, domain, view));
            return;
          }
          List<Diagram> drawn = new ArrayList<>();
          split(id, units, REQUESTS.and(DATA), List.of(REQUESTS, DATA), true, drawn, subsections);
          split(id, units, MESSAGING, List.of(), false, drawn, subsections);
        });
  }

  /**
   * Adds the view of one concern as a subsection, or when it shows more than {@link #MAX_ELEMENTS}
   * elements, those of its parts, else one per unit.
   *
   * @param everyContainer whether the view draws every container shown, with lines or not, so the
   *     first view leaves none out
   * @param drawn the views drawn so far, to leave out a per-unit view that repeats one
   */
  private void split(
      String section,
      SortedSet<EntityId> shown,
      Concern concern,
      List<Concern> parts,
      boolean everyContainer,
      List<Diagram> drawn,
      List<Section> subsections) {
    String id = assembler.id(section, slug(concern.title));
    Diagram view =
        containerDiagram(assembler.id(id, "diagram"), shown, "Containers", concern, everyContainer);
    if (view.edges().isEmpty() && !everyContainer) {
      return;
    }
    if (fits(view)) {
      drawn.add(view);
      subsections.add(subsection(id, concern.title, null, view));
      return;
    }
    if (!parts.isEmpty()) {
      boolean first = everyContainer;
      for (Concern part : parts) {
        split(section, shown, part, List.of(), first, drawn, subsections);
        first = false;
      }
      return;
    }
    // Per unit, with only its neighbours in this concern.
    Set<EntityId> lined = new HashSet<>();
    for (Line link : links) {
      if (concern.relationships.contains(link.relationship())) {
        lined.add(link.from());
        lined.add(link.to());
      }
    }
    for (EntityId unit : shown) {
      boolean alone =
          everyContainer
              && links.stream().noneMatch(l -> l.from().equals(unit) || l.to().equals(unit));
      if (!lined.contains(unit) && !alone) {
        continue;
      }
      String unitId = assembler.id(id, unit.toString());
      Diagram around =
          containerDiagram(
              assembler.id(unitId, "diagram"),
              only(unit),
              "Containers around " + assembler.name(unit),
              concern,
              true);
      if (drawn.stream().anyMatch(d -> repeats(around, d))) {
        continue;
      }
      drawn.add(around);
      subsections.add(
          subsection(unitId, concern.title + " around " + assembler.name(unit), unit, around));
    }
  }

  private Section subsection(String id, String title, EntityId entity, Diagram diagram) {
    return new Section(
        id, title, SectionKind.CONTAINERS.levels(), entity, List.of(), List.of(diagram), List.of());
  }

  /**
   * Whether a container diagram shows at most {@link #MAX_ELEMENTS} elements besides the system.
   */
  private static boolean fits(Diagram diagram) {
    return diagram.nodes().size() - 1 <= MAX_ELEMENTS;
  }

  /** Whether a diagram shows nothing another does not: its elements and lines are among its. */
  private static boolean repeats(Diagram diagram, Diagram other) {
    Set<EntityId> nodes = new HashSet<>();
    other.nodes().forEach(n -> nodes.add(n.id()));
    Set<String> edges = new HashSet<>();
    other.edges().forEach(e -> edges.add(e.from() + " " + e.relationship() + " " + e.to()));
    return diagram.nodes().stream().allMatch(n -> nodes.contains(n.id()))
        && diagram.edges().stream()
            .allMatch(e -> edges.contains(e.from() + " " + e.relationship() + " " + e.to()));
  }

  private static String slug(String title) {
    return title.toLowerCase(Locale.ROOT).replace(' ', '-');
  }

  /**
   * The environments the scope's deployment descriptors define and the containers each runs (ADR
   * 0082): one row per container, with its image, the product and version the image is, the units
   * and data stores the linker found running in it, and its ports. Absent when there are none.
   */
  Optional<Table> deployment(String section) {
    // The stored instances (#617), one row per descriptor entry each rests on, with what runs in
    // it.
    Map<String, SortedSet<EntityId>> running = new TreeMap<>();
    Map<String, List<Fact>> runningFacts = new TreeMap<>();
    List<Fact> containers = new ArrayList<>();
    Set<EntityId> instances = new HashSet<>();
    for (Fact instance : knowledge.withPredicate(DeploymentInstances.DEPLOYED_AS)) {
      for (Fact container : basis(instance)) {
        if (repos.isEmpty() || repos.contains(container.evidence().repo())) {
          containers.add(container);
        }
      }
      if (!instances.add(instance.subject())) {
        continue;
      }
      for (Fact runs : knowledge.relations(instance.subject(), DeploymentInstances.RUNS)) {
        for (Fact fact : basis(runs)) {
          String container = attribute(fact, "fact");
          if (container != null) {
            running
                .computeIfAbsent(container, c -> new TreeSet<>(Knowledge.BY_ID))
                .add(Knowledge.object(runs));
            runningFacts.computeIfAbsent(container, c -> new ArrayList<>()).add(fact);
          }
        }
      }
    }
    if (containers.isEmpty()) {
      return Optional.empty();
    }
    containers.sort(
        Comparator.comparing(C4Views::environment)
            .thenComparing(f -> f.object().value())
            .thenComparing(Fact::id));
    List<Table.Row> rows = new ArrayList<>();
    for (Fact fact : containers) {
      String image = attribute(fact, "image");
      String build = attribute(fact, "build");
      String product = attribute(fact, "product");
      String version = attribute(fact, "version");
      List<String> runs = new ArrayList<>();
      for (EntityId entity : running.getOrDefault(fact.id(), new TreeSet<>())) {
        runs.add(assembler.name(entity));
      }
      List<String> ports = Elements.strings(fact, "ports");
      List<Fact> cited = new ArrayList<>(List.of(fact));
      cited.addAll(runningFacts.getOrDefault(fact.id(), List.of()));
      rows.add(
          new Table.Row(
              null,
              List.of(
                  cell(environment(fact)),
                  cell(fact.object().value()),
                  cell(image != null ? image : build == null ? null : "built from " + build),
                  cell(
                      product == null ? null : version == null ? product : product + " " + version),
                  cell(String.join(", ", runs)),
                  cell(String.join(", ", ports))),
              assembler.facts(cited)));
    }
    return Optional.of(
        new Table(
            assembler.id(section, "deployment"),
            "Deployment",
            List.of(
                new Table.Column("Environment"),
                new Table.Column("Container"),
                new Table.Column("Image"),
                new Table.Column("Technology"),
                new Table.Column("Runs"),
                new Table.Column("Ports")),
            rows,
            List.of()));
  }

  /** A container's environment as documents name it, such as "Docker Compose (local)". */
  private static String environment(Fact container) {
    // As the linker names it on deployed-in facts; the composer does not depend on the extractor.
    String platform = attribute(container, "platform");
    String environment = attribute(container, "environment");
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
   * A container diagram of the shown containers and their neighbours.
   *
   * @param title what the diagram shows, before "of" and the system's name
   * @param concern the concern whose lines it draws, or null for all of them
   * @param everyContainer whether it draws every shown container, with lines or not
   */
  private Diagram containerDiagram(
      String id, SortedSet<EntityId> shown, String title, Concern concern, boolean everyContainer) {
    String scope = concern == null ? "" : ": " + concern.title.toLowerCase(Locale.ROOT);
    return containerDiagram(
        id,
        shown,
        title + " of " + assembler.name(system) + scope,
        concern,
        everyContainer,
        Map.of());
  }

  /**
   * A container diagram of the shown units and what they connect to, each line end lifted to the
   * group that stands for it in the view, if any (docs/design.md, Composition reads the model): a
   * line whose ends become one is left out, and the rest merge per ordered pair of ends.
   *
   * @param lift the group each unit drawn as its group is drawn as
   */
  private Diagram containerDiagram(
      String id,
      SortedSet<EntityId> shown,
      String title,
      Concern concern,
      boolean everyContainer,
      Map<EntityId, EntityId> lift) {
    Map<EntityId, Diagram.Node> nodes = new TreeMap<>(Knowledge.BY_ID);
    nodes.put(system, node(system, null, null, List.of()));
    if (everyContainer) {
      shown.forEach(unit -> container(nodes, lift.getOrDefault(unit, unit)));
    }
    Edges edges = new Edges();
    for (Line link : links) {
      EntityId from = lift.getOrDefault(link.from(), link.from());
      EntityId to = lift.getOrDefault(link.to(), link.to());
      if ((concern == null || concern.relationships.contains(link.relationship()))
          && (shown.contains(link.from()) || shown.contains(link.to()))
          && !merged(lift, link.from(), link.to())) {
        container(nodes, from);
        container(nodes, to);
        edges.add(from, to, link);
      }
    }
    // Routes between exchanges and queues both drawn already.
    for (Line link : links) {
      if (link.relationship().equals(Types.ROUTES_TO)
          && (concern == null || concern.relationships.contains(Types.ROUTES_TO))
          && nodes.containsKey(link.from())
          && nodes.containsKey(link.to())) {
        edges.add(link);
      }
    }
    return new Diagram(
        id,
        Diagram.Kind.C4_CONTAINER,
        title,
        List.copyOf(nodes.values()),
        edges.build(assembler, true),
        List.of());
  }

  /**
   * The order pages about containers run in, from the entry inward: frontends first, then units by
   * how many HTTP calls away they are from a frontend or a gateway (a unit that routes requests),
   * or when there are none, from the units nothing in the system calls; units none of those reach
   * last; then by ID.
   */
  Comparator<EntityId> entryFirst() {
    if (entryFirst == null) {
      Map<EntityId, Integer> depth = new HashMap<>();
      Deque<EntityId> queue = new ArrayDeque<>();
      Set<EntityId> called = new HashSet<>();
      List<EntityId> gateways = new ArrayList<>();
      for (Line link : links) {
        if (link.relationship().equals(Types.CALLS_HTTP) && Elements.runsCode(link.to())) {
          called.add(link.to());
          if ("Routes".equals(link.prefix())) {
            gateways.add(link.from());
          }
        }
      }
      List<EntityId> roots = new ArrayList<>(Elements.frontends(knowledge));
      roots.addAll(gateways);
      if (roots.isEmpty()) {
        Elements.units(knowledge).stream().filter(u -> !called.contains(u)).forEach(roots::add);
      }
      for (EntityId root : roots) {
        if (depth.putIfAbsent(root, 0) == null) {
          queue.add(root);
        }
      }
      while (!queue.isEmpty()) {
        EntityId from = queue.poll();
        for (Line link : links) {
          if (link.relationship().equals(Types.CALLS_HTTP)
              && link.from().equals(from)
              && Elements.runsCode(link.to())
              && depth.putIfAbsent(link.to(), depth.get(from) + 1) == null) {
            queue.add(link.to());
          }
        }
      }
      entryFirst =
          Comparator.comparing((EntityId e) -> !e.type().equals(Types.FRONTEND))
              .thenComparing(e -> depth.getOrDefault(e, Integer.MAX_VALUE))
              .thenComparing(Knowledge.BY_ID);
    }
    return entryFirst;
  }

  private Comparator<EntityId> entryFirst;

  /** Adds a container, store, external system or person to a container diagram's nodes. */
  private void container(Map<EntityId, Diagram.Node> nodes, EntityId entity) {
    if (nodes.containsKey(entity)) {
      return;
    }
    // Inside the system's boundary where the diagram draws it.
    EntityId parent =
        entity.type().equals(Types.EXTERNAL_SYSTEM)
                || entity.type().equals(Types.PERSON)
                || !nodes.containsKey(system)
            ? null
            : system;
    if (entity.type().equals(Types.GROUP)) {
      nodes.put(entity, groupNode(entity, parent));
      return;
    }
    nodes.put(
        entity, node(entity, elements.technology(entity), parent, elements.containerFacts(entity)));
  }

  /**
   * A domain or feature area drawn in place of its members: named as Name groups named it, with how
   * many it holds as its technology line, citing the facts that make it and hold its members.
   */
  private Diagram.Node groupNode(EntityId group, EntityId parent) {
    List<Fact> facts = new ArrayList<>(knowledge.about(group));
    facts.addAll(knowledge.relations(group, Types.GROUPS));
    int members = knowledge.relations(group, Types.GROUPS).size();
    String what = group.key().contains("/" + Groups.DOMAIN + "/") ? "container" : "component";
    return node(group, members + " " + what + (members == 1 ? "" : "s"), parent, facts);
  }

  /**
   * The system's domains the scope draws (#535; ADR 0117): each unit in scope that a stored domain
   * holds with another unit in scope, with that domain. A domain holding one unit in scope stands
   * for nothing and is left out.
   */
  private Map<EntityId, EntityId> domainOf(Set<EntityId> shown) {
    Map<EntityId, EntityId> result = new HashMap<>();
    for (Groups.Group domain : Groups.of(knowledge, Groups.DOMAIN)) {
      List<EntityId> inScope = domain.members().stream().filter(shown::contains).toList();
      if (inScope.size() >= 2) {
        inScope.forEach(m -> result.putIfAbsent(m, domain.id()));
      }
    }
    return result;
  }

  /**
   * The decision records of the scope's repositories, oldest number first, each with its rationale
   * quoted from its context section or marked as not recorded; a note when there are none, since
   * then why the system is built as it is is not known (Quality bar, Architecture Explorer).
   */
  Optional<Block> decisions(String section) {
    SortedSet<EntityId> decisions = new TreeSet<>(Knowledge.BY_ID);
    for (Fact fact : knowledge.withPredicate(Types.TITLE)) {
      EntityId subject = fact.subject();
      String key = subject.key();
      if (subject.type().equals(Types.DECISION)
          && key.contains(":")
          && repos.contains(key.substring(0, key.indexOf(':')))) {
        decisions.add(subject);
      }
    }
    if (decisions.isEmpty()) {
      if (repos.isEmpty()) {
        return Optional.empty();
      }
      List<String> names = new ArrayList<>(new TreeSet<>(repos));
      String last = names.removeLast();
      String where = names.isEmpty() ? last : String.join(", ", names) + " and " + last;
      // A note: what the code does not show cites no fact (Quality bar, ADR 0046).
      return Optional.of(
          new Callout(
              assembler.id(section, "decisions"),
              Callout.Variant.NOTE,
              null,
              "Decisions",
              List.of(
                  TextRun.of(
                      "No decision records were found in "
                          + where
                          + ", so the reasons for its design are not known.")),
              List.of()));
    }
    List<Table.Row> rows = new ArrayList<>();
    for (EntityId decision : decisions) {
      assembler.entity(decision);
      Optional<String> number = knowledge.attribute(decision, Types.DECISION_NUMBER);
      rows.add(
          new Table.Row(
              decision,
              List.of(
                  cell(number.map(n -> "ADR " + n).orElse(null)),
                  cell(assembler.name(decision)),
                  cell(knowledge.attribute(decision, Types.DECISION_STATUS).orElse(null)),
                  cell(knowledge.attribute(decision, Types.DECISION_OUTCOME).orElse(null)),
                  cell(rationale(decision))),
              assembler.facts(knowledge.attributes(decision))));
    }
    rows.sort(
        (a, b) -> {
          int x = number(a.entity());
          int y = number(b.entity());
          return x != y
              ? Integer.compare(x, y)
              : a.entity().toString().compareTo(b.entity().toString());
        });
    return Optional.of(
        new Table(
            assembler.id(section, "decisions"),
            "Decisions",
            List.of(
                new Table.Column("ID"),
                new Table.Column("Decision"),
                new Table.Column("Status"),
                new Table.Column("Outcome"),
                new Table.Column("Rationale")),
            rows,
            List.of()));
  }

  /** The first paragraph of a decision record's context section, or that it has none. */
  private String rationale(EntityId decision) {
    return knowledge
        .attribute(decision, Types.DECISION_CONTEXT)
        .map(String::strip)
        .filter(c -> !c.isEmpty())
        .map(c -> c.split("\\n\\s*\\n", 2)[0].strip().replaceAll("\\s*\\n\\s*", " "))
        .orElse(NOT_RECORDED);
  }

  private int number(EntityId decision) {
    return knowledge
        .attribute(decision, Types.DECISION_NUMBER)
        .map(n -> n.matches("[0-9]{1,9}") ? Integer.parseInt(n) : Integer.MAX_VALUE)
        .orElse(Integer.MAX_VALUE);
  }

  /** A cell of the decision record's Markdown text, its code spans as code. */
  private static List<Inline> cell(String text) {
    return text == null || text.isBlank() ? List.of() : Assembler.withCodeSpans(text);
  }

  // Components.

  /**
   * One subsection per unit in scope with more than one component, holding its component diagram:
   * one component per controller, message listener or scheduled job with the beans reachable only
   * from it, a bean reachable from several entry points (or from none, when it reaches outside the
   * unit) as a component of its own, and the containers they call or are called by.
   */
  List<Section> components(String section) {
    List<Section> result = new ArrayList<>();
    // From the entry inward, as the container pages.
    SortedSet<EntityId> ordered = new TreeSet<>(entryFirst());
    ordered.addAll(units);
    for (EntityId unit : ordered) {
      List<Elements.Component> components = elements.components(unit);
      if (components.size() < 2) {
        continue;
      }
      String id = assembler.id(section, unit.toString());
      Set<EntityId> all = new HashSet<>();
      components.forEach(c -> all.add(c.main()));
      Diagram diagram =
          componentDiagram(
              assembler.id(id, "diagram"),
              unit,
              components,
              "Components of " + assembler.name(unit),
              all,
              Map.of());
      List<Section> areas = new ArrayList<>();
      if (!fits(diagram)) {
        Map<EntityId, EntityId> areaOf = areaOf(unit, components);
        if (!areaOf.isEmpty()) {
          diagram =
              componentDiagram(
                  assembler.id(id, "diagram"),
                  unit,
                  components,
                  "Components of " + assembler.name(unit) + ", by feature area",
                  all,
                  areaOf);
          areas = areas(id, unit, components, areaOf);
        }
      }
      List<Block> blocks = new ArrayList<>(List.of(diagram));
      alternativesNote(assembler.id(id, "profiles"), only(unit), false).ifPresent(blocks::add);
      result.add(
          new Section(
              id,
              assembler.name(unit),
              SectionKind.COMPONENTS.levels(),
              unit,
              List.of(),
              blocks,
              areas));
    }
    return result;
  }

  /**
   * The unit's feature areas the component view draws (#535; ADR 0117): each component a stored
   * feature area of the unit holds, by its main type, with that area.
   */
  private Map<EntityId, EntityId> areaOf(EntityId unit, List<Elements.Component> components) {
    Map<EntityId, EntityId> byComponent = new HashMap<>();
    for (Groups.Group area : Groups.of(knowledge, Groups.FEATURE_AREA)) {
      if (unit.toString().equals(area.scope())) {
        area.members().forEach(m -> byComponent.putIfAbsent(m, area.id()));
      }
    }
    Map<EntityId, EntityId> result = new HashMap<>();
    for (Elements.Component component : components) {
      EntityId area = byComponent.get(Components.id(unit, component.main()));
      if (area != null) {
        result.put(component.main(), area);
      }
    }
    return result;
  }

  /**
   * One page per feature area of a unit drawn by area (ADR 0117), in the order of the areas' first
   * components: the area's components and their neighbours outside it, lifted to their areas.
   */
  private List<Section> areas(
      String id,
      EntityId unit,
      List<Elements.Component> components,
      Map<EntityId, EntityId> areaOf) {
    Map<EntityId, Set<EntityId>> members = new java.util.LinkedHashMap<>();
    for (Elements.Component component : components) {
      EntityId area = areaOf.get(component.main());
      if (area != null) {
        members.computeIfAbsent(area, a -> new HashSet<>()).add(component.main());
      }
    }
    List<Section> result = new ArrayList<>();
    members.forEach(
        (area, mains) -> {
          Map<EntityId, EntityId> others = new HashMap<>(areaOf);
          others.keySet().removeAll(mains);
          String areaId = assembler.id(id, area.toString());
          String name = assembler.name(area);
          Diagram view =
              componentDiagram(
                  assembler.id(areaId, "diagram"),
                  unit,
                  components,
                  "Components of " + assembler.name(unit) + " in " + name,
                  mains,
                  others);
          result.add(
              new Section(
                  areaId,
                  name,
                  SectionKind.COMPONENTS.levels(),
                  area,
                  List.of(),
                  List.of(view),
                  List.of()));
        });
    return result;
  }

  /**
   * A component diagram of a unit: the components in focus and the lines that touch them, each
   * component drawn as the feature area that stands for it in the view, if any; a line whose ends
   * become one is left out, and the rest merge per ordered pair of ends.
   *
   * @param focus the main types of the components the view is about
   * @param lift the feature area each component drawn as its area is drawn as, by main type
   */
  private Diagram componentDiagram(
      String id,
      EntityId unit,
      List<Elements.Component> components,
      String title,
      Set<EntityId> focus,
      Map<EntityId, EntityId> lift) {
    Map<EntityId, Diagram.Node> nodes = new TreeMap<>(Knowledge.BY_ID);
    nodes.put(unit, node(unit, elements.technology(unit), null, elements.containerFacts(unit)));
    Map<EntityId, Elements.Component> byMain = new HashMap<>();
    components.forEach(c -> byMain.put(c.main(), c));
    java.util.function.Function<EntityId, EntityId> shown =
        main -> {
          EntityId end = lift.getOrDefault(main, main);
          if (!nodes.containsKey(end)) {
            if (end.type().equals(Types.GROUP)) {
              nodes.put(end, groupNode(end, unit));
            } else {
              Elements.Component component = byMain.get(main);
              Diagram.Node node =
                  node(main, component.technology(), unit, elements.componentFacts(component));
              nodes.put(
                  main,
                  new Diagram.Node(
                      node.id(),
                      elements.componentName(component),
                      node.technology(),
                      node.parent(),
                      node.style(),
                      node.position(),
                      node.facts()));
            }
          }
          return end;
        };
    for (Elements.Component component : components) {
      if (focus.contains(component.main())) {
        shown.apply(component.main());
      }
    }
    Edges edges = new Edges();
    // The stored lines between the unit's components (#617), and where the code end of each of
    // its links to other containers is.
    Map<EntityId, EntityId> mainOf = new TreeMap<>(Knowledge.BY_ID);
    components.forEach(c -> mainOf.put(Components.id(unit, c.main()), c.main()));
    Map<String, EntityId> codeEnds = new HashMap<>();
    for (Elements.Component component : components) {
      for (Fact line :
          knowledge.relations(
              Components.id(unit, component.main()), ComponentInteractions.COMPONENT_LINK)) {
        String kind = attribute(line, "kind");
        String detail = attribute(line, "detail");
        if (ComponentInteractions.CODE_END.equals(kind)) {
          // The side the code is on, then each link it places, by the fact it cites.
          String side = attribute(line, "target").split(" ", 2)[0];
          Elements.strings(line, "facts")
              .forEach(link -> codeEnds.put(side + " " + link, component.main()));
          continue;
        }
        EntityId to = mainOf.get(Knowledge.object(line));
        if (to == null
            || !(focus.contains(component.main()) || focus.contains(to))
            || merged(lift, component.main(), to)) {
          continue;
        }
        EntityId fromEnd = shown.apply(component.main());
        EntityId toEnd = shown.apply(to);
        String relationship =
            ComponentInteractions.ADVISES_KIND.equals(kind) ? Types.ADVISES : BuiltInTypes.CALLS;
        String plain =
            switch (kind) {
              case ComponentInteractions.APPLIES -> "Applies";
              case ComponentInteractions.ADVISES_KIND -> "Advises";
              default -> "Calls";
            };
        String prefix = ComponentInteractions.APPLIES.equals(kind) ? "Applies on routes" : plain;
        EdgeBuilder edge = edges.get(fromEnd, relationship, toEnd);
        for (Fact cited : basis(line)) {
          edge.add(plain, prefix, detail, null, null);
          edge.facts.add(cited);
          edge.phrased(null);
        }
        if (fromEnd.type().equals(Types.GROUP) && edge.intent == null) {
          // Name groups keyed the line by its other end as the model names it, and its kind.
          EntityId other = toEnd.type().equals(Types.GROUP) ? toEnd : Components.id(unit, toEnd);
          Assembler.Phrased intent =
              assembler.groupIntent(fromEnd, other + " " + (kind == null ? "calls" : kind));
          if (intent != null) {
            edge.intent = intent.text();
            edge.facts.addAll(intent.facts());
          }
        }
      }
    }
    // Lines to and from other containers, at the code on this unit's end.
    for (Line link : links) {
      if (link.from().equals(unit)) {
        for (Fact fact : link.facts()) {
          EntityId from = codeEnds.get("source " + fact.id());
          if (from != null && fact.subject().equals(unit) && focus.contains(from)) {
            container(nodes, link.to());
            edges.add(shown.apply(from), link.to(), link.from(from));
          }
        }
      } else if (link.to().equals(unit)) {
        for (Fact fact : link.facts()) {
          String side = fact.subject().equals(unit) ? "source" : "target";
          EntityId to = codeEnds.get(side + " " + fact.id());
          if (to != null
              && focus.contains(to)
              && link.relationship().equals(Types.CONSUMES) == fact.subject().equals(unit)) {
            container(nodes, link.from());
            edges.add(link.from(), shown.apply(to), link.to(to));
          }
        }
      }
    }
    // Data access, to the databases the unit's datasources point to.
    for (Elements.Component component : components) {
      String access =
          knowledge
              .attribute(Components.id(unit, component.main()), ComponentInteractions.DATA_ACCESS)
              .orElse(null);
      if (access == null || !focus.contains(component.main())) {
        continue;
      }
      for (Line link : links) {
        if (link.from().equals(unit) && link.relationship().equals(Types.USES_DATABASE)) {
          container(nodes, link.to());
          EdgeBuilder edge =
              edges.get(shown.apply(component.main()), Types.USES_DATABASE, link.to());
          edge.add(access, null, null, link.technology(), link.profile());
          edge.facts.addAll(link.facts());
          edge.phrased(null);
        }
      }
    }
    return new Diagram(
        id,
        Diagram.Kind.C4_COMPONENT,
        title,
        List.copyOf(nodes.values()),
        edges.build(assembler, true),
        List.of());
  }

  /** Whether lifting made a line's two ends one: a line inside a group the view draws. */
  private static boolean merged(Map<EntityId, EntityId> lift, EntityId from, EntityId to) {
    return !from.equals(to) && lift.getOrDefault(from, from).equals(lift.getOrDefault(to, to));
  }

  private static SortedSet<EntityId> only(EntityId entity) {
    SortedSet<EntityId> set = new TreeSet<>(Knowledge.BY_ID);
    set.add(entity);
    return set;
  }

  private Diagram.Node node(EntityId entity, String technology, EntityId parent, List<Fact> facts) {
    assembler.entity(entity);
    return new Diagram.Node(
        entity, assembler.name(entity), technology, parent, null, null, assembler.facts(facts));
  }
}

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
import io.lantern.core.types.Types;
import io.lantern.derive.ArchitectureModel;
import io.lantern.derive.ComponentInteractions;
import io.lantern.derive.Components;
import io.lantern.derive.Decisions;
import io.lantern.derive.DeploymentInstances;
import io.lantern.derive.Elements;
import io.lantern.derive.Elements.Line;
import io.lantern.derive.Groups;
import io.lantern.derive.MergedLines;
import io.lantern.derive.ScopeFlows;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
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

  /** The most participants a sequence diagram shows (docs/design.md, Sequence diagrams). */
  static final int MAX_PARTICIPANTS = 15;

  /** How many details an edge label names before it counts the rest. */
  private static final int NAMED = 2;

  private final ArchitectureModel model;
  private final Assembler assembler;
  private final EntityId system;

  /** How much a container or component diagram may hold before it is folded or split (#836). */
  private final DiagramLimits limits;

  private final SortedSet<EntityId> units;

  private final Set<String> repos;

  /** What the diagrams show, read from the model (#717). */
  private final Elements elements;

  private final List<Line> links;

  /**
   * The lines the container views draw: {@link #links}, or with topics and queues between one
   * sender and one receiver folded into "via" labels when the whole system's view is not readable
   * without (ADR 0117).
   */
  private List<Line> drawing;

  /**
   * Whether the diagram being drawn merges the lines between two elements into one, whatever they
   * carry: the first step when a view at its coarsest level still does not fit (owner's decision
   * (b) of 4 Nov 2026 on #764). Otherwise each ordered pair draws one line per relationship.
   */
  private boolean perPair;

  /** Whether the view being drawn leaves the systems outside off (#764). */
  private boolean withoutOutside;

  private boolean outside(EntityId entity) {
    return isType(entity.type(), Types.EXTERNAL_SYSTEM);
  }

  /** The name of the domain whose page is being split, which each part's title names. */
  private String splitIn;

  /** A split page's title, naming the domain it is a part of when one is being split. */
  private String splitTitle(String title) {
    return splitIn == null ? title : splitIn + ": " + title.toLowerCase(Locale.ROOT);
  }

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
      ArchitectureModel model,
      Assembler assembler,
      EntityId system,
      SortedSet<EntityId> units,
      Set<String> repos,
      DiagramLimits limits) {
    this.model = model;
    this.limits = java.util.Objects.requireNonNull(limits, "limits");
    this.assembler = assembler;
    this.system = system;
    this.units = units;
    this.repos = repos;
    this.elements = new Elements(model, assembler.vocabulary(), assembler.names(), repos);
    this.links = elements.lines();
    this.drawing = links;
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
      EntityId target = ArchitectureModel.object(profiled);
      if (!of.contains(profiled.subject())
          || (externalOnly && !isType(target.type(), Types.EXTERNAL_SYSTEM))) {
        continue;
      }
      String verb = Elements.alternativeVerb(profiled);
      assembler.entity(target);
      content.add(
          TextRun.of(
              (content.isEmpty() ? "" : " ")
                  + "Under the Spring profile "
                  + ArchitectureModel.view(profiled)
                  + ", "
                  + assembler.name(profiled.subject())
                  + " "
                  + verb
                  + " "
                  + assembler.name(target)
                  + " instead of "
                  + assembler.name(ArchitectureModel.object(fallback))
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
     * The intent phrases of the edge's links with how many links say each, used as its label when
     * any link has one (#739).
     */
    final SortedMap<String, Integer> phrases = new TreeMap<>();

    /** The intent Name groups stored for a line leaving a group, which labels it; or null. */
    String intent;

    /**
     * The intent Describe stored for the line as a whole, which labels it when no phrase of its
     * links covers a majority of them (ADR 0103, amendment for #810); or null.
     */
    String lineIntent;

    /** The distinct claims of the links drawn as the edge. */
    final Set<String> claims = new HashSet<>();

    EdgeBuilder(EntityId from, String relationship, EntityId to) {
      this.from = from;
      this.relationship = relationship;
      this.to = to;
    }

    void add(Line link, Assembler.Phrased phrase) {
      claims.add(MergedLines.claim(link.facts().getFirst()));
      add(link.plain(), link.prefix(), link.detail(), link.technology(), link.profile());
      if (link.via() != null) {
        vias.add(link.via());
      }
      facts.addAll(link.facts());
      phrased(phrase);
    }

    /**
     * Adds the intent phrase of a link of the edge, with the facts it cites; a link without one
     * adds nothing, and the edge keeps its static label only when none of its links has one.
     */
    void phrased(Assembler.Phrased phrase) {
      if (phrase != null) {
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
     * The intent phrase of its links when any link has one: one intent per line, the one more than
     * half its links say; else the intent Describe wrote for the line as a whole; else, on a line
     * of fewer than three claims or one phrase, the one most of its links say (the first
     * alphabetically on a tie). The rest stay with the line's facts. Else what the links record.
     */
    String label() {
      if (intent != null) {
        return intent;
      }
      if (!phrases.isEmpty()) {
        Optional<String> majority = MergedLines.majority(phrases);
        if (majority.isPresent()) {
          return majority.get();
        }
        if (lineIntent != null) {
          return lineIntent;
        }
        // A line Describe words as a whole that holds no intent of its own reads what its links
        // record, not one request's intent (ADR 0103, amendment for #810).
        if (claims.size() < MergedLines.MIN_CLAIMS || phrases.size() < 2) {
          return phrases.entrySet().stream()
              .max(
                  Map.Entry.<String, Integer>comparingByValue()
                      .thenComparing(Map.Entry.<String, Integer>comparingByKey().reversed()))
              .orElseThrow()
              .getKey();
        }
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
     * itself. A line of a group takes the intent Name groups stored for it (ADR 0107), keyed by the
     * line's other end: the group stores a line from its own side, as its members' facts run, so a
     * line drawn into the group (a queue to the units that consume it) is looked up from its other
     * end too.
     */
    void add(EntityId from, EntityId to, Line link) {
      EdgeBuilder edge = get(from, link.relationship(), to);
      edge.add(
          link,
          link.relationship().equals(Elements.USES) && link.from().type().equals(Types.PERSON)
              // A person's use of a frontend is worded per frontend (ADR 0103, amendment for #739).
              ? assembler.personPhrase(link.facts().getFirst(), link.to())
              : assembler.phrase(link.facts().getFirst()));
      if (from.type().equals(Types.GROUP) && edge.intent == null) {
        intent(edge, assembler.groupIntent(from, to + " " + link.relationship()));
      }
      if (to.type().equals(Types.GROUP) && edge.intent == null) {
        intent(edge, assembler.groupIntent(to, from + " " + link.relationship()));
        // A consume line through a RabbitMQ queue folded into its exchange is drawn from the
        // exchange; the group stored its intent under the queue its members consume from (#765).
        EntityId queue = Elements.consumedFrom(link.facts().getFirst());
        if (edge.intent == null && queue != null && !queue.equals(from)) {
          intent(edge, assembler.groupIntent(to, queue + " " + link.relationship()));
        }
      }
      // A line drawn between the link's own ends may carry Describe's intent for the whole line.
      if (edge.lineIntent == null && from.equals(link.from()) && to.equals(link.to())) {
        Assembler.Phrased whole = assembler.linePhrase(from, to, link.relationship());
        if (whole != null) {
          edge.lineIntent = whole.text();
          edge.facts.addAll(whole.facts());
        }
      }
    }

    private static void intent(EdgeBuilder edge, Assembler.Phrased intent) {
      if (intent != null) {
        edge.intent = intent.text();
        edge.facts.addAll(intent.facts());
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
      return perPair ? merged(result) : result;
    }
  }

  /**
   * One line per ordered pair of elements: the first line's relationship, each label and each
   * technology once, the profiles only if every line it merges holds only under some, and every
   * fact cited.
   */
  static List<Diagram.Edge> merged(List<Diagram.Edge> edges) {
    Map<String, List<Diagram.Edge>> byPair = new java.util.LinkedHashMap<>();
    for (Diagram.Edge edge : edges) {
      byPair.computeIfAbsent(edge.from() + " " + edge.to(), k -> new ArrayList<>()).add(edge);
    }
    List<Diagram.Edge> result = new ArrayList<>();
    for (List<Diagram.Edge> pair : byPair.values()) {
      Diagram.Edge first = pair.getFirst();
      if (pair.size() == 1) {
        result.add(first);
        continue;
      }
      Set<String> labels = new LinkedHashSet<>();
      Set<String> technologies = new LinkedHashSet<>();
      Set<String> profiles = new LinkedHashSet<>();
      Set<String> facts = new LinkedHashSet<>();
      boolean always = false;
      for (Diagram.Edge edge : pair) {
        if (edge.label() != null) {
          labels.add(edge.label());
        }
        if (edge.technology() != null) {
          technologies.add(edge.technology());
        }
        always |= edge.profiles().isEmpty();
        profiles.addAll(edge.profiles());
        facts.addAll(edge.facts());
      }
      result.add(
          new Diagram.Edge(
              first.id(),
              first.from(),
              first.to(),
              labels.isEmpty() ? null : String.join("; ", labels),
              technologies.isEmpty() ? null : String.join(", ", technologies),
              first.relationship(),
              always ? List.of() : List.copyOf(profiles),
              List.copyOf(facts)));
    }
    return result;
  }

  // System context.

  /**
   * The system context: the scope's system, the external systems its units call and the lines to
   * them, without technology; then a note that its users are unknown.
   */
  List<Block> context(String section) {
    Map<EntityId, Diagram.Node> nodes = new TreeMap<>(ArchitectureModel.BY_ID);
    nodes.put(system, node(system, null, null, List.of()));
    Edges edges = new Edges();
    for (Line link : links) {
      if ((units.contains(link.from()) || frontends.contains(link.from()))
          && isType(link.to().type(), Types.EXTERNAL_SYSTEM)) {
        nodes.computeIfAbsent(link.to(), e -> node(e, null, null, List.of()));
        EdgeBuilder edge = edges.get(system, link.relationship(), link.to());
        edge.add(link.plain(), link.prefix(), link.detail(), null, link.profile());
        edge.facts.addAll(link.facts());
        edge.phrased(assembler.phrase(link.facts().getFirst()));
      }
    }
    SortedMap<EntityId, Fact> people = elements.people();
    for (Map.Entry<EntityId, Fact> person : people.entrySet()) {
      List<Fact> cited = new ArrayList<>(List.of(person.getValue()));
      cited.addAll(model.citedFacts(person.getValue()));
      nodes.put(person.getKey(), node(person.getKey(), null, null, cited));
      EdgeBuilder edge = edges.get(person.getKey(), Elements.USES, system);
      List<String> frontends = Elements.personFrontends(person.getValue());
      edge.add(
          frontends.isEmpty() ? "Uses" : "Uses, through " + String.join(", ", frontends),
          null,
          null,
          null,
          null);
      edge.facts.addAll(cited);
      // The person's intent for each frontend they use (ADR 0103, amendment for #739).
      for (EntityId frontend : elements.screens(person.getValue()).keySet()) {
        edge.phrased(assembler.personPhrase(person.getValue(), frontend));
      }
    }
    // The Unknown caller, where entry points of the scope's units nothing reaches (#766).
    for (Line link : links) {
      if (link.relationship().equals(Types.CALLS_UNREACHED) && units.contains(link.to())) {
        nodes.computeIfAbsent(
            link.from(), e -> node(e, null, null, List.of(link.facts().getFirst())));
        EdgeBuilder edge = edges.get(link.from(), link.relationship(), system);
        edge.add(link.plain(), null, null, null, null);
        edge.facts.addAll(link.facts());
        edge.phrased(null);
      }
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

  /**
   * How many deployable units the scope has, and how many data stores, queues and external systems
   * they use, each citing the facts it counts.
   */
  private Metrics counts(String section, SortedMap<EntityId, Fact> people) {
    List<Fact> unitFacts = new ArrayList<>();
    units.forEach(u -> unitFacts.addAll(elements.containerFacts(u)));
    Map<String, SortedMap<EntityId, List<Fact>>> used = new TreeMap<>();
    for (String kind : List.of(Types.DATABASE, Types.QUEUE, Types.EXTERNAL_SYSTEM)) {
      used.put(kind, new TreeMap<>(ArchitectureModel.BY_ID));
    }
    for (Line link : links) {
      for (EntityId end : List.of(link.from(), link.to())) {
        EntityId other = end.equals(link.from()) ? link.to() : link.from();
        String kind =
            isType(end.type(), Types.DATABASE)
                ? Types.DATABASE
                : isType(end.type(), Types.TOPIC)
                        || isType(end.type(), Types.QUEUE)
                        || isType(end.type(), Types.EXCHANGE)
                    ? Types.QUEUE
                    : isType(end.type(), Types.EXTERNAL_SYSTEM) ? Types.EXTERNAL_SYSTEM : null;
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
   * A concern a container view is split by when the whole system's view would show more than the
   * limits' most elements (design, Quality bar): its title and the relationships its lines draw.
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
          Set.of(
              Types.CALLS_HTTP,
              Elements.USES,
              Types.CALLS_UNREACHED,
              Types.SENDS_MAIL,
              Types.USES_IDENTITY_PROVIDER));
  private static final Concern DATA =
      new Concern("Data", Set.of(Types.USES_DATABASE, Types.ACCESSES_DATA_OF));
  private static final Concern MESSAGING =
      new Concern("Messaging", Set.of(Types.PUBLISHES, Types.CONSUMES, Types.ROUTES_TO));

  /**
   * The container view of the whole system: one diagram of the scope's units and frontends and what
   * they connect to, inside the system. When it is not readable ({@link #fits}), in order, stopping
   * at the first readable view (ADR 0117): topics and queues from one unit to one other become
   * "via" labels, with a note and the messaging view one page away; else the domains are drawn in
   * place of their units, with a page per domain; else it is split by concern, one page each, from
   * the entry inward: requests and data, then messaging; a view still not readable splits requests
   * from data, and one still not readable that is drawn per unit with its neighbours in that
   * concern, leaving out a unit whose view shows nothing a view before it does not. A per-unit view
   * still not readable is drawn whole and fails the readability check in the document's quality
   * report (ADR 0046). What is not at a C4 level, the deployment table, the decision records and
   * the links not resolved, is in sections of its own (ADR 0086).
   *
   * @param section the containers section's ID
   * @param blocks receives the blocks of the section itself
   * @param subsections receives one subsection per view when the diagram is split
   */
  void containers(String section, List<Block> blocks, List<Section> subsections) {
    try {
      drawContainers(section, blocks, subsections);
    } finally {
      plainDrawing();
    }
  }

  /**
   * Back to drawing every line as it is: the switches the fallback steps set while drawing a view
   * never carry over to the next view, whatever ends the view.
   */
  private void plainDrawing() {
    perPair = false;
    withoutOutside = false;
    callersElsewhere = Set.of();
    splitIn = null;
    drawing = links;
  }

  private void drawContainers(String section, List<Block> blocks, List<Section> subsections) {
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
    // A container no line reaches says nothing on a map of how the parts connect: it is left off
    // every other container view, and drawn on a page of its own, always (owner's decision of 6 Nov
    // 2026
    // on #764).
    SortedSet<EntityId> alone = new TreeSet<>(entryFirst());
    shown.stream()
        .filter(c -> links.stream().noneMatch(l -> l.from().equals(c) || l.to().equals(c)))
        .forEach(alone::add);
    if (alone.size() < shown.size()) {
      shown.removeAll(alone);
    } else {
      alone.clear();
    }
    Diagram whole =
        containerDiagram(assembler.id(section, "diagram"), shown, "Containers", null, true);
    if (fits(whole) || shown.size() == 1) {
      blocks.add(whole);
      profiles.ifPresent(blocks::add);
      alone(section, alone, subsections);
      return;
    }
    // The coarser level the code proves (owner's decisions (a) and (b) of 4 Nov 2026 on #764):
    // each domain one box standing for its units, with a page per domain. A view still too large
    // there, or with no domains, merges its lines per pair, then folds topics and queues between
    // one sender and one receiver into "via" labels.
    Map<EntityId, EntityId> domainOf = domainOf(shown);
    String id = assembler.id(section, "diagram");
    String byDomain = "Containers of " + assembler.name(system) + ", by domain";
    String title = domainOf.isEmpty() ? "Containers of " + assembler.name(system) : byDomain;
    Map<EntityId, EntityId> lifted = inner(domainOf);
    Diagram coarsest = containerDiagram(id, shown, title, null, true, lifted);
    SortedSet<EntityId> folded = new TreeSet<>(ArchitectureModel.BY_ID);
    boolean outsideOff = false;
    if (!fits(coarsest) && !domainOf.isEmpty()) {
      // A map of the domains still too large leaves the systems outside off: the context view and
      // each domain's page draw them (owner's decision of 6 Nov 2026 on #764).
      withoutOutside = true;
      Diagram inside = containerDiagram(id, shown, title, null, true, lifted);
      outsideOff = inside.nodes().size() < coarsest.nodes().size();
      coarsest = inside;
    }
    if (!fits(coarsest)) {
      perPair = true;
      coarsest = containerDiagram(id, shown, title, null, true, lifted);
      if (!fits(coarsest)) {
        drawing = foldTopics(folded);
        coarsest = containerDiagram(id, shown, title, null, true, lifted);
      }
    }
    boolean fitted = fits(coarsest);
    // The units in no domain whose lines to the systems outside the map leaves off: no domain's
    // page draws them, so a page of their own does (#764).
    SortedSet<EntityId> loose = new TreeSet<>(entryFirst());
    if (outsideOff) {
      for (EntityId unit : shown) {
        if (!domainOf.containsKey(unit)
            && links.stream()
                .anyMatch(
                    l ->
                        l.from().equals(unit) && outside(l.to())
                            || l.to().equals(unit) && outside(l.from()))) {
          loose.add(unit);
        }
      }
    }
    if (!domainOf.isEmpty() || fitted) {
      blocks.add(coarsest);
      if (outsideOff) {
        blocks.add(
            new Callout(
                assembler.id(section, "outside"),
                Callout.Variant.NOTE,
                null,
                null,
                List.of(
                    TextRun.of(
                        "The systems outside are left off this map to keep it readable; the"
                            + " system context view and each domain's page"
                            + (loose.isEmpty() ? "" : " and the page of the units in no domain")
                            + " draw them.")),
                List.of()));
      }
      foldNote(assembler.id(section, "folded"), folded).ifPresent(blocks::add);
      profiles.ifPresent(blocks::add);
      perPair = false;
      withoutOutside = false;
      drawing = links;
      List<Diagram> drawn = new ArrayList<>(List.of(coarsest));
      if (!domainOf.isEmpty()) {
        // The overview at domain level is the smallest the code proves; each domain's page draws
        // its units in full, split by concern where it still does not fit.
        domains(section, shown, domainOf, subsections);
      }
      if (!loose.isEmpty()) {
        String looseId = assembler.id(section, "no-domain");
        Diagram view =
            containerDiagram(
                assembler.id(looseId, "diagram"),
                loose,
                "Containers of " + assembler.name(system) + " in no domain",
                null,
                true,
                domainOf);
        if (fits(view)) {
          subsections.add(subsection(looseId, NO_DOMAIN, null, view));
        } else {
          splitIn = NO_DOMAIN;
          try {
            split(
                section,
                loose,
                REQUESTS.and(DATA),
                List.of(REQUESTS, DATA),
                true,
                drawn,
                subsections);
          } finally {
            splitIn = null;
          }
        }
      }
      if (!folded.isEmpty()) {
        // The detail folded away stays one page away: the messaging view draws every topic and
        // queue as an element, split per unit when it is not readable either.
        split(section, shown, MESSAGING, List.of(), false, drawn, subsections);
      }
      alone(section, alone, subsections);
      return;
    }
    // No domain and still too large: split by concern, each view drawing every topic and queue.
    perPair = false;
    withoutOutside = false;
    drawing = links;
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
    alone(section, alone, subsections);
  }

  /** The page of the containers no line reaches, when there are any (#764). */
  private void alone(String section, SortedSet<EntityId> alone, List<Section> subsections) {
    if (alone.isEmpty()) {
      return;
    }
    String id = assembler.id(section, "unconnected");
    subsections.add(
        subsection(
            id,
            ALONE,
            null,
            containerDiagram(
                assembler.id(id, "diagram"),
                alone,
                "Containers of " + assembler.name(system) + " with no connections found",
                null,
                true,
                Map.of())));
  }

  /**
   * The title of the page of the units in no domain, when a map of domains leaves the outside off.
   */
  static final String NO_DOMAIN = "In no domain";

  /** The title of the page of the components in no feature area that reach outside the unit. */
  static final String NO_AREA = "In no feature area";

  /** The title of the page of the containers no line reaches. */
  static final String ALONE = "With no connections found";

  /**
   * The pages of the container view at domain level (ADR 0117), after its readable overview: one
   * page per domain, from the entry inward, with its units and their neighbours outside it, lifted
   * to their domains. A domain page that is not readable is split by concern.
   */
  private void domains(
      String section,
      SortedSet<EntityId> shown,
      Map<EntityId, EntityId> domainOf,
      List<Section> subsections) {
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
          // Each page names the domain it is a part of (#764).
          List<Diagram> drawn = new ArrayList<>();
          splitIn = name;
          try {
            split(id, units, REQUESTS.and(DATA), List.of(REQUESTS, DATA), true, drawn, subsections);
            split(id, units, MESSAGING, List.of(), false, drawn, subsections);
          } finally {
            splitIn = null;
          }
        });
  }

  /**
   * Adds the view of one concern as a subsection, or when it shows more than the limits' most
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
      subsections.add(subsection(id, splitTitle(concern.title), null, view));
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
    for (Line link : drawing) {
      if (concern.relationships.contains(link.relationship())) {
        lined.add(link.from());
        lined.add(link.to());
      }
    }
    for (EntityId unit : shown) {
      boolean alone =
          everyContainer
              && drawing.stream().noneMatch(l -> l.from().equals(unit) || l.to().equals(unit));
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
          subsection(
              unitId, splitTitle(concern.title + " around " + assembler.name(unit)), unit, around));
    }
  }

  /**
   * The lines with each topic or queue folded away that carries messages from one unit to one other
   * unit and has no other lines (C4: queues and topics as "via" labels where the coupling is point
   * to point). A topic with more senders or receivers is a coupling point and stays an element, as
   * does one with other lines, such as routes.
   *
   * @param folded receives the topics and queues folded
   */
  private List<Line> foldTopics(SortedSet<EntityId> folded) {
    Map<EntityId, List<Line>> sent = new TreeMap<>(ArchitectureModel.BY_ID);
    Map<EntityId, List<Line>> delivered = new TreeMap<>(ArchitectureModel.BY_ID);
    Set<EntityId> kept = new HashSet<>();
    for (Line link : links) {
      if (isTopic(link.to())
          && link.relationship().equals(Types.PUBLISHES)
          && elements.runsCode(link.from())) {
        sent.computeIfAbsent(link.to(), t -> new ArrayList<>()).add(link);
      } else if (isTopic(link.from())
          && link.relationship().equals(Types.CONSUMES)
          && elements.runsCode(link.to())) {
        delivered.computeIfAbsent(link.from(), t -> new ArrayList<>()).add(link);
      } else {
        for (EntityId end : List.of(link.from(), link.to())) {
          if (isTopic(end)) {
            kept.add(end);
          }
        }
      }
    }
    for (Map.Entry<EntityId, List<Line>> entry : sent.entrySet()) {
      EntityId topic = entry.getKey();
      List<Line> in = delivered.get(topic);
      if (in == null || kept.contains(topic)) {
        continue;
      }
      Set<EntityId> senders = new HashSet<>();
      entry.getValue().forEach(l -> senders.add(l.from()));
      Set<EntityId> receivers = new HashSet<>();
      in.forEach(l -> receivers.add(l.to()));
      if (senders.size() == 1 && receivers.size() == 1 && !senders.equals(receivers)) {
        folded.add(topic);
      }
    }
    if (folded.isEmpty()) {
      return links;
    }
    List<Line> result = new ArrayList<>();
    for (Line link : links) {
      if (folded.contains(link.to()) && link.relationship().equals(Types.PUBLISHES)) {
        for (Line delivery : delivered.get(link.to())) {
          List<Fact> facts = new ArrayList<>(link.facts());
          facts.addAll(delivery.facts());
          result.add(
              new Line(
                  link.from(),
                  link.relationship(),
                  delivery.to(),
                  link.plain(),
                  link.prefix(),
                  link.detail(),
                  link.technology(),
                  assembler.name(link.to()),
                  link.profile() != null ? link.profile() : delivery.profile(),
                  facts));
        }
      } else if (!folded.contains(link.from()) && !folded.contains(link.to())) {
        result.add(link);
      }
    }
    return result;
  }

  private boolean isTopic(EntityId element) {
    return isType(element.type(), Types.TOPIC) || isType(element.type(), Types.QUEUE);
  }

  /**
   * What the overview folded away, said under it (docs/design.md, Readable diagrams): a note, since
   * it states how the view is drawn, not a claim about the system.
   */
  private Optional<Callout> foldNote(String id, SortedSet<EntityId> folded) {
    if (folded.isEmpty()) {
      return Optional.empty();
    }
    List<String> names = folded.stream().map(assembler::name).toList();
    return Optional.of(
        new Callout(
            id,
            Callout.Variant.NOTE,
            null,
            null,
            List.of(
                TextRun.of(
                    (names.size() == 1 ? "The topic or queue " : "The topics and queues ")
                        + ScopeFlows.list(names)
                        + (names.size() == 1 ? " carries" : " carry")
                        + " messages from one sender to one receiver, so the views draw "
                        + (names.size() == 1 ? "it" : "them")
                        + " as \"via\" on the line between them; the messaging view draws"
                        + (names.size() == 1 ? " it" : " them")
                        + " in full.")),
            List.of()));
  }

  private Section subsection(String id, String title, EntityId entity, Diagram diagram) {
    return new Section(
        id, title, SectionKind.CONTAINERS.levels(), entity, List.of(), List.of(diagram), List.of());
  }

  /**
   * Whether a container or component diagram is readable by the signals measured before layout:
   * within the limits' elements, the boundaries it draws others in not counted, and lines per
   * element (docs/design.md, Readable diagrams).
   */
  static boolean fits(Diagram diagram, DiagramLimits limits) {
    return limits.fits(elements(diagram), diagram.edges().size());
  }

  private boolean fits(Diagram diagram) {
    return fits(diagram, limits);
  }

  /** The elements a diagram draws, the boundaries others are drawn in not counted. */
  static int elements(Diagram diagram) {
    Set<EntityId> boundaries = new HashSet<>();
    diagram
        .nodes()
        .forEach(
            n -> {
              if (n.parent() != null) {
                boundaries.add(n.parent());
              }
            });
    return (int) diagram.nodes().stream().filter(n -> !boundaries.contains(n.id())).count();
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

  /** Whether a type is the given type or extends it, as the model's vocabulary says (ADR 0114). */
  private boolean isType(String type, String ancestor) {
    return type.equals(ancestor) || model.vocabulary().isA(type, ancestor);
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
    List<DeploymentInstances.Deployed> containers = DeploymentInstances.deployed(model, repos);
    if (containers.isEmpty()) {
      return Optional.empty();
    }
    List<Table.Row> rows = new ArrayList<>();
    for (DeploymentInstances.Deployed deployed : containers) {
      List<String> runs = new ArrayList<>();
      for (EntityId entity : deployed.runs()) {
        runs.add(assembler.name(entity));
      }
      String image = deployed.image();
      String build = deployed.build();
      String product = deployed.product();
      String version = deployed.version();
      rows.add(
          new Table.Row(
              null,
              List.of(
                  cell(deployed.environment()),
                  cell(deployed.name()),
                  cell(image != null ? image : build == null ? null : "built from " + build),
                  cell(
                      product == null ? null : version == null ? product : product + " " + version),
                  cell(String.join(", ", runs)),
                  cell(String.join(", ", deployed.ports()))),
              assembler.facts(deployed.facts())));
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
    String in = splitIn == null ? "" : " in " + splitIn;
    return containerDiagram(
        id,
        shown,
        title + " of " + assembler.name(system) + in + scope,
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
    Map<EntityId, Diagram.Node> nodes = new TreeMap<>(ArchitectureModel.BY_ID);
    nodes.put(system, node(system, null, null, List.of()));
    if (everyContainer) {
      shown.forEach(unit -> container(nodes, lift.getOrDefault(unit, unit)));
    }
    Edges edges = new Edges();
    for (Line link : drawing) {
      EntityId from = lift.getOrDefault(link.from(), link.from());
      EntityId to = lift.getOrDefault(link.to(), link.to());
      if ((concern == null || concern.relationships.contains(link.relationship()))
          && (shown.contains(link.from()) || shown.contains(link.to()))
          && !merged(lift, link.from(), link.to())
          && !(withoutOutside && (outside(link.from()) || outside(link.to())))) {
        container(nodes, from);
        container(nodes, to);
        edges.add(from, to, link);
      }
    }
    // Routes between exchanges and queues both drawn already.
    for (Line link : drawing) {
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
        if (link.relationship().equals(Types.CALLS_HTTP) && elements.runsCode(link.to())) {
          called.add(link.to());
          if ("Routes".equals(link.prefix())) {
            gateways.add(link.from());
          }
        }
      }
      List<EntityId> roots = new ArrayList<>(Elements.frontends(model));
      roots.addAll(gateways);
      if (roots.isEmpty()) {
        Elements.units(model).stream().filter(u -> !called.contains(u)).forEach(roots::add);
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
              && elements.runsCode(link.to())
              && depth.putIfAbsent(link.to(), depth.get(from) + 1) == null) {
            queue.add(link.to());
          }
        }
      }
      entryFirst =
          Comparator.comparing((EntityId e) -> !isType(e.type(), Types.FRONTEND))
              .thenComparing(e -> depth.getOrDefault(e, Integer.MAX_VALUE))
              .thenComparing(ArchitectureModel.BY_ID);
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
        isType(entity.type(), Types.EXTERNAL_SYSTEM)
                || isType(entity.type(), Types.ACTOR)
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
    List<Fact> facts = model.groupFacts(group);
    int members = model.groupMembers(group).size();
    String what = group.key().contains("/" + Groups.DOMAIN + "/") ? "container" : "component";
    return node(group, members + " " + what + (members == 1 ? "" : "s"), parent, facts);
  }

  /**
   * The system's domains the scope draws (#535; ADR 0117): each unit in scope that a stored domain
   * holds with enough other units in scope, the limits' smallest group, with that domain. A smaller
   * domain stands for nothing and is left out.
   */
  private Map<EntityId, EntityId> domainOf(Set<EntityId> shown) {
    Map<EntityId, EntityId> result = new HashMap<>();
    for (Groups.Group domain : Groups.of(model, Groups.DOMAIN)) {
      List<EntityId> inScope = domain.members().stream().filter(shown::contains).toList();
      if (inScope.size() >= limits.smallestGroup()) {
        inScope.forEach(m -> result.putIfAbsent(m, domain.id()));
      }
    }
    return result;
  }

  /**
   * The groups each unit is drawn as, with each data store, topic, queue or exchange that only the
   * units of one group use drawn as that group too: a group's own detail, which its page shows
   * (#764).
   */
  private Map<EntityId, EntityId> inner(Map<EntityId, EntityId> groupOf) {
    if (groupOf.isEmpty()) {
      return groupOf;
    }
    Map<EntityId, Set<EntityId>> users = new TreeMap<>(ArchitectureModel.BY_ID);
    for (Line link : drawing) {
      if (link.relationship().equals(Types.ROUTES_TO)) {
        continue;
      }
      for (EntityId[] ends :
          List.of(
              new EntityId[] {link.from(), link.to()}, new EntityId[] {link.to(), link.from()})) {
        if (STORES.contains(ends[0].type())) {
          users
              .computeIfAbsent(ends[0], e -> new HashSet<>())
              .add(groupOf.getOrDefault(ends[1], ends[1]));
        }
      }
    }
    Map<EntityId, EntityId> result = new HashMap<>(groupOf);
    users.forEach(
        (store, by) -> {
          if (by.size() == 1 && by.iterator().next().type().equals(Types.GROUP)) {
            result.put(store, by.iterator().next());
          }
        });
    return result;
  }

  /** What a group's own detail can be. */
  private static final Set<String> STORES =
      Set.of(Types.DATABASE, Types.TOPIC, Types.QUEUE, Types.EXCHANGE);

  /**
   * The decision records of the scope's repositories, oldest number first, each with its rationale
   * quoted from its context section or marked as not recorded; a note when there are none, since
   * then why the system is built as it is is not known (Quality bar, Architecture Explorer).
   */
  Optional<Block> decisions(String section) {
    SortedMap<EntityId, Decisions.Decision> decisions = new TreeMap<>(ArchitectureModel.BY_ID);
    for (Decisions.Decision decision : Decisions.of(model)) {
      if (repos.contains(decision.repo())) {
        decisions.putIfAbsent(decision.id(), decision);
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
    for (Decisions.Decision decision : decisions.values()) {
      assembler.entity(decision.id());
      rows.add(
          new Table.Row(
              decision.id(),
              List.of(
                  cell(decision.number() == null ? null : "ADR " + decision.number()),
                  cell(assembler.name(decision.id())),
                  cell(decision.status()),
                  cell(decision.outcome()),
                  cell(rationale(decision))),
              assembler.facts(decision.facts())));
    }
    rows.sort(
        (a, b) -> {
          int x = number(decisions.get(a.entity()));
          int y = number(decisions.get(b.entity()));
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
  private static String rationale(Decisions.Decision decision) {
    return Optional.ofNullable(decision.context())
        .map(String::strip)
        .filter(c -> !c.isEmpty())
        .map(c -> c.split("\\n\\s*\\n", 2)[0].strip().replaceAll("\\s*\\n\\s*", " "))
        .orElse(NOT_RECORDED);
  }

  private static int number(Decisions.Decision decision) {
    return Optional.ofNullable(decision.number())
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
    try {
      return drawComponents(section);
    } finally {
      plainDrawing();
    }
  }

  private List<Section> drawComponents(String section) {
    List<Section> result = new ArrayList<>();
    // From the entry inward, as the container pages.
    SortedSet<EntityId> ordered = new TreeSet<>(entryFirst());
    ordered.addAll(units);
    for (EntityId unit : ordered) {
      plainDrawing();
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
      // A component no line reaches is on a page of its own and on no other view of the unit
      // (owner's decision of 6 Nov 2026 on #764, as for containers).
      Set<EntityId> lined = new HashSet<>();
      diagram
          .edges()
          .forEach(
              e -> {
                lined.add(e.from());
                lined.add(e.to());
              });
      // An entry point is reached from outside the unit, whether or not the code shows from where.
      components.stream().filter(Elements.Component::entryPoint).forEach(c -> lined.add(c.main()));
      List<Elements.Component> alone =
          components.stream().filter(c -> !lined.contains(c.main())).toList();
      if (!alone.isEmpty() && alone.size() < components.size()) {
        components = components.stream().filter(c -> lined.contains(c.main())).toList();
        all.clear();
        components.forEach(c -> all.add(c.main()));
        diagram =
            componentDiagram(
                assembler.id(id, "diagram"),
                unit,
                components,
                "Components of " + assembler.name(unit),
                all,
                Map.of());
      } else {
        alone = List.of();
      }
      List<Section> areas = new ArrayList<>();
      boolean outsideOff = false;
      List<Elements.Component> outsideOnly = new ArrayList<>();
      Set<EntityId> looseOutside = new java.util.LinkedHashSet<>();
      Set<EntityId> overview = new java.util.LinkedHashSet<>(all);
      Set<EntityId> crossOff = new HashSet<>();
      if (!fits(diagram)) {
        // The coarser level the code proves, each feature area one box with a page of its own;
        // a view still too large there, or with no areas, merges its lines per pair (owner's
        // decisions (a) and (b) of 4 Nov 2026 on #764).
        Map<EntityId, EntityId> areaOf = areaOf(unit, components);
        String title =
            "Components of " + assembler.name(unit) + (areaOf.isEmpty() ? "" : ", by feature area");
        diagram =
            componentDiagram(assembler.id(id, "diagram"), unit, components, title, all, areaOf);
        if (!fits(diagram) && !areaOf.isEmpty()) {
          // What the unit's components reach outside it is left off, as on a map of domains;
          // each area's page draws it (owner's decision of 6 Nov 2026 on #764). With no areas
          // there is no such page, so the view is split instead.
          Diagram full = diagram;
          withoutOutside = true;
          Diagram inside =
              componentDiagram(assembler.id(id, "diagram"), unit, components, title, all, areaOf);
          outsideOff = inside.nodes().size() < diagram.nodes().size();
          // What cuts across the areas (an aspect, a bundled library's class) is left off too,
          // with a note; each area's page draws it (owner's decision of 6 Nov 2026 on #764).
          for (Elements.Component component : components) {
            if (component.crossCutting() && !areaOf.containsKey(component.main())) {
              crossOff.add(component.main());
            }
          }
          if (!crossOff.isEmpty() && crossOff.size() < overview.size()) {
            overview.removeAll(crossOff);
            // Its lines are left off with it, so it is not drawn as a neighbour.
            callersElsewhere = crossOff;
            inside =
                componentDiagram(
                    assembler.id(id, "diagram"), unit, components, title, overview, areaOf);
          } else {
            crossOff.clear();
          }
          diagram = inside;
          // A component whose every line goes outside is left with none here: it is not part of
          // what this view shows, and is drawn on a page of its own with its lines (owner's
          // decision of 6 Nov 2026 on #764, as for what no line reaches).
          Set<EntityId> inView = new HashSet<>();
          inside
              .edges()
              .forEach(
                  e -> {
                    inView.add(e.from());
                    inView.add(e.to());
                  });
          for (Elements.Component component : components) {
            if (!areaOf.containsKey(component.main())
                && !inView.contains(component.main())
                && !crossOff.contains(component.main())) {
              outsideOnly.add(component);
            }
          }
          // A component in no area that stays here has its lines outside left off with no area page
          // to draw them, so a page of its own does, as for a unit in no domain (#764).
          Set<EntityId> mainsHere = new HashSet<>();
          components.forEach(c -> mainsHere.add(c.main()));
          for (Elements.Component component : components) {
            EntityId main = component.main();
            if (areaOf.containsKey(main) || outsideOnly.contains(component)) {
              continue;
            }
            // Outside is what is neither a component of the unit nor one of its feature areas.
            java.util.function.Predicate<EntityId> beyond =
                end -> !mainsHere.contains(end) && !end.type().equals(Types.GROUP);
            boolean outside =
                full.edges().stream()
                    .anyMatch(
                        e ->
                            (e.from().equals(main) && beyond.test(e.to()))
                                || (e.to().equals(main) && beyond.test(e.from())));
            if (outside) {
              looseOutside.add(main);
            }
          }
          // A feature area with no line here is off it too; its own page draws it.
          Set<EntityId> unlinedAreas = new HashSet<>();
          for (Elements.Component component : components) {
            EntityId area = areaOf.get(component.main());
            if (area != null && !inView.contains(area)) {
              unlinedAreas.add(area);
            }
          }
          if (!unlinedAreas.isEmpty()
              && unlinedAreas.size() < new HashSet<>(areaOf.values()).size()) {
            for (Elements.Component component : components) {
              if (unlinedAreas.contains(areaOf.get(component.main()))) {
                overview.remove(component.main());
              }
            }
            diagram =
                componentDiagram(
                    assembler.id(id, "diagram"), unit, components, title, overview, areaOf);
          }
          if (!outsideOnly.isEmpty() && outsideOnly.size() < components.size()) {
            outsideOnly.forEach(c -> overview.remove(c.main()));
            diagram =
                componentDiagram(
                    assembler.id(id, "diagram"), unit, components, title, overview, areaOf);
          } else {
            outsideOnly.clear();
          }
        }
        if (!fits(diagram)) {
          perPair = true;
          diagram =
              componentDiagram(
                  assembler.id(id, "diagram"), unit, components, title, overview, areaOf);
          perPair = false;
        }
        withoutOutside = false;
        callersElsewhere = Set.of();
        if (!areaOf.isEmpty()) {
          areas = areas(id, unit, components, areaOf);
        } else if (!fits(diagram)) {
          // No area to draw and still too large: split by groups of entry points.
          areas =
              byEntryPoint(
                  id,
                  unit,
                  assembler.name(unit),
                  unit,
                  components,
                  "Components of " + assembler.name(unit),
                  all,
                  Map.of());
          diagram = null;
          outsideOff = false;
        }
      }
      if (!looseOutside.isEmpty() && outsideOff && diagram != null) {
        String looseId = assembler.id(id, "no-area");
        String looseTitle =
            "Components of " + assembler.name(unit) + " " + NO_AREA.toLowerCase(Locale.ROOT);
        Diagram looseView =
            componentDiagram(
                assembler.id(looseId, "diagram"),
                unit,
                components,
                looseTitle,
                looseOutside,
                Map.of());
        if (fits(looseView)) {
          areas.add(
              new Section(
                  looseId,
                  NO_AREA,
                  SectionKind.COMPONENTS.levels(),
                  null,
                  List.of(),
                  List.of(looseView),
                  List.of()));
        } else {
          areas.addAll(
              byEntryPoint(
                  looseId, null, NO_AREA, unit, components, looseTitle, looseOutside, Map.of()));
        }
      }
      if (!outsideOnly.isEmpty()) {
        Set<EntityId> mains = new HashSet<>();
        outsideOnly.forEach(c -> mains.add(c.main()));
        String outsideId = assembler.id(id, "outside-only");
        String outsideTitle = "Connected to no feature area";
        String outsideDiagramTitle =
            "Components of " + assembler.name(unit) + " connected to no feature area";
        Diagram outsideView =
            componentDiagram(
                assembler.id(outsideId, "diagram"),
                unit,
                components,
                outsideDiagramTitle,
                mains,
                Map.of());
        if (fits(outsideView)) {
          areas.add(
              new Section(
                  outsideId,
                  outsideTitle,
                  SectionKind.COMPONENTS.levels(),
                  null,
                  List.of(),
                  List.of(outsideView),
                  List.of()));
        } else {
          // Too many for one page: split by groups of entry points like any other page.
          areas.addAll(
              byEntryPoint(
                  outsideId,
                  null,
                  outsideTitle,
                  unit,
                  components,
                  outsideDiagramTitle,
                  mains,
                  Map.of()));
        }
      }
      if (!alone.isEmpty()) {
        Set<EntityId> mains = new HashSet<>();
        alone.forEach(c -> mains.add(c.main()));
        String aloneId = assembler.id(id, "unconnected");
        areas.add(
            new Section(
                aloneId,
                ALONE,
                SectionKind.COMPONENTS.levels(),
                null,
                List.of(),
                List.of(
                    componentDiagram(
                        assembler.id(aloneId, "diagram"),
                        unit,
                        alone,
                        "Components of " + assembler.name(unit) + " with no connections found",
                        mains,
                        Map.of())),
                List.of()));
      }
      List<Block> blocks = new ArrayList<>();
      if (diagram != null) {
        blocks.add(diagram);
      }
      if (outsideOff) {
        blocks.add(
            new Callout(
                assembler.id(id, "outside"),
                Callout.Variant.NOTE,
                null,
                null,
                List.of(
                    TextRun.of(
                        "What these components reach outside "
                            + assembler.name(unit)
                            + " is left off this view to keep it readable; each area's page"
                            + " draws it"
                            + (looseOutside.isEmpty()
                                ? "."
                                : ", and the page " + NO_AREA + " draws it for the rest."))),
                List.of()));
      }
      if (!crossOff.isEmpty()) {
        List<String> names = new ArrayList<>();
        components.forEach(
            c -> {
              if (crossOff.contains(c.main())) {
                names.add(elements.componentName(c));
              }
            });
        blocks.add(
            new Callout(
                assembler.id(id, "cross-cutting"),
                Callout.Variant.NOTE,
                null,
                null,
                List.of(
                    TextRun.of(
                        ScopeFlows.list(names)
                            + (names.size() == 1 ? " cuts" : " cut")
                            + " across the feature areas and "
                            + (names.size() == 1 ? "is" : "are")
                            + " left off this view to keep it readable; each area's page draws"
                            + " where it applies.")),
                List.of()));
      }
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
    for (Groups.Group area : Groups.of(model, Groups.FEATURE_AREA)) {
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
    // An area with fewer of the view's components than the smallest group stands for nothing.
    Map<EntityId, Long> sizes = new HashMap<>();
    result.values().forEach(a -> sizes.merge(a, 1L, Long::sum));
    result.values().removeIf(a -> sizes.get(a) < limits.smallestGroup());
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
          String title = "Components of " + assembler.name(unit) + " in " + name;
          Diagram view =
              componentDiagram(
                  assembler.id(areaId, "diagram"), unit, components, title, mains, others);
          if (!fits(view)) {
            // Too large: its lines merged per pair first (docs/design.md, Readable diagrams).
            perPair = true;
            view =
                componentDiagram(
                    assembler.id(areaId, "diagram"), unit, components, title, mains, others);
            perPair = false;
          }
          if (!fits(view)) {
            // An area too large for one page is split by groups of entry points (docs/design.md,
            // Readable diagrams), each page naming the area.
            result.addAll(byEntryPoint(areaId, area, name, unit, components, title, mains, others));
            return;
          }
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
   * The pages of components too many for one view (#764; docs/design.md, Readable diagrams): each
   * entry point in focus (or, with none, each component nothing in focus calls) with the components
   * it reaches in focus, entry points in order put on one page while it fits; what no entry point
   * reaches goes on the last page. Each page is titled by the scope and its entry points.
   */
  /**
   * The components whose lines to or from a page's focus are left off it: on the pages of a split,
   * the rest of the components being split, each drawn on a page of its own.
   */
  private Set<EntityId> callersElsewhere = Set.of();

  /** The ID of a diagram drawn only to measure it, never shown. */
  private static final String TRIAL = "trial";

  private List<Section> byEntryPoint(
      String id,
      EntityId entity,
      String scope,
      EntityId unit,
      List<Elements.Component> components,
      String title,
      Set<EntityId> focus,
      Map<EntityId, EntityId> lift) {
    // Split only after merging lines per pair, so each page draws them merged too.
    perPair = true;
    callersElsewhere = focus;
    try {
      return pagesByEntryPoint(id, entity, scope, unit, components, title, focus, lift);
    } finally {
      perPair = false;
      callersElsewhere = Set.of();
    }
  }

  private List<Section> pagesByEntryPoint(
      String id,
      EntityId entity,
      String scope,
      EntityId unit,
      List<Elements.Component> components,
      String title,
      Set<EntityId> focus,
      Map<EntityId, EntityId> lift) {
    // The calls between the components in focus, each drawn as itself.
    Diagram flat = componentDiagram(TRIAL, unit, components, title, focus, Map.of());
    Map<EntityId, Set<EntityId>> calls = new HashMap<>();
    Set<EntityId> called = new HashSet<>();
    for (Diagram.Edge edge : flat.edges()) {
      if (focus.contains(edge.from()) && focus.contains(edge.to())) {
        calls
            .computeIfAbsent(edge.from(), f -> new java.util.TreeSet<>(ArchitectureModel.BY_ID))
            .add(edge.to());
        called.add(edge.to());
      }
    }
    List<EntityId> inOrder = new ArrayList<>();
    components.forEach(
        c -> {
          if (focus.contains(c.main())) {
            inOrder.add(c.main());
          }
        });
    List<EntityId> entries =
        components.stream()
            .filter(c -> focus.contains(c.main()) && c.entryPoint())
            .map(Elements.Component::main)
            .toList();
    if (entries.isEmpty()) {
      entries = inOrder.stream().filter(c -> !called.contains(c)).toList();
    }
    if (entries.isEmpty()) {
      entries = inOrder;
    }
    List<Set<EntityId>> pages = new ArrayList<>();
    List<List<EntityId>> pageEntries = new ArrayList<>();
    // The pages of what no entry point reaches, by index.
    Set<Integer> restOnly = new HashSet<>();
    Set<EntityId> current = new java.util.LinkedHashSet<>();
    List<EntityId> currentEntries = new ArrayList<>();
    for (EntityId entry : entries) {
      Set<EntityId> reach = new java.util.LinkedHashSet<>();
      Deque<EntityId> todo = new ArrayDeque<>(List.of(entry));
      while (!todo.isEmpty()) {
        EntityId next = todo.pop();
        if (reach.add(next)) {
          todo.addAll(calls.getOrDefault(next, Set.of()));
        }
      }
      Set<EntityId> together = new java.util.LinkedHashSet<>(current);
      together.addAll(reach);
      if (current.isEmpty()
          || fits(componentDiagram(TRIAL, unit, components, title, together, lift))) {
        current = together;
        currentEntries.add(entry);
      } else {
        pages.add(current);
        pageEntries.add(currentEntries);
        current = reach;
        currentEntries = new ArrayList<>(List.of(entry));
      }
    }
    Set<EntityId> left = new java.util.LinkedHashSet<>(inOrder);
    pages.forEach(left::removeAll);
    left.removeAll(current);
    pages.add(current);
    pageEntries.add(currentEntries);
    // What no entry point reaches goes on pages of its own, as many as fit, each component with
    // what it calls, as an entry point's page has, so every line between two of them is drawn on
    // some page and no split drops one (#764 review).
    Set<EntityId> rest = new java.util.LinkedHashSet<>();
    List<EntityId> restHeads = new ArrayList<>();
    Set<EntityId> restPlaced = new HashSet<>();
    for (EntityId component : left) {
      if (restPlaced.contains(component)) {
        continue;
      }
      Set<EntityId> reach = new java.util.LinkedHashSet<>();
      Deque<EntityId> todo = new ArrayDeque<>(List.of(component));
      while (!todo.isEmpty()) {
        EntityId next = todo.pop();
        if (reach.add(next)) {
          todo.addAll(calls.getOrDefault(next, Set.of()));
        }
      }
      Set<EntityId> together = new java.util.LinkedHashSet<>(rest);
      together.addAll(reach);
      if (rest.isEmpty()
          || fits(componentDiagram(TRIAL, unit, components, title, together, lift))) {
        rest = together;
        restHeads.add(component);
      } else {
        pages.add(rest);
        pageEntries.add(List.copyOf(restHeads));
        restOnly.add(pages.size() - 1);
        rest = reach;
        restHeads = new ArrayList<>(List.of(component));
      }
      reach.stream().filter(left::contains).forEach(restPlaced::add);
    }
    if (!rest.isEmpty()) {
      pages.add(rest);
      pageEntries.add(List.copyOf(restHeads));
      restOnly.add(pages.size() - 1);
    }
    // A page still too large for its one entry point goes one step down per page (#764): the
    // entry point with what it calls directly, then each of those that calls further, the same way.
    List<String> headings = new ArrayList<>();
    List<Set<EntityId>> drawn = new ArrayList<>();
    Set<EntityId> visited = new HashSet<>();
    for (int i = 0; i < pages.size(); i++) {
      if (restOnly.contains(i)) {
        if (pageEntries.get(i).size() == 1
            && !fits(componentDiagram(TRIAL, unit, components, title, pages.get(i), lift))) {
          stepDown(
              pageEntries.get(i).getFirst(),
              false,
              calls,
              visited,
              unit,
              components,
              title,
              lift,
              headings,
              drawn);
          continue;
        }
        headings.add("called by no entry point");
        drawn.add(pages.get(i));
        continue;
      }
      String from =
          "from " + ScopeFlows.list(pageEntries.get(i).stream().map(assembler::name).toList());
      if (pageEntries.get(i).size() == 1
          && !fits(componentDiagram(TRIAL, unit, components, title, pages.get(i), lift))) {
        stepDown(
            pageEntries.get(i).getFirst(),
            true,
            calls,
            visited,
            unit,
            components,
            title,
            lift,
            headings,
            drawn);
        continue;
      }
      headings.add(from);
      drawn.add(pages.get(i));
    }
    List<Section> result = new ArrayList<>();
    for (int i = 0; i < drawn.size(); i++) {
      String from = headings.get(i);
      String pageId = assembler.id(id, "part-" + (i + 1));
      result.add(
          new Section(
              pageId,
              scope + ": " + from,
              SectionKind.COMPONENTS.levels(),
              entity,
              List.of(),
              List.of(
                  componentDiagram(
                      assembler.id(pageId, "diagram"),
                      unit,
                      components,
                      title + ", " + from,
                      drawn.get(i),
                      lift)),
              List.of()));
    }
    return result;
  }

  /**
   * The pages of one component and what it calls directly, as many as it takes to fit, then the
   * same for each of those that calls further, each component a page of its own at most once.
   */
  private void stepDown(
      EntityId root,
      boolean entry,
      Map<EntityId, Set<EntityId>> calls,
      Set<EntityId> visited,
      EntityId unit,
      List<Elements.Component> components,
      String title,
      Map<EntityId, EntityId> lift,
      List<String> headings,
      List<Set<EntityId>> drawn) {
    if (!visited.add(root)) {
      return;
    }
    Set<EntityId> direct = calls.getOrDefault(root, Set.of());
    List<Set<EntityId>> parts = new ArrayList<>();
    Set<EntityId> current = new java.util.LinkedHashSet<>(List.of(root));
    for (EntityId callee : direct) {
      Set<EntityId> together = new java.util.LinkedHashSet<>(current);
      together.add(callee);
      if (current.size() == 1
          || fits(componentDiagram(TRIAL, unit, components, title, together, lift))) {
        current = together;
      } else {
        parts.add(current);
        current = new java.util.LinkedHashSet<>(List.of(root, callee));
      }
    }
    parts.add(current);
    String heading =
        entry ? "from " + assembler.name(root) : "what " + assembler.name(root) + " calls";
    for (int i = 0; i < parts.size(); i++) {
      headings.add(
          parts.size() == 1 ? heading : heading + " (" + (i + 1) + " of " + parts.size() + ")");
      drawn.add(parts.get(i));
    }
    for (EntityId callee : direct) {
      if (!calls.getOrDefault(callee, Set.of()).isEmpty()) {
        stepDown(callee, false, calls, visited, unit, components, title, lift, headings, drawn);
      }
    }
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
    Map<EntityId, Diagram.Node> nodes = new TreeMap<>(ArchitectureModel.BY_ID);
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
    Map<EntityId, EntityId> mainOf = new TreeMap<>(ArchitectureModel.BY_ID);
    components.forEach(c -> mainOf.put(Components.id(unit, c.main()), c.main()));
    Map<String, EntityId> codeEnds = new HashMap<>();
    for (Elements.Component component : components) {
      for (ComponentInteractions.ComponentLine stored :
          ComponentInteractions.lines(model, Components.id(unit, component.main()))) {
        Fact line = stored.fact();
        String kind = stored.kind();
        String detail = stored.detail();
        if (ComponentInteractions.CODE_END.equals(kind)) {
          // The side the code is on, then each link it places, by the fact it cites.
          String side = stored.target().split(" ", 2)[0];
          stored.facts().forEach(link -> codeEnds.put(side + " " + link, component.main()));
          continue;
        }
        EntityId to = mainOf.get(ArchitectureModel.object(line));
        if (to == null
            || !(focus.contains(component.main()) || focus.contains(to))
            || (!focus.contains(component.main()) && callersElsewhere.contains(component.main()))
            || (!focus.contains(to) && callersElsewhere.contains(to))
            || merged(lift, component.main(), to)) {
          continue;
        }
        EntityId fromEnd = shown.apply(component.main());
        EntityId toEnd = shown.apply(to);
        String relationship =
            ComponentInteractions.ADVISES_KIND.equals(kind) ? Types.ADVISES : Types.CALLS;
        String plain =
            switch (kind) {
              case ComponentInteractions.APPLIES -> "Applies";
              case ComponentInteractions.ADVISES_KIND -> "Advises";
              default -> "Calls";
            };
        String prefix = ComponentInteractions.APPLIES.equals(kind) ? "Applies on routes" : plain;
        EdgeBuilder edge = edges.get(fromEnd, relationship, toEnd);
        // The intent Describe stored for the component line (#739).
        Assembler.Phrased phrase = assembler.phrase(line);
        for (Fact cited : model.derivedFrom(line)) {
          edge.add(plain, prefix, detail, null, null);
          edge.facts.add(cited);
          edge.phrased(phrase);
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
    for (Line link : withoutOutside ? List.<Line>of() : links) {
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
      String access = model.dataAccess(Components.id(unit, component.main())).orElse(null);
      if (access == null || !focus.contains(component.main()) || withoutOutside) {
        continue;
      }
      for (Line link : links) {
        if (link.from().equals(unit) && link.relationship().equals(Types.USES_DATABASE)) {
          container(nodes, link.to());
          EdgeBuilder edge =
              edges.get(shown.apply(component.main()), Types.USES_DATABASE, link.to());
          edge.add(access, null, null, link.technology(), link.profile());
          edge.facts.addAll(link.facts());
          // The unit's intent for its store, which the component's access serves (#739).
          edge.phrased(assembler.phrase(link.facts().getFirst()));
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
    SortedSet<EntityId> set = new TreeSet<>(ArchitectureModel.BY_ID);
    set.add(entity);
    return set;
  }

  private Diagram.Node node(EntityId entity, String technology, EntityId parent, List<Fact> facts) {
    assembler.entity(entity);
    return new Diagram.Node(
        entity, assembler.name(entity), technology, parent, null, null, assembler.facts(facts));
  }
}

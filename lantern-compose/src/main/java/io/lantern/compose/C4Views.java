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
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
import tools.jackson.databind.JsonNode;

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
 * under. People are drawn only when the knowledge names them, which it does not yet, so a note says
 * who uses the system is unknown.
 */
final class C4Views {

  static final String DEPLOYABLE_UNIT = "deployable-unit";
  static final String APPLICATION_NAME = "application-name";
  static final String TECHNOLOGY = "technology";
  static final String BUNDLES = "BUNDLES";
  static final String CALLS_HTTP = "CALLS_HTTP";
  static final String SENDS_MAIL = "SENDS_MAIL";
  static final String PUBLISHES = "PUBLISHES";
  static final String CONSUMES = "CONSUMES";
  static final String ROUTES_TO = "ROUTES_TO";
  static final String USES_DATABASE = "USES_DATABASE";
  static final String ACCESSES_DATA_OF = "ACCESSES_DATA_OF";
  static final String UNRESOLVED_LINK = "unresolved-link";
  static final String DATABASE = "database";
  static final String TOPIC = "topic";
  static final String QUEUE = "queue";
  static final String EXCHANGE = "exchange";
  static final String EXTERNAL_SYSTEM = "external-system";
  static final String DECISION = "decision";

  /** The kinds of container that are not deployable units: data stores and destinations. */
  static final Set<String> DESTINATIONS = Set.of(DATABASE, TOPIC, QUEUE, EXCHANGE, EXTERNAL_SYSTEM);

  /** A decision record's rationale when it has no context section. */
  static final String NOT_RECORDED = "Not recorded.";

  /** The most elements a diagram shows before it is split (docs/design.md, Quality bar). */
  static final int MAX_ELEMENTS = 15;

  /** How many details an edge label names before it counts the rest. */
  private static final int NAMED = 2;

  private static final String METHOD = "method";
  private static final String STEREOTYPE = "stereotype";
  private static final String MAIN_CLASS = "main-class";
  private static final String ENDPOINT = "endpoint";
  private static final String MESSAGE_LISTENER = "message-listener";
  private static final String SCHEDULED = "scheduled";
  private static final String BACKGROUND_JOB = "background-job";
  private static final String HTTP_CLIENT = "http-client";
  private static final String HTTP_CALL = "http-call";
  private static final String MESSAGE_SEND = "message-send";
  private static final String REPOSITORY_FOR = "REPOSITORY_FOR";
  private static final String READS_TABLE = "READS_TABLE";
  private static final String WRITES_TABLE = "WRITES_TABLE";
  private static final String INJECTS = "INJECTS";
  private static final Set<String> BEAN_STEREOTYPES =
      Set.of("controller", "service", "repository", "component");

  private final Knowledge knowledge;
  private final Assembler assembler;
  private final EntityId system;
  private final SortedSet<EntityId> units;
  private final Set<String> repos;
  private final List<Link> links;

  /** Each profile-only link that replaces a default one under its profile, with that default. */
  private final Map<Fact, Fact> alternatives;

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
    this.alternatives = alternatives();
    this.links = containerLinks();
    links.removeIf(link -> alternatives.containsKey(link.facts.getFirst()));
  }

  /**
   * Each profile-only link that replaces a default link under its profile, with that default; the
   * diagrams show the default.
   */
  Map<Fact, Fact> alternativeLinks() {
    return alternatives;
  }

  /**
   * A line of the container diagrams seen from one of its ends (ADR 0056).
   *
   * @param other the element at the other end
   * @param relationship the relationship the line draws, such as {@code CALLS_HTTP}
   * @param incoming whether the line runs from the other end to this one
   * @param via what it goes through (the name of a queue, or the tables read), or null
   * @param facts its link facts
   */
  record Neighbour(
      EntityId other, String relationship, boolean incoming, String via, List<Fact> facts) {}

  /**
   * The data stores, topics, queues, exchanges and external systems the container diagrams draw:
   * the ends of their lines to and from units that are not units themselves, profile alternatives
   * and queues folded into a line left out. Routes between exchanges and queues add nothing, as on
   * the diagrams, which draw them only between elements drawn already.
   */
  SortedSet<EntityId> destinations() {
    SortedSet<EntityId> result = new TreeSet<>(Knowledge.BY_ID);
    for (Link link : links) {
      if (!link.from.type().equals(BuiltInTypes.SERVICE)
          && !link.to.type().equals(BuiltInTypes.SERVICE)) {
        continue;
      }
      for (EntityId end : List.of(link.from, link.to)) {
        if (DESTINATIONS.contains(end.type())) {
          result.add(end);
        }
      }
    }
    return result;
  }

  /**
   * The lines of the container diagrams that touch the element, in the order of the links: those to
   * and from units, and routes between it and another of the {@link #destinations()}.
   */
  List<Neighbour> neighbours(EntityId element) {
    Set<EntityId> drawn = destinations();
    List<Neighbour> result = new ArrayList<>();
    for (Link link : links) {
      if (link.relationship.equals(ROUTES_TO)
          && !(drawn.contains(link.from) && drawn.contains(link.to))) {
        continue;
      }
      if (link.to.equals(element)) {
        result.add(new Neighbour(link.from, link.relationship, true, link.via, link.facts));
      } else if (link.from.equals(element)) {
        result.add(new Neighbour(link.to, link.relationship, false, link.via, link.facts));
      }
    }
    return result;
  }

  // The deployable units and their names.

  /** Every deployable unit of the knowledge. */
  static SortedSet<EntityId> units(Knowledge knowledge) {
    SortedSet<EntityId> units = new TreeSet<>(Knowledge.BY_ID);
    knowledge.withPredicate(DEPLOYABLE_UNIT).forEach(f -> units.add(f.subject()));
    return units;
  }

  /**
   * The deployable units whose code is in one of the modules: their own module, or a library they
   * bundle.
   */
  static SortedSet<EntityId> units(Knowledge knowledge, Set<EntityId> modules) {
    SortedSet<EntityId> result = new TreeSet<>(Knowledge.BY_ID);
    for (EntityId unit : units(knowledge)) {
      if (codeModules(knowledge, unit).stream().anyMatch(modules::contains)) {
        result.add(unit);
      }
    }
    return result;
  }

  /** The modules whose code runs in the unit: its own and those of the libraries it bundles. */
  static SortedSet<EntityId> codeModules(Knowledge knowledge, EntityId unit) {
    SortedSet<EntityId> modules = knowledge.parts(unit, BuiltInTypes.MODULE);
    for (Fact fact : knowledge.relations(unit, BUNDLES)) {
      modules.addAll(knowledge.parts(Knowledge.object(fact), BuiltInTypes.MODULE));
    }
    return modules;
  }

  /** The name a unit runs under in Spring's default profile, if it has one. */
  static Optional<String> applicationName(Knowledge knowledge, EntityId unit) {
    return knowledge.attributes(unit).stream()
        .filter(f -> f.predicate().equals(APPLICATION_NAME) && !f.attributes().has("profile"))
        .map(f -> f.object().value())
        .findFirst();
  }

  /** The facts that make an entity a container of the diagrams, and its technology. */
  List<Fact> containerFacts(EntityId entity) {
    return knowledge.attributes(entity).stream()
        .filter(
            f ->
                f.predicate().equals(DEPLOYABLE_UNIT)
                    || f.predicate().equals(TECHNOLOGY)
                    || (f.predicate().equals(APPLICATION_NAME) && !f.attributes().has("profile")))
        .toList();
  }

  /** The technology line of a container: what it is built with, or what kind of store it is. */
  String technology(EntityId entity) {
    Optional<String> technology = knowledge.attribute(entity, TECHNOLOGY);
    String broker = entity.key().substring(0, Math.max(entity.key().indexOf(':'), 0));
    return switch (entity.type()) {
      case BuiltInTypes.SERVICE, DATABASE -> technology.orElse(null);
      case TOPIC -> brokerName(broker) + " topic";
      case QUEUE -> brokerName(broker) + " queue";
      case EXCHANGE -> brokerName(broker) + " exchange";
      default -> null;
    };
  }

  private static String brokerName(String broker) {
    return switch (broker) {
      case "kafka" -> "Kafka";
      case "jms" -> "JMS";
      case "rabbitmq" -> "RabbitMQ";
      default -> broker;
    };
  }

  // Links between containers.

  /**
   * A line between two elements of a container diagram, from one link fact: what it does (a label
   * without details, and a prefix for the details), a detail such as an endpoint, its protocol,
   * what it goes through (a queue, the tables read), the profile it alone holds under and its
   * facts.
   */
  private record Link(
      EntityId from,
      String relationship,
      EntityId to,
      String plain,
      String prefix,
      String detail,
      String technology,
      String via,
      String profile,
      List<Fact> facts) {

    Link from(EntityId other) {
      return new Link(
          other, relationship, to, plain, prefix, detail, technology, via, profile, facts);
    }

    Link to(EntityId other) {
      return new Link(
          from, relationship, other, plain, prefix, detail, technology, via, profile, facts);
    }

    Link via(String through, Fact also) {
      List<Fact> both = new ArrayList<>(facts);
      both.add(also);
      return new Link(
          from, relationship, to, plain, prefix, detail, technology, through, profile, both);
    }
  }

  /** The relationships whose profile-only links may replace a default link of their unit. */
  private static final List<String> REPLACEABLE =
      List.of(CALLS_HTTP, SENDS_MAIL, PUBLISHES, CONSUMES, USES_DATABASE, ACCESSES_DATA_OF);

  /**
   * The profile-only links that stand in for a default link of the same unit, relationship and code
   * or config (the same calling method, listener or datasource key, or a linked alternate host),
   * the default one not holding under that profile: a datasource the {@code local} profile points
   * at {@code localhost}, PayGate's production host. They are left out of the diagrams and counts,
   * which show the default, and are listed in a note instead. A profile-only link without such a
   * default, such as a send only the {@code audit} profile makes, stays a line of its own.
   */
  private Map<Fact, Fact> alternatives() {
    Map<Fact, Fact> result = new java.util.LinkedHashMap<>();
    for (EntityId unit : units(knowledge)) {
      for (String relationship : REPLACEABLE) {
        List<Fact> facts = knowledge.relations(unit, relationship);
        for (Fact profiled : facts) {
          String profile = attribute(profiled, "profile");
          if (profile == null) {
            continue;
          }
          for (Fact fallback : facts) {
            if (attribute(fallback, "profile") == null
                && strings(fallback, "exceptProfiles").contains(profile)
                && sameOrigin(profiled, fallback)) {
              result.put(profiled, fallback);
              break;
            }
          }
        }
      }
    }
    return result;
  }

  /** Whether two links come from the same code or config, or name each other as alternates. */
  private static boolean sameOrigin(Fact a, Fact b) {
    if (strings(a, "alternates").contains(Knowledge.object(b).toString())) {
      return true;
    }
    for (String anchor : List.of("source", "key")) {
      String x = attribute(a, anchor);
      if (x != null) {
        return x.equals(attribute(b, anchor));
      }
    }
    return false;
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
          || (externalOnly && !target.type().equals(EXTERNAL_SYSTEM))) {
        continue;
      }
      String verb =
          switch (profiled.predicate()) {
            case CALLS_HTTP -> "calls";
            case SENDS_MAIL -> "sends mail through";
            case PUBLISHES -> "sends to";
            case CONSUMES -> "listens on";
            case USES_DATABASE -> "connects to";
            default -> "reads tables of";
          };
      assembler.entity(target);
      content.add(
          TextRun.of(
              (content.isEmpty() ? "" : " ")
                  + "Under the Spring profile "
                  + attribute(profiled, "profile")
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

  /** The links of every unit, with RabbitMQ queues an exchange routes to folded into the line. */
  private List<Link> containerLinks() {
    Set<EntityId> folded = foldedQueues();
    List<Link> result = new ArrayList<>();
    for (EntityId unit : units(knowledge)) {
      for (Fact fact : knowledge.relations(unit, CALLS_HTTP)) {
        result.add(
            link(
                unit,
                CALLS_HTTP,
                Knowledge.object(fact),
                "Calls",
                "Calls",
                endpoint(fact),
                protocol(fact),
                fact));
      }
      for (Fact fact : knowledge.relations(unit, SENDS_MAIL)) {
        result.add(
            link(
                unit,
                SENDS_MAIL,
                Knowledge.object(fact),
                "Sends mail through",
                "Sends mail",
                null,
                protocol(fact),
                fact));
      }
      for (Fact fact : knowledge.relations(unit, PUBLISHES)) {
        result.add(
            link(
                unit,
                PUBLISHES,
                Knowledge.object(fact),
                "Sends messages",
                "Sends",
                attribute(fact, "routingKey"),
                protocol(fact),
                fact));
      }
      for (Fact fact : knowledge.relations(unit, CONSUMES)) {
        EntityId queue = Knowledge.object(fact);
        if (!folded.contains(queue)) {
          result.add(
              link(queue, CONSUMES, unit, "Delivers messages", null, null, protocol(fact), fact));
          continue;
        }
        for (Fact route : routesTo(queue)) {
          result.add(
              link(
                      route.subject(),
                      CONSUMES,
                      unit,
                      "Delivers messages",
                      "Delivers",
                      attribute(route, "bindingKey"),
                      protocol(fact),
                      fact)
                  .via(assembler.name(queue), route));
        }
      }
      for (Fact fact : knowledge.relations(unit, USES_DATABASE)) {
        boolean ownsSchema = !strings(fact, "schemas").isEmpty();
        result.add(
            link(
                unit,
                USES_DATABASE,
                Knowledge.object(fact),
                ownsSchema ? "Stores its data in" : "Connects to",
                null,
                null,
                protocol(fact),
                fact));
      }
      for (Fact fact : knowledge.relations(unit, ACCESSES_DATA_OF)) {
        List<String> reads = strings(fact, "reads");
        List<String> writes = strings(fact, "writes");
        String verb =
            reads.isEmpty()
                ? "Writes tables of"
                : writes.isEmpty() ? "Reads tables of" : "Reads and writes tables of";
        Set<String> tables = new TreeSet<>();
        reads.forEach(t -> tables.add(t.substring(t.lastIndexOf(':') + 1)));
        writes.forEach(t -> tables.add(t.substring(t.lastIndexOf(':') + 1)));
        Link access =
            link(
                unit,
                ACCESSES_DATA_OF,
                Knowledge.object(fact),
                verb,
                null,
                null,
                protocol(fact),
                fact);
        result.add(
            new Link(
                access.from,
                access.relationship,
                access.to,
                access.plain,
                access.prefix,
                access.detail,
                access.technology,
                tables.isEmpty() ? null : String.join(", ", tables),
                access.profile,
                access.facts));
      }
    }
    for (Fact route : knowledge.withPredicate(ROUTES_TO)) {
      if (Knowledge.isEntity(route) && !folded.contains(Knowledge.object(route))) {
        result.add(
            link(
                route.subject(),
                ROUTES_TO,
                Knowledge.object(route),
                "Routes messages",
                "Routes",
                attribute(route, "bindingKey"),
                protocol(route),
                route));
      }
    }
    return result;
  }

  private static Link link(
      EntityId from,
      String relationship,
      EntityId to,
      String plain,
      String prefix,
      String detail,
      String technology,
      Fact fact) {
    return new Link(
        from,
        relationship,
        to,
        plain,
        prefix,
        detail,
        technology,
        null,
        attribute(fact, "profile"),
        List.of(fact));
  }

  /**
   * RabbitMQ queues drawn as the line from the exchange that routes to them instead of as boxes of
   * their own: those an exchange routes to and no unit sends to directly.
   */
  private Set<EntityId> foldedQueues() {
    Set<EntityId> sentTo = new HashSet<>();
    knowledge.withPredicate(PUBLISHES).stream()
        .filter(Knowledge::isEntity)
        .forEach(f -> sentTo.add(Knowledge.object(f)));
    Set<EntityId> folded = new HashSet<>();
    for (Fact route : knowledge.withPredicate(ROUTES_TO)) {
      if (Knowledge.isEntity(route) && !sentTo.contains(Knowledge.object(route))) {
        folded.add(Knowledge.object(route));
      }
    }
    return folded;
  }

  private List<Fact> routesTo(EntityId queue) {
    return knowledge.withPredicate(ROUTES_TO).stream()
        .filter(f -> Knowledge.isEntity(f) && Knowledge.object(f).equals(queue))
        .toList();
  }

  /** The endpoint a call reaches, or its method and path. */
  private static String endpoint(Fact fact) {
    String endpoint = attribute(fact, "endpoint");
    if (endpoint != null) {
      return endpoint;
    }
    String method = attribute(fact, "httpMethod");
    String path = attribute(fact, "path");
    if (path == null) {
      return null;
    }
    return method == null ? path : method + " " + path;
  }

  /**
   * A link's protocol; for a link the LLM resolver picked (ADR 0045) it adds that and its
   * confidence, so the line shows it was not matched statically.
   */
  static String protocol(Fact fact) {
    String protocol = attribute(fact, "protocol");
    String resolution = resolution(fact);
    if (resolution == null) {
      return protocol;
    }
    return protocol == null ? resolution : protocol + " · " + resolution;
  }

  /** "resolved by the LLM, confidence 0.28" for a link the LLM resolver picked, else null. */
  static String resolution(Fact fact) {
    // The resolver's links name the link left open they resolve (ADR 0045), whatever their kind.
    if (!fact.attributes().has("resolves")) {
      return null;
    }
    return "resolved by the LLM, confidence "
        + String.format(Locale.ROOT, "%.2f", fact.confidence());
  }

  private static String attribute(Fact fact, String name) {
    return RepoSections.attribute(fact, name, null);
  }

  static List<String> strings(Fact fact, String name) {
    JsonNode values = fact.attributes().get(name);
    List<String> result = new ArrayList<>();
    if (values != null && values.isArray()) {
      for (JsonNode value : values) {
        result.add(value.asString());
      }
    }
    return result;
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

    /** The intent phrases of the edge's links, used as its label only if every link has one. */
    final Set<String> phrases = new TreeSet<>();

    boolean unphrased;

    EdgeBuilder(EntityId from, String relationship, EntityId to) {
      this.from = from;
      this.relationship = relationship;
      this.to = to;
    }

    void add(Link link, Assembler.Phrased phrase) {
      add(link.plain, link.prefix, link.detail, link.technology, link.profile);
      if (link.via != null) {
        vias.add(link.via);
      }
      facts.addAll(link.facts);
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
        phrases.add(phrase.text());
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
     * The intent phrases of its links when every link has one, naming two and counting the rest;
     * else what the links record.
     */
    String label() {
      if (!unphrased && !phrases.isEmpty()) {
        List<String> named = new ArrayList<>(phrases);
        StringBuilder text = new StringBuilder(named.getFirst());
        for (String phrase : named.subList(1, Math.min(NAMED, named.size()))) {
          text.append("; ").append(lowerFirst(phrase));
        }
        if (named.size() > NAMED) {
          text.append(" and ").append(named.size() - NAMED).append(" more");
        }
        return text.toString();
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

    void add(Link link) {
      get(link.from, link.relationship, link.to).add(link, assembler.phrase(link.facts.getFirst()));
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
    for (Link link : links) {
      if (units.contains(link.from) && link.to.type().equals(EXTERNAL_SYSTEM)) {
        nodes.computeIfAbsent(link.to, e -> node(e, null, null, List.of()));
        EdgeBuilder edge = edges.get(system, link.relationship, link.to);
        edge.add(link.plain, link.prefix, link.detail, null, link.profile);
        edge.facts.addAll(link.facts);
        edge.phrased(assembler.phrase(link.facts.getFirst()));
      }
    }
    List<Block> blocks = new ArrayList<>();
    blocks.add(counts(section));
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
                    "The code names no people or user roles, so who uses "
                        + assembler.name(system)
                        + " is not known.")),
            List.of()));
    return blocks;
  }

  /**
   * How many deployable units the scope has, and how many data stores, queues and external systems
   * they use, each citing the facts it counts.
   */
  private Metrics counts(String section) {
    List<Fact> unitFacts = new ArrayList<>();
    units.forEach(u -> unitFacts.addAll(containerFacts(u)));
    Map<String, SortedMap<EntityId, List<Fact>>> used = new TreeMap<>();
    for (String kind : List.of(DATABASE, QUEUE, EXTERNAL_SYSTEM)) {
      used.put(kind, new TreeMap<>(Knowledge.BY_ID));
    }
    for (Link link : links) {
      for (EntityId end : List.of(link.from, link.to)) {
        EntityId other = end.equals(link.from) ? link.to : link.from;
        String kind =
            switch (end.type()) {
              case DATABASE -> DATABASE;
              case TOPIC, QUEUE, EXCHANGE -> QUEUE;
              case EXTERNAL_SYSTEM -> EXTERNAL_SYSTEM;
              default -> null;
            };
        if (kind != null && units.contains(other)) {
          used.get(kind).computeIfAbsent(end, e -> new ArrayList<>()).addAll(link.facts);
        }
      }
    }
    List<Metrics.Item> items = new ArrayList<>();
    items.add(metric("Deployable units", units.size(), unitFacts));
    items.add(metric("Databases", used.get(DATABASE)));
    items.add(metric("Topics, queues and exchanges", used.get(QUEUE)));
    items.add(metric("External systems", used.get(EXTERNAL_SYSTEM)));
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
   * The container diagram of the scope's units and their direct neighbours, inside the system; one
   * per unit when that would show more than {@link #MAX_ELEMENTS} elements. Then the links Lantern
   * could not resolve and the scope's decision records.
   *
   * @param section the containers section's ID
   * @param blocks receives the blocks of the section itself
   * @param subsections receives one subsection per unit when the diagram is split
   */
  void containers(String section, List<Block> blocks, List<Section> subsections) {
    if (units.isEmpty()) {
      // A note: what the code does not show cites no fact (Quality bar, ADR 0046).
      blocks.add(
          new Callout(
              assembler.id(section, "none"),
              Callout.Variant.NOTE,
              null,
              null,
              List.of(TextRun.of("No deployable unit was found in the code.")),
              List.of()));
    } else {
      Diagram whole = containerDiagram(assembler.id(section, "diagram"), units, "Containers");
      if (whole.nodes().size() - 1 <= MAX_ELEMENTS || units.size() == 1) {
        blocks.add(whole);
      } else {
        for (EntityId unit : units) {
          String id = assembler.id(section, unit.toString());
          Diagram around =
              containerDiagram(
                  assembler.id(id, "diagram"),
                  only(unit),
                  "Containers around " + assembler.name(unit));
          subsections.add(
              new Section(
                  id,
                  assembler.name(unit),
                  SectionKind.CONTAINERS.levels(),
                  unit,
                  List.of(),
                  List.of(around),
                  List.of()));
        }
      }
    }
    alternativesNote(assembler.id(section, "profiles"), units, false).ifPresent(blocks::add);
    unresolved(section).ifPresent(blocks::add);
    decisions(section).ifPresent(blocks::add);
  }

  private Diagram containerDiagram(String id, SortedSet<EntityId> shown, String title) {
    Map<EntityId, Diagram.Node> nodes = new TreeMap<>(Knowledge.BY_ID);
    nodes.put(system, node(system, null, null, List.of()));
    shown.forEach(unit -> container(nodes, unit));
    Edges edges = new Edges();
    for (Link link : links) {
      if (shown.contains(link.from) || shown.contains(link.to)) {
        container(nodes, link.from);
        container(nodes, link.to);
        edges.add(link);
      }
    }
    // Routes between exchanges and queues both drawn already.
    for (Link link : links) {
      if (link.relationship.equals(ROUTES_TO)
          && nodes.containsKey(link.from)
          && nodes.containsKey(link.to)) {
        edges.add(link);
      }
    }
    return new Diagram(
        id,
        Diagram.Kind.C4_CONTAINER,
        title + " of " + assembler.name(system),
        List.copyOf(nodes.values()),
        edges.build(assembler, true),
        List.of());
  }

  /** Adds a container, store or external system to a container diagram's nodes. */
  private void container(Map<EntityId, Diagram.Node> nodes, EntityId entity) {
    if (nodes.containsKey(entity)) {
      return;
    }
    // Inside the system's boundary where the diagram draws it.
    EntityId parent =
        entity.type().equals(EXTERNAL_SYSTEM) || !nodes.containsKey(system) ? null : system;
    nodes.put(entity, node(entity, technology(entity), parent, containerFacts(entity)));
  }

  /** The calls, sends and datasources of the scope's units that Lantern could not link. */
  private Optional<Callout> unresolved(String section) {
    List<Inline> content = new ArrayList<>();
    List<Fact> facts = new ArrayList<>();
    for (EntityId unit : units) {
      for (Fact fact : knowledge.attributes(unit)) {
        if (!fact.predicate().equals(UNRESOLVED_LINK) || knowledge.resolved(fact)) {
          continue;
        }
        facts.add(fact);
        String reason = attribute(fact, "reason");
        List<String> keys = strings(fact, "unresolved");
        StringBuilder text =
            new StringBuilder(content.isEmpty() ? "" : " ")
                .append(assembler.name(unit))
                .append(": ")
                .append(fact.object().value());
        if (reason != null) {
          text.append(" (").append(reason.replace('-', ' '));
          if (!keys.isEmpty()) {
            text.append(" ").append(String.join(", ", keys));
          }
          text.append(")");
        }
        text.append('.');
        content.add(TextRun.of(text.toString()));
      }
    }
    if (content.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new Callout(
            assembler.id(section, "unresolved"),
            Callout.Variant.WARNING,
            null,
            "Not linked",
            content,
            assembler.facts(facts)));
  }

  /**
   * The decision records of the scope's repositories, oldest number first, each with its rationale
   * quoted from its context section or marked as not recorded; a note when there are none, since
   * then why the system is built as it is is not known (Quality bar, Architecture Explorer).
   */
  private Optional<Block> decisions(String section) {
    SortedSet<EntityId> decisions = new TreeSet<>(Knowledge.BY_ID);
    for (Fact fact : knowledge.withPredicate("title")) {
      EntityId subject = fact.subject();
      String key = subject.key();
      if (subject.type().equals(DECISION)
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
      Optional<String> number = knowledge.attribute(decision, "decision-number");
      rows.add(
          new Table.Row(
              decision,
              List.of(
                  cell(number.map(n -> "ADR " + n).orElse(null)),
                  cell(assembler.name(decision)),
                  cell(knowledge.attribute(decision, "decision-status").orElse(null)),
                  cell(knowledge.attribute(decision, "decision-outcome").orElse(null)),
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
        .attribute(decision, "decision-context")
        .map(String::strip)
        .filter(c -> !c.isEmpty())
        .map(c -> c.split("\\n\\s*\\n", 2)[0].strip().replaceAll("\\s*\\n\\s*", " "))
        .orElse(NOT_RECORDED);
  }

  private int number(EntityId decision) {
    return knowledge
        .attribute(decision, "decision-number")
        .map(n -> n.matches("[0-9]{1,9}") ? Integer.parseInt(n) : Integer.MAX_VALUE)
        .orElse(Integer.MAX_VALUE);
  }

  private static List<Inline> cell(String text) {
    return text == null || text.isBlank() ? List.of() : List.of(TextRun.of(text));
  }

  // Components.

  /**
   * A component: a group of types behind one role, named after its main type, the entry point or
   * bean it is built around.
   */
  record Component(EntityId main, SortedSet<EntityId> types, String technology) {}

  /**
   * One subsection per unit in scope with more than one component, holding its component diagram:
   * one component per controller, message listener or scheduled job with the beans reachable only
   * from it, a bean reachable from several entry points (or from none, when it reaches outside the
   * unit) as a component of its own, and the containers they call or are called by.
   */
  List<Section> components(String section) {
    List<Section> result = new ArrayList<>();
    for (EntityId unit : units) {
      List<Component> components = components(unit);
      if (components.size() < 2) {
        continue;
      }
      String id = assembler.id(section, unit.toString());
      Diagram diagram = componentDiagram(assembler.id(id, "diagram"), unit, components);
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
              List.of()));
    }
    return result;
  }

  /** The unit's components by role, entry points first, each in ID order. */
  List<Component> components(EntityId unit) {
    SortedSet<EntityId> code = new TreeSet<>(Knowledge.BY_ID);
    for (EntityId module : codeModules(knowledge, unit)) {
      code.addAll(knowledge.typesWithin(module));
      for (EntityId pkg : knowledge.parts(module, BuiltInTypes.PACKAGE)) {
        code.addAll(knowledge.typesWithin(pkg));
      }
    }
    SortedSet<EntityId> entries = new TreeSet<>(Knowledge.BY_ID);
    SortedSet<EntityId> beans = new TreeSet<>(Knowledge.BY_ID);
    for (EntityId type : code) {
      if (knowledge.attribute(type, MAIN_CLASS).isPresent()) {
        continue;
      }
      if (!entryKinds(type).isEmpty()) {
        entries.add(type);
      } else if (isBean(type)) {
        beans.add(type);
      }
    }
    Map<EntityId, Set<EntityId>> uses = typeGraph(code);
    Map<EntityId, SortedSet<EntityId>> reachedBy = new TreeMap<>(Knowledge.BY_ID);
    for (EntityId entry : entries) {
      Set<EntityId> seen = new HashSet<>(Set.of(entry));
      Deque<EntityId> todo = new ArrayDeque<>(List.of(entry));
      while (!todo.isEmpty()) {
        for (EntityId next : uses.getOrDefault(todo.removeFirst(), Set.of())) {
          if (!entries.contains(next) && seen.add(next)) {
            todo.add(next);
            if (beans.contains(next)) {
              reachedBy.computeIfAbsent(next, b -> new TreeSet<>(Knowledge.BY_ID)).add(entry);
            }
          }
        }
      }
    }
    List<Component> components = new ArrayList<>();
    for (EntityId entry : entries) {
      SortedSet<EntityId> types = new TreeSet<>(Knowledge.BY_ID);
      types.add(entry);
      reachedBy.forEach(
          (bean, from) -> {
            if (from.size() == 1 && from.first().equals(entry)) {
              types.add(bean);
            }
          });
      components.add(new Component(entry, types, String.join(" · ", entryKinds(entry))));
    }
    for (EntityId bean : beans) {
      SortedSet<EntityId> from = reachedBy.get(bean);
      boolean own = from == null ? reachesOutside(bean) : from.size() > 1;
      if (own) {
        components.add(new Component(bean, only(bean), beanKind(bean)));
      }
    }
    return components;
  }

  /** The roles that make a type an entry point: web controller, listener, scheduled job. */
  private List<String> entryKinds(EntityId type) {
    Set<String> kinds = new LinkedHashSet<>();
    knowledge.attributes(type).stream()
        .filter(f -> f.predicate().equals(STEREOTYPE) && f.object().value().equals("controller"))
        .forEach(
            f ->
                kinds.add(
                    RepoSections.attribute(f, "rest", "false").equals("true")
                        ? "Spring MVC REST controller"
                        : "Spring MVC controller"));
    for (EntityId method : knowledge.parts(type, METHOD)) {
      for (Fact fact : knowledge.attributes(method)) {
        switch (fact.predicate()) {
          case ENDPOINT -> {
            if (kinds.isEmpty()) {
              kinds.add("HTTP endpoints");
            }
          }
          case MESSAGE_LISTENER ->
              kinds.add(
                  brokerName(RepoSections.attribute(fact, "broker", "message")) + " listener");
          case SCHEDULED, BACKGROUND_JOB -> kinds.add(ExplorerSections.jobKind(fact));
          default -> {}
        }
      }
    }
    return List.copyOf(kinds);
  }

  private boolean isBean(EntityId type) {
    for (Fact fact : knowledge.attributes(type)) {
      if ((fact.predicate().equals(STEREOTYPE) && BEAN_STEREOTYPES.contains(fact.object().value()))
          || fact.predicate().equals(HTTP_CLIENT)) {
        return true;
      }
    }
    return !knowledge.relations(type, REPOSITORY_FOR).isEmpty();
  }

  /** What kind of bean a component built around one bean is. */
  private String beanKind(EntityId type) {
    Optional<Fact> client =
        knowledge.attributes(type).stream()
            .filter(f -> f.predicate().equals(HTTP_CLIENT))
            .findFirst();
    if (client.isPresent()) {
      String kind = client.get().object().value();
      return kind.equals("feign") ? "Feign client" : "HTTP client";
    }
    for (Fact fact : knowledge.relations(type, REPOSITORY_FOR)) {
      String base = RepoSections.attribute(fact, "base", "");
      return base.contains(".jpa.") ? "Spring Data JPA repository" : "Spring Data repository";
    }
    return knowledge
        .attribute(type, STEREOTYPE)
        .map(
            s ->
                switch (s) {
                  case "service" -> "Spring service";
                  case "repository" -> "Spring repository";
                  default -> "Spring component";
                })
        .orElse(null);
  }

  /** Whether a type's code reaches outside the unit: HTTP calls, sends or SQL, or data access. */
  private boolean reachesOutside(EntityId type) {
    if (!knowledge.relations(type, REPOSITORY_FOR).isEmpty()
        || knowledge.attribute(type, HTTP_CLIENT).isPresent()) {
      return true;
    }
    for (EntityId member : withMethods(type)) {
      for (Fact fact : knowledge.about(member)) {
        switch (fact.predicate()) {
          case HTTP_CALL, MESSAGE_SEND, READS_TABLE, WRITES_TABLE -> {
            return true;
          }
          default -> {}
        }
      }
    }
    return false;
  }

  private List<EntityId> withMethods(EntityId type) {
    List<EntityId> members = new ArrayList<>(List.of(type));
    members.addAll(knowledge.parts(type, METHOD));
    return members;
  }

  /**
   * Which types of the unit's code each uses: the types whose methods it calls and the clients
   * injected into it, and for an interface the types of the code implementing it.
   */
  private Map<EntityId, Set<EntityId>> typeGraph(Set<EntityId> code) {
    Map<EntityId, Set<EntityId>> uses = new TreeMap<>(Knowledge.BY_ID);
    for (EntityId type : code) {
      Set<EntityId> targets = new TreeSet<>(Knowledge.BY_ID);
      for (EntityId member : withMethods(type)) {
        for (Fact fact : knowledge.relations(member, BuiltInTypes.CALLS)) {
          targets.add(PackageDependencies.ownerType(knowledge, Knowledge.object(fact)));
        }
      }
      knowledge.relations(type, INJECTS).forEach(f -> targets.add(Knowledge.object(f)));
      targets.remove(type);
      targets.retainAll(code);
      uses.computeIfAbsent(type, t -> new TreeSet<>(Knowledge.BY_ID)).addAll(targets);
      for (Fact fact : knowledge.relations(type, BuiltInTypes.IMPLEMENTS)) {
        EntityId contract = Knowledge.object(fact);
        if (code.contains(contract)) {
          uses.computeIfAbsent(contract, c -> new TreeSet<>(Knowledge.BY_ID)).add(type);
        }
      }
    }
    return uses;
  }

  private Diagram componentDiagram(String id, EntityId unit, List<Component> components) {
    Map<EntityId, Diagram.Node> nodes = new TreeMap<>(Knowledge.BY_ID);
    nodes.put(unit, node(unit, technology(unit), null, containerFacts(unit)));
    Map<EntityId, EntityId> componentOf = new TreeMap<>(Knowledge.BY_ID);
    for (Component component : components) {
      nodes.put(
          component.main,
          node(component.main, component.technology, unit, componentFacts(component)));
      component.types.forEach(t -> componentOf.put(t, component.main));
    }
    Edges edges = new Edges();
    // Calls between components, labelled with the methods called.
    for (Component component : components) {
      for (EntityId type : component.types) {
        for (EntityId member : withMethods(type)) {
          for (Fact fact : knowledge.relations(member, BuiltInTypes.CALLS)) {
            EntityId called = Knowledge.object(fact);
            for (EntityId target : implementers(PackageDependencies.ownerType(knowledge, called))) {
              EntityId to = componentOf.get(target);
              if (to != null && !to.equals(component.main)) {
                EdgeBuilder edge = edges.get(component.main, BuiltInTypes.CALLS, to);
                edge.add("Calls", "Calls", methodName(called), null, null);
                edge.facts.add(fact);
                edge.phrased(null);
              }
            }
          }
        }
      }
    }
    // Lines to and from other containers, at the code on this unit's end.
    for (Link link : links) {
      if (link.from.equals(unit)) {
        for (Fact fact : link.facts) {
          EntityId from = componentAt(componentOf, attribute(fact, "source"));
          if (from != null && fact.subject().equals(unit)) {
            container(nodes, link.to);
            edges.add(link.from(from));
          }
        }
      } else if (link.to.equals(unit)) {
        for (Fact fact : link.facts) {
          String code =
              fact.subject().equals(unit) ? attribute(fact, "source") : attribute(fact, "target");
          EntityId to = componentAt(componentOf, code);
          if (to != null && link.relationship.equals(CONSUMES) == fact.subject().equals(unit)) {
            container(nodes, link.from);
            edges.add(link.to(to));
          }
        }
      }
    }
    // Data access, to the databases the unit's datasources point to.
    for (Component component : components) {
      String access = dataAccess(component);
      if (access == null) {
        continue;
      }
      for (Link link : links) {
        if (link.from.equals(unit) && link.relationship.equals(USES_DATABASE)) {
          container(nodes, link.to);
          EdgeBuilder edge = edges.get(component.main, USES_DATABASE, link.to);
          edge.add(access, null, null, link.technology, link.profile);
          edge.facts.addAll(link.facts);
          edge.phrased(null);
        }
      }
    }
    return new Diagram(
        id,
        Diagram.Kind.C4_COMPONENT,
        "Components of " + assembler.name(unit),
        List.copyOf(nodes.values()),
        edges.build(assembler, true),
        List.of());
  }

  /** The type and the types of the unit's code implementing it, if it is an interface. */
  private Set<EntityId> implementers(EntityId type) {
    Set<EntityId> result = new LinkedHashSet<>(List.of(type));
    for (Fact fact : knowledge.withPredicate(BuiltInTypes.IMPLEMENTS)) {
      if (Knowledge.isEntity(fact) && Knowledge.object(fact).equals(type)) {
        result.add(fact.subject());
      }
    }
    return result;
  }

  /** The component holding the code a link's end names, if any. */
  private EntityId componentAt(Map<EntityId, EntityId> componentOf, String code) {
    if (code == null) {
      return null;
    }
    EntityId entity;
    try {
      entity = EntityId.parse(code);
    } catch (IllegalArgumentException e) {
      return null;
    }
    return componentOf.get(PackageDependencies.ownerType(knowledge, entity));
  }

  /** How a component reaches its unit's databases, or null if it does not. */
  private String dataAccess(Component component) {
    boolean reads = false;
    boolean writes = false;
    for (EntityId type : component.types) {
      if (!knowledge.relations(type, REPOSITORY_FOR).isEmpty()) {
        return "Reads and writes";
      }
      for (EntityId member : withMethods(type)) {
        reads |= !knowledge.relations(member, READS_TABLE).isEmpty();
        writes |= !knowledge.relations(member, WRITES_TABLE).isEmpty();
      }
    }
    if (reads && writes) {
      return "Reads and writes";
    }
    return reads ? "Reads" : writes ? "Writes" : null;
  }

  /** The facts that make the component's main type what it is: its stereotype and entry points. */
  List<Fact> componentFacts(Component component) {
    List<Fact> facts = new ArrayList<>();
    for (EntityId member : withMethods(component.main)) {
      for (Fact fact : knowledge.attributes(member)) {
        switch (fact.predicate()) {
          case STEREOTYPE, HTTP_CLIENT, ENDPOINT, MESSAGE_LISTENER, SCHEDULED, BACKGROUND_JOB ->
              facts.add(fact);
          default -> {}
        }
      }
    }
    facts.addAll(knowledge.relations(component.main, REPOSITORY_FOR));
    return facts;
  }

  private static SortedSet<EntityId> only(EntityId entity) {
    SortedSet<EntityId> set = new TreeSet<>(Knowledge.BY_ID);
    set.add(entity);
    return set;
  }

  /** A method's name without its parameters. */
  private static String methodName(EntityId method) {
    String key = method.key();
    String name = key.substring(key.indexOf('#') + 1);
    int paren = name.indexOf('(');
    return paren < 0 ? name : name.substring(0, paren);
  }

  private Diagram.Node node(EntityId entity, String technology, EntityId parent, List<Fact> facts) {
    assembler.entity(entity);
    return new Diagram.Node(
        entity, assembler.name(entity), technology, parent, null, null, assembler.facts(facts));
  }
}

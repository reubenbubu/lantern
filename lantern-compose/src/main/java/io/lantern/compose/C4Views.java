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
import io.lantern.derive.Knowledge;
import io.lantern.derive.Screens;
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
import java.util.Objects;
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
 * under. People are drawn only when the code shows them (ADR 0074): the linker infers them from
 * roles, memberships, invitations and sign-in, and they are marked inferred; otherwise a note says
 * who uses the system is unknown.
 */
final class C4Views {

  static final String DEPLOYABLE_UNIT = "deployable-unit";
  static final String APPLICATION_NAME = "application-name";
  static final String TECHNOLOGY = "technology";
  static final String BUNDLES = "BUNDLES";
  static final String CALLS_HTTP = "CALLS_HTTP";
  static final String SENDS_MAIL = "SENDS_MAIL";
  static final String USES_IDENTITY_PROVIDER = "USES_IDENTITY_PROVIDER";
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
  static final String PERSON = "person";
  static final String PERSON_BASIS = "person-basis";
  static final String USES = "USES";
  static final String FRONTEND = "frontend";
  static final String FRONTEND_APP = "frontend-app";
  static final String ROUTE = "route";

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
  private static final String ENDPOINT = "endpoint";
  private static final String MESSAGE_LISTENER = "message-listener";
  private static final String SCHEDULED = "scheduled";
  private static final String BACKGROUND_JOB = "background-job";
  private static final String GATEWAY_ROUTE = "gateway-route";
  private static final String GATEWAY_FILTER = "gateway-filter";

  /** The name of a component of the routes a gateway's config defines. */
  static final String CONFIG_ROUTES = "Gateway routes";

  private static final String HTTP_CLIENT = "http-client";
  private static final String REPOSITORY_FOR = "REPOSITORY_FOR";
  private static final String ASPECT = "aspect";
  private static final String ADVISES = "ADVISES";

  private final Knowledge knowledge;
  private final Assembler assembler;
  private final EntityId system;
  private final SortedSet<EntityId> units;

  /** The frontends in scope: those of the scope's repositories (ADR 0083). */
  private final SortedSet<EntityId> frontends;

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
    this.frontends = new TreeSet<>(Knowledge.BY_ID);
    for (Fact app : knowledge.withPredicate(FRONTEND_APP)) {
      if (repos.isEmpty() || repos.contains(app.evidence().repo())) {
        frontends.add(app.subject());
      }
    }
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
      if (!runsCode(link.from) && !runsCode(link.to)) {
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

  /** Whether an element is a container that runs code: a deployable unit or a frontend. */
  private static boolean runsCode(EntityId element) {
    return element.type().equals(BuiltInTypes.SERVICE) || element.type().equals(FRONTEND);
  }

  /** Every frontend of the knowledge (ADR 0083). */
  static SortedSet<EntityId> frontends(Knowledge knowledge) {
    SortedSet<EntityId> result = new TreeSet<>(Knowledge.BY_ID);
    knowledge.withPredicate(FRONTEND_APP).forEach(f -> result.add(f.subject()));
    return result;
  }

  /** The frontends in scope. */
  SortedSet<EntityId> frontends() {
    return frontends;
  }

  /** A frontend's {@code frontend-app} fact, if it has one. */
  Optional<Fact> frontendApp(EntityId frontend) {
    return frontendApp(knowledge, frontend);
  }

  static Optional<Fact> frontendApp(Knowledge knowledge, EntityId frontend) {
    return knowledge.attributes(frontend).stream()
        .filter(f -> f.predicate().equals(FRONTEND_APP))
        .findFirst();
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
    return Components.codeModules(knowledge, unit);
  }

  /** The name a unit runs under in Spring's default profile, if it has one. */
  static Optional<String> applicationName(Knowledge knowledge, EntityId unit) {
    return knowledge.attributes(unit).stream()
        .filter(f -> f.predicate().equals(APPLICATION_NAME) && Knowledge.view(f) == null)
        .map(f -> f.object().value())
        .findFirst();
  }

  /** The facts that make an entity a container of the diagrams, and its technology. */
  List<Fact> containerFacts(EntityId entity) {
    return knowledge.attributes(entity).stream()
        .filter(
            f ->
                f.predicate().equals(DEPLOYABLE_UNIT)
                    || f.predicate().equals(FRONTEND_APP)
                    || f.predicate().equals(TECHNOLOGY)
                    || (f.predicate().equals(APPLICATION_NAME) && Knowledge.view(f) == null))
        .toList();
  }

  /** The technology line of a container: what it is built with, or what kind of store it is. */
  String technology(EntityId entity) {
    Optional<String> technology = knowledge.attribute(entity, TECHNOLOGY);
    String broker = entity.key().substring(0, Math.max(entity.key().indexOf(':'), 0));
    return switch (entity.type()) {
      case BuiltInTypes.SERVICE, DATABASE -> technology.orElse(null);
      case FRONTEND -> frontendApp(entity).map(f -> attribute(f, TECHNOLOGY)).orElse(null);
      case TOPIC -> brokerName(broker) + " topic";
      case QUEUE -> brokerName(broker) + " queue";
      case EXCHANGE -> {
        String type =
            knowledge.relations(entity, ROUTES_TO).stream()
                .map(f -> attribute(f, "exchangeType"))
                .filter(Objects::nonNull)
                .sorted()
                .findFirst()
                .orElse(null);
        yield brokerName(broker) + (type == null ? "" : " " + type) + " exchange";
      }
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

  /**
   * The profile-only links that stand in for a default link of the same unit (ADR 0090): left out
   * of the diagrams and counts, which show the default, and listed in a note instead.
   */
  private Map<Fact, Fact> alternatives() {
    return Screens.alternatives(knowledge);
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
            case USES_IDENTITY_PROVIDER -> "uses as its identity provider";
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

  /** The links of every unit, with RabbitMQ queues an exchange routes to folded into the line. */
  private List<Link> containerLinks() {
    Set<EntityId> folded = foldedQueues();
    List<Link> result = new ArrayList<>();
    for (EntityId unit : units(knowledge)) {
      for (Fact fact : knowledge.relations(unit, CALLS_HTTP)) {
        List<String> paths = strings(fact, "paths");
        boolean route = attribute(fact, "route") != null;
        result.add(
            link(
                unit,
                CALLS_HTTP,
                Knowledge.object(fact),
                route ? "Routes requests" : "Calls",
                route ? "Routes" : "Calls",
                route ? (paths.isEmpty() ? null : paths.getFirst()) : endpoint(fact),
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
      for (Fact fact : knowledge.relations(unit, USES_IDENTITY_PROVIDER)) {
        String[] words = identityWords(attribute(fact, "purpose"));
        result.add(
            link(
                unit,
                USES_IDENTITY_PROVIDER,
                Knowledge.object(fact),
                words[0],
                words[1],
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
    for (EntityId frontend : frontends(knowledge)) {
      for (Fact fact : knowledge.relations(frontend, CALLS_HTTP)) {
        String proxy = attribute(fact, "proxy");
        boolean socket = "WebSocket".equals(attribute(fact, "protocol"));
        boolean passed =
            !socket && attribute(fact, "httpMethod") == null && attribute(fact, "path") == null;
        result.add(
            link(
                frontend,
                CALLS_HTTP,
                Knowledge.object(fact),
                passed ? "Passes requests on to" : socket ? "Opens a socket to" : "Calls",
                passed ? "Passes on" : socket ? "Opens" : "Calls",
                passed ? proxy : socket ? attribute(fact, "path") : endpoint(fact),
                protocol(fact),
                fact));
      }
    }
    result.addAll(peopleLinks());
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

  /**
   * How a line to an identity provider reads, by what the unit does with it (ADR 0085): its label
   * and the words two such lines are joined by.
   */
  private static String[] identityWords(String purpose) {
    return switch (purpose == null ? "" : purpose) {
      case "validates-tokens" ->
          new String[] {"Validates tokens with the keys of", "Validates tokens"};
      case "signs-in-users" -> new String[] {"Signs users in through", "Signs users in"};
      case "gets-tokens" -> new String[] {"Gets tokens from", "Gets tokens"};
      case "manages-users" -> new String[] {"Manages users through", "Manages users"};
      default -> new String[] {"Uses as its identity provider", "Uses"};
    };
  }

  /**
   * The lines from people to the frontends that have screens for them (ADR 0074, ADR 0083): from
   * the frontends their {@code person-basis} names and the routes it cites, each to the frontend
   * whose folder holds the route's file.
   */
  private List<Link> peopleLinks() {
    List<Link> result = new ArrayList<>();
    for (Map.Entry<EntityId, Fact> person : people().entrySet()) {
      Fact basis = person.getValue();
      screens(basis)
          .forEach(
              (frontend, routes) -> {
                for (Fact route : routes) {
                  List<Fact> facts = new ArrayList<>(List.of(basis, route));
                  frontendApp(frontend).ifPresent(facts::add);
                  result.add(
                      new Link(
                          person.getKey(),
                          USES,
                          frontend,
                          "Uses the screens of",
                          "Uses the screens",
                          attribute(route, "path"),
                          "Web browser",
                          null,
                          null,
                          facts));
                }
              });
    }
    return result;
  }

  /** The screens a person has, by frontend ({@link Screens#screens}). */
  private Map<EntityId, List<Fact>> screens(Fact basis) {
    return Screens.screens(knowledge, basis);
  }

  /**
   * The people of the scope who have screens on a frontend, each with its {@code person-basis} fact
   * and the routes of those screens, by person ID.
   */
  SortedMap<EntityId, List<Fact>> screensOn(EntityId frontend) {
    return Screens.screensOn(knowledge, frontend, repos);
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
        Knowledge.view(fact),
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

    /**
     * The intent phrases of the edge's links with how many links say each, used as its label only
     * if every link has one.
     */
    final SortedMap<String, Integer> phrases = new TreeMap<>();

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
      if ((units.contains(link.from) || frontends.contains(link.from))
          && link.to.type().equals(EXTERNAL_SYSTEM)) {
        nodes.computeIfAbsent(link.to, e -> node(e, null, null, List.of()));
        EdgeBuilder edge = edges.get(system, link.relationship, link.to);
        edge.add(link.plain, link.prefix, link.detail, null, link.profile);
        edge.facts.addAll(link.facts);
        edge.phrased(assembler.phrase(link.facts.getFirst()));
      }
    }
    SortedMap<EntityId, Fact> people = people();
    for (Map.Entry<EntityId, Fact> person : people.entrySet()) {
      List<Fact> cited = cited(person.getValue());
      nodes.put(person.getKey(), node(person.getKey(), null, null, cited));
      EdgeBuilder edge = edges.get(person.getKey(), USES, system);
      List<String> frontends = strings(person.getValue(), "frontends");
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

  /**
   * The people the linker inferred from the code (ADR 0074) that the scope's repositories show,
   * each with its {@code person-basis} fact.
   */
  SortedMap<EntityId, Fact> people() {
    return Screens.people(knowledge, repos);
  }

  /** The facts a derived fact was made from, named by its {@code facts} attribute. */
  private List<Fact> basis(Fact derived) {
    List<Fact> facts = new ArrayList<>();
    for (String id : strings(derived, "facts")) {
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
    for (String id : strings(basis, "cites")) {
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
        if (kind != null && (units.contains(other) || frontends.contains(other))) {
          used.get(kind).computeIfAbsent(end, e -> new ArrayList<>()).addAll(link.facts);
        }
      }
    }
    List<Metrics.Item> items = new ArrayList<>();
    items.add(metric("Deployable units", units.size(), unitFacts));
    if (!frontends.isEmpty()) {
      List<Fact> apps = new ArrayList<>();
      frontends.forEach(f -> frontendApp(f).ifPresent(apps::add));
      items.add(metric("Frontends", frontends.size(), apps));
    }
    items.add(metric("Databases", used.get(DATABASE)));
    items.add(metric("Topics, queues and exchanges", used.get(QUEUE)));
    items.add(metric("External systems", used.get(EXTERNAL_SYSTEM)));
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
      new Concern("Requests", Set.of(CALLS_HTTP, USES, SENDS_MAIL, USES_IDENTITY_PROVIDER));
  private static final Concern DATA = new Concern("Data", Set.of(USES_DATABASE, ACCESSES_DATA_OF));
  private static final Concern MESSAGING =
      new Concern("Messaging", Set.of(PUBLISHES, CONSUMES, ROUTES_TO));

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
    for (Link link : links) {
      if (concern.relationships.contains(link.relationship)) {
        lined.add(link.from);
        lined.add(link.to);
      }
    }
    for (EntityId unit : shown) {
      boolean alone =
          everyContainer && links.stream().noneMatch(l -> l.from.equals(unit) || l.to.equals(unit));
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
      List<String> ports = strings(fact, "ports");
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
    Map<EntityId, Diagram.Node> nodes = new TreeMap<>(Knowledge.BY_ID);
    nodes.put(system, node(system, null, null, List.of()));
    if (everyContainer) {
      shown.forEach(unit -> container(nodes, unit));
    }
    Edges edges = new Edges();
    for (Link link : links) {
      if ((concern == null || concern.relationships.contains(link.relationship))
          && (shown.contains(link.from) || shown.contains(link.to))) {
        container(nodes, link.from);
        container(nodes, link.to);
        edges.add(link);
      }
    }
    // Routes between exchanges and queues both drawn already.
    for (Link link : links) {
      if (link.relationship.equals(ROUTES_TO)
          && (concern == null || concern.relationships.contains(ROUTES_TO))
          && nodes.containsKey(link.from)
          && nodes.containsKey(link.to)) {
        edges.add(link);
      }
    }
    String scope = concern == null ? "" : ": " + concern.title.toLowerCase(Locale.ROOT);
    return new Diagram(
        id,
        Diagram.Kind.C4_CONTAINER,
        title + " of " + assembler.name(system) + scope,
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
      for (Link link : links) {
        if (link.relationship.equals(CALLS_HTTP) && runsCode(link.to)) {
          called.add(link.to);
          if ("Routes".equals(link.prefix)) {
            gateways.add(link.from);
          }
        }
      }
      List<EntityId> roots = new ArrayList<>(frontends(knowledge));
      roots.addAll(gateways);
      if (roots.isEmpty()) {
        units(knowledge).stream().filter(u -> !called.contains(u)).forEach(roots::add);
      }
      for (EntityId root : roots) {
        if (depth.putIfAbsent(root, 0) == null) {
          queue.add(root);
        }
      }
      while (!queue.isEmpty()) {
        EntityId from = queue.poll();
        for (Link link : links) {
          if (link.relationship.equals(CALLS_HTTP)
              && link.from.equals(from)
              && runsCode(link.to)
              && depth.putIfAbsent(link.to, depth.get(from) + 1) == null) {
            queue.add(link.to);
          }
        }
      }
      entryFirst =
          Comparator.comparing((EntityId e) -> !e.type().equals(FRONTEND))
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
        entity.type().equals(EXTERNAL_SYSTEM)
                || entity.type().equals(PERSON)
                || !nodes.containsKey(system)
            ? null
            : system;
    nodes.put(entity, node(entity, technology(entity), parent, containerFacts(entity)));
  }

  /** The calls, sends and datasources of the scope's units that Lantern could not link. */
  Optional<Callout> unresolved(String section) {
    List<Inline> content = new ArrayList<>();
    List<Fact> facts = new ArrayList<>();
    for (EntityId unit : units) {
      // By what the code calls or sends to, then the code that does it, the reason, the keys left
      // unresolved, the call site and the profile, so the box reads the same on every run whatever
      // the facts' IDs (#605).
      List<Fact> open =
          knowledge.attributes(unit).stream()
              .filter(f -> f.predicate().equals(UNRESOLVED_LINK) && !knowledge.resolved(f))
              .sorted(
                  Comparator.comparing((Fact f) -> f.object().value())
                      .thenComparing(f -> Objects.toString(attribute(f, "source"), ""))
                      .thenComparing(f -> Objects.toString(attribute(f, "reason"), ""))
                      .thenComparing(f -> String.join(",", strings(f, "unresolved")))
                      .thenComparing(f -> Objects.toString(attribute(f, "site"), ""))
                      .thenComparing(f -> Objects.toString(attribute(f, "view"), ""))
                      .thenComparing(Fact::id))
              .toList();
      for (Fact fact : open) {
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
  Optional<Block> decisions(String section) {
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

  /** A cell of the decision record's Markdown text, its code spans as code. */
  private static List<Inline> cell(String text) {
    return text == null || text.isBlank() ? List.of() : Assembler.withCodeSpans(text);
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
    // From the entry inward, as the container pages.
    SortedSet<EntityId> ordered = new TreeSet<>(entryFirst());
    ordered.addAll(units);
    for (EntityId unit : ordered) {
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

  /**
   * The unit's stored components (#523): the routes its gateway config defines, then its entry
   * points and aspects, then the beans they share, each in ID order.
   */
  List<Component> components(EntityId unit) {
    List<Component> routes = new ArrayList<>();
    List<Component> entries = new ArrayList<>();
    List<Component> shared = new ArrayList<>();
    for (Fact part : knowledge.withPredicate(Components.PART_OF)) {
      if (!part.subject().type().equals(BuiltInTypes.COMPONENT)
          || !Knowledge.isEntity(part)
          || !Knowledge.object(part).equals(unit)) {
        continue;
      }
      EntityId component = part.subject();
      EntityId main =
          knowledge.relations(component, Components.ANCHORED_ON).stream()
              .map(Knowledge::object)
              .findFirst()
              .orElse(null);
      if (main == null) {
        continue;
      }
      SortedSet<EntityId> types = new TreeSet<>(Knowledge.BY_ID);
      knowledge
          .relations(component, Components.HAS_MEMBER)
          .forEach(f -> types.add(Knowledge.object(f)));
      String role = RepoSections.attribute(part, "role", "");
      Component found =
          new Component(main, types, RepoSections.attribute(part, "technology", null));
      switch (role) {
        case Components.CONFIG_ROUTES_ROLE -> routes.add(found);
        case Components.SHARED -> shared.add(found);
        default -> entries.add(found);
      }
    }
    Comparator<Component> byMain = Comparator.comparing(c -> c.main().toString());
    routes.sort(byMain);
    entries.sort(byMain);
    shared.sort(byMain);
    List<Component> components = new ArrayList<>(routes);
    components.addAll(entries);
    components.addAll(shared);
    return components;
  }

  /**
   * A component's name: its main type's, or {@value #CONFIG_ROUTES} for the routes a gateway's
   * config defines.
   */
  String componentName(Component component) {
    return component.main.type().equals(BuiltInTypes.MODULE)
        ? CONFIG_ROUTES
        : assembler.name(component.main);
  }

  private List<EntityId> withMethods(EntityId type) {
    List<EntityId> members = new ArrayList<>(List.of(type));
    members.addAll(knowledge.parts(type, METHOD));
    return members;
  }

  private Diagram componentDiagram(String id, EntityId unit, List<Component> components) {
    Map<EntityId, Diagram.Node> nodes = new TreeMap<>(Knowledge.BY_ID);
    nodes.put(unit, node(unit, technology(unit), null, containerFacts(unit)));
    for (Component component : components) {
      Diagram.Node node =
          node(component.main, component.technology, unit, componentFacts(component));
      nodes.put(
          component.main,
          new Diagram.Node(
              node.id(),
              componentName(component),
              node.technology(),
              node.parent(),
              node.style(),
              node.position(),
              node.facts()));
    }
    Edges edges = new Edges();
    // The stored lines between the unit's components (#617), and where the code end of each of
    // its links to other containers is.
    Map<EntityId, EntityId> mainOf = new TreeMap<>(Knowledge.BY_ID);
    components.forEach(c -> mainOf.put(Components.id(unit, c.main), c.main));
    Map<String, EntityId> codeEnds = new HashMap<>();
    for (Component component : components) {
      for (Fact line :
          knowledge.relations(
              Components.id(unit, component.main), ComponentInteractions.COMPONENT_LINK)) {
        String kind = attribute(line, "kind");
        String detail = attribute(line, "detail");
        if (ComponentInteractions.CODE_END.equals(kind)) {
          // The side the code is on, then each link it places, by the fact it cites.
          String side = attribute(line, "target").split(" ", 2)[0];
          strings(line, "facts").forEach(link -> codeEnds.put(side + " " + link, component.main));
          continue;
        }
        EntityId to = mainOf.get(Knowledge.object(line));
        if (to == null) {
          continue;
        }
        String relationship =
            ComponentInteractions.ADVISES_KIND.equals(kind) ? ADVISES : BuiltInTypes.CALLS;
        String plain =
            switch (kind) {
              case ComponentInteractions.APPLIES -> "Applies";
              case ComponentInteractions.ADVISES_KIND -> "Advises";
              default -> "Calls";
            };
        String prefix = ComponentInteractions.APPLIES.equals(kind) ? "Applies on routes" : plain;
        EdgeBuilder edge = edges.get(component.main, relationship, to);
        for (Fact cited : basis(line)) {
          edge.add(plain, prefix, detail, null, null);
          edge.facts.add(cited);
          edge.phrased(null);
        }
      }
    }
    // Lines to and from other containers, at the code on this unit's end.
    for (Link link : links) {
      if (link.from.equals(unit)) {
        for (Fact fact : link.facts) {
          EntityId from = codeEnds.get("source " + fact.id());
          if (from != null && fact.subject().equals(unit)) {
            container(nodes, link.to);
            edges.add(link.from(from));
          }
        }
      } else if (link.to.equals(unit)) {
        for (Fact fact : link.facts) {
          String side = fact.subject().equals(unit) ? "source" : "target";
          EntityId to = codeEnds.get(side + " " + fact.id());
          if (to != null && link.relationship.equals(CONSUMES) == fact.subject().equals(unit)) {
            container(nodes, link.from);
            edges.add(link.to(to));
          }
        }
      }
    }
    // Data access, to the databases the unit's datasources point to.
    for (Component component : components) {
      String access =
          knowledge
              .attribute(Components.id(unit, component.main), ComponentInteractions.DATA_ACCESS)
              .orElse(null);
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

  /** The facts that make the component's main type what it is: its stereotype and entry points. */
  List<Fact> componentFacts(Component component) {
    List<Fact> facts = new ArrayList<>();
    for (EntityId member : withMethods(component.main)) {
      for (Fact fact : knowledge.attributes(member)) {
        switch (fact.predicate()) {
          case STEREOTYPE,
              HTTP_CLIENT,
              ENDPOINT,
              MESSAGE_LISTENER,
              SCHEDULED,
              BACKGROUND_JOB,
              ASPECT,
              GATEWAY_ROUTE,
              GATEWAY_FILTER ->
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

  private Diagram.Node node(EntityId entity, String technology, EntityId parent, List<Fact> facts) {
    assembler.entity(entity);
    return new Diagram.Node(
        entity, assembler.name(entity), technology, parent, null, null, assembler.facts(facts));
  }
}

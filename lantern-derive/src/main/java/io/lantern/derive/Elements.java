package io.lantern.derive;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.types.Types;
import io.lantern.core.vocabulary.C4Level;
import io.lantern.core.vocabulary.Vocabulary;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;

/**
 * What the container and component diagrams of a scope show, read from the model (knowledge-build
 * epic, section 12; #717): the units, frontends and destinations, each element's technology, the
 * lines between them (one per link fact, with its words, detail, protocol, profile and facts;
 * queues an exchange routes to folded into the line; profile alternatives apart), an element's
 * neighbours, the people and their screens, and a unit's components. The composer draws diagrams
 * from these queries and the LLM roles ask about the same things, so neither works them out for
 * itself.
 */
public final class Elements {

  /** The relationship of a line from a person to what they use. */
  public static final String USES = "USES";

  /** The name of a component of the routes a gateway's config defines. */
  public static final String CONFIG_ROUTES = "Gateway routes";

  /** What opens everything Investigate found, in every template (ADR 0110). */
  public static final String FOUND_BY_LLM = "Found by the LLM:";

  private final Knowledge knowledge;
  private final Vocabulary vocabulary;
  private final ElementNames names;
  private final Set<String> repos;

  /** The frontends in scope: those of the scope's repositories (ADR 0083). */
  private final SortedSet<EntityId> frontends;

  private final List<Line> lines;

  /** Each profile-only link that replaces a default one under its profile, with that default. */
  private final Map<Fact, Fact> alternatives;

  /**
   * The elements of the model, with the names of the document's entities.
   *
   * @param names the names of the elements, which a line through a folded queue names
   * @param repos the repositories in scope, whose frontends and people are drawn; empty for all
   */
  public Elements(
      ArchitectureModel model, Vocabulary vocabulary, ElementNames names, Set<String> repos) {
    this(model.index(), vocabulary, names, repos);
  }

  /** As the other constructor, over the index of facts. */
  public Elements(
      Knowledge knowledge, Vocabulary vocabulary, ElementNames names, Set<String> repos) {
    this.knowledge = Objects.requireNonNull(knowledge, "knowledge");
    this.vocabulary = Objects.requireNonNull(vocabulary, "vocabulary");
    this.names = Objects.requireNonNull(names, "names");
    this.repos = Set.copyOf(repos);
    this.frontends = new TreeSet<>(Knowledge.BY_ID);
    for (Fact app : knowledge.withPredicate(Types.FRONTEND_APP)) {
      if (repos.isEmpty() || repos.contains(app.evidence().repo())) {
        frontends.add(app.subject());
      }
    }
    this.alternatives = alternatives();
    List<Line> found = containerLinks();
    found.removeIf(line -> alternatives.containsKey(line.facts.getFirst()));
    this.lines = List.copyOf(found);
  }

  /**
   * The lines of the container diagrams, in the order they are found: each unit's calls, mail,
   * identity providers, messages and data, then the frontends' calls, the units' other
   * interactions, people's screens, what Investigate found and routes between exchanges and queues.
   */
  public List<Line> lines() {
    return lines;
  }

  /** Whether the entity is one the container diagrams draw besides units. */
  public boolean isDestination(EntityId id) {
    return isDestination(vocabulary, id);
  }

  /**
   * Whether the entity is a data store, destination or system outside the scope: an element the
   * container diagrams draw that runs none of the system's code, at the container or context level
   * by its type or the nearest ancestor that has one (ADR 0114). A message broker and an
   * infrastructure server are containers too, but the diagrams draw a broker's topics, queues and
   * exchanges instead, and keep infrastructure apart from the business dependencies.
   */
  public static boolean isDestination(Vocabulary vocabulary, EntityId id) {
    String type = id.type();
    if (vocabulary.find(type).isEmpty()
        || type.equals(Types.SYSTEM)
        || vocabulary.isA(type, Types.ACTOR)
        || vocabulary.isA(type, Types.MESSAGE_BROKER)
        || vocabulary.isA(type, Types.INFRASTRUCTURE)
        || runsCode(id)) {
      return false;
    }
    C4Level level = vocabulary.c4Level(type).orElse(null);
    return level == C4Level.CONTAINER || level == C4Level.CONTEXT;
  }

  /**
   * Each profile-only link that replaces a default link under its profile, with that default; the
   * diagrams show the default.
   */
  public Map<Fact, Fact> alternativeLinks() {
    return alternatives;
  }

  /** How the elements are named. */
  public ElementNames names() {
    return names;
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
  public record Neighbour(
      EntityId other, String relationship, boolean incoming, String via, List<Fact> facts) {}

  /**
   * The data stores, topics, queues, exchanges and external systems the container diagrams draw:
   * the ends of their lines to and from units that are not units themselves, profile alternatives
   * and queues folded into a line left out. Routes between exchanges and queues add nothing, as on
   * the diagrams, which draw them only between elements drawn already.
   */
  public SortedSet<EntityId> destinations() {
    SortedSet<EntityId> result = new TreeSet<>(Knowledge.BY_ID);
    for (Line link : lines) {
      if (!runsCode(link.from) && !runsCode(link.to)) {
        continue;
      }
      for (EntityId end : List.of(link.from, link.to)) {
        if (isDestination(end)) {
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
  public List<Neighbour> neighbours(EntityId element) {
    Set<EntityId> drawn = destinations();
    List<Neighbour> result = new ArrayList<>();
    for (Line link : lines) {
      if (link.relationship.equals(Types.ROUTES_TO)
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
  public static boolean runsCode(EntityId element) {
    return element.type().equals(Types.SERVICE) || element.type().equals(Types.FRONTEND);
  }

  /** Every frontend of the model (ADR 0083). */
  public static SortedSet<EntityId> frontends(ArchitectureModel model) {
    return frontends(model.index());
  }

  /** Every frontend of the knowledge (ADR 0083). */
  public static SortedSet<EntityId> frontends(Knowledge knowledge) {
    SortedSet<EntityId> result = new TreeSet<>(Knowledge.BY_ID);
    knowledge.withPredicate(Types.FRONTEND_APP).forEach(f -> result.add(f.subject()));
    return result;
  }

  /** The frontends in scope. */
  public SortedSet<EntityId> frontends() {
    return frontends;
  }

  /**
   * A frontend's {@code frontend-app} fact read as the document describes the app (ADR 0083): the
   * framework it is built with, its version and the server it is served by, as its manifest and
   * Dockerfile state them.
   *
   * @param fact the fact
   * @param framework the framework, or {@code null} when none is stated
   * @param version the framework's version, or {@code null}
   * @param server the server serving the app, or {@code null}
   */
  public record FrontendApp(Fact fact, String framework, String version, String server) {}

  /** A frontend's app read as {@link FrontendApp}, if it has one. */
  public static Optional<FrontendApp> frontendAppOf(ArchitectureModel model, EntityId frontend) {
    return frontendApp(model, frontend)
        .map(
            app ->
                new FrontendApp(
                    app,
                    attribute(app, "framework", "a framework"),
                    attribute(app, "version", null),
                    attribute(app, "server", null)));
  }

  /** The entity a link's {@code source} attribute names, or {@code null} when it names none. */
  public static EntityId sourceOf(Fact link) {
    String id = attribute(link, "source", null);
    if (id == null) {
      return null;
    }
    try {
      return EntityId.parse(id);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** Whether a WebSocket endpoint speaks STOMP. */
  public static boolean isStomp(Fact endpoint) {
    return "stomp".equals(attribute(endpoint, "protocol", ""));
  }

  /** The frontends a person uses, as stored on the person's fact. */
  public static List<String> personFrontends(Fact person) {
    return strings(person, "frontends");
  }

  /** Whether a database link names schemas of its own, so the unit stores its data in it. */
  public static boolean ownsSchemas(Fact usesDatabase) {
    return !strings(usesDatabase, "schemas").isEmpty();
  }

  /** Whether a table access reads tables. */
  public static boolean readsTables(Fact access) {
    return !strings(access, "reads").isEmpty();
  }

  /** Whether a table access writes tables. */
  public static boolean writesTables(Fact access) {
    return !strings(access, "writes").isEmpty();
  }

  /** The fact saying what a person rests on (ADR 0074), or {@code null}. */
  public static Fact personBasis(ArchitectureModel model, EntityId person) {
    return model.attributes(person, Types.PERSON_BASIS).stream().findFirst().orElse(null);
  }

  /**
   * The name of a role the code checks, if the fact is a role's evidence (#229): the name, which
   * may be blank, else empty.
   */
  public static Optional<String> roleEvidence(Fact fact) {
    if (fact.predicate().equals(Types.PERSON_EVIDENCE) && fact.object().value().equals("role")) {
      return Optional.of(fact.attributes().path("name").asString(""));
    }
    return Optional.empty();
  }

  /** A frontend's {@code frontend-app} fact, if it has one. */
  public Optional<Fact> frontendApp(EntityId frontend) {
    return frontendApp(knowledge, frontend);
  }

  /** A frontend's {@code frontend-app} fact, if it has one. */
  public static Optional<Fact> frontendApp(ArchitectureModel model, EntityId frontend) {
    return frontendApp(model.index(), frontend);
  }

  public static Optional<Fact> frontendApp(Knowledge knowledge, EntityId frontend) {
    return knowledge.attributes(frontend).stream()
        .filter(f -> f.predicate().equals(Types.FRONTEND_APP))
        .findFirst();
  }

  /** Every deployable unit of the model. */
  public static SortedSet<EntityId> units(ArchitectureModel model) {
    return units(model.index());
  }

  /** Every deployable unit of the knowledge. */
  public static SortedSet<EntityId> units(Knowledge knowledge) {
    SortedSet<EntityId> units = new TreeSet<>(Knowledge.BY_ID);
    knowledge.withPredicate(Types.DEPLOYABLE_UNIT).forEach(f -> units.add(f.subject()));
    return units;
  }

  /**
   * The deployable units whose code is in one of the modules: their own module, or a library they
   * bundle.
   */
  public static SortedSet<EntityId> units(ArchitectureModel model, Set<EntityId> modules) {
    return units(model.index(), modules);
  }

  /** The deployable units whose code is in one of the modules, over the index of facts. */
  public static SortedSet<EntityId> units(Knowledge knowledge, Set<EntityId> modules) {
    SortedSet<EntityId> result = new TreeSet<>(Knowledge.BY_ID);
    for (EntityId unit : units(knowledge)) {
      if (codeModules(knowledge, unit).stream().anyMatch(modules::contains)) {
        result.add(unit);
      }
    }
    return result;
  }

  /** The modules whose code runs in the unit: its own and those of the libraries it bundles. */
  public static SortedSet<EntityId> codeModules(ArchitectureModel model, EntityId unit) {
    return codeModules(model.index(), unit);
  }

  /** The modules whose code runs in the unit, over the index of facts. */
  public static SortedSet<EntityId> codeModules(Knowledge knowledge, EntityId unit) {
    return Components.codeModules(knowledge, unit);
  }

  /** The name a unit runs under in Spring's default profile, if it has one. */
  public static Optional<String> applicationName(Knowledge knowledge, EntityId unit) {
    return knowledge.attributes(unit).stream()
        .filter(f -> f.predicate().equals(Types.APPLICATION_NAME) && Knowledge.view(f) == null)
        .map(f -> f.object().value())
        .findFirst();
  }

  /** The facts that make an entity a container of the diagrams, and its technology. */
  public List<Fact> containerFacts(EntityId entity) {
    return knowledge.attributes(entity).stream()
        .filter(
            f ->
                f.predicate().equals(Types.DEPLOYABLE_UNIT)
                    || f.predicate().equals(Types.FRONTEND_APP)
                    || f.predicate().equals(Types.TECHNOLOGY)
                    || (f.predicate().equals(Types.APPLICATION_NAME) && Knowledge.view(f) == null))
        .toList();
  }

  /**
   * Whether a type is the given type or extends it, as the vocabulary says (ADR 0114): a type that
   * extends container is a container wherever the composer draws one.
   */
  public boolean isType(String type, String ancestor) {
    return type.equals(ancestor) || vocabulary.isA(type, ancestor);
  }

  /** The technology line of a container: what it is built with, or what kind of store it is. */
  public String technology(EntityId entity) {
    Optional<String> technology = knowledge.attribute(entity, Types.TECHNOLOGY);
    String broker = entity.key().substring(0, Math.max(entity.key().indexOf(':'), 0));
    return switch (entity.type()) {
      case Types.FRONTEND -> frontendApp(entity).map(f -> attribute(f, "technology")).orElse(null);
      case Types.TOPIC -> brokerName(broker) + " topic";
      case Types.QUEUE -> brokerName(broker) + " queue";
      case Types.EXCHANGE -> {
        String type =
            knowledge.relations(entity, Types.ROUTES_TO).stream()
                .map(f -> attribute(f, "exchangeType"))
                .filter(Objects::nonNull)
                .sorted()
                .findFirst()
                .orElse(null);
        yield brokerName(broker) + (type == null ? "" : " " + type) + " exchange";
      }
      // A service, a data store or any other container states its technology.
      default ->
          isType(entity.type(), Types.SERVICE)
                  || isType(entity.type(), Types.DATABASE)
                  || isType(entity.type(), Types.CONTAINER)
              ? technology.orElse(null)
              : null;
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

  /**
   * A line between two elements of a container diagram, from one link fact: what it does (a label
   * without details, and a prefix for the details), a detail such as an endpoint, its protocol,
   * what it goes through (a queue, the tables read), the profile it alone holds under and its
   * facts.
   */
  public record Line(
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

    public Line from(EntityId other) {
      return new Line(
          other, relationship, to, plain, prefix, detail, technology, via, profile, facts);
    }

    public Line to(EntityId other) {
      return new Line(
          from, relationship, other, plain, prefix, detail, technology, via, profile, facts);
    }

    public Line via(String through, Fact also) {
      List<Fact> both = new ArrayList<>(facts);
      both.add(also);
      return new Line(
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

  /** The links of every unit, with RabbitMQ queues an exchange routes to folded into the line. */
  private List<Line> containerLinks() {
    Set<EntityId> folded = foldedQueues();
    List<Line> result = new ArrayList<>();
    for (EntityId unit : units(knowledge)) {
      for (Fact fact : knowledge.relations(unit, Types.CALLS_HTTP)) {
        List<String> paths = strings(fact, "paths");
        boolean route = attribute(fact, "route") != null;
        result.add(
            link(
                unit,
                Types.CALLS_HTTP,
                Knowledge.object(fact),
                route ? "Routes requests" : "Calls",
                route ? "Routes" : "Calls",
                route ? (paths.isEmpty() ? null : paths.getFirst()) : endpoint(fact),
                protocol(fact),
                fact));
      }
      for (Fact fact : knowledge.relations(unit, Types.SENDS_MAIL)) {
        result.add(
            link(
                unit,
                Types.SENDS_MAIL,
                Knowledge.object(fact),
                "Sends mail through",
                "Sends mail",
                null,
                protocol(fact),
                fact));
      }
      for (Fact fact : knowledge.relations(unit, Types.USES_IDENTITY_PROVIDER)) {
        String[] words = identityWords(attribute(fact, "purpose"));
        result.add(
            link(
                unit,
                Types.USES_IDENTITY_PROVIDER,
                Knowledge.object(fact),
                words[0],
                words[1],
                null,
                protocol(fact),
                fact));
      }
      for (Fact fact : knowledge.relations(unit, Types.PUBLISHES)) {
        result.add(
            link(
                unit,
                Types.PUBLISHES,
                Knowledge.object(fact),
                "Sends messages",
                "Sends",
                attribute(fact, "routingKey"),
                protocol(fact),
                fact));
      }
      for (Fact fact : knowledge.relations(unit, Types.CONSUMES)) {
        EntityId queue = Knowledge.object(fact);
        if (!folded.contains(queue)) {
          result.add(
              link(
                  queue,
                  Types.CONSUMES,
                  unit,
                  "Delivers messages",
                  null,
                  null,
                  protocol(fact),
                  fact));
          continue;
        }
        for (Fact route : routesTo(queue)) {
          result.add(
              link(
                      route.subject(),
                      Types.CONSUMES,
                      unit,
                      "Delivers messages",
                      "Delivers",
                      attribute(route, "bindingKey"),
                      protocol(fact),
                      fact)
                  .via(names.name(queue), route));
        }
      }
      for (Fact fact : knowledge.relations(unit, Types.USES_DATABASE)) {
        boolean ownsSchema = !strings(fact, "schemas").isEmpty();
        result.add(
            link(
                unit,
                Types.USES_DATABASE,
                Knowledge.object(fact),
                ownsSchema ? "Stores its data in" : "Connects to",
                null,
                null,
                protocol(fact),
                fact));
      }
      for (Fact fact : knowledge.relations(unit, Types.ACCESSES_DATA_OF)) {
        List<String> reads = strings(fact, "reads");
        List<String> writes = strings(fact, "writes");
        String verb =
            reads.isEmpty()
                ? "Writes tables of"
                : writes.isEmpty() ? "Reads tables of" : "Reads and writes tables of";
        Set<String> tables = new TreeSet<>();
        reads.forEach(t -> tables.add(t.substring(t.lastIndexOf(':') + 1)));
        writes.forEach(t -> tables.add(t.substring(t.lastIndexOf(':') + 1)));
        Line access =
            link(
                unit,
                Types.ACCESSES_DATA_OF,
                Knowledge.object(fact),
                verb,
                null,
                null,
                protocol(fact),
                fact);
        result.add(
            new Line(
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
      for (Fact fact : knowledge.relations(frontend, Types.CALLS_HTTP)) {
        String proxy = attribute(fact, "proxy");
        boolean socket = "WebSocket".equals(attribute(fact, "protocol"));
        boolean passed =
            !socket && attribute(fact, "httpMethod") == null && attribute(fact, "path") == null;
        result.add(
            link(
                frontend,
                Types.CALLS_HTTP,
                Knowledge.object(fact),
                passed ? "Passes requests on to" : socket ? "Opens a socket to" : "Calls",
                passed ? "Passes on" : socket ? "Opens" : "Calls",
                passed ? proxy : socket ? attribute(fact, "path") : endpoint(fact),
                protocol(fact),
                fact));
      }
    }
    result.addAll(otherLinks());
    result.addAll(peopleLinks());
    result.addAll(unknownCallerLinks());
    // What Investigate found, marked as such on every line that draws it (ADR 0110).
    for (EntityId unit : units(knowledge)) {
      for (Fact fact : knowledge.relations(unit, Types.INVESTIGATED_CONNECTION)) {
        if (Knowledge.isEntity(fact)) {
          result.add(
              link(
                  unit,
                  Types.INVESTIGATED_CONNECTION,
                  Knowledge.object(fact),
                  FOUND_BY_LLM + " " + attribute(fact, "description"),
                  null,
                  null,
                  attribute(fact, "technology"),
                  fact));
        }
      }
    }
    for (Fact route : knowledge.withPredicate(Types.ROUTES_TO)) {
      if (Knowledge.isEntity(route) && !folded.contains(Knowledge.object(route))) {
        result.add(
            link(
                route.subject(),
                Types.ROUTES_TO,
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
   * The interactions the lines above draw their own way; a unit's use of its message broker, whose
   * topics, queues and exchanges the diagrams draw instead of one broker box; and its use of
   * infrastructure servers, which the diagrams keep apart from its business dependencies.
   */
  private static final Set<String> DRAWN_OTHERWISE =
      Set.of(
          Types.CALLS_HTTP,
          Types.SENDS_MAIL,
          Types.USES_IDENTITY_PROVIDER,
          Types.PUBLISHES,
          Types.CONSUMES,
          Types.ROUTES_TO,
          Types.USES_DATABASE,
          Types.ACCESSES_DATA_OF,
          Types.INVESTIGATED_CONNECTION,
          Types.USES_BROKER,
          Types.USES_INFRASTRUCTURE);

  /**
   * Every other interaction of a unit with an element at the container or context level, drawn as
   * what it is a kind of, a line from the unit (ADR 0114): the vocabulary, not this class, decides
   * what a type is, so a type that extends {@code container} is drawn as one.
   */
  private List<Line> otherLinks() {
    List<Line> result = new ArrayList<>();
    for (EntityId unit : units(knowledge)) {
      for (Fact fact : knowledge.about(unit)) {
        if (Knowledge.isEntity(fact)
            && !DRAWN_OTHERWISE.contains(fact.predicate())
            && vocabulary.isA(fact.predicate(), Types.INTERACTS_WITH)
            && isDestination(Knowledge.object(fact))) {
          result.add(
              link(
                  unit,
                  fact.predicate(),
                  Knowledge.object(fact),
                  "Uses",
                  "Uses",
                  null,
                  protocol(fact),
                  fact));
        }
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
  private List<Line> peopleLinks() {
    List<Line> result = new ArrayList<>();
    for (Map.Entry<EntityId, Fact> person : people().entrySet()) {
      Fact basis = person.getValue();
      screens(basis)
          .forEach(
              (frontend, routes) -> {
                for (Fact route : routes) {
                  List<Fact> facts = new ArrayList<>(List.of(basis, route));
                  frontendApp(frontend).ifPresent(facts::add);
                  result.add(
                      new Line(
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

  /**
   * The lines from the Unknown caller to the units whose entry points meant to be called nothing in
   * the set reaches (ADR 0074, amendment for #365; #766): naming up to two of them and how many
   * more, with their protocols, citing the line's fact and the entry points'.
   */
  private List<Line> unknownCallerLinks() {
    List<Line> result = new ArrayList<>();
    for (Fact line : knowledge.withPredicate(Types.CALLS_UNREACHED)) {
      List<Fact> facts = new ArrayList<>(List.of(line));
      SortedSet<String> protocols = new TreeSet<>();
      List<String> named = new ArrayList<>();
      for (String id : strings(line, "facts")) {
        Fact entry = knowledge.fact(id);
        if (entry == null) {
          continue;
        }
        facts.add(entry);
        named.add(entry.object().value());
        protocols.add(entryProtocol(line, entry));
      }
      String detail =
          named.size() <= 2
              ? String.join(", ", named)
              : String.join(", ", named.subList(0, 2)) + " and " + (named.size() - 2) + " more";
      result.add(
          new Line(
              UnknownCallers.CALLER,
              Types.CALLS_UNREACHED,
              Knowledge.object(line),
              "Calls entry points nothing in the set reaches",
              "Calls",
              detail.isEmpty() ? null : detail,
              protocols.isEmpty() ? null : String.join(", ", protocols),
              null,
              null,
              facts));
    }
    return result;
  }

  /** The protocol an entry point is called by: HTTP, WebSocket, or its listener's. */
  private String entryProtocol(Fact line, Fact entry) {
    return switch (entry.predicate()) {
      case Types.WEBSOCKET_ENDPOINT -> "WebSocket";
      case Types.MESSAGE_LISTENER ->
          knowledge.relations(Knowledge.object(line), Types.CONSUMES).stream()
              .filter(c -> entry.subject().toString().equals(attribute(c, "source")))
              .map(c -> attribute(c, "protocol"))
              .filter(Objects::nonNull)
              .findFirst()
              .orElse("Messaging");
      default -> "HTTP";
    };
  }

  /** The screens a person has, by frontend ({@link Screens#screens}). */
  private Map<EntityId, List<Fact>> screens(Fact basis) {
    return Screens.screens(knowledge, basis);
  }

  /**
   * The people of the scope who have screens on a frontend, each with its {@code person-basis} fact
   * and the routes of those screens, by person ID.
   */
  public SortedMap<EntityId, List<Fact>> screensOn(EntityId frontend) {
    return Screens.screensOn(knowledge, frontend, repos);
  }

  private static Line link(
      EntityId from,
      String relationship,
      EntityId to,
      String plain,
      String prefix,
      String detail,
      String technology,
      Fact fact) {
    return new Line(
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
    knowledge.withPredicate(Types.PUBLISHES).stream()
        .filter(Knowledge::isEntity)
        .forEach(f -> sentTo.add(Knowledge.object(f)));
    Set<EntityId> folded = new HashSet<>();
    for (Fact route : knowledge.withPredicate(Types.ROUTES_TO)) {
      if (Knowledge.isEntity(route) && !sentTo.contains(Knowledge.object(route))) {
        folded.add(Knowledge.object(route));
      }
    }
    return folded;
  }

  private List<Fact> routesTo(EntityId queue) {
    return knowledge.withPredicate(Types.ROUTES_TO).stream()
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
  public static String protocol(Fact fact) {
    String protocol = attribute(fact, "protocol");
    String resolution = resolution(fact);
    if (resolution == null) {
      return protocol;
    }
    return protocol == null ? resolution : protocol + " · " + resolution;
  }

  /** "resolved by the LLM, confidence 0.28" for a link the LLM resolver picked, else null. */
  public static String resolution(Fact fact) {
    // The resolver's links name the link left open they resolve (ADR 0045), whatever their kind.
    if (!fact.attributes().has("resolves")) {
      return null;
    }
    return "resolved by the LLM, confidence "
        + String.format(Locale.ROOT, "%.2f", fact.confidence());
  }

  public static List<String> strings(Fact fact, String name) {
    JsonNode values = fact.attributes().get(name);
    List<String> result = new ArrayList<>();
    if (values != null && values.isArray()) {
      for (JsonNode value : values) {
        result.add(value.asString());
      }
    }
    return result;
  }

  /**
   * The people the linker inferred from the code (ADR 0074) that the scope's repositories show,
   * each with its {@code person-basis} fact.
   */
  public SortedMap<EntityId, Fact> people() {
    return Screens.people(knowledge, repos);
  }

  /**
   * A component: a group of types behind one role, named after its main type, the entry point or
   * bean it is built around.
   */
  public record Component(EntityId main, SortedSet<EntityId> types, String technology) {}

  /**
   * The unit's stored components (#523): the routes its gateway config defines, then its entry
   * points and aspects, then the beans they share, each in ID order.
   */
  public List<Component> components(EntityId unit) {
    List<Component> routes = new ArrayList<>();
    List<Component> entries = new ArrayList<>();
    List<Component> shared = new ArrayList<>();
    for (Fact part : knowledge.withPredicate(Types.PART_OF)) {
      if (!part.subject().type().equals(Types.COMPONENT)
          || !Knowledge.isEntity(part)
          || !Knowledge.object(part).equals(unit)) {
        continue;
      }
      EntityId component = part.subject();
      EntityId main =
          knowledge.relations(component, Types.ANCHORED_ON).stream()
              .map(Knowledge::object)
              .findFirst()
              .orElse(null);
      if (main == null) {
        continue;
      }
      SortedSet<EntityId> types = new TreeSet<>(Knowledge.BY_ID);
      knowledge.relations(component, Types.HAS_MEMBER).forEach(f -> types.add(Knowledge.object(f)));
      String role = attribute(part, "role", "");
      Component found = new Component(main, types, attribute(part, "technology", null));
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
  public String componentName(Component component) {
    return component.main.type().equals(Types.MODULE) ? CONFIG_ROUTES : names.name(component.main);
  }

  /** The facts that make the component's main type what it is: its stereotype and entry points. */
  public List<Fact> componentFacts(Component component) {
    List<Fact> facts = new ArrayList<>();
    for (EntityId member : withMethods(component.main)) {
      for (Fact fact : knowledge.attributes(member)) {
        switch (fact.predicate()) {
          case Types.STEREOTYPE,
              Types.HTTP_CLIENT,
              Types.ENDPOINT,
              Types.MESSAGE_LISTENER,
              Types.SCHEDULED,
              Types.BACKGROUND_JOB,
              Types.ASPECT,
              Types.GATEWAY_ROUTE,
              Types.GATEWAY_FILTER ->
              facts.add(fact);
          default -> {}
        }
      }
    }
    facts.addAll(knowledge.relations(component.main, Types.REPOSITORY_FOR));
    return facts;
  }

  private static String attribute(Fact fact, String name) {
    return attribute(fact, name, null);
  }

  /** A fact's attribute as text, or the given value when it has none. */
  public static String attribute(Fact fact, String name, String otherwise) {
    JsonNode value = fact.attributes().get(name);
    return value == null || value.isNull() ? otherwise : value.asString();
  }

  private List<EntityId> withMethods(EntityId type) {
    List<EntityId> members = new ArrayList<>(List.of(type));
    members.addAll(knowledge.parts(type, Types.METHOD));
    return members;
  }
}

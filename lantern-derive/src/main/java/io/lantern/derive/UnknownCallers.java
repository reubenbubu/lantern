package io.lantern.derive;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.types.Types;
import io.lantern.core.vocabulary.VocabularyType;
import io.lantern.link.LinkRule;
import io.lantern.link.RuleInfo;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Unknown caller (ADR 0074, amendment for #365; #766): an API is there to be called, so a unit
 * whose entry points meant to be called nothing in the set reaches and no person uses is drawn as
 * called by one {@code actor:unknown-caller}, marked unknown. An entry point meant to be called is
 * an HTTP endpoint, a WebSocket endpoint, or a message listener on a queue or topic nothing
 * publishes to; a scheduled job is none. An endpoint a gateway routes to is reached through it, and
 * the gateway's routes are then the entry points: a gateway nothing calls gets the line instead.
 *
 * <p>One {@code CALLS_UNREACHED} fact per unit, from the caller to the unit, citing the entry
 * points' facts. Reaching is a property of the finished link graph, so the rule is derived, not
 * linked (knowledge-build epic, section 9).
 */
public final class UnknownCallers implements LinkRule.OverFacts {

  public static final String NAME = "unknown-callers";

  /** Raised whenever the lines it gives for the same facts change. */
  public static final String VERSION = "1";

  /** The one actor that calls what nothing in the set reaches. */
  public static final EntityId CALLER = new EntityId(Types.ACTOR, "unknown-caller");

  /** What the Unknown caller is named wherever it is drawn. */
  public static final String CALLER_NAME = "Unknown caller";

  private static final Origin ORIGIN = new Origin(Origin.Kind.STATIC, NAME, VERSION);

  private static final Set<String> READS =
      Set.of(
          EXTRACTED,
          LINKED,
          Types.PART_OF,
          Types.CALLS_HTTP,
          Types.SENDS_MESSAGES_TO,
          Types.USES_IDENTITY_PROVIDER,
          Types.PUBLISHES,
          Types.CONSUMES,
          Types.ROUTES_TO,
          Types.ROUTED_ENDPOINT,
          Types.INVESTIGATED_CONNECTION,
          Types.PERSON_BASIS);

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
    return Set.of(Types.CALLS_UNREACHED);
  }

  /** The vocabulary type of the rule's facts. */
  public static List<VocabularyType> vocabulary() {
    return Derivation.load("/vocabulary/unknown-callers-types.json");
  }

  @Override
  public List<Fact> derive(List<Fact> facts, long snapshot) {
    Knowledge knowledge = new Knowledge(facts);
    Validity validity = Validity.from(snapshot);
    List<Fact> result = new ArrayList<>();
    unreached(knowledge)
        .forEach(
            (unit, entries) -> {
              ObjectNode attributes = FactJson.newObject();
              ArrayNode ids = attributes.putArray("facts");
              entries.forEach(f -> ids.add(f.id()));
              result.add(
                  Fact.create(
                      CALLER,
                      Types.CALLS_UNREACHED,
                      FactObject.entity(unit),
                      attributes,
                      ORIGIN,
                      entries.getFirst().evidence(),
                      1.0,
                      validity));
            });
    return result;
  }

  /**
   * The entry points meant to be called that nothing in the set reaches and no person uses, by
   * unit, in the order of the units' IDs, each unit's by the entry point's method and text; for a
   * gateway nothing calls, its routes.
   */
  static Map<EntityId, List<Fact>> unreached(Knowledge knowledge) {
    SortedSet<EntityId> units = Screens.units(knowledge);
    Set<String> reached = new HashSet<>();
    Set<EntityId> called = new HashSet<>();
    Set<EntityId> published = new HashSet<>();
    // Units a link reaches without naming the entry point: what it calls is not known, so none of
    // the unit's entry points of that kind is one nothing reaches (#766, review).
    Set<EntityId> calledWhole = new HashSet<>();
    Set<EntityId> messagedWhole = new HashSet<>();
    Set<String> followed = followed(knowledge);
    // Units a followed gateway route reaches: of their endpoints, only those the linker recorded
    // as unrouted may be ones nothing reaches.
    Set<EntityId> routedTo = new HashSet<>();
    Set<String> unrouted = new HashSet<>();
    for (Fact fact : knowledge.withPredicate(Types.UNROUTED_ENDPOINT)) {
      reach(unrouted, parse(attribute(fact, "target")), attribute(fact, "endpoint"));
    }
    for (Fact fact : knowledge.withPredicate(Types.CALLS_HTTP)) {
      called.add(Knowledge.object(fact));
      reach(reached, Knowledge.object(fact), attribute(fact, "target"));
      reach(reached, parse(attribute(fact, "reachesUnit")), attribute(fact, "reaches"));
      // A gateway's route reaches only the endpoints a routed-endpoint names, when the linker
      // followed its routes to the unit and recorded the rest as unrouted (#766, review of 4 Nov).
      if (attribute(fact, "target") == null
          && attribute(fact, "reaches") == null
          && !followedRoute(fact, followed)) {
        calledWhole.add(Knowledge.object(fact));
      } else if (followedRoute(fact, followed)) {
        routedTo.add(Knowledge.object(fact));
      }
    }
    for (String predicate : List.of(Types.SENDS_MESSAGES_TO, Types.USES_IDENTITY_PROVIDER)) {
      for (Fact fact : knowledge.withPredicate(predicate)) {
        if (Knowledge.isEntity(fact)) {
          reach(reached, Knowledge.object(fact), attribute(fact, "target"));
          if (attribute(fact, "target") == null) {
            (predicate.equals(Types.SENDS_MESSAGES_TO) ? messagedWhole : calledWhole)
                .add(Knowledge.object(fact));
          }
        }
      }
    }
    // A connection Investigate found into a unit reaches it (ADR 0110).
    for (Fact fact : knowledge.withPredicate(Types.INVESTIGATED_CONNECTION)) {
      if (Knowledge.isEntity(fact)) {
        calledWhole.add(Knowledge.object(fact));
        messagedWhole.add(Knowledge.object(fact));
      }
    }
    for (Fact fact : knowledge.withPredicate(Types.ROUTED_ENDPOINT)) {
      reach(reached, parse(attribute(fact, "target")), attribute(fact, "endpoint"));
    }
    for (Fact fact : knowledge.withPredicate(Types.PUBLISHES)) {
      if (Knowledge.isEntity(fact)) {
        published.add(Knowledge.object(fact));
      }
    }
    // A queue an exchange that is published to routes to is published to as well, through any
    // chain of exchanges.
    for (boolean grew = true; grew; ) {
      grew = false;
      for (Fact fact : knowledge.withPredicate(Types.ROUTES_TO)) {
        if (Knowledge.isEntity(fact)
            && published.contains(fact.subject())
            && published.add(Knowledge.object(fact))) {
          grew = true;
        }
      }
    }
    Set<String> used = used(knowledge);
    Map<EntityId, List<Fact>> result = new LinkedHashMap<>();
    for (EntityId unit : units) {
      List<Fact> entries = new ArrayList<>();
      for (EntityId method : unitMethods(knowledge, unit)) {
        if (reached.contains(unit + " " + method)
            || used.contains(method.toString())
            || used.contains(declaringType(method))) {
          continue;
        }
        for (Fact fact : knowledge.attributes(method)) {
          boolean callable =
              switch (fact.predicate()) {
                case Types.ENDPOINT, Types.WEBSOCKET_ENDPOINT ->
                    !calledWhole.contains(unit)
                        && (!routedTo.contains(unit) || unrouted.contains(unit + " " + method));
                case Types.MESSAGE_LISTENER ->
                    !messagedWhole.contains(unit)
                        && unpublished(knowledge, unit, method, published);
                default -> false;
              };
          if (callable) {
            entries.add(fact);
          }
        }
      }
      // A gateway's routes are its entry points; the endpoints they reach are reached through it.
      List<Fact> routes = new ArrayList<>(knowledge.attributes(unit));
      routes.removeIf(f -> !f.predicate().equals(Types.ROUTED_ENDPOINT));
      if (!routes.isEmpty() && !called.contains(unit)) {
        entries.addAll(routes);
      }
      if (!entries.isEmpty()) {
        entries.sort(
            Comparator.comparing((Fact f) -> f.subject().toString())
                .thenComparing(f -> f.object().value())
                .thenComparing(Fact::id));
        result.put(unit, entries);
      }
    }
    return result;
  }

  /**
   * The gateways and units the linker followed every route between, as {@code "<gateway> <unit>"}:
   * it records an {@code unrouted-endpoint} for each endpoint of the unit the routes do not reach
   * only then (RouteLinker).
   */
  private static Set<String> followed(Knowledge knowledge) {
    Set<String> result = new HashSet<>();
    for (Fact fact : knowledge.withPredicate(Types.UNROUTED_ENDPOINT)) {
      String target = attribute(fact, "target");
      if (target != null) {
        result.add(fact.subject() + " " + target);
      }
    }
    return result;
  }

  /** Whether the link is a gateway's route to a unit whose routes the linker followed. */
  private static boolean followedRoute(Fact link, Set<String> followed) {
    return attribute(link, "route") != null
        && Knowledge.isEntity(link)
        && followed.contains(link.subject() + " " + Knowledge.object(link));
  }

  /**
   * Whether the listener's queues or topics, as its unit's {@code CONSUMES} facts name them, are
   * ones nothing in the set publishes to and no link delivers to it on; false when none is named,
   * since then the code does not say what it listens to.
   */
  private static boolean unpublished(
      Knowledge knowledge, EntityId unit, EntityId method, Set<EntityId> published) {
    List<EntityId> destinations = new ArrayList<>();
    for (Fact consumes : knowledge.relations(unit, Types.CONSUMES)) {
      if (Knowledge.isEntity(consumes) && method.toString().equals(attribute(consumes, "source"))) {
        destinations.add(Knowledge.object(consumes));
      }
    }
    return !destinations.isEmpty() && destinations.stream().noneMatch(published::contains);
  }

  /**
   * The methods and types a person's basis cites facts about: what a person is shown to use, such
   * as an account endpoint or a controller whose roles they hold.
   */
  private static Set<String> used(Knowledge knowledge) {
    Set<String> result = new HashSet<>();
    for (Fact basis : knowledge.withPredicate(Types.PERSON_BASIS)) {
      for (String id : Screens.strings(basis, "cites")) {
        Fact cited = knowledge.fact(id);
        if (cited != null) {
          result.add(cited.subject().toString());
          if (cited.subject().type().equals(Types.METHOD)) {
            result.add(declaringType(cited.subject()));
          }
        }
      }
    }
    return result;
  }

  /**
   * What is wrong with the Unknown caller's lines in the facts, checked apart from how {@link
   * #derive} finds them (#766): each line must cite only entry points meant to be called that
   * nothing in the set reaches and no person uses. Empty when every line holds.
   */
  public static List<String> violations(Knowledge knowledge) {
    List<String> result = new ArrayList<>();
    List<Fact> all = new ArrayList<>();
    for (Origin.Kind kind : Origin.Kind.values()) {
      all.addAll(knowledge.withOrigin(kind));
    }
    for (Fact line : knowledge.withPredicate(Types.CALLS_UNREACHED)) {
      EntityId unit = Knowledge.object(line);
      for (String id : Screens.strings(line, "facts")) {
        Fact entry = knowledge.fact(id);
        String why =
            entry == null ? "the set does not hold" : reachedBy(knowledge, all, unit, entry);
        if (why != null) {
          result.add(
              "The line to "
                  + unit
                  + " cites "
                  + (entry == null ? id : entry.subject() + " " + entry.object().value())
                  + ", which "
                  + why);
        }
      }
    }
    return result;
  }

  /** Why the entry point is not one nothing reaches, or null when nothing does. */
  private static String reachedBy(Knowledge knowledge, List<Fact> all, EntityId unit, Fact entry) {
    if (entry.predicate().equals(Types.ROUTED_ENDPOINT)) {
      if (!entry.subject().equals(unit)) {
        return "is a route of another unit, " + entry.subject();
      }
      for (Fact call : knowledge.withPredicate(Types.CALLS_HTTP)) {
        if (Knowledge.object(call).equals(unit)) {
          return "is a route of a gateway " + call.subject() + " calls";
        }
      }
      return null;
    }
    if (!Set.of(Types.ENDPOINT, Types.WEBSOCKET_ENDPOINT, Types.MESSAGE_LISTENER)
        .contains(entry.predicate())) {
      return "is no entry point meant to be called (" + entry.predicate() + ")";
    }
    String method = entry.subject().toString();
    if (!unitMethods(knowledge, unit).contains(entry.subject())) {
      return "is not in the code of " + unit;
    }
    boolean listener = entry.predicate().equals(Types.MESSAGE_LISTENER);
    Set<String> followed = followed(knowledge);
    Set<String> unrouted = new HashSet<>();
    for (Fact fact : knowledge.withPredicate(Types.UNROUTED_ENDPOINT)) {
      reach(unrouted, parse(attribute(fact, "target")), attribute(fact, "endpoint"));
    }
    EntityId behind = null;
    for (Fact fact : all) {
      boolean toUnit = Knowledge.isEntity(fact) && Knowledge.object(fact).equals(unit);
      if (toUnit
          && !listener
          && fact.predicate().equals(Types.CALLS_HTTP)
          && followedRoute(fact, followed)
          && !unrouted.contains(unit + " " + method)) {
        behind = fact.subject();
      }
      // A link into the unit that names no entry point, or one Investigate found, may reach any;
      // a gateway's followed routes reach only what a routed-endpoint names.
      if (toUnit
          && !(fact.predicate().equals(Types.CALLS_HTTP) && followedRoute(fact, followed))
          && attribute(fact, "target") == null
          && attribute(fact, "reaches") == null
          && (fact.predicate().equals(Types.INVESTIGATED_CONNECTION)
              || (listener
                  ? fact.predicate().equals(Types.SENDS_MESSAGES_TO)
                  : fact.predicate().equals(Types.CALLS_HTTP)
                      || fact.predicate().equals(Types.USES_IDENTITY_PROVIDER)))) {
        return "the unit is reached by "
            + fact.subject()
            + " "
            + fact.predicate()
            + ", which names no entry point";
      }
      if ((toUnit && method.equals(attribute(fact, "target")))
          || (unit.toString().equals(attribute(fact, "reachesUnit"))
              && method.equals(attribute(fact, "reaches")))
          || (fact.predicate().equals(Types.ROUTED_ENDPOINT)
              && unit.toString().equals(attribute(fact, "target"))
              && method.equals(attribute(fact, "endpoint")))) {
        return "is reached by " + fact.subject() + " " + fact.predicate();
      }
    }
    if (behind != null) {
      return "is behind the routes of " + behind + ", which the linker did not record as unrouted";
    }
    for (Fact basis : knowledge.withPredicate(Types.PERSON_BASIS)) {
      for (String id : Screens.strings(basis, "cites")) {
        Fact cited = knowledge.fact(id);
        if (cited != null
            && (cited.subject().toString().equals(method)
                || cited.subject().toString().equals(declaringType(entry.subject()))
                || (cited.subject().type().equals(Types.METHOD)
                    && declaringType(cited.subject()).equals(declaringType(entry.subject()))))) {
          return "is used by " + basis.subject();
        }
      }
    }
    if (entry.predicate().equals(Types.MESSAGE_LISTENER)) {
      for (Fact consumes : knowledge.relations(unit, Types.CONSUMES)) {
        if (!method.equals(attribute(consumes, "source"))) {
          continue;
        }
        EntityId destination = Knowledge.object(consumes);
        for (Fact publishes : knowledge.withPredicate(Types.PUBLISHES)) {
          EntityId to = Knowledge.object(publishes);
          if (to.equals(destination)
              || knowledge.relations(to, Types.ROUTES_TO).stream()
                  .anyMatch(r -> Knowledge.object(r).equals(destination))) {
            return "listens on " + destination + ", which " + publishes.subject() + " publishes to";
          }
        }
      }
    }
    return null;
  }

  /** The type that declares the method, as an entity ID: {@code type:a.B} of {@code a.B#m()}. */
  private static String declaringType(EntityId method) {
    String key = method.key();
    int hash = key.indexOf('#');
    return Types.TYPE + ":" + (hash < 0 ? key : key.substring(0, hash));
  }

  private static void reach(Set<String> reached, EntityId unit, String method) {
    if (unit != null && method != null) {
      reached.add(unit + " " + method);
    }
  }

  /** The methods of the unit's code: its modules and the libraries it bundles. */
  static List<EntityId> unitMethods(Knowledge knowledge, EntityId unit) {
    SortedSet<EntityId> code = new TreeSet<>(Knowledge.BY_ID);
    for (EntityId module : Components.codeModules(knowledge, unit)) {
      code.addAll(knowledge.typesWithin(module));
      for (EntityId pkg : knowledge.parts(module, Types.PACKAGE)) {
        code.addAll(knowledge.typesWithin(pkg));
      }
    }
    List<EntityId> result = new ArrayList<>();
    for (EntityId type : code) {
      result.addAll(knowledge.parts(type, Types.METHOD));
    }
    return result;
  }

  private static EntityId parse(String id) {
    return FlowTracer.parse(id);
  }

  private static String attribute(Fact fact, String name) {
    return Screens.attribute(fact, name, null);
  }
}

package io.lantern.derive;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;

/**
 * Who has screens on which frontend (ADR 0074, ADR 0083), and the links a profile replaces: what
 * the flows (#618) and the composer's diagrams both read, so they read it the same way.
 */
public final class Screens {

  private static final String FRONTEND_APP = "frontend-app";
  private static final String PERSON = "person";
  private static final String PERSON_BASIS = "person-basis";
  private static final String PERSON_EVIDENCE = "person-evidence";
  private static final String ROUTE = "route";
  private static final String DEPLOYABLE_UNIT = "deployable-unit";

  /** The relationships whose profile-only links may replace a default link of their unit. */
  private static final List<String> REPLACEABLE =
      List.of(
          "CALLS_HTTP",
          "SENDS_MAIL",
          "USES_IDENTITY_PROVIDER",
          "PUBLISHES",
          "CONSUMES",
          "USES_DATABASE",
          "ACCESSES_DATA_OF");

  private Screens() {}

  /** Every frontend of the knowledge (ADR 0083). */
  public static SortedSet<EntityId> frontends(Knowledge knowledge) {
    SortedSet<EntityId> result = new TreeSet<>(Knowledge.BY_ID);
    knowledge.withPredicate(FRONTEND_APP).forEach(f -> result.add(f.subject()));
    return result;
  }

  /** Every deployable unit of the knowledge. */
  public static SortedSet<EntityId> units(Knowledge knowledge) {
    SortedSet<EntityId> units = new TreeSet<>(Knowledge.BY_ID);
    knowledge.withPredicate(DEPLOYABLE_UNIT).forEach(f -> units.add(f.subject()));
    return units;
  }

  /** A frontend's {@code frontend-app} fact, if it has one. */
  public static Optional<Fact> frontendApp(Knowledge knowledge, EntityId frontend) {
    return knowledge.attributes(frontend).stream()
        .filter(f -> f.predicate().equals(FRONTEND_APP))
        .findFirst();
  }

  /**
   * The people shown in a scope, each with its {@code person-basis} fact, by person ID: those whose
   * basis names one of the repositories, every one when there are none.
   */
  public static SortedMap<EntityId, Fact> people(Knowledge knowledge, Set<String> repos) {
    SortedMap<EntityId, Fact> result = new TreeMap<>(Knowledge.BY_ID);
    for (Fact basis : knowledge.withPredicate(PERSON_BASIS)) {
      if (!basis.subject().type().equals(PERSON)) {
        continue;
      }
      List<String> shownBy = strings(basis, "repos");
      if (repos.isEmpty() || shownBy.stream().anyMatch(repos::contains)) {
        result.putIfAbsent(basis.subject(), basis);
      }
    }
    return result;
  }

  /**
   * The screens a person has, by frontend: the {@code route} evidence its {@code person-basis}
   * cites, among the repositories it names as {@code frontends}, each under the frontend whose
   * folder holds the route's file.
   */
  public static Map<EntityId, List<Fact>> screens(Knowledge knowledge, Fact basis) {
    List<String> named = strings(basis, "frontends");
    Map<EntityId, List<Fact>> screens = new TreeMap<>(Knowledge.BY_ID);
    for (Fact route : cited(knowledge, basis)) {
      if (!route.predicate().equals(PERSON_EVIDENCE)
          || !route.object().value().equals(ROUTE)
          || !named.contains(route.evidence().repo())) {
        continue;
      }
      EntityId frontend = frontendOf(knowledge, route);
      if (frontend != null) {
        screens.computeIfAbsent(frontend, f -> new ArrayList<>()).add(route);
      }
    }
    return screens;
  }

  /**
   * The people of a scope who have screens on a frontend, each with its {@code person-basis} fact
   * and the routes of those screens, by person ID.
   */
  public static SortedMap<EntityId, List<Fact>> screensOn(
      Knowledge knowledge, EntityId frontend, Set<String> repos) {
    SortedMap<EntityId, List<Fact>> result = new TreeMap<>(Knowledge.BY_ID);
    for (Map.Entry<EntityId, Fact> person : people(knowledge, repos).entrySet()) {
      List<Fact> routes = screens(knowledge, person.getValue()).get(frontend);
      if (routes != null) {
        List<Fact> facts = new ArrayList<>(List.of(person.getValue()));
        facts.addAll(routes);
        result.put(person.getKey(), facts);
      }
    }
    return result;
  }

  /**
   * The person who uses a frontend in a scope, with the facts that say so: its only one, else the
   * one with most screens on it, by ID among equals; null when none has screens on it.
   */
  public static Map.Entry<EntityId, List<Fact>> user(
      Knowledge knowledge, EntityId frontend, Set<String> repos) {
    Map.Entry<EntityId, List<Fact>> chosen = null;
    for (Map.Entry<EntityId, List<Fact>> e : screensOn(knowledge, frontend, repos).entrySet()) {
      if (chosen == null || e.getValue().size() > chosen.getValue().size()) {
        chosen = e;
      }
    }
    return chosen;
  }

  /** The frontend whose folder holds a fact's file, the deepest one; null if none does. */
  private static EntityId frontendOf(Knowledge knowledge, Fact fact) {
    EntityId found = null;
    int depth = -1;
    for (EntityId frontend : frontends(knowledge)) {
      Fact app = frontendApp(knowledge, frontend).orElse(null);
      if (app == null || !app.evidence().repo().equals(fact.evidence().repo())) {
        continue;
      }
      String directory = attribute(app, "directory", "");
      if ((directory.isEmpty() || fact.evidence().path().startsWith(directory + "/"))
          && directory.length() > depth) {
        found = frontend;
        depth = directory.length();
      }
    }
    return found;
  }

  /** The facts a person rests on, with its own. */
  private static List<Fact> cited(Knowledge knowledge, Fact basis) {
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
   * The profile-only links that stand in for a default link of the same unit, relationship and code
   * or config (the same calling method, listener or datasource key, or a linked alternate host),
   * the default one not holding under that profile, each with that default: a datasource the {@code
   * local} profile points at {@code localhost}, PayGate's production host. Diagrams and flows show
   * the default. A profile-only link without such a default, such as a send only the {@code audit}
   * profile makes, is not one.
   */
  public static Map<Fact, Fact> alternatives(Knowledge knowledge) {
    Map<Fact, Fact> result = new java.util.LinkedHashMap<>();
    for (EntityId unit : units(knowledge)) {
      for (String relationship : REPLACEABLE) {
        List<Fact> facts = knowledge.relations(unit, relationship);
        for (Fact profiled : facts) {
          String profile = Knowledge.view(profiled);
          if (profile == null) {
            continue;
          }
          for (Fact fallback : facts) {
            if (Knowledge.view(fallback) == null
                && strings(fallback, "absentIn").contains(profile)
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
      String x = attribute(a, anchor, null);
      if (x != null) {
        return x.equals(attribute(b, anchor, null));
      }
    }
    return false;
  }

  static String attribute(Fact fact, String name, String otherwise) {
    JsonNode value = fact.attributes().get(name);
    return value == null || value.isNull() ? otherwise : value.asString();
  }

  /** The strings of an array attribute; empty when it is absent or not an array. */
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
}

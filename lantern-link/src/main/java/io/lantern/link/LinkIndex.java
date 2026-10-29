package io.lantern.link;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactObject;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.maven.MavenExtractor;
import io.lantern.extract.persistence.PersistenceExtractor;
import io.lantern.extract.spring.SpringExtractor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.core.env.Profiles;
import tools.jackson.databind.JsonNode;

/**
 * The facts of a snapshot that the cross-repo linker reads, indexed: the deployable units with
 * their modules and names, where code and tables are declared, the units' config, the profiles and
 * the profile gates. See {@code docs/adr/0041-cross-repo-links.md}.
 */
final class LinkIndex {

  /** A deployable unit with the modules it runs: its own module first, then those it bundles. */
  record Unit(EntityId id, List<EntityId> modules, Fact fact) {

    /** The unit's Maven artifact ID, the last part of its key. */
    String artifactId() {
      return id.key().substring(id.key().lastIndexOf(':') + 1);
    }
  }

  /** Orders entity IDs by their text. */
  static final Comparator<EntityId> BY_TEXT = Comparator.comparing(EntityId::toString);

  /** Facts in the order of their subject, object and ID, so choices among them are stable. */
  static final Comparator<Fact> BY_ID =
      Comparator.comparing((Fact f) -> f.subject().toString())
          .thenComparing(f -> f.object().value())
          .thenComparing(Fact::id);

  /** The units by ID. */
  final Map<EntityId, Unit> units = new TreeMap<>(BY_TEXT);

  /** The units each module runs in. */
  private final Map<EntityId, List<Unit>> unitsOfModule = new HashMap<>();

  /** Each unit's {@code application-name} facts, the default one under the key null. */
  private final Map<EntityId, Map<String, Fact>> names = new HashMap<>();

  /** What contains each package, type, member, schema or table (not Maven's containment). */
  private final Map<EntityId, Set<EntityId>> parents = new HashMap<>();

  /** A module's config-like facts ({@code config-value}, {@code datasource}). */
  private final Map<EntityId, List<Fact>> config = new HashMap<>();

  /** The {@code profile-gate} facts by the type or method they are written on. */
  private final Map<EntityId, List<Fact>> gates = new HashMap<>();

  /** The REST controllers ({@code stereotype controller} with {@code rest}), by type. */
  final Set<EntityId> restControllers = new HashSet<>();

  /** Every fact by predicate, in ID order. */
  private final Map<String, List<Fact>> byPredicate = new HashMap<>();

  /** The profiles the snapshot's facts name, sorted. */
  final List<String> profiles;

  LinkIndex(List<Fact> facts) {
    Map<EntityId, Fact> containerOf = new HashMap<>();
    Set<String> profileNames = new TreeSet<>();
    List<Fact> sorted = new ArrayList<>(facts);
    sorted.sort(BY_ID);
    for (Fact f : sorted) {
      byPredicate.computeIfAbsent(f.predicate(), p -> new ArrayList<>()).add(f);
      String profile = text(f, "profile");
      if (profile != null) {
        profileNames.add(profile);
      }
      boolean entity = f.object().kind() == FactObject.Kind.ENTITY;
      switch (f.predicate()) {
        case BuiltInTypes.CONTAINS -> {
          if (!entity) {
            break;
          }
          EntityId object = f.object().entityId();
          if (f.origin().name().equals(MavenExtractor.NAME)) {
            if (object.type().equals(BuiltInTypes.MODULE)) {
              containerOf.putIfAbsent(f.subject(), f);
            }
          } else {
            parents.computeIfAbsent(object, o -> new LinkedHashSet<>()).add(f.subject());
          }
        }
        case ConfigExtractor.CONFIG_VALUE, PersistenceExtractor.DATASOURCE ->
            config.computeIfAbsent(f.subject(), s -> new ArrayList<>()).add(f);
        case SpringExtractor.PROFILE_GATE -> {
          gates.computeIfAbsent(f.subject(), s -> new ArrayList<>()).add(f);
          JsonNode named = f.attributes().get("profiles");
          if (named != null && named.isArray()) {
            // As written, so "!local" or "a & b": the profiles are the names in the expression.
            named.forEach(
                p -> {
                  for (String name : p.asString().split("[!&|()\\s]+")) {
                    if (!name.isEmpty()) {
                      profileNames.add(name);
                    }
                  }
                });
          }
        }
        case SpringExtractor.STEREOTYPE -> {
          if ("controller".equals(f.object().value())
              && f.attributes().path("rest").asBoolean(false)) {
            restControllers.add(f.subject());
          }
        }
        case DeployableUnits.APPLICATION_NAME ->
            names.computeIfAbsent(f.subject(), s -> new HashMap<>()).putIfAbsent(profile, f);
        default -> {}
      }
    }
    profileNames.remove("default");
    profiles = List.copyOf(profileNames);

    Map<EntityId, List<Fact>> bundles = new HashMap<>();
    for (Fact f : of(DeployableUnits.BUNDLES)) {
      bundles.computeIfAbsent(f.subject(), s -> new ArrayList<>()).add(f);
    }
    for (Fact unit : of(DeployableUnits.DEPLOYABLE_UNIT)) {
      if (units.containsKey(unit.subject())) {
        continue;
      }
      List<EntityId> modules = new ArrayList<>();
      // A unit found from a library's application class has the library's key.
      Fact own = containerOf.get(unit.subject());
      if (own == null) {
        own = containerOf.get(new EntityId(BuiltInTypes.LIBRARY, unit.subject().key()));
      }
      if (own != null) {
        modules.add(own.object().entityId());
      }
      // Nearest first: those the unit's own module depends on, then by their declarations.
      List<Fact> bundled = new ArrayList<>(bundles.getOrDefault(unit.subject(), List.of()));
      bundled.sort(
          Comparator.comparingInt((Fact b) -> b.attributes().path("via").size())
              .thenComparingInt(
                  b ->
                      b.evidence().lines() == null
                          ? Integer.MAX_VALUE
                          : b.evidence().lines().start())
              .thenComparing(Fact::id));
      for (Fact b : bundled) {
        Fact contains = containerOf.get(b.object().entityId());
        if (contains != null && !modules.contains(contains.object().entityId())) {
          modules.add(contains.object().entityId());
        }
      }
      Unit u = new Unit(unit.subject(), List.copyOf(modules), unit);
      units.put(u.id(), u);
      for (EntityId module : modules) {
        unitsOfModule.computeIfAbsent(module, m -> new ArrayList<>()).add(u);
      }
    }
  }

  /** The facts of a predicate, in ID order. */
  List<Fact> of(String predicate) {
    return byPredicate.getOrDefault(predicate, List.of());
  }

  /** The views to link in: the default view (null), then each profile the facts name. */
  List<String> views() {
    List<String> views = new ArrayList<>();
    views.add(null);
    views.addAll(profiles);
    return views;
  }

  // ---------------------------------------------------------------------------------------------
  // Where code runs

  /**
   * The modules that declare an entity (a method, field, type, schema or table), found up its
   * containers; by ID.
   */
  List<EntityId> modulesOf(EntityId entity) {
    Set<EntityId> modules = new TreeSet<>(BY_TEXT);
    Set<EntityId> seen = new HashSet<>();
    Deque<EntityId> queue = new ArrayDeque<>(List.of(entity));
    while (!queue.isEmpty()) {
      EntityId current = queue.poll();
      if (!seen.add(current)) {
        continue;
      }
      if (current.type().equals(BuiltInTypes.MODULE)) {
        modules.add(current);
        continue;
      }
      Set<EntityId> up = parents.get(current);
      if (up != null) {
        queue.addAll(up);
      } else if (current.key().contains("#")) {
        // A member the Java facts do not list is still declared by its type.
        queue.add(typeOf(current));
      }
    }
    return List.copyOf(modules);
  }

  /** The units an entity's code runs in: those that run a module declaring it; by ID. */
  List<Unit> unitsOf(EntityId entity) {
    Set<Unit> result = new TreeSet<>(Comparator.comparing(Unit::id, BY_TEXT));
    for (EntityId module : modulesOf(entity)) {
      result.addAll(unitsOfModule.getOrDefault(module, List.of()));
    }
    return List.copyOf(result);
  }

  /** The type that declares a member ({@code method:a.B#m()} is in {@code type:a.B}). */
  static EntityId typeOf(EntityId entity) {
    int hash = entity.key().indexOf('#');
    return hash < 0 ? entity : new EntityId(BuiltInTypes.TYPE, entity.key().substring(0, hash));
  }

  // ---------------------------------------------------------------------------------------------
  // Names, config and profiles

  /** A unit's {@code application-name} fact in a view: the view's own, else the default one. */
  Fact name(EntityId unit, String view) {
    Map<String, Fact> byProfile = names.getOrDefault(unit, Map.of());
    Fact fact = view == null ? null : byProfile.get(view);
    return fact != null ? fact : byProfile.get(null);
  }

  /**
   * The config fact (a {@code config-value} or {@code datasource}) for a key that a unit sees in a
   * view, as Spring Boot finds it on its classpath (ADR 0039): the {@code application} files and
   * the {@code bootstrap} files each from the first module that has any, the application files
   * winning; in each, the view's profile file before the default one. Null if none sets it.
   */
  Fact config(Unit unit, String view, String predicate, String key) {
    for (String family : List.of("application", "bootstrap")) {
      EntityId provider = provider(unit, family);
      if (provider == null) {
        continue;
      }
      Fact fallback = null;
      for (Fact f : config.getOrDefault(provider, List.of())) {
        if (!f.predicate().equals(predicate)
            || !key.equals(text(f, "key"))
            || !family.equals(DeployableUnits.configFamily(f))) {
          continue;
        }
        String profile = text(f, "profile");
        if (view != null && view.equals(profile)) {
          return f;
        }
        if (profile == null && fallback == null) {
          fallback = f;
        }
      }
      if (fallback != null) {
        return fallback;
      }
    }
    return null;
  }

  /** The keys of a predicate's config facts that a unit's config files set, in any view; sorted. */
  Set<String> configKeys(Unit unit, String predicate) {
    Set<String> keys = new TreeSet<>();
    for (String family : List.of("application", "bootstrap")) {
      EntityId provider = provider(unit, family);
      if (provider == null) {
        continue;
      }
      for (Fact f : config.getOrDefault(provider, List.of())) {
        String key = text(f, "key");
        if (f.predicate().equals(predicate)
            && key != null
            && family.equals(DeployableUnits.configFamily(f))) {
          keys.add(key);
        }
      }
    }
    return keys;
  }

  /** The first of a unit's modules with config files of a family, or null. */
  private EntityId provider(Unit unit, String family) {
    for (EntityId module : unit.modules()) {
      for (Fact f : config.getOrDefault(module, List.of())) {
        if (f.predicate().equals(ConfigExtractor.CONFIG_VALUE)
            && family.equals(DeployableUnits.configFamily(f))) {
          return module;
        }
      }
    }
    return null;
  }

  /**
   * Whether code on a type or method is active in a view: every {@code @Profile} gate on it, and on
   * the type declaring it, matches the view's profile (the default view has the {@code default}
   * profile active, as in Spring). A gate Spring could not read counts as matching.
   */
  boolean active(EntityId code, String view) {
    String active = view == null ? "default" : view;
    List<Fact> on = new ArrayList<>(gates.getOrDefault(code, List.of()));
    EntityId type = typeOf(code);
    if (!type.equals(code)) {
      on.addAll(gates.getOrDefault(type, List.of()));
    }
    for (Fact gate : on) {
      try {
        if (!Profiles.of(gate.object().value()).matches(active::equals)) {
          return false;
        }
      } catch (IllegalArgumentException e) {
        // Spring would refuse the expression at startup; it does not decide the link.
      }
    }
    return true;
  }

  // ---------------------------------------------------------------------------------------------
  // Facts per view

  /**
   * The facts of one place in the code or config that hold in a view: for each site (facts with the
   * same site key, the default view's and each profile's that differs from it), the view's own
   * fact, else the default one; sites with neither are left out. Sorted by site key.
   */
  static Map<String, Fact> inView(Map<String, List<Fact>> sites, String view) {
    Map<String, Fact> result = new TreeMap<>();
    for (Map.Entry<String, List<Fact>> site : sites.entrySet()) {
      Fact own = null;
      Fact fallback = null;
      for (Fact f : site.getValue()) {
        String profile = text(f, "profile");
        if (view != null && view.equals(profile) && own == null) {
          own = f;
        } else if (profile == null && fallback == null) {
          fallback = f;
        }
      }
      Fact chosen = own != null ? own : fallback;
      if (chosen != null) {
        result.put(site.getKey(), chosen);
      }
    }
    return result;
  }

  /**
   * Facts grouped by the place in the code they come from: their subject, their evidence, and what
   * was written there ({@code written}, else the object), so a profile's fact joins the default one
   * it differs from.
   */
  static Map<String, List<Fact>> codeSites(List<Fact> facts) {
    Map<String, List<Fact>> sites = new TreeMap<>();
    for (Fact f : facts) {
      String written = text(f, "written");
      String key =
          f.subject()
              + "|"
              + f.evidence().repo()
              + "|"
              + f.evidence().path()
              + "|"
              + (f.evidence().lines() == null ? "" : f.evidence().lines().start())
              + "|"
              + (written != null ? written : f.object().value());
      sites.computeIfAbsent(key, k -> new ArrayList<>()).add(f);
    }
    return sites;
  }

  static String text(Fact fact, String attribute) {
    JsonNode value = fact.attributes().get(attribute);
    return value == null || !value.isString() ? null : value.stringValue();
  }

  static boolean flag(Fact fact, String attribute) {
    return fact.attributes().path(attribute).asBoolean(false);
  }

  /** The strings of an array attribute, or none. */
  static List<String> texts(Fact fact, String attribute) {
    JsonNode value = fact.attributes().get(attribute);
    List<String> result = new ArrayList<>();
    if (value != null && value.isArray()) {
      value.forEach(v -> result.add(v.asString()));
    }
    return result;
  }
}

package io.lantern.link;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactObject;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.extract.attributes.ProfileGateAttributes;
import io.lantern.extract.attributes.StereotypeAttributes;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.maven.MavenExtractor;
import io.lantern.extract.persistence.PersistenceExtractor;
import io.lantern.extract.spring.SpringExtractor;
import io.lantern.link.attributes.BundlesAttributes;
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

/**
 * The facts of a snapshot that the cross-repo linker reads, indexed: the deployable units with
 * their modules and names, where code and tables are declared, the units' config, the profiles and
 * the profile gates. See {@code docs/adr/0042-cross-repo-links.md}.
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

  /** The callers of each method, from {@code CALLS} facts; built on first use. */
  private Map<EntityId, Set<EntityId>> callers;

  /** Each type's direct supertypes and subtypes, from {@code EXTENDS} and {@code IMPLEMENTS}. */
  private Map<EntityId, Set<EntityId>> supertypes;

  private Map<EntityId, Set<EntityId>> subtypes;

  /** Every fact by predicate, in ID order. */
  private final Map<String, List<Fact>> byPredicate = new HashMap<>();

  /** The profiles the snapshot's facts name, sorted. */
  final List<String> profiles;

  private final Map<String, Fact> byId = new HashMap<>();

  LinkIndex(List<Fact> facts) {
    Map<EntityId, Fact> containerOf = new HashMap<>();
    Set<String> profileNames = new TreeSet<>();
    List<Fact> sorted = new ArrayList<>(facts);
    sorted.sort(BY_ID);
    for (Fact f : sorted) {
      byId.putIfAbsent(f.id(), f);
      byPredicate.computeIfAbsent(f.predicate(), p -> new ArrayList<>()).add(f);
      String profile = Claims.profile(f);
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
          List<String> named = Claims.of(f, ProfileGateAttributes.class).profiles();
          if (named != null) {
            // As written, so "!local" or "a & b": the profiles are the names in the expression.
            for (String p : named) {
              for (String name : p.split("[!&|()\\s]+")) {
                if (!name.isEmpty()) {
                  profileNames.add(name);
                }
              }
            }
          }
        }
        case SpringExtractor.STEREOTYPE -> {
          if ("controller".equals(f.object().value())
              && Boolean.TRUE.equals(Claims.of(f, StereotypeAttributes.class).rest())) {
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
          Comparator.comparingInt(
                  (Fact b) -> {
                    List<String> via = Claims.of(b, BundlesAttributes.class).via();
                    return via == null ? 0 : via.size();
                  })
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

  /** A fact by its ID, or null. */
  Fact fact(String id) {
    return id == null ? null : byId.get(id);
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

  /**
   * The units that run an entity's code and call it: of the units running it ({@link #unitsOf}),
   * those whose own module declares it, or that run other code calling it, directly or through
   * other methods ({@code CALLS}). A call may point at the method as an interface or a supertype
   * declares it, or as a subtype inherits it, so each method's callers include those of the same
   * signature on its type's supertypes and subtypes ({@code EXTENDS}, {@code IMPLEMENTS}). Library
   * code bundled by several units thus belongs to the units that use it. When no unit calls it (it
   * runs on its own, from a listener or a schedule, say), or only one unit runs it, every unit
   * running it does.
   */
  List<Unit> unitsCalling(EntityId entity) {
    List<Unit> running = unitsOf(entity);
    if (running.size() < 2) {
      return running;
    }
    if (callers == null) {
      callers = new HashMap<>();
      for (Fact call : of(BuiltInTypes.CALLS)) {
        if (call.object().kind() == FactObject.Kind.ENTITY) {
          callers
              .computeIfAbsent(call.object().entityId(), o -> new LinkedHashSet<>())
              .add(call.subject());
        }
      }
      supertypes = new HashMap<>();
      subtypes = new HashMap<>();
      for (String relation : List.of(BuiltInTypes.EXTENDS, BuiltInTypes.IMPLEMENTS)) {
        for (Fact f : of(relation)) {
          if (f.object().kind() == FactObject.Kind.ENTITY) {
            supertypes
                .computeIfAbsent(f.subject(), t -> new LinkedHashSet<>())
                .add(f.object().entityId());
            subtypes
                .computeIfAbsent(f.object().entityId(), t -> new LinkedHashSet<>())
                .add(f.subject());
          }
        }
      }
    }
    Set<EntityId> declaring = new HashSet<>(modulesOf(entity));
    Set<EntityId> callerModules = new HashSet<>();
    Set<EntityId> seen = new HashSet<>(Set.of(entity));
    Deque<EntityId> queue = new ArrayDeque<>(List.of(entity));
    while (!queue.isEmpty()) {
      EntityId current = queue.poll();
      for (EntityId target : dispatchedAs(current)) {
        for (EntityId caller : callers.getOrDefault(target, Set.of())) {
          if (seen.add(caller)) {
            queue.add(caller);
            callerModules.addAll(modulesOf(caller));
          }
        }
      }
    }
    callerModules.removeAll(declaring);
    List<Unit> calling =
        running.stream()
            .filter(
                u ->
                    (!u.modules().isEmpty() && declaring.contains(u.modules().getFirst()))
                        || u.modules().stream().anyMatch(callerModules::contains))
            .toList();
    return calling.isEmpty() ? running : calling;
  }

  /**
   * The methods a call may point at to run this one: itself, and the method of the same signature
   * on each supertype and subtype of its type, through {@code EXTENDS} and {@code IMPLEMENTS}.
   */
  private Set<EntityId> dispatchedAs(EntityId method) {
    Set<EntityId> result = new LinkedHashSet<>(List.of(method));
    int hash = method.key().indexOf('#');
    if (hash < 0) {
      return result;
    }
    String signature = method.key().substring(hash);
    EntityId own = typeOf(method);
    for (Map<EntityId, Set<EntityId>> relation : List.of(supertypes, subtypes)) {
      Set<EntityId> seen = new HashSet<>(Set.of(own));
      Deque<EntityId> todo = new ArrayDeque<>(List.of(own));
      while (!todo.isEmpty()) {
        for (EntityId next : relation.getOrDefault(todo.poll(), Set.of())) {
          if (seen.add(next)) {
            todo.add(next);
            result.add(new EntityId(method.type(), next.key() + signature));
          }
        }
      }
    }
    return result;
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
    Map<String, Fact> byProfile = names.get(unit);
    if (byProfile == null) {
      return null;
    }
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
    for (String family : FAMILIES) {
      for (EntityId provider : providers(unit, family)) {
        Fact fallback = null;
        for (Fact f : config.getOrDefault(provider, List.of())) {
          if (!f.predicate().equals(predicate)
              || !key.equals(Claims.key(f))
              || !family.equals(DeployableUnits.configFamily(f))) {
            continue;
          }
          String profile = Claims.profile(f);
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
    }
    return null;
  }

  /**
   * What a unit's endpoints are served behind in a view: its {@code server.servlet.context-path}
   * and {@code spring.mvc.servlet.path} joined, {@code ""} for none.
   */
  String servletPrefix(Unit unit, String view) {
    String prefix = "";
    for (String key : List.of("server.servlet.context-path", "spring.mvc.servlet.path")) {
      Fact value = config(unit, view, ConfigExtractor.CONFIG_VALUE, key);
      if (value != null && !Claims.masked(value)) {
        prefix = LinkText.join(prefix, value.object().value());
      }
    }
    return prefix;
  }

  /** The keys of a predicate's config facts that a unit's config files set, in any view; sorted. */
  Set<String> configKeys(Unit unit, String predicate) {
    Set<String> keys = new TreeSet<>();
    for (String family : FAMILIES) {
      for (EntityId provider : providers(unit, family)) {
        for (Fact f : config.getOrDefault(provider, List.of())) {
          String key = Claims.key(f);
          if (f.predicate().equals(predicate)
              && key != null
              && family.equals(DeployableUnits.configFamily(f))) {
            keys.add(key);
          }
        }
      }
    }
    return keys;
  }

  /**
   * The config families a unit sees, highest precedence first: the {@code application} and {@code
   * bootstrap} files, then the defaults that libraries add in code (ADR 0032, amendment for #333).
   */
  private static final List<String> FAMILIES =
      List.of("application", "bootstrap", DeployableUnits.DEFAULTS);

  /**
   * The modules whose config of a family a unit sees, in the order it wins: for the files, the
   * first module with any (Spring Boot loads them from the first on the classpath); for the
   * defaults, every module that adds some, since each bundled library adds its own.
   */
  private List<EntityId> providers(Unit unit, String family) {
    List<EntityId> providers = new ArrayList<>();
    for (EntityId module : unit.modules()) {
      for (Fact f : config.getOrDefault(module, List.of())) {
        if (f.predicate().equals(ConfigExtractor.CONFIG_VALUE)
            && family.equals(DeployableUnits.configFamily(f))) {
          providers.add(module);
          break;
        }
      }
      if (!providers.isEmpty() && !family.equals(DeployableUnits.DEFAULTS)) {
        break;
      }
    }
    return providers;
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
        String profile = Claims.profile(f);
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
      String written = Claims.written(f);
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
}

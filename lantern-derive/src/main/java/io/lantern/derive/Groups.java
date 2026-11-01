package io.lantern.derive;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.core.vocabulary.VocabularyType;
import io.lantern.link.LinkRule;
import io.lantern.link.RuleInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The groups of the architecture model (#535; knowledge-build epic, section 9, Grouping): related
 * elements bundled by what the code shows, so a large system or service can be drawn a level
 * higher. Membership comes from explicit signals only; the LLM never decides it.
 *
 * <ul>
 *   <li><b>Feature areas</b> group a unit's components: by feature folders when the unit is laid
 *       out by feature, else by the resource segment of their endpoints' paths; then a component
 *       neither placed joins the area it calls or is called by most, else the area whose tables it
 *       shares.
 *   <li><b>Domains</b> group the system's units: by a shared relational database, else a shared
 *       gateway route prefix, else a shared repository, else a shared segment of their Maven
 *       groups.
 * </ul>
 *
 * <p>A group is {@code group:<scope>/<kind>/<signal>}, keyed on its signal and never its members,
 * so adding a member renames none. It has a {@code group-of} fact naming its kind, scope and basis
 * and {@code GROUPS} (a kind of {@code CONTAINS}) each member, with how it joined. A group has at
 * least 2 members and a scope stores a kind only when it yields at least 2 groups of it; an element
 * is in at most one group of each kind. Lines between groups are not stored: they follow from the
 * groups and the member lines ({@link #lines}).
 */
public final class Groups implements LinkRule.OverFacts {

  public static final String NAME = "groups";

  /** Raised whenever the groups it gives for the same facts change. */
  public static final String VERSION = "1";

  public static final String GROUP = "group";
  public static final String GROUP_OF = "group-of";
  public static final String GROUPS = "GROUPS";

  public static final String FEATURE_AREA = "feature-area";
  public static final String DOMAIN = "domain";

  /** The bases of a group, and of a member joining one. */
  public static final String FEATURE_FOLDER = "feature-folder";

  public static final String RESOURCE_PATH = "resource-path";
  public static final String CALLS = "calls";
  public static final String TABLES = "tables";
  public static final String DATABASE = "database";
  public static final String GATEWAY_ROUTE = "gateway-route";
  public static final String REPOSITORY = "repository";
  public static final String MAVEN_GROUP = "maven-group";

  /** The scope of the system's domains: derivation runs on one system's snapshot. */
  public static final String SYSTEM = "system";

  /** Package names that name a layer, never a feature. */
  static final Set<String> LAYERS =
      Set.of(
          "web",
          "controller",
          "controllers",
          "rest",
          "api",
          "service",
          "services",
          "repository",
          "repositories",
          "persistence",
          "data",
          "domain",
          "model",
          "dto",
          "entity",
          "config",
          "configuration",
          "messaging",
          "jobs",
          "integration",
          "client",
          "clients",
          "common",
          "util",
          "support",
          "infra",
          "infrastructure",
          "adapter",
          "adapters");

  /** How many sibling feature packages with an entry point make a unit laid out by feature. */
  static final int FEATURE_PACKAGES = 3;

  /** The least Jaccard similarity of two table sets for a component to join by tables. */
  static final double TABLE_SIMILARITY = 0.5;

  private static final Pattern VERSION_SEGMENT = Pattern.compile("v\\d+");
  private static final Set<String> RELATIONAL = Set.of("JDBC", "R2DBC");

  private static final String DEPLOYABLE_UNIT = "deployable-unit";
  private static final String ENDPOINT = "endpoint";
  private static final String USES_DATABASE = "USES_DATABASE";
  private static final String ROUTED_ENDPOINT = "routed-endpoint";
  private static final String METHOD = "method";

  private static final Origin ORIGIN = new Origin(Origin.Kind.STATIC, NAME, VERSION);

  @Override
  public RuleInfo info() {
    return new RuleInfo(NAME, VERSION, Origin.Kind.STATIC);
  }

  @Override
  public Set<String> reads() {
    return Set.of(
        EXTRACTED,
        LINKED,
        Components.PART_OF,
        Components.ANCHORED_ON,
        Components.HAS_MEMBER,
        ComponentInteractions.COMPONENT_LINK,
        ComponentInteractions.DATA_ACCESS);
  }

  @Override
  public Set<String> writes() {
    return Set.of(GROUP_OF, GROUPS);
  }

  /** The vocabulary types of the groups' facts besides the built-in ones. */
  public static List<VocabularyType> vocabulary() {
    return Derivation.load("/vocabulary/groups-types.json");
  }

  /** The group of a kind in a scope (a unit, or {@link #SYSTEM}) with a signal. */
  public static EntityId id(String scope, String kind, String signal) {
    String segment = kind.equals(FEATURE_AREA) ? "area" : kind;
    return new EntityId(
        GROUP, scope + "/" + segment + "/" + DeploymentInstances.encoded(signal, "/"));
  }

  @Override
  public List<Fact> derive(List<Fact> facts, long snapshot) {
    Knowledge knowledge = new Knowledge(facts);
    Validity validity = Validity.from(snapshot);
    List<Fact> derived = new ArrayList<>();
    SortedSet<EntityId> units = new TreeSet<>(Knowledge.BY_ID);
    knowledge.withPredicate(DEPLOYABLE_UNIT).forEach(f -> units.add(f.subject()));
    for (EntityId unit : units) {
      write(featureAreas(knowledge, unit), FEATURE_AREA, unit.toString(), validity, derived);
    }
    write(domains(knowledge, units, units), DOMAIN, SYSTEM, validity, derived);
    return Derivation.merged(derived);
  }

  // Groups being formed.

  /** A group being formed: its signal and basis, and its members with the facts they joined by. */
  static final class Forming {
    final String signal;
    final String basis;
    final SortedMap<EntityId, Joined> members = new TreeMap<>(Knowledge.BY_ID);

    Forming(String signal, String basis) {
      this.signal = signal;
      this.basis = basis;
    }

    void join(EntityId member, String how, Collection<Fact> facts) {
      members.computeIfAbsent(member, m -> new Joined(how, new ArrayList<>())).facts.addAll(facts);
    }
  }

  /** How a member joined its group, and the facts saying so. */
  record Joined(String how, List<Fact> facts) {}

  /**
   * The groups of a scope as stored: those of at least 2 members, and none when fewer than 2 such
   * groups remain.
   */
  private static void write(
      SortedMap<String, Forming> groups,
      String kind,
      String scope,
      Validity validity,
      List<Fact> derived) {
    List<Forming> kept = groups.values().stream().filter(g -> g.members.size() >= 2).toList();
    if (kept.size() < 2) {
      return;
    }
    for (Forming group : kept) {
      EntityId id = id(scope, kind, group.signal);
      List<Fact> cited = new ArrayList<>();
      group.members.values().forEach(j -> cited.addAll(j.facts));
      if (cited.isEmpty() || group.members.values().stream().anyMatch(j -> j.facts.isEmpty())) {
        continue;
      }
      ObjectNode of = FactJson.newObject();
      of.put("scope", scope);
      of.put("basis", group.basis);
      of.put("signal", group.signal);
      cite(of, cited);
      derived.add(
          Fact.create(
              id,
              GROUP_OF,
              FactObject.string(kind),
              of,
              ORIGIN,
              cited.getFirst().evidence(),
              1.0,
              validity));
      group.members.forEach(
          (member, joined) -> {
            ObjectNode in = FactJson.newObject();
            in.put("basis", joined.how());
            cite(in, joined.facts());
            derived.add(
                Fact.create(
                    id,
                    GROUPS,
                    FactObject.entity(member),
                    in,
                    ORIGIN,
                    joined.facts().getFirst().evidence(),
                    1.0,
                    validity));
          });
    }
  }

  private static void cite(ObjectNode attributes, List<Fact> facts) {
    ArrayNode ids = attributes.putArray("facts");
    new LinkedHashSet<>(facts).forEach(f -> ids.add(f.id()));
  }

  // Feature areas.

  /** A component of a unit: its ID, role, anchor and the facts giving it. */
  private record Component(EntityId id, String role, EntityId anchor, Fact partOf) {}

  private static List<Component> components(Knowledge knowledge, EntityId unit) {
    List<Component> components = new ArrayList<>();
    for (Fact fact : knowledge.withPredicate(Components.PART_OF)) {
      if (!Knowledge.isEntity(fact) || !Knowledge.object(fact).equals(unit)) {
        continue;
      }
      EntityId id = fact.subject();
      List<Fact> anchored = knowledge.relations(id, Components.ANCHORED_ON);
      if (anchored.isEmpty()) {
        continue;
      }
      components.add(
          new Component(
              id, attribute(fact, "role", ""), Knowledge.object(anchored.getFirst()), fact));
    }
    components.sort((a, b) -> Knowledge.BY_ID.compare(a.id(), b.id()));
    return components;
  }

  static SortedMap<String, Forming> featureAreas(Knowledge knowledge, EntityId unit) {
    List<Component> components = components(knowledge, unit);
    SortedMap<String, Forming> areas = new TreeMap<>();
    Map<EntityId, String> placed = new HashMap<>();
    if (!byFeatureFolder(knowledge, components, areas, placed)) {
      byResourcePath(knowledge, components, areas, placed);
    }
    if (areas.isEmpty()) {
      return areas;
    }
    byCallsOrTables(knowledge, components, areas, placed, ownCode(knowledge, unit));
    return areas;
  }

  /**
   * Whether a component cuts across the unit's areas, so rule 3 never joins it to one: an aspect,
   * which advises whatever its pointcut selects, or a class from a library the unit bundles rather
   * than its own modules, which every unit bundling it shares.
   */
  private static boolean crossCutting(Component component, Set<EntityId> own) {
    return component.role().equals(Components.ASPECT_ROLE) || !own.contains(component.anchor());
  }

  /** The classes of the unit's own modules, without the libraries it bundles. */
  private static Set<EntityId> ownCode(Knowledge knowledge, EntityId unit) {
    Set<EntityId> own = new HashSet<>();
    for (EntityId module : knowledge.parts(unit, BuiltInTypes.MODULE)) {
      own.addAll(knowledge.typesWithin(module));
      for (EntityId pkg : knowledge.parts(module, BuiltInTypes.PACKAGE)) {
        own.addAll(knowledge.typesWithin(pkg));
      }
    }
    return own;
  }

  /** The package a class is in, by name, or null. */
  private static String packageOf(Knowledge knowledge, EntityId type) {
    EntityId at = type;
    for (int depth = 0; depth < 8 && at != null; depth++) {
      SortedSet<EntityId> packages = knowledge.wholes(at, BuiltInTypes.PACKAGE);
      if (!packages.isEmpty()) {
        String key = packages.first().key();
        return key.substring(key.lastIndexOf(':') + 1);
      }
      SortedSet<EntityId> outer = knowledge.wholes(at, BuiltInTypes.TYPE);
      at = outer.isEmpty() ? null : outer.first();
    }
    return null;
  }

  /**
   * Rule 1: below the common base package of the components' anchors (and below a {@code feature}
   * or {@code features} package, if there is one), at least {@value #FEATURE_PACKAGES} sibling
   * packages hold an entry point and none is named as a layer: each is an area holding the
   * components anchored in it.
   */
  private static boolean byFeatureFolder(
      Knowledge knowledge,
      List<Component> components,
      SortedMap<String, Forming> areas,
      Map<EntityId, String> placed) {
    Map<Component, List<String>> segments = new HashMap<>();
    for (Component component : components) {
      if (!component.anchor().type().equals(BuiltInTypes.TYPE)) {
        continue;
      }
      String pkg = packageOf(knowledge, component.anchor());
      if (pkg != null) {
        segments.put(component, Arrays.asList(pkg.split("\\.")));
      }
    }
    if (segments.isEmpty()) {
      return false;
    }
    // The base is the package all entry points are below: a shared bean or client elsewhere
    // (a library's package, say) does not move it.
    List<String> base = null;
    for (Map.Entry<Component, List<String>> e : segments.entrySet()) {
      if (e.getKey().role().equals(Components.ENTRY_POINT)) {
        base = base == null ? e.getValue() : common(base, e.getValue());
      }
    }
    if (base == null) {
      return false;
    }
    int depth = base.size();
    final List<String> root = base;
    // Below a feature package, if the code has one.
    Set<String> next = new TreeSet<>();
    segments.values().stream()
        .filter(s -> s.size() > depth && s.subList(0, depth).equals(root))
        .forEach(s -> next.add(s.get(depth)));
    int below =
        next.stream().anyMatch(n -> n.equals("feature") || n.equals("features"))
            ? depth + 1
            : depth;
    SortedMap<String, List<Component>> byPackage = new TreeMap<>();
    segments.forEach(
        (component, s) -> {
          boolean under =
              s.size() > depth
                  && s.subList(0, depth).equals(root)
                  && (below == depth || isFeature(s.get(depth)));
          if (under && s.size() > below) {
            byPackage.computeIfAbsent(s.get(below), p -> new ArrayList<>()).add(component);
          }
        });
    // Packages named as a layer are not features; their components are left to rule 3.
    SortedSet<String> withEntryPoints = new TreeSet<>();
    byPackage.forEach(
        (pkg, held) -> {
          if (!LAYERS.contains(pkg)
              && held.stream().anyMatch(c -> c.role().equals(Components.ENTRY_POINT))) {
            withEntryPoints.add(pkg);
          }
        });
    if (withEntryPoints.size() < FEATURE_PACKAGES) {
      return false;
    }
    for (String pkg : withEntryPoints) {
      Forming area = new Forming("pkg-" + pkg, FEATURE_FOLDER);
      for (Component component : byPackage.get(pkg)) {
        area.join(component.id(), FEATURE_FOLDER, List.of(component.partOf()));
        placed.put(component.id(), area.signal);
      }
      areas.put(area.signal, area);
    }
    return true;
  }

  private static boolean isFeature(String segment) {
    return segment.equals("feature") || segment.equals("features");
  }

  private static List<String> common(List<String> a, List<String> b) {
    int n = 0;
    while (n < a.size() && n < b.size() && a.get(n).equals(b.get(n))) {
      n++;
    }
    return a.subList(0, n);
  }

  /**
   * Rule 2: an endpoint's resource segment is the first literal segment after the longest prefix
   * all the unit's endpoints share, skipping path variables, {@code api} and versions; for an
   * endpoint with none left, the last literal segment of the prefix. A component joins the segment
   * most of its endpoints hold, ties to the one that sorts first.
   */
  private static void byResourcePath(
      Knowledge knowledge,
      List<Component> components,
      SortedMap<String, Forming> areas,
      Map<EntityId, String> placed) {
    Map<Component, List<Fact>> endpoints = new HashMap<>();
    List<String> prefix = null;
    for (Component component : components) {
      if (!component.anchor().type().equals(BuiltInTypes.TYPE)) {
        continue;
      }
      List<Fact> found = new ArrayList<>();
      Set<String> seen = new HashSet<>();
      for (EntityId method : knowledge.parts(component.anchor(), METHOD)) {
        for (Fact fact : knowledge.attributes(method)) {
          // An endpoint seen in several views counts once.
          if (fact.predicate().equals(ENDPOINT) && seen.add(method + " " + fact.object().value())) {
            found.add(fact);
            List<String> path = path(fact.object().value());
            prefix = prefix == null ? path : common(prefix, path);
          }
        }
      }
      if (!found.isEmpty()) {
        endpoints.put(component, found);
      }
    }
    if (prefix == null) {
      return;
    }
    for (Component component : components) {
      List<Fact> found = endpoints.get(component);
      if (found == null) {
        continue;
      }
      SortedMap<String, List<Fact>> bySegment = new TreeMap<>();
      for (Fact fact : found) {
        String segment = resource(path(fact.object().value()), prefix);
        if (segment != null) {
          bySegment.computeIfAbsent(segment, s -> new ArrayList<>()).add(fact);
        }
      }
      String best = null;
      for (Map.Entry<String, List<Fact>> e : bySegment.entrySet()) {
        if (best == null || e.getValue().size() > bySegment.get(best).size()) {
          best = e.getKey();
        }
      }
      if (best == null) {
        continue;
      }
      String signal = "path-" + best;
      Forming area = areas.computeIfAbsent(signal, s -> new Forming(s, RESOURCE_PATH));
      List<Fact> cited = new ArrayList<>(bySegment.get(best));
      area.join(component.id(), RESOURCE_PATH, cited);
      placed.put(component.id(), signal);
    }
  }

  /** The segments of an endpoint's path: {@code GET /a/b} gives a, b. */
  static List<String> path(String endpoint) {
    String path = endpoint.contains(" ") ? endpoint.substring(endpoint.indexOf(' ') + 1) : endpoint;
    int query = path.indexOf('?');
    if (query >= 0) {
      path = path.substring(0, query);
    }
    List<String> segments = new ArrayList<>();
    for (String s : path.split("/")) {
      if (!s.isBlank()) {
        segments.add(s);
      }
    }
    return segments;
  }

  /** The resource segment of a path after the prefix all share, or null. */
  static String resource(List<String> path, List<String> prefix) {
    for (String s : path.subList(prefix.size(), path.size())) {
      if (literal(s)) {
        return s;
      }
    }
    for (int i = prefix.size() - 1; i >= 0; i--) {
      if (literal(prefix.get(i))) {
        return prefix.get(i);
      }
    }
    return null;
  }

  /** Whether a path segment names a resource: no variable, {@code api} or version. */
  static boolean literal(String segment) {
    return !segment.contains("{")
        && !segment.contains("*")
        && !segment.equals("api")
        && !VERSION_SEGMENT.matcher(segment).matches();
  }

  /**
   * Rule 3: a component neither rule placed joins the area whose components it calls, or is called
   * by, most often; failing that, the area whose tables it shares, at a Jaccard similarity of at
   * least {@value #TABLE_SIMILARITY}. Ties go to the area whose key sorts first. It runs in rounds,
   * each against the placements of the rounds before, until a round places none, so a component
   * linked only to one placed in an earlier round joins too and the order components are looked at
   * does not matter. A cross-cutting component never joins by this rule (#638), though one placed
   * by rule 1 or 2 counts for its area.
   */
  private static void byCallsOrTables(
      Knowledge knowledge,
      List<Component> components,
      SortedMap<String, Forming> areas,
      Map<EntityId, String> placed,
      Set<EntityId> ownCode) {
    Map<EntityId, Set<String>> tables = new HashMap<>();
    Map<EntityId, List<Fact>> links = new HashMap<>();
    Set<EntityId> ids = new HashSet<>();
    components.forEach(c -> ids.add(c.id()));
    for (Fact fact : knowledge.withPredicate(ComponentInteractions.COMPONENT_LINK)) {
      if (Knowledge.isEntity(fact)
          && ids.contains(fact.subject())
          && ids.contains(Knowledge.object(fact))) {
        links.computeIfAbsent(fact.subject(), c -> new ArrayList<>()).add(fact);
        links.computeIfAbsent(Knowledge.object(fact), c -> new ArrayList<>()).add(fact);
      }
    }
    for (Component component : components) {
      tables.put(component.id(), tables(knowledge, component.id()));
    }
    while (true) {
      Map<String, Set<String>> areaTables = new TreeMap<>();
      placed.forEach(
          (component, area) ->
              areaTables.computeIfAbsent(area, a -> new TreeSet<>()).addAll(tables.get(component)));
      Map<EntityId, String> joins = new TreeMap<>(Knowledge.BY_ID);
      Map<EntityId, List<Fact>> because = new HashMap<>();
      Map<EntityId, String> how = new HashMap<>();
      for (Component component : components) {
        EntityId id = component.id();
        if (placed.containsKey(id) || crossCutting(component, ownCode)) {
          continue;
        }
        SortedMap<String, List<Fact>> calls = new TreeMap<>();
        for (Fact fact : links.getOrDefault(id, List.of())) {
          EntityId other = fact.subject().equals(id) ? Knowledge.object(fact) : fact.subject();
          String area = placed.get(other);
          if (area != null) {
            calls.computeIfAbsent(area, a -> new ArrayList<>()).add(fact);
          }
        }
        String best = null;
        for (Map.Entry<String, List<Fact>> e : calls.entrySet()) {
          if (best == null || e.getValue().size() > calls.get(best).size()) {
            best = e.getKey();
          }
        }
        if (best != null) {
          joins.put(id, best);
          because.put(id, calls.get(best));
          how.put(id, CALLS);
          continue;
        }
        Set<String> own = tables.get(id);
        if (own.isEmpty()) {
          continue;
        }
        double most = TABLE_SIMILARITY;
        for (Map.Entry<String, Set<String>> e : areaTables.entrySet()) {
          double similarity = jaccard(own, e.getValue());
          if (similarity >= most && (best == null || similarity > most)) {
            best = e.getKey();
            most = similarity;
          }
        }
        if (best != null) {
          joins.put(id, best);
          because.put(id, dataAccess(knowledge, id));
          how.put(id, TABLES);
        }
      }
      if (joins.isEmpty()) {
        return;
      }
      joins.forEach(
          (component, area) -> {
            areas.get(area).join(component, how.get(component), because.get(component));
            placed.put(component, area);
          });
    }
  }

  private static List<Fact> dataAccess(Knowledge knowledge, EntityId component) {
    return knowledge.attributes(component).stream()
        .filter(f -> f.predicate().equals(ComponentInteractions.DATA_ACCESS))
        .toList();
  }

  /** The tables (or persisted classes) a component's data access rests on. */
  private static Set<String> tables(Knowledge knowledge, EntityId component) {
    Set<String> tables = new TreeSet<>();
    for (Fact access : dataAccess(knowledge, component)) {
      for (String id : strings(access, "facts")) {
        Fact fact = knowledge.fact(id);
        if (fact != null && Knowledge.isEntity(fact)) {
          tables.add(Knowledge.object(fact).toString());
        }
      }
    }
    return tables;
  }

  static double jaccard(Set<String> a, Set<String> b) {
    Set<String> union = new HashSet<>(a);
    union.addAll(b);
    if (union.isEmpty()) {
      return 0;
    }
    Set<String> both = new HashSet<>(a);
    both.retainAll(b);
    return (double) both.size() / union.size();
  }

  // Domains.

  static SortedMap<String, Forming> domains(
      Knowledge knowledge, SortedSet<EntityId> units, Set<EntityId> system) {
    SortedMap<String, Forming> domains = new TreeMap<>();
    Set<EntityId> left = new TreeSet<>(Knowledge.BY_ID);
    left.addAll(units);
    stage(domains, left, byDatabase(knowledge, left));
    stage(domains, left, byRoute(knowledge, left));
    stage(domains, left, byRepository(knowledge, left));
    stage(domains, left, byMavenGroup(knowledge, left, system));
    return domains;
  }

  /** Keeps a signal's groups of at least 2 units; their units are grouped. */
  private static void stage(
      SortedMap<String, Forming> domains, Set<EntityId> left, SortedMap<String, Forming> found) {
    found.forEach(
        (signal, group) -> {
          if (group.members.size() >= 2) {
            domains.put(signal, group);
            left.removeAll(group.members.keySet());
          }
        });
  }

  /**
   * Signal 1: units connected to one relational database (JDBC or R2DBC). A unit with several joins
   * the one whose datasource lists the most of its schemas, ties by key.
   */
  private static SortedMap<String, Forming> byDatabase(Knowledge knowledge, Set<EntityId> units) {
    SortedMap<String, Forming> found = new TreeMap<>();
    for (EntityId unit : units) {
      Fact best = null;
      for (Fact fact : knowledge.relations(unit, USES_DATABASE)) {
        // The default view's datasources only: a profile's (local, test) is not where it keeps its
        // data.
        if (!RELATIONAL.contains(attribute(fact, "protocol", ""))
            || attribute(fact, "view", null) != null
            || attribute(fact, "profile", null) != null) {
          continue;
        }
        if (best == null
            || schemas(fact) > schemas(best)
            || schemas(fact) == schemas(best)
                && Knowledge.BY_ID.compare(Knowledge.object(fact), Knowledge.object(best)) < 0) {
          best = fact;
        }
      }
      if (best != null) {
        String signal = "db-" + Knowledge.object(best).key();
        found
            .computeIfAbsent(signal, s -> new Forming(s, DATABASE))
            .join(unit, DATABASE, List.of(best));
      }
    }
    return found;
  }

  private static int schemas(Fact usesDatabase) {
    return strings(usesDatabase, "schemas").size();
  }

  /**
   * Signal 2: units a gateway routes to under the same first path segment of the request, skipping
   * {@code api} and versions; a unit routed under several joins the one most of its routed
   * endpoints are under, ties to the segment that sorts first.
   */
  private static SortedMap<String, Forming> byRoute(Knowledge knowledge, Set<EntityId> units) {
    Map<EntityId, SortedMap<String, List<Fact>>> byUnit = new TreeMap<>(Knowledge.BY_ID);
    for (Fact fact : knowledge.withPredicate(ROUTED_ENDPOINT)) {
      String target = attribute(fact, "target", null);
      if (target == null) {
        continue;
      }
      EntityId unit = EntityId.parse(target);
      if (!units.contains(unit)) {
        continue;
      }
      String segment = null;
      for (String s : path(fact.object().value())) {
        if (literal(s)) {
          segment = s;
          break;
        }
      }
      if (segment != null) {
        byUnit
            .computeIfAbsent(unit, u -> new TreeMap<>())
            .computeIfAbsent(segment, s -> new ArrayList<>())
            .add(fact);
      }
    }
    SortedMap<String, Forming> found = new TreeMap<>();
    byUnit.forEach(
        (unit, segments) -> {
          String best = null;
          for (Map.Entry<String, List<Fact>> e : segments.entrySet()) {
            if (best == null || e.getValue().size() > segments.get(best).size()) {
              best = e.getKey();
            }
          }
          String signal = "route-" + best;
          found
              .computeIfAbsent(signal, s -> new Forming(s, GATEWAY_ROUTE))
              .join(unit, GATEWAY_ROUTE, segments.get(best));
        });
    return found;
  }

  /** Signal 3: units built from one repository, the repository of their own modules. */
  private static SortedMap<String, Forming> byRepository(Knowledge knowledge, Set<EntityId> units) {
    SortedMap<String, Forming> found = new TreeMap<>();
    for (EntityId unit : units) {
      SortedSet<String> repos = new TreeSet<>();
      List<Fact> facts = new ArrayList<>();
      for (EntityId module : knowledge.parts(unit, BuiltInTypes.MODULE)) {
        repos.addAll(knowledge.repos(module));
        facts.addAll(knowledge.containsFacts(unit, module));
      }
      if (repos.size() == 1) {
        String signal = "repo-" + repos.first();
        found
            .computeIfAbsent(signal, s -> new Forming(s, REPOSITORY))
            .join(unit, REPOSITORY, facts);
      }
    }
    return found;
  }

  /**
   * Signal 4: units whose Maven groupId shares the segment after the longest dotted prefix common
   * to every unit's groupId in the system. When that prefix is empty the groupIds share no
   * organization, and the next segment is a top-level domain (com, org), which names no domain: no
   * group.
   */
  private static SortedMap<String, Forming> byMavenGroup(
      Knowledge knowledge, Set<EntityId> units, Set<EntityId> system) {
    SortedMap<String, Forming> found = new TreeMap<>();
    List<String> prefix = null;
    for (EntityId unit : system) {
      List<String> g = groupId(unit);
      if (g != null) {
        prefix = prefix == null ? g : common(prefix, g);
      }
    }
    if (prefix == null || prefix.isEmpty()) {
      return found;
    }
    int depth = prefix.size();
    for (EntityId unit : units) {
      List<String> g = groupId(unit);
      if (g != null && g.size() > depth) {
        String signal = "maven-" + g.get(depth);
        found
            .computeIfAbsent(signal, s -> new Forming(s, MAVEN_GROUP))
            .join(unit, MAVEN_GROUP, deployable(knowledge, unit));
      }
    }
    return found;
  }

  /** The segments of a unit's Maven groupId, from its ID (service:group:artifact), or null. */
  private static List<String> groupId(EntityId unit) {
    int colon = unit.key().indexOf(':');
    return colon > 0 ? Arrays.asList(unit.key().substring(0, colon).split("\\.")) : null;
  }

  /** The facts that make a unit deployable: those behind a group of its Maven groupId. */
  private static List<Fact> deployable(Knowledge knowledge, EntityId unit) {
    return knowledge.attributes(unit).stream()
        .filter(f -> f.predicate().equals(DEPLOYABLE_UNIT))
        .toList();
  }

  // Reading.

  /** A stored group: its ID, kind, scope, basis and members. */
  public record Group(
      EntityId id, String kind, String scope, String basis, SortedSet<EntityId> members) {}

  /** The stored groups of a kind, by ID. */
  public static List<Group> of(Knowledge knowledge, String kind) {
    List<Group> groups = new ArrayList<>();
    SortedMap<EntityId, Fact> byId = new TreeMap<>(Knowledge.BY_ID);
    for (Fact fact : knowledge.withPredicate(GROUP_OF)) {
      if (fact.object().value().equals(kind)) {
        byId.put(fact.subject(), fact);
      }
    }
    byId.forEach(
        (id, fact) -> {
          SortedSet<EntityId> members = new TreeSet<>(Knowledge.BY_ID);
          knowledge.relations(id, GROUPS).forEach(f -> members.add(Knowledge.object(f)));
          groups.add(
              new Group(
                  id,
                  kind,
                  attribute(fact, "scope", null),
                  attribute(fact, "basis", null),
                  members));
        });
    return groups;
  }

  /** A line between two elements, by protocol, and the facts it stands for. */
  public record Line(EntityId from, EntityId to, String protocol, List<Fact> facts) {}

  /**
   * The lines between groups (#535): one per ordered pair of ends (a group, or an element no group
   * of the kind holds) and protocol, standing for the member lines and citing their facts. Lines
   * inside one group are left out.
   *
   * @param groups the groups of one kind and scope
   * @param lines the lines between their members and other elements
   */
  public static List<Line> lines(List<Group> groups, List<Line> lines) {
    Map<EntityId, EntityId> groupOf = new HashMap<>();
    groups.forEach(g -> g.members().forEach(m -> groupOf.put(m, g.id())));
    SortedMap<String, Line> merged = new TreeMap<>();
    for (Line line : lines) {
      EntityId from = groupOf.getOrDefault(line.from(), line.from());
      EntityId to = groupOf.getOrDefault(line.to(), line.to());
      if (from.equals(to)) {
        continue;
      }
      String key = from + " " + to + " " + line.protocol();
      merged
          .computeIfAbsent(key, k -> new Line(from, to, line.protocol(), new ArrayList<>()))
          .facts()
          .addAll(line.facts());
    }
    return List.copyOf(merged.values());
  }

  private static String attribute(Fact fact, String name, String otherwise) {
    tools.jackson.databind.JsonNode value = fact.attributes().get(name);
    return value == null || value.isNull() ? otherwise : value.asString();
  }

  private static List<String> strings(Fact fact, String name) {
    tools.jackson.databind.JsonNode value = fact.attributes().get(name);
    List<String> result = new ArrayList<>();
    if (value != null && value.isArray()) {
      value.forEach(v -> result.add(v.asString()));
    }
    return result;
  }
}

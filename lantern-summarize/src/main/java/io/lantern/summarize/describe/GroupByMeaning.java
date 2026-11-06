package io.lantern.summarize.describe;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.types.Types;
import io.lantern.derive.ArchitectureModel;
import io.lantern.derive.Components;
import io.lantern.derive.ElementNames;
import io.lantern.derive.Elements;
import io.lantern.derive.Groups;
import io.lantern.derive.Knowledge;
import io.lantern.derive.ScopeFlows;
import io.lantern.harness.FingerprintInput;
import io.lantern.harness.Fingerprints;
import io.lantern.harness.Harness;
import io.lantern.harness.Item;
import io.lantern.harness.LlmFacts;
import io.lantern.harness.Outcome;
import io.lantern.harness.PacketRole;
import io.lantern.harness.PromptLayout;
import io.lantern.harness.Prompts;
import io.lantern.harness.RoleSession;
import io.lantern.harness.RoleSpec;
import io.lantern.harness.RunMode;
import io.lantern.harness.checks.CommonPrompt;
import io.lantern.harness.render.Blocks;
import io.lantern.harness.render.FactLines;
import io.lantern.harness.render.Refs;
import io.lantern.harness.verify.Check;
import io.lantern.harness.verify.CheckInputs;
import io.lantern.harness.verify.CheckedText;
import io.lantern.harness.verify.Checks;
import io.lantern.harness.verify.ItemAnswer;
import io.lantern.harness.verify.JsonSchema;
import io.lantern.harness.verify.Pipeline;
import io.lantern.harness.verify.Rejection;
import io.lantern.llm.Role;
import io.lantern.llm.Tokens;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The grouping role (#919; ADR 0129): groups by meaning what the code signals left ungrouped, so
 * that the diagrams fit. One request a system, after Describe, since it reads what Describe wrote:
 * the containers with what each does, when the system has more than a view holds; and each service
 * with more components than a view holds, its components with what each does and the endpoint paths
 * they serve; and the traced flows that start there, every part by its reference.
 *
 * <p>It writes membership only: groups of basis {@value #BASIS}, stored as the code-signal groups
 * are ({@link Groups}), which Name groups then names like any other. The code's groups are fixed:
 * the model may add members to them, never move one out. Its own earlier groups are fixed too and
 * written again, so a later run asks only of what is new (docs/research/llm-grouping-by-meaning.md,
 * Stability). An answer that fails its checks twice groups nothing.
 */
public final class GroupByMeaning {

  /** The role's name, which is its facts' origin name. */
  public static final String NAME = "group-by-meaning";

  /** The prompt version, part of every answer's cache key. */
  static final String VERSION = "1";

  /** The basis of a group, and of a member joining one, the role formed. */
  public static final String BASIS = "meaning";

  /** The prefix of the signal of a group the role formed. */
  static final String SIGNAL = "llm-";

  /** The subject of the role's one item. */
  static final String SUBJECT = "grouping";

  /** The most flows an item shows. */
  static final int MAX_FLOWS = 40;

  /** The most endpoint paths an item shows of a component. */
  static final int MAX_PATHS = 5;

  static final String TASK = DescribeRole.prompt("group-by-meaning.task.txt");
  static final String SCHEMA = DescribeRole.prompt("group-by-meaning.schema.json");

  static final io.lantern.harness.Contract.Range REASON_WORDS =
      io.lantern.harness.Contract.of(NAME).acceptedWords("reason");

  /** The role: one request a system, a judgement on the reasoning tier, with thinking. */
  public static final RoleSpec ROLE =
      RoleSpec.single(
              NAME,
              Role.GROUP_BY_MEANING,
              Prompts.version(
                  VERSION,
                  "common.system.txt",
                  "group-by-meaning.task.txt",
                  "group-by-meaning.schema.json"),
              "name-groups",
              RunMode.INTERACTIVE,
              2_000)
          .withThinking(4_000);

  static final String DOMAINS = "domains";
  static final String AREAS = "areas";
  static final String LEFT_OUT = "left_out";

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final JsonNode ITEM_SCHEMA = JsonSchema.itemSchema(JSON.readTree(SCHEMA));
  private static final Set<Character> CITABLE = Set.of('E');
  private static final Pattern KEY = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
  private static final Logger LOG = LoggerFactory.getLogger(GroupByMeaning.class);

  private final Harness harness;
  private final int target;
  private final int tolerance;

  /**
   * @param target the boxes a view aims to stay within, the diagrams' target
   * @param tolerance how many past it a view may still show
   */
  public GroupByMeaning(Harness harness, int target, int tolerance) {
    this.harness = Objects.requireNonNull(harness, "harness");
    this.target = target;
    this.tolerance = tolerance;
  }

  /** With the diagrams' default limits. */
  public GroupByMeaning(Harness harness) {
    this(
        harness,
        Integer.parseInt(Groups.diagramDefault("target")),
        Integer.parseInt(Groups.diagramDefault("tolerance")));
  }

  /**
   * What a run wrote.
   *
   * @param facts the groups' facts, of origin {@code llm}: those kept from earlier runs and those
   *     the answer adds
   * @param asked whether the model was asked, or the cache answered
   * @param added how many members the answer placed
   */
  public record Result(List<Fact> facts, boolean asked, int added) {

    public Result {
      facts = List.copyOf(facts);
    }

    /** The role's facts by origin, as a run records them. */
    public Map<Origin, List<Fact>> byOrigin() {
      return Map.of(LlmFacts.origin(ROLE), facts);
    }

    /** The facts with these instead of the role's earlier ones, as the roles after it read them. */
    public List<Fact> replacing(Collection<Fact> facts) {
      List<Fact> all = new ArrayList<>();
      for (Fact fact : facts) {
        if (!(fact.origin().kind() == Origin.Kind.LLM && fact.origin().name().equals(NAME))) {
          all.add(fact);
        }
      }
      all.addAll(this.facts);
      return all;
    }
  }

  /** A part the item lists, by its reference. */
  record Part(EntityId id, String name, EntityId container, EntityId group) {

    boolean loose() {
      return group == null;
    }
  }

  /**
   * The item with what its references stand for.
   *
   * @param item the harness item
   * @param refs its references, {@code E} its parts
   * @param parts each part by its reference
   * @param domains whether the item asks for domains
   * @param groups the groups the item names, by their key in a scope (the system, or a unit)
   * @param names the parts' names, which a reason may say without citing them
   */
  record Asked(
      Item item,
      Refs refs,
      Map<String, Part> parts,
      boolean domains,
      Map<String, Map<String, EntityId>> groups,
      Set<String> names) {}

  /**
   * Groups what the code left ungrouped, where a view would otherwise hold too much.
   *
   * @param facts the facts that hold in the snapshot, the groups and Describe's responsibilities
   *     included, and the role's own earlier groups
   * @param snapshot the snapshot the facts it writes hold from
   */
  public Result group(Collection<Fact> facts, long snapshot) {
    Knowledge knowledge = new Knowledge(facts);
    List<Fact> kept = kept(knowledge, snapshot);
    // Read with the kept groups only, so a group that lost its members frees them.
    List<Fact> current = new ArrayList<>();
    for (Fact fact : facts) {
      if (!(fact.origin().kind() == Origin.Kind.LLM && fact.origin().name().equals(NAME))) {
        current.add(fact);
      }
    }
    current.addAll(kept);
    Knowledge now = new Knowledge(current);
    Optional<Asked> asked = item(now, current);
    if (asked.isEmpty()) {
      return new Result(kept, false, 0);
    }
    RoleSession session = harness.session(ROLE);
    Item item = asked.get().item();
    session.plan(List.of(item), Tokens.estimate(CommonPrompt.SYSTEM + task()));
    Outcome outcome = session.askPackets(List.of(item), new OneItem(asked.get())).get(SUBJECT);
    if (outcome == null
        || !(outcome.kind() == Outcome.Kind.CACHED || outcome.kind() == Outcome.Kind.ANSWERED)) {
      LOG.info(
          "Group by meaning: nothing grouped, {}",
          outcome == null ? "not asked" : outcome.kind() + " " + outcome.reason());
      return new Result(kept, true, 0);
    }
    String model = session.model().provider() + "/" + session.model().model();
    List<Fact> added =
        facts(
            now,
            asked.get(),
            JSON.readTree(outcome.answer()),
            model,
            outcome.answerKey(),
            snapshot);
    List<Fact> all = new ArrayList<>(kept);
    all.addAll(added);
    long members = added.stream().filter(f -> f.predicate().equals(Types.GROUPS)).count();
    LOG.info(
        "Group by meaning: {} members placed, {} groups formed",
        members,
        added.stream().filter(f -> f.predicate().equals(Types.GROUP_OF)).count());
    return new Result(all, true, (int) members);
  }

  /** The task block, with the diagrams' target. */
  String task() {
    return TASK.replace("${limit}", String.valueOf(target));
  }

  // ---------------------------------------------------------------------------------------------
  // Kept groups

  /**
   * The role's earlier groups, written again from this snapshot: each member that still exists in
   * the group it was given; a group it formed with fewer than 2 members left is dropped, which
   * frees them.
   */
  static List<Fact> kept(Knowledge knowledge, long snapshot) {
    Set<EntityId> exists = new HashSet<>();
    for (Fact part : knowledge.withPredicate(Types.PART_OF)) {
      exists.add(part.subject());
    }
    exists.addAll(Elements.units(knowledge));
    exists.addAll(Elements.frontends(knowledge));
    Map<EntityId, Fact> formed = new TreeMap<>(Knowledge.BY_ID);
    Map<EntityId, List<Fact>> members = new TreeMap<>(Knowledge.BY_ID);
    Set<EntityId> groups = new HashSet<>();
    knowledge.withPredicate(Types.GROUP_OF).forEach(f -> groups.add(f.subject()));
    for (Fact fact : knowledge.withPredicate(Types.GROUP_OF)) {
      if (own(fact)) {
        formed.put(fact.subject(), fact);
      }
    }
    for (Fact fact : knowledge.withPredicate(Types.GROUPS)) {
      if (own(fact) && groups.contains(fact.subject()) && exists.contains(Knowledge.object(fact))) {
        members.computeIfAbsent(fact.subject(), g -> new ArrayList<>()).add(fact);
      }
    }
    List<Fact> result = new ArrayList<>();
    formed.forEach(
        (group, fact) -> {
          if (members.getOrDefault(group, List.of()).size() >= 2) {
            result.add(again(fact, snapshot));
          }
        });
    members.forEach(
        (group, joined) -> {
          if (!formed.containsKey(group) || joined.size() >= 2) {
            joined.forEach(f -> result.add(again(f, snapshot)));
          }
        });
    return result;
  }

  private static boolean own(Fact fact) {
    return fact.origin().kind() == Origin.Kind.LLM && fact.origin().name().equals(NAME);
  }

  /** The fact as it holds from the snapshot, of this version of the role. */
  private static Fact again(Fact fact, long snapshot) {
    Fact copy =
        Fact.create(
            fact.subject(),
            fact.predicate(),
            fact.object(),
            fact.attributes(),
            LlmFacts.origin(ROLE),
            fact.evidence(),
            fact.confidence(),
            Validity.from(snapshot));
    return fact.lineage() == null || fact.lineage().isEmpty()
        ? copy
        : copy.withLineage(fact.lineage());
  }

  // ---------------------------------------------------------------------------------------------
  // The item

  /**
   * The item, or empty when nothing needs grouping: no view holds more than the target and its
   * tolerance, or all it holds is grouped already.
   */
  Optional<Asked> item(Knowledge knowledge, List<Fact> facts) {
    int most = target + tolerance;
    ElementNames names = new ElementNames(new ArchitectureModel(facts), Describer.SYSTEM_NAME);
    Elements elements =
        new Elements(knowledge, new ArchitectureModel(facts).vocabulary(), names, Set.of());
    Map<EntityId, String> responsibilities = new HashMap<>();
    for (Fact fact : facts) {
      if (fact.origin().kind() == Origin.Kind.LLM
          && fact.predicate().equals(Types.RESPONSIBILITY)) {
        responsibilities.putIfAbsent(
            fact.subject(), FactLines.oneLine(fact.attributes().path("text").asString()));
      }
    }
    Map<EntityId, EntityId> domainOf = new HashMap<>();
    Map<String, Map<String, EntityId>> groups = new TreeMap<>();
    for (Groups.Group domain : Groups.of(knowledge, Groups.DOMAIN)) {
      domain.members().forEach(m -> domainOf.putIfAbsent(m, domain.id()));
      groups
          .computeIfAbsent(Groups.SYSTEM, s -> new TreeMap<>())
          .put(key(domain.id()), domain.id());
    }
    Map<EntityId, EntityId> areaOf = new HashMap<>();
    for (Groups.Group area : Groups.of(knowledge, Groups.FEATURE_AREA)) {
      area.members().forEach(m -> areaOf.putIfAbsent(m, area.id()));
      groups.computeIfAbsent(area.scope(), s -> new TreeMap<>()).put(key(area.id()), area.id());
    }
    SortedSet<EntityId> containers = new TreeSet<>(Knowledge.BY_ID);
    containers.addAll(Elements.units(knowledge));
    containers.addAll(Elements.frontends(knowledge));
    boolean domains =
        containers.size() > most && containers.stream().anyMatch(c -> !domainOf.containsKey(c));
    Map<EntityId, List<Elements.Component>> services = new LinkedHashMap<>();
    for (EntityId unit : Elements.units(knowledge)) {
      List<Elements.Component> components = elements.components(unit);
      if (components.size() > most
          && components.stream()
              .anyMatch(c -> !areaOf.containsKey(Components.id(unit, c.main())))) {
        services.put(unit, components);
      }
    }
    if (!domains && services.isEmpty()) {
      return Optional.empty();
    }
    Set<EntityId> connected = connected(knowledge);
    Refs refs = new Refs();
    Map<String, Part> parts = new LinkedHashMap<>();
    Map<EntityId, String> tokens = new HashMap<>();
    Map<String, String> byName = new LinkedHashMap<>();
    List<FingerprintInput> inputs = new ArrayList<>();
    StringBuilder text = new StringBuilder();
    text.append("Containers: [ref] name | domain | what it does\n");
    for (EntityId container : containers) {
      String name = FactLines.oneLine(NameGroups.name(knowledge, names, container));
      EntityId group = domainOf.get(container);
      String does = responsibilities.getOrDefault(container, "");
      if (!connected.contains(container)) {
        does = (does + " (nothing connects to it)").strip();
      }
      String line = name + " | " + (group == null ? "-" : key(group)) + " | " + does;
      String token = refs.add('E', container.toString(), container, line);
      tokens.put(container, token);
      byName.putIfAbsent(name, token);
      parts.put(ref(token), new Part(container, name, null, group));
      text.append('[').append(token).append("] ").append(line).append('\n');
    }
    if (domains) {
      text.append(
          "\nDomains are asked: the system has "
              + containers.size()
              + " containers, more than a diagram holds.\n");
    } else {
      text.append("\nDomains are not asked: the system's containers fit one diagram.\n");
    }
    for (Map.Entry<EntityId, List<Elements.Component>> service : services.entrySet()) {
      EntityId unit = service.getKey();
      text.append("\nComponents of ")
          .append('[')
          .append(tokens.get(unit))
          .append("] ")
          .append(FactLines.oneLine(NameGroups.name(knowledge, names, unit)))
          .append(": [ref] name | area | what it does | endpoint paths\n");
      for (Elements.Component component : service.getValue()) {
        EntityId id = Components.id(unit, component.main());
        String name = FactLines.oneLine(NameGroups.name(knowledge, names, id));
        EntityId group = areaOf.get(id);
        StringBuilder line =
            new StringBuilder(name)
                .append(" | ")
                .append(group == null ? "-" : key(group))
                .append(" | ")
                .append(responsibilities.getOrDefault(id, ""));
        List<String> paths = paths(knowledge, component);
        if (!paths.isEmpty()) {
          line.append(" | ").append(String.join(", ", paths));
        }
        String token = refs.add('E', id.toString(), id, line.toString());
        tokens.put(id, token);
        component.types().forEach(t -> tokens.putIfAbsent(t, token));
        byName.putIfAbsent(name, token);
        parts.put(ref(token), new Part(id, name, unit, group));
        text.append('[').append(token).append("] ").append(line).append('\n');
      }
    }
    List<String> flows = flows(knowledge, elements, services, domains, tokens, byName);
    if (!flows.isEmpty()) {
      text.append("\nFlows: where it starts: what happens\n");
      flows.forEach(f -> text.append(f).append('\n'));
    }
    String rendering = text.toString();
    inputs.add(FingerprintInput.text(rendering));
    responsibilities.values().stream()
        .distinct()
        .forEach(
            r -> inputs.add(FingerprintInput.answer(Fingerprints.sha256(r), List.of("describe"))));
    Set<String> shown = new TreeSet<>();
    parts.values().forEach(p -> shown.add(p.name()));
    return Optional.of(
        new Asked(
            new Item(SUBJECT, SUBJECT, inputs, rendering), refs, parts, domains, groups, shown));
  }

  /** The containers a link names, from or to. */
  private static Set<EntityId> connected(Knowledge knowledge) {
    Set<EntityId> connected = new HashSet<>();
    for (String predicate : Describer.LINKS) {
      for (Fact link : knowledge.withPredicate(predicate)) {
        connected.add(link.subject());
        if (Knowledge.isEntity(link)) {
          connected.add(Knowledge.object(link));
        }
      }
    }
    return connected;
  }

  /**
   * At most {@value #MAX_PATHS} distinct paths the component's endpoints serve, methods dropped.
   */
  static List<String> paths(Knowledge knowledge, Elements.Component component) {
    Set<String> paths = new TreeSet<>();
    for (EntityId type : component.types()) {
      for (EntityId method : knowledge.parts(type, Types.METHOD)) {
        for (Fact fact : knowledge.attributes(method)) {
          if (fact.predicate().equals(Types.ENDPOINT)) {
            String endpoint = fact.object().value().toString();
            paths.add(
                endpoint.contains(" ") ? endpoint.substring(endpoint.indexOf(' ') + 1) : endpoint);
          }
        }
      }
    }
    return paths.stream().limit(MAX_PATHS).toList();
  }

  /**
   * The traced flows the item shows, each once: those that start at a listed component and, when
   * domains are asked, those that cross containers; the parts their steps name by their references.
   */
  private static List<String> flows(
      Knowledge knowledge,
      Elements elements,
      Map<EntityId, List<Elements.Component>> services,
      boolean domains,
      Map<EntityId, String> tokens,
      Map<String, String> byName) {
    List<ScopeFlows.Candidate> candidates;
    try {
      candidates = new ScopeFlows(knowledge, elements, Elements.units(knowledge)).candidates();
    } catch (RuntimeException e) {
      LOG.warn("Group by meaning: no flows shown, tracing failed: {}", e.toString());
      return List.of();
    }
    // Longest names first, so a name inside another is not replaced in it.
    List<String> namesByLength = new ArrayList<>(byName.keySet());
    namesByLength.sort(Comparator.comparingInt(String::length).reversed());
    List<String> lines = new ArrayList<>();
    for (ScopeFlows.Candidate flow : candidates) {
      if (lines.size() >= MAX_FLOWS) {
        break;
      }
      String start = start(flow, tokens);
      boolean listed = start != null && services.containsKey(flow.unit());
      if (!listed && !(domains && flow.crossesUnits())) {
        continue;
      }
      List<String> steps = new ArrayList<>();
      for (ScopeFlows.StepLine step : flow.steps()) {
        steps.add(byRef(FactLines.oneLine(step.text()), namesByLength, byName));
      }
      String from = start != null ? "[" + start + "]" : "[" + tokens.get(flow.unit()) + "]";
      lines.add(from + ": " + String.join("; ", steps));
    }
    return lines;
  }

  /** The token of the component a flow starts at, or null if the item lists none. */
  private static String start(ScopeFlows.Candidate flow, Map<EntityId, String> tokens) {
    EntityId subject = flow.entry().subject();
    if (subject.type().equals(Types.METHOD) && subject.key().contains("#")) {
      EntityId type =
          new EntityId(Types.TYPE, subject.key().substring(0, subject.key().indexOf('#')));
      return tokens.get(type);
    }
    return tokens.get(subject);
  }

  /** The text with each listed part's name replaced by its reference. */
  private static String byRef(String text, List<String> names, Map<String, String> byName) {
    String result = text;
    for (String name : names) {
      if (name.isBlank()) {
        continue;
      }
      Matcher m =
          Pattern.compile("(?<![\\w-])" + Pattern.quote(name) + "(?![\\w-])").matcher(result);
      result = m.replaceAll(Matcher.quoteReplacement("[" + byName.get(name) + "]"));
    }
    return result;
  }

  /** A group's key as the item shows it: the last part of its ID, its signal. */
  static String key(EntityId group) {
    String key = group.key();
    return key.substring(key.lastIndexOf('/') + 1);
  }

  /** A reference without its token marks. */
  private static String ref(String token) {
    return token.replaceAll("[^A-Z0-9]", "");
  }

  // ---------------------------------------------------------------------------------------------
  // The facts

  /** The groups and members an accepted answer adds, as facts. */
  static List<Fact> facts(
      Knowledge knowledge,
      Asked asked,
      JsonNode answer,
      String model,
      String fingerprint,
      long snapshot) {
    List<Fact> facts = new ArrayList<>();
    Set<EntityId> placed = new HashSet<>();
    for (Placement p : placements(asked, answer)) {
      List<EntityId> members = new ArrayList<>();
      for (EntityId member : p.members()) {
        if (placed.add(member)) {
          members.add(member);
        }
      }
      if (members.isEmpty() || p.group() == null && members.size() < 2) {
        continue;
      }
      EntityId group =
          p.group() != null ? p.group() : Groups.id(p.scope(), p.kind(), SIGNAL + p.key());
      if (p.group() == null) {
        List<Fact> basis = new ArrayList<>();
        members.forEach(m -> basis.add(basis(knowledge, m)));
        ObjectNode of = FactJson.newObject();
        of.put("scope", p.scope());
        of.put("basis", BASIS);
        of.put("signal", SIGNAL + p.key());
        cite(of, basis);
        provenance(of, model, fingerprint);
        facts.add(fact(group, Types.GROUP_OF, FactObject.string(p.kind()), of, basis, snapshot));
      }
      for (EntityId member : members) {
        Fact basis = basis(knowledge, member);
        ObjectNode in = FactJson.newObject();
        in.put("basis", BASIS);
        cite(in, List.of(basis));
        provenance(in, model, fingerprint);
        facts.add(
            fact(group, Types.GROUPS, FactObject.entity(member), in, List.of(basis), snapshot));
      }
    }
    return facts;
  }

  private static Fact fact(
      EntityId subject,
      String predicate,
      FactObject object,
      ObjectNode attributes,
      List<Fact> cited,
      long snapshot) {
    return Fact.create(
        subject,
        predicate,
        object,
        attributes,
        LlmFacts.origin(ROLE),
        LlmFacts.evidence(cited),
        LlmFacts.CONFIDENCE,
        Validity.from(snapshot));
  }

  /** The model and the stored answer a fact comes from (ADR 0124). */
  private static void provenance(ObjectNode attributes, String model, String fingerprint) {
    attributes.put("model", model);
    if (fingerprint != null && !fingerprint.isEmpty()) {
      attributes.put(LlmFacts.ANSWER, fingerprint);
    }
  }

  private static void cite(ObjectNode attributes, List<Fact> facts) {
    ArrayNode ids = attributes.putArray("facts");
    new LinkedHashSet<>(facts).forEach(f -> ids.add(f.id()));
  }

  /** The fact a member rests on: what makes it a component of its unit, else its first fact. */
  private static Fact basis(Knowledge knowledge, EntityId member) {
    List<Fact> about = new ArrayList<>(knowledge.about(member));
    about.sort(Knowledge.STABLE);
    return about.stream()
        .filter(f -> f.predicate().equals(Types.PART_OF))
        .findFirst()
        .orElseGet(about::getFirst);
  }

  /**
   * A group an answer places members in.
   *
   * @param kind a domain or a feature area
   * @param scope the system, or the unit whose components it groups
   * @param key its key, as the answer gives it
   * @param group the existing group it extends, or null for a new one
   * @param members the members it places, each a loose part of its scope
   */
  record Placement(String kind, String scope, String key, EntityId group, List<EntityId> members) {}

  /** The answer's groups, as {@link Grouping} accepts them. */
  static List<Placement> placements(Asked asked, JsonNode answer) {
    List<Placement> result = new ArrayList<>();
    for (JsonNode domain : answer.path(DOMAINS)) {
      result.add(placement(asked, domain, Groups.DOMAIN, Groups.SYSTEM));
    }
    for (JsonNode area : answer.path(AREAS)) {
      Part service = asked.parts().get(clean(area.path("service").asString("")));
      if (service != null && service.container() == null) {
        result.add(placement(asked, area, Groups.FEATURE_AREA, service.id().toString()));
      }
    }
    return result;
  }

  private static Placement placement(Asked asked, JsonNode group, String kind, String scope) {
    Map<String, EntityId> existing = asked.groups().getOrDefault(scope, Map.of());
    String extended = group.path("extends").asString("");
    String key = clean(group.path("key").asString("")).toLowerCase(java.util.Locale.ROOT);
    EntityId id = existing.get(extended);
    if (id == null) {
      id = existing.get(key);
    }
    if (id == null) {
      id = existing.get(SIGNAL + key);
    }
    List<EntityId> members = new ArrayList<>();
    for (JsonNode member : group.path("members")) {
      Part part = asked.parts().get(clean(member.asString("")));
      if (part != null && part.loose() && scope.equals(scopeOf(part))) {
        members.add(part.id());
      }
    }
    return new Placement(kind, scope, key, id, members);
  }

  /** The scope a part is grouped in: the system for a container, else its unit. */
  private static String scopeOf(Part part) {
    return part.container() == null ? Groups.SYSTEM : part.container().toString();
  }

  /** A reference as an answer may write it, {@code E3} or {@code [E3]}, bare. */
  static String clean(String ref) {
    return ref.strip().replaceAll("^\\[|]$", "");
  }

  // ---------------------------------------------------------------------------------------------
  // Asking

  /** The layout, checks and storage of the one item. */
  final class OneItem implements PacketRole {

    private final Asked asked;

    OneItem(Asked asked) {
      this.asked = asked;
    }

    @Override
    public PromptLayout layout(List<Item> packet, List<String> ids) {
      Refs.Resolved resolved =
          Refs.resolve(List.of(asked.item().rendering()), List.of(asked.refs()));
      return new PromptLayout(
          CommonPrompt.SYSTEM,
          task() + DescribeRole.SCHEMA_HEADING + SCHEMA,
          "",
          "",
          false,
          Blocks.items(ids, resolved.texts()));
    }

    @Override
    public int maxOutputTokens(int items) {
      // About 40 tokens a part placed, and a reason for each group, with room to spare.
      return Math.max(2_000, 60 * asked.parts().size() + 1_000);
    }

    @Override
    public CheckInputs inputs(List<Item> packet, List<String> ids) {
      return new CheckInputs(
          ITEM_SCHEMA,
          asked.refs().lines(),
          CITABLE,
          false,
          (repo, path) -> Optional.empty(),
          Map.of(),
          Set.of(),
          asked.names());
    }

    @Override
    public Pipeline pipeline() {
      List<Check> checks = new ArrayList<>(Checks.STANDARD);
      checks.add(new Grouping(asked, target));
      return new Pipeline(checks);
    }

    @Override
    public ItemAnswer read(String id, Item item, JsonNode entry) {
      List<CheckedText> texts = new ArrayList<>();
      if (entry != null && entry.isObject()) {
        for (String list : List.of(DOMAINS, AREAS)) {
          int i = 0;
          for (JsonNode group : entry.path(list)) {
            JsonNode reason = group.path("reason");
            if (reason.path("text").isString()) {
              List<String> cites = new ArrayList<>();
              reason.path("cites").forEach(c -> cites.add(clean(c.asString(""))));
              texts.add(
                  new CheckedText(
                      list + "." + i + ".reason",
                      CheckedText.Kind.PROSE,
                      reason.path("text").asString(),
                      cites,
                      REASON_WORDS,
                      "",
                      Set.of()));
            }
            i++;
          }
        }
      }
      return new ItemAnswer(id, entry, texts, List.of());
    }

    /** The accepted answer as it was written: its references are the item's own. */
    @Override
    public String store(Item item, ItemAnswer accepted) {
      return accepted.raw().toString();
    }
  }

  /**
   * The role's own checks (ADR 0129): every member and service is a part the item lists, every
   * member a part without a group, placed once, in a group of its own container; an extended group
   * is one of its scope; a new key is kebab-case; domains only when asked, and at most the target
   * in all.
   */
  static final class Grouping implements Check {

    private final Asked asked;
    private final int target;

    Grouping(Asked asked, int target) {
      this.asked = asked;
      this.target = target;
    }

    @Override
    public String name() {
      return "Grouping";
    }

    @Override
    public List<Rejection> check(ItemAnswer answer, CheckInputs inputs) {
      List<String> problems = new ArrayList<>();
      JsonNode raw = answer.raw();
      if (raw == null || !raw.isObject()) {
        return List.of();
      }
      Map<String, String> placedIn = new HashMap<>();
      JsonNode domains = raw.path(DOMAINS);
      if (!asked.domains() && domains.size() > 0) {
        problems.add("Domains are not asked; answer them as an empty list.");
      }
      int newDomains = 0;
      for (JsonNode domain : domains) {
        String extended = domain.path("extends").asString("");
        Map<String, EntityId> existing = asked.groups().getOrDefault(Groups.SYSTEM, Map.of());
        if (!extended.isEmpty() && !existing.containsKey(extended)) {
          problems.add(
              "Domain " + label(domain) + " extends " + extended + ", which is no domain.");
        } else if (extended.isEmpty()) {
          newDomains++;
          keyProblem(domain).ifPresent(problems::add);
        }
        members(domain, Groups.SYSTEM, "domain " + label(domain), placedIn, problems);
      }
      int existingDomains = asked.groups().getOrDefault(Groups.SYSTEM, Map.of()).size();
      if (asked.domains() && existingDomains + newDomains > target) {
        problems.add(
            "There are "
                + (existingDomains + newDomains)
                + " domains in all, more than "
                + target
                + "; make fewer, broader ones.");
      }
      for (JsonNode area : raw.path(AREAS)) {
        String service = clean(area.path("service").asString(""));
        Part unit = asked.parts().get(service);
        if (unit == null || unit.container() != null) {
          problems.add(
              "Area " + label(area) + " names " + service + ", which is no service listed.");
          continue;
        }
        String scope = unit.id().toString();
        String extended = area.path("extends").asString("");
        if (!extended.isEmpty()
            && !asked.groups().getOrDefault(scope, Map.of()).containsKey(extended)) {
          problems.add(
              "Area "
                  + label(area)
                  + " extends "
                  + extended
                  + ", which is no area of "
                  + service
                  + ".");
        } else if (extended.isEmpty()) {
          keyProblem(area).ifPresent(problems::add);
        }
        members(area, scope, "area " + label(area), placedIn, problems);
      }
      List<Rejection> found = new ArrayList<>();
      problems.forEach(p -> found.add(new Rejection(answer.item(), "", name(), p)));
      return found;
    }

    private void members(
        JsonNode group,
        String scope,
        String what,
        Map<String, String> placedIn,
        List<String> problems) {
      for (JsonNode member : group.path("members")) {
        String ref = clean(member.asString(""));
        Part part = asked.parts().get(ref);
        if (part == null) {
          problems.add("The " + what + " holds " + ref + ", which is no part listed.");
        } else if (!scope.equals(scopeOf(part))) {
          problems.add(
              "The "
                  + what
                  + " holds "
                  + ref
                  + ", which is "
                  + (part.container() == null ? "a container" : "a component of another service")
                  + ".");
        } else if (!part.loose()) {
          problems.add(
              "The "
                  + what
                  + " holds "
                  + ref
                  + ", which the code put in "
                  + key(part.group())
                  + "; never move a part out of its group.");
        } else {
          String before = placedIn.put(ref, what);
          if (before != null && !before.equals(what)) {
            problems.add(ref + " is placed twice, in the " + before + " and the " + what + ".");
          }
        }
      }
    }

    private static Optional<String> keyProblem(JsonNode group) {
      String key = group.path("key").asString("");
      return KEY.matcher(key).matches()
          ? Optional.empty()
          : Optional.of("The key \"" + key + "\" is not a short kebab-case name.");
    }

    private static String label(JsonNode group) {
      return group.path("key").asString("?");
    }
  }
}

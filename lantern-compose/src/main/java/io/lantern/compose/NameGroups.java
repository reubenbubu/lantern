package io.lantern.compose;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.derive.ComponentInteractions;
import io.lantern.derive.Components;
import io.lantern.derive.Findings;
import io.lantern.derive.Groups;
import io.lantern.derive.Knowledge;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Name groups role (knowledge-build epic, section 11.8; ADR 0107): a name, a responsibility
 * sentence and an intent for each line leaving it, for every group the derive stage formed (ADR
 * 0099), from its members' responsibilities and their lines' intents, which Describe wrote. It
 * never changes membership: a suggestion to merge two groups is stored as a finding and never
 * applied.
 *
 * <p>Each group is an item; up to {@value #PACKET} groups of one kind and scope (the feature areas
 * of one unit, or the system's domains) share a request. Block 2 is the epic's instructions and
 * answer schema, read from {@code prompts/name-groups.*}.
 */
public final class NameGroups {

  /** The prompt version, part of every answer's cache key. */
  static final String VERSION = "5";

  /** The most groups a request holds, a starting size to calibrate. */
  static final int PACKET = 8;

  /** The most words of a name, a responsibility, an intent and a merge reason. */
  static final int NAME_WORDS = 4;

  static final int SENTENCE_WORDS = 25;
  static final int PHRASE_WORDS = 8;
  static final int REASON_WORDS = 20;

  /** The most other groups an item lists for a merge suggestion. */
  static final int MAX_OTHERS = 30;

  /** Block 2's instructions, as the epic writes them. */
  static final String TASK = DescribeRole.prompt("name-groups.task.txt");

  /** The answer schema, as the epic writes it. */
  static final String SCHEMA = DescribeRole.prompt("name-groups.schema.json");

  /** The display name of a group: {@code (group, display-name, "c4")}. */
  public static final String DISPLAY_NAME = "display-name";

  /** The kind of finding a merge suggestion is. */
  public static final String GROUP_MERGE = "group-merge";

  /** The role. */
  public static final RoleSpec ROLE =
      RoleSpec.single(
              "name-groups",
              Role.NAME_GROUPS,
              Prompts.version(
                  VERSION, "common.system.txt", "name-groups.task.txt", "name-groups.schema.json"),
              "name-groups",
              RunMode.INTERACTIVE,
              250)
          .withPacketSize(PACKET);

  static final String NAME = "name";
  static final String RESPONSIBILITY = "responsibility";
  static final String LINES = "lines";
  static final String MERGE = "merge";

  /** The reference under which an item's check inputs name the group itself. */
  static final String SELF = "#self";

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final JsonNode ITEM_SCHEMA = JsonSchema.itemSchema(JSON.readTree(SCHEMA));
  private static final Set<Character> CITABLE = Set.of('F', 'A');
  private static final int MIN_OUTPUT_TOKENS = 1_000;
  private static final int ITEM_OUTPUT_TOKENS = 250;
  private static final int LINE_OUTPUT_TOKENS = 60;
  private static final Logger LOG = LoggerFactory.getLogger(NameGroups.class);

  private final Harness harness;

  public NameGroups(Harness harness) {
    this.harness = java.util.Objects.requireNonNull(harness, "harness");
  }

  /**
   * What a run wrote.
   *
   * @param facts the names, responsibilities and intents, of origin {@code llm}
   * @param findings the merge suggestions, as findings of origin {@code llm}
   * @param unknown the groups left without a name
   */
  public record Result(List<Fact> facts, List<Fact> findings, List<String> unknown) {

    public Result {
      facts = List.copyOf(facts);
      findings = List.copyOf(findings);
      unknown = List.copyOf(unknown);
    }

    /** The role's facts by origin, as a run records them. */
    public Map<Origin, List<Fact>> byOrigin() {
      Map<Origin, List<Fact>> all = new LinkedHashMap<>();
      List<Fact> both = new ArrayList<>(facts);
      both.addAll(findings);
      all.put(LlmFacts.origin(ROLE), both);
      return all;
    }
  }

  /** A line leaving a group, as an item shows it. */
  record Shown(Groups.Line line, String key) {}

  /**
   * An item with what its references stand for.
   *
   * @param group the group
   * @param refs its references: {@code F} its facts, {@code A} its members' responsibilities and
   *     their lines' intents
   * @param members each member's name
   * @param lines its lines leaving the group, by their key {@code R..}
   * @param others the other groups of its kind, by their key {@code G..}
   * @param item the harness item
   */
  record Named(
      Groups.Group group,
      Refs refs,
      List<String> members,
      Map<String, Shown> lines,
      Map<String, EntityId> others,
      Item item) {}

  /**
   * Names every stored group of the facts.
   *
   * @param facts the facts that hold in the snapshot, the groups and Describe's responsibilities
   *     and intents included
   * @param snapshot the snapshot the facts it writes hold from
   */
  public Result name(Collection<Fact> facts, long snapshot) {
    Knowledge knowledge = new Knowledge(facts);
    Assembler assembler = new Assembler(knowledge, Map.of(), Set.of(), null, "The system");
    Map<EntityId, Fact> responsibilities = new HashMap<>();
    Map<String, Fact> intents = new HashMap<>();
    for (Fact fact : facts) {
      if (fact.origin().kind() != Origin.Kind.LLM) {
        continue;
      }
      if (fact.predicate().equals(LlmFacts.RESPONSIBILITY)) {
        responsibilities.putIfAbsent(fact.subject(), fact);
      } else if (fact.predicate().equals(LlmFacts.INTENT)) {
        intents.putIfAbsent(fact.object().value().toString(), fact);
      }
    }
    Set<String> technologies = technologies(knowledge);
    List<Named> named = new ArrayList<>();
    for (String kind : List.of(Groups.FEATURE_AREA, Groups.DOMAIN)) {
      List<Groups.Group> groups = Groups.of(knowledge, kind);
      SortedMap<String, List<Groups.Group>> byScope = new TreeMap<>();
      groups.forEach(g -> byScope.computeIfAbsent(g.scope(), s -> new ArrayList<>()).add(g));
      for (List<Groups.Group> scoped : byScope.values()) {
        List<Groups.Line> lines = Groups.lines(scoped, memberLines(knowledge, kind, scoped));
        for (Groups.Group group : scoped) {
          named.add(item(knowledge, assembler, group, scoped, lines, responsibilities, intents));
        }
      }
    }
    if (named.isEmpty()) {
      return new Result(List.of(), List.of(), List.of());
    }
    Map<String, Named> bySubject = new ConcurrentHashMap<>();
    named.forEach(n -> bySubject.put(n.item().subject(), n));
    RoleSession session = harness.session(ROLE);
    List<Item> items = named.stream().map(Named::item).toList();
    session.plan(items, Tokens.estimate(CommonPrompt.SYSTEM + TASK));
    Map<String, Outcome> outcomes = session.askPackets(items, new Packets(bySubject, technologies));
    String model = session.model().provider() + "/" + session.model().model();
    List<Fact> written = new ArrayList<>();
    List<Fact> findings = new ArrayList<>();
    List<String> unknown = new ArrayList<>();
    for (Named n : named) {
      Outcome outcome = outcomes.get(n.item().subject());
      String answer =
          outcome != null
                  && (outcome.kind() == Outcome.Kind.CACHED
                      || outcome.kind() == Outcome.Kind.ANSWERED)
              ? outcome.answer()
              : null;
      if (answer == null) {
        LOG.info(
            "Group {} left unnamed: {}",
            n.group().id(),
            outcome == null ? "not asked" : outcome.kind() + " " + outcome.reason());
        unknown.add(n.group().id().toString());
        continue;
      }
      boolean hasName = facts(n, JSON.readTree(answer), model, snapshot, written, findings);
      if (!hasName) {
        unknown.add(n.group().id().toString());
      }
    }
    LOG.info(
        "Name groups: {} groups, {} facts written, {} merge suggestions, {} unnamed",
        named.size(),
        written.size(),
        findings.stream().map(Fact::subject).distinct().count(),
        unknown.size());
    Map<EntityId, Fact> oneEach = new LinkedHashMap<>();
    findings.forEach(f -> oneEach.putIfAbsent(f.subject(), f));
    return new Result(written, List.copyOf(oneEach.values()), unknown);
  }

  /** The predicates whose values name a technology: a product or framework. */
  static final List<String> TECHNOLOGY_PREDICATES =
      List.of("technology", "broker-technology", "database-technology");

  /**
   * The products and frameworks the snapshot's technology facts name, without versions: "Spring
   * Boot 3.5.14 / Java 21" names Spring Boot and Java.
   */
  static Set<String> technologies(Knowledge knowledge) {
    Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    for (String predicate : TECHNOLOGY_PREDICATES) {
      for (Fact fact : knowledge.withPredicate(predicate)) {
        for (String part : fact.object().value().toString().split("[/,;+()]")) {
          String product = part.replaceAll("\\s+v?[0-9][0-9A-Za-z.\\-]*", " ").strip();
          if (product.length() > 2 && !product.matches("[0-9.]+")) {
            names.add(product);
          }
        }
      }
    }
    return names;
  }

  /**
   * The lines between the members of a kind's groups and other elements: for feature areas, the
   * lines between the unit's components; for domains, the links of the units to other containers.
   */
  static List<Groups.Line> memberLines(
      Knowledge knowledge, String kind, List<Groups.Group> groups) {
    List<Groups.Line> lines = new ArrayList<>();
    if (kind.equals(Groups.FEATURE_AREA)) {
      Set<EntityId> unitComponents = new TreeSet<>(Knowledge.BY_ID);
      String unit = groups.getFirst().scope();
      for (Fact partOf : knowledge.withPredicate(Components.PART_OF)) {
        if (Knowledge.isEntity(partOf) && Knowledge.object(partOf).toString().equals(unit)) {
          unitComponents.add(partOf.subject());
        }
      }
      groups.forEach(g -> unitComponents.addAll(g.members()));
      for (EntityId component : unitComponents) {
        for (Fact line :
            FactLines.sorted(
                knowledge.relations(component, ComponentInteractions.COMPONENT_LINK))) {
          if ("code-end".equals(RepoSections.attribute(line, "kind", ""))) {
            continue;
          }
          EntityId other = Knowledge.object(line);
          if (unitComponents.contains(other)) {
            lines.add(
                new Groups.Line(
                    component,
                    other,
                    RepoSections.attribute(line, "kind", "calls"),
                    List.of(line)));
          }
        }
      }
    } else {
      Set<EntityId> units = new TreeSet<>(Knowledge.BY_ID);
      units.addAll(C4Views.units(knowledge));
      groups.forEach(g -> units.addAll(g.members()));
      for (EntityId unit : units) {
        for (String predicate : Describer.LINKS) {
          for (Fact link : FactLines.sorted(knowledge.relations(unit, predicate))) {
            lines.add(new Groups.Line(unit, Knowledge.object(link), predicate, List.of(link)));
          }
        }
      }
    }
    return lines;
  }

  /** The item of a group. */
  private static Named item(
      Knowledge knowledge,
      Assembler assembler,
      Groups.Group group,
      List<Groups.Group> scoped,
      List<Groups.Line> lines,
      Map<EntityId, Fact> responsibilities,
      Map<String, Fact> intents) {
    Refs refs = new Refs();
    List<FingerprintInput> inputs = new ArrayList<>();
    StringBuilder text = new StringBuilder();
    String kindWords =
        group.kind().equals(Groups.FEATURE_AREA)
            ? "a feature area of the unit " + assembler.name(EntityId.parse(group.scope()))
            : "a domain of the system";
    text.append("Group ").append(group.id()).append(", ").append(kindWords).append('\n');
    List<Fact> own = new ArrayList<>();
    knowledge.about(group.id()).stream()
        .filter(f -> f.predicate().equals(Groups.GROUP_OF))
        .forEach(own::add);
    own.addAll(knowledge.relations(group.id(), Groups.GROUPS));
    text.append("Why its members belong together (its basis) and how each joined:\n");
    facts(text, refs, inputs, own);
    text.append("Members:\n");
    List<String> members = new ArrayList<>();
    for (EntityId member : group.members()) {
      String name = FactLines.oneLine(name(knowledge, assembler, member));
      members.add(name);
      text.append("- ").append(name);
      Fact said = responsibilities.get(member);
      if (said != null) {
        String sentence = FactLines.oneLine(said.attributes().path("text").asString());
        text.append(": [").append(refs.add('A', key(said), said, sentence)).append("] ");
        text.append(sentence);
        inputs.add(FingerprintInput.answer(Fingerprints.sha256(sentence)));
        inputs.add(DescribeRole.input(said));
      }
      text.append('\n');
    }
    Map<String, Shown> shown = new LinkedHashMap<>();
    Map<EntityId, Integer> shared = new HashMap<>();
    for (Groups.Line line : lines) {
      if (line.from().equals(group.id())) {
        shared.merge(line.to(), 1, Integer::sum);
      } else if (line.to().equals(group.id())) {
        shared.merge(line.from(), 1, Integer::sum);
      }
    }
    List<Groups.Line> leaving = lines.stream().filter(l -> l.from().equals(group.id())).toList();
    if (!leaving.isEmpty()) {
      text.append("Its lines to other groups and elements:\n");
      for (Groups.Line line : leaving) {
        String ref = "R" + (shown.size() + 1);
        shown.put(ref, new Shown(line, lineKey(line)));
        text.append('[')
            .append(Refs.token(ref))
            .append("] to ")
            .append(FactLines.oneLine(endName(knowledge, assembler, scoped, line.to())))
            .append(" (")
            .append(line.protocol())
            .append(")\n");
        for (Fact member : line.facts()) {
          inputs.add(DescribeRole.input(member));
          Fact intent = intents.get(member.claimId() != null ? member.claimId() : member.id());
          String what =
              FactLines.oneLine(name(knowledge, assembler, member.subject()))
                  + " -> "
                  + FactLines.oneLine(name(knowledge, assembler, Knowledge.object(member)));
          if (intent != null) {
            String phrase = FactLines.oneLine(intent.attributes().path("text").asString());
            text.append("  [")
                .append(refs.add('A', key(intent), intent, what + ": " + phrase))
                .append("] ")
                .append(what)
                .append(": ")
                .append(phrase)
                .append('\n');
            inputs.add(FingerprintInput.answer(Fingerprints.sha256(phrase)));
          } else {
            text.append("  ").append(what).append(" (no intent worded)\n");
          }
        }
      }
    }
    // The other groups of its kind in its scope, those sharing the most lines first, then by key.
    List<Groups.Group> candidates =
        scoped.stream()
            .filter(g -> !g.id().equals(group.id()))
            .sorted(
                Comparator.comparing((Groups.Group g) -> -shared.getOrDefault(g.id(), 0))
                    .thenComparing(g -> g.id().toString()))
            .limit(MAX_OTHERS)
            .toList();
    Map<String, EntityId> others = new LinkedHashMap<>();
    if (!candidates.isEmpty()) {
      text.append("Other groups of this kind:\n<groups>\n");
      for (Groups.Group other : candidates) {
        String ref = "G" + (others.size() + 1);
        others.put(ref, other.id());
        text.append('[').append(Refs.token(ref)).append("] ").append(other.id()).append('\n');
        inputs.add(FingerprintInput.answer(Fingerprints.sha256(other.id().toString())));
      }
      text.append("</groups>\n");
    }
    Item item =
        new Item(
            group.id().toString(), group.scope() + "/" + group.kind(), inputs, text.toString());
    return new Named(group, refs, List.copyOf(members), shown, others, item);
  }

  /** A line's key among a group's intents: its other end and protocol. */
  static String lineKey(Groups.Line line) {
    return line.to() + " " + line.protocol();
  }

  /** The name of a line's other end: a group by its key's last part, else the element's. */
  private static String endName(
      Knowledge knowledge, Assembler assembler, List<Groups.Group> groups, EntityId end) {
    if (groups.stream().anyMatch(g -> g.id().equals(end))) {
      return "the group " + end;
    }
    return name(knowledge, assembler, end);
  }

  /**
   * The name diagrams give an element: a component by its anchor, as the component diagram names
   * it, the gateway's routes by what they are; anything else as the assembler names it.
   */
  static String name(Knowledge knowledge, Assembler assembler, EntityId element) {
    if (element.type().equals(io.lantern.core.vocabulary.BuiltInTypes.COMPONENT)) {
      for (Fact anchor : knowledge.relations(element, Components.ANCHORED_ON)) {
        EntityId main = Knowledge.object(anchor);
        return main.type().equals(io.lantern.core.vocabulary.BuiltInTypes.MODULE)
            ? C4Views.CONFIG_ROUTES
            : assembler.name(main);
      }
    }
    return assembler.name(element);
  }

  private static void facts(
      StringBuilder text, Refs refs, List<FingerprintInput> inputs, List<Fact> facts) {
    text.append("<facts>\n");
    for (Fact fact : FactLines.sorted(facts)) {
      String line = FactLines.line(fact);
      text.append('[').append(refs.add('F', key(fact), fact, line)).append("] ");
      text.append(line).append('\n');
      inputs.add(DescribeRole.input(fact));
    }
    text.append("</facts>\n");
  }

  private static String key(Fact fact) {
    return fact.claimId() != null ? fact.claimId() : fact.id();
  }

  /** The group's last key part, the name a responsibility must not restate. */
  static String signal(EntityId group) {
    String key = group.toString();
    return key.substring(key.lastIndexOf('/') + 1);
  }

  /**
   * The facts of an accepted answer: its name, responsibility and intents, and a merge suggestion
   * as a finding.
   *
   * @return whether it gave the group a name
   */
  private static boolean facts(
      Named n,
      JsonNode answer,
      String model,
      long snapshot,
      List<Fact> written,
      List<Fact> findings) {
    EntityId group = n.group().id();
    boolean hasName = false;
    for (String field : List.of(NAME, RESPONSIBILITY)) {
      Optional<Cited> text = cited(n, answer.get(field));
      if (text.isPresent()) {
        written.add(
            LlmFacts.text(
                ROLE,
                model,
                group,
                field.equals(NAME) ? DISPLAY_NAME : LlmFacts.RESPONSIBILITY,
                LlmFacts.C4,
                text.get().text(),
                text.get().facts(),
                text.get().basis(),
                snapshot));
        hasName |= field.equals(NAME);
      }
    }
    JsonNode lines = answer.path(LINES);
    for (Map.Entry<String, Shown> line : n.lines().entrySet()) {
      cited(n, lines.get(line.getKey()))
          .ifPresent(
              t ->
                  written.add(
                      LlmFacts.text(
                          ROLE,
                          model,
                          group,
                          LlmFacts.INTENT,
                          line.getValue().key(),
                          t.text(),
                          t.facts(),
                          t.basis(),
                          snapshot)));
    }
    JsonNode merge = answer.get(MERGE);
    if (merge != null && merge.path("with").isString()) {
      EntityId with = EntityId.parse(merge.path("with").stringValue());
      Optional<Cited> reason = cited(n, merge.get("reason"));
      if (reason.isPresent() && !with.equals(group)) {
        findings.add(mergeFinding(group, with, reason.get(), snapshot));
      }
    }
    return hasName;
  }

  /** A text of a stored answer with the facts it cites; empty if it cites nothing known. */
  record Cited(String text, List<Fact> facts, LlmFacts.Basis basis) {}

  private static Optional<Cited> cited(Named n, JsonNode node) {
    if (node == null || !node.isObject() || !node.path("text").isString()) {
      return Optional.empty();
    }
    List<Fact> facts = new ArrayList<>();
    boolean evidenced = false;
    for (JsonNode cite : node.path("cites")) {
      Optional<Object> thing = n.refs().thing(cite.asString());
      if (thing.isPresent() && thing.get() instanceof Fact fact && !facts.contains(fact)) {
        facts.add(fact);
        evidenced |=
            fact.origin().kind() == Origin.Kind.LLM
                && "evidenced".equals(fact.attributes().path("basis").asString());
      }
    }
    if (facts.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new Cited(
            node.path("text").stringValue(),
            facts,
            evidenced ? LlmFacts.Basis.EVIDENCED : LlmFacts.Basis.INFERRED));
  }

  /**
   * A merge suggestion as a finding, {@code finding:group-merge/<a>/<b>} with the two groups' keys
   * sorted, so the suggestion made from either side is one finding.
   */
  static Fact mergeFinding(EntityId group, EntityId with, Cited reason, long snapshot) {
    List<EntityId> pair = new ArrayList<>(List.of(group, with));
    pair.sort(Knowledge.BY_ID);
    ObjectNode attributes = FactJson.newObject();
    attributes.put("about", pair.get(0).toString());
    attributes.put("target", pair.get(1).toString());
    attributes.put("rule", Findings.NAME + "/" + GROUP_MERGE);
    attributes.put("reason", reason.text());
    var ids = attributes.putArray("facts");
    new LinkedHashSet<>(reason.facts()).forEach(f -> ids.add(f.id()));
    Fact fact =
        Fact.create(
            Findings.id(GROUP_MERGE, pair.get(0) + "/" + pair.get(1)),
            Findings.RAISED,
            FactObject.string(GROUP_MERGE),
            attributes,
            LlmFacts.origin(ROLE),
            LlmFacts.evidence(reason.facts()),
            LlmFacts.CONFIDENCE,
            Validity.from(snapshot));
    List<String> lineage = new ArrayList<>();
    reason.facts().stream()
        .map(Fact::claimId)
        .filter(java.util.Objects::nonNull)
        .distinct()
        .forEach(lineage::add);
    return lineage.isEmpty() ? fact : fact.withLineage(lineage);
  }

  // ---------------------------------------------------------------------------------------------
  // The packet role

  /** Lays out a request, reads each entry into the checks' form, and stores an accepted entry. */
  static final class Packets implements PacketRole {

    private final Map<String, Named> bySubject;
    private final Set<String> technologies;
    // Each item's request references to its own, from the layout of the request it is in.
    private final Map<String, Map<String, String>> toLocal = new ConcurrentHashMap<>();

    Packets(Map<String, Named> bySubject, Set<String> technologies) {
      this.bySubject = bySubject;
      this.technologies = Set.copyOf(technologies);
    }

    private Named named(Item item) {
      Named n = bySubject.get(item.subject());
      if (n == null) {
        throw new IllegalStateException("No Name groups item for " + item.subject());
      }
      return n;
    }

    private Refs.Resolved resolve(List<Item> packet) {
      List<String> renderings = new ArrayList<>();
      List<Refs> refs = new ArrayList<>();
      for (Item item : packet) {
        renderings.add(named(item).item().rendering());
        refs.add(named(item).refs());
      }
      Refs.Resolved resolved = Refs.resolve(renderings, refs);
      for (int i = 0; i < packet.size(); i++) {
        toLocal.put(packet.get(i).subject(), resolved.toLocal().get(i));
      }
      return resolved;
    }

    @Override
    public PromptLayout layout(List<Item> packet, List<String> ids) {
      Refs.Resolved resolved = resolve(packet);
      return new PromptLayout(
          CommonPrompt.SYSTEM,
          TASK + DescribeRole.SCHEMA_HEADING + SCHEMA,
          "",
          "",
          false,
          Blocks.items(ids, resolved.texts()));
    }

    @Override
    public int maxOutputTokens(int items) {
      return Math.max(MIN_OUTPUT_TOKENS, ITEM_OUTPUT_TOKENS * items);
    }

    @Override
    public int maxOutputTokens(List<Item> packet) {
      int tokens = 0;
      for (Item item : packet) {
        tokens += ITEM_OUTPUT_TOKENS + LINE_OUTPUT_TOKENS * named(item).lines().size();
      }
      return Math.max(MIN_OUTPUT_TOKENS, tokens);
    }

    @Override
    public CheckInputs inputs(List<Item> packet, List<String> ids) {
      return inputs(packet, ids, 0);
    }

    /**
     * Each item is checked against its own references only, as the request numbers them, with its
     * other groups' keys ({@code G..}), its members' names ({@code M..}) and its own key ({@value
     * #SELF}) for the group checks.
     */
    @Override
    public CheckInputs inputs(List<Item> packet, List<String> ids, int index) {
      Refs.Resolved resolved = resolve(packet);
      Named n = named(packet.get(index));
      Map<String, String> references = new LinkedHashMap<>(resolved.global().get(index));
      n.others().forEach((ref, group) -> references.put(ref, group.toString()));
      for (int i = 0; i < n.members().size(); i++) {
        references.put("M" + (i + 1), n.members().get(i));
      }
      references.put(SELF, n.group().id().toString());
      return new CheckInputs(
          ITEM_SCHEMA,
          references,
          CITABLE,
          false,
          (repo, path) -> Optional.empty(),
          Map.of(),
          Set.of());
    }

    @Override
    public Pipeline pipeline() {
      List<Check> checks = new ArrayList<>(Checks.STANDARD);
      checks.add(new GroupChecks(technologies));
      return new Pipeline(checks);
    }

    @Override
    public ItemAnswer read(String id, Item item, JsonNode entry) {
      Named n = named(item);
      List<CheckedText> texts = new ArrayList<>();
      if (entry != null && entry.isObject() && !entry.has("omitted")) {
        String signal = signal(n.group().id());
        text(entry.get(NAME))
            .ifPresent(t -> texts.add(checked(NAME, CheckedText.Kind.PROSE, t, NAME_WORDS, "")));
        text(entry.get(RESPONSIBILITY))
            .ifPresent(
                t ->
                    texts.add(
                        checked(
                            RESPONSIBILITY,
                            CheckedText.Kind.RESPONSIBILITY,
                            t,
                            SENTENCE_WORDS,
                            signal)));
        JsonNode lines = entry.get(LINES);
        if (lines != null && lines.isObject()) {
          for (String ref : n.lines().keySet()) {
            text(lines.get(ref))
                .ifPresent(
                    t ->
                        texts.add(
                            checked(
                                LINES + "." + ref, CheckedText.Kind.INTENT, t, PHRASE_WORDS, "")));
          }
        }
        JsonNode merge = entry.get(MERGE);
        if (merge != null && merge.isObject()) {
          text(merge.get("reason"))
              .ifPresent(
                  t ->
                      texts.add(
                          checked(MERGE + ".reason", CheckedText.Kind.PROSE, t, REASON_WORDS, "")));
        }
      }
      return new ItemAnswer(id, entry, texts, List.of());
    }

    /**
     * The accepted entry with its citations as the item numbers them and a merge's group by its
     * key, so the stored answer does not depend on which items shared its request.
     */
    @Override
    public String store(Item item, ItemAnswer accepted) {
      Named n = named(item);
      Map<String, String> back = toLocal.getOrDefault(item.subject(), Map.of());
      ObjectNode stored = JSON.createObjectNode();
      for (CheckedText text : accepted.texts()) {
        ObjectNode value = JSON.createObjectNode();
        value.put("text", text.text());
        var cites = value.putArray("cites");
        text.cites().forEach(c -> cites.add(back.getOrDefault(c, c)));
        String[] path = text.field().split("\\.", 2);
        if (path.length == 1) {
          stored.set(path[0], value);
        } else if (path[0].equals(MERGE)) {
          ObjectNode merge = stored.putObject(MERGE);
          String with = accepted.raw().path(MERGE).path("with").asString().strip();
          EntityId group = n.others().get(cleanRef(with));
          merge.put("with", group == null ? with : group.toString());
          merge.set("reason", value);
        } else {
          ObjectNode group =
              stored.has(path[0]) ? (ObjectNode) stored.get(path[0]) : stored.putObject(path[0]);
          group.set(path[1], value);
        }
      }
      return stored.toString();
    }
  }

  private static CheckedText checked(
      String field, CheckedText.Kind kind, DescribeRole.Text text, int words, String subject) {
    return new CheckedText(field, kind, text.text(), text.cites(), words, subject, Set.of());
  }

  private static Optional<DescribeRole.Text> text(JsonNode node) {
    if (node == null || !node.isObject() || !node.path("text").isString()) {
      return Optional.empty();
    }
    List<String> cites = new ArrayList<>();
    node.path("cites").forEach(c -> cites.add(cleanRef(c.asString())));
    return Optional.of(new DescribeRole.Text(node.path("text").stringValue().strip(), cites));
  }

  /** A reference as the model may write it ({@code [g3]}), as the checks read it. */
  static String cleanRef(String ref) {
    String clean = ref.strip();
    if (clean.startsWith("[") && clean.endsWith("]")) {
      clean = clean.substring(1, clean.length() - 1).strip();
    }
    return clean.matches("(?i)[fagrm][0-9]+") ? clean.toUpperCase(Locale.ROOT) : clean;
  }

  /**
   * The role's own checks (epic, section 11.8): a name is not a member's name and holds no protocol
   * word or technology; a merge names one of the item's other groups.
   */
  static final class GroupChecks implements Check {

    private final Set<String> technologies;

    GroupChecks(Set<String> technologies) {
      this.technologies = technologies;
    }

    @Override
    public String name() {
      return "Group";
    }

    @Override
    public List<Rejection> check(ItemAnswer answer, CheckInputs inputs) {
      List<Rejection> found = new ArrayList<>();
      if (answer.omitted() || answer.raw() == null) {
        return found;
      }
      for (CheckedText text : answer.texts()) {
        if (!text.field().equals(NAME)) {
          continue;
        }
        List<String> tokens = Checks.Form.tokens(text.text());
        for (Map.Entry<String, String> ref : inputs.references().entrySet()) {
          if (ref.getKey().matches("M[0-9]+")
              && Checks.Form.tokens(ref.getValue()).equals(tokens)) {
            found.add(
                reject(
                    answer,
                    "\""
                        + text.text()
                        + "\" is the name of a member, "
                        + ref.getValue()
                        + "; name what the group as a whole is about."));
            break;
          }
        }
        for (String word : Checks.PROTOCOL_WORDS) {
          if (contains(text.text(), word)) {
            found.add(
                reject(
                    answer,
                    "\""
                        + text.text()
                        + "\" names the protocol "
                        + word
                        + "; name what the group is about."));
            break;
          }
        }
        for (String technology : technologies) {
          if (technology.length() > 2 && contains(text.text(), technology)) {
            found.add(
                reject(
                    answer,
                    "\""
                        + text.text()
                        + "\" names the technology "
                        + technology
                        + "; name what the group is about."));
            break;
          }
        }
      }
      JsonNode merge = answer.raw().get(MERGE);
      if (merge != null && merge.isObject()) {
        String with = cleanRef(merge.path("with").asString(""));
        String target = inputs.references().get(with);
        if (!with.matches("G[0-9]+") || target == null) {
          found.add(
              new Rejection(
                  answer.item(),
                  MERGE,
                  name(),
                  "Merges with " + with + ", which is not one of this item's groups [G..]."));
        } else if (target.equals(inputs.references().get(SELF))) {
          found.add(
              new Rejection(
                  answer.item(), MERGE + ".with", name(), "Merges the group with itself."));
        }
      }
      return found;
    }

    private Rejection reject(ItemAnswer answer, String reason) {
      return new Rejection(answer.item(), NAME, name(), reason);
    }

    private static boolean contains(String text, String word) {
      return Pattern.compile(
              "(?<![\\p{L}\\p{N}])" + Pattern.quote(word) + "(?![\\p{L}\\p{N}])",
              Pattern.CASE_INSENSITIVE)
          .matcher(text)
          .find();
    }
  }
}

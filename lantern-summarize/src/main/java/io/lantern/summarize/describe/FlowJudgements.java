package io.lantern.summarize.describe;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Validity;
import io.lantern.core.types.Types;
import io.lantern.derive.FlowModel;
import io.lantern.derive.Knowledge;
import io.lantern.derive.ScopeFlows;
import io.lantern.harness.FingerprintInput;
import io.lantern.harness.Fingerprints;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The two flow roles (knowledge-build epic, section 11.5; ADR 0108): which traced flows are worth
 * drawing for a scope, and the narrative of each one drawn. Both answer in the harness's envelope,
 * one item a request, with block 2 the epic's instructions and schema, read from {@code
 * prompts/flow-*}.
 */
public final class FlowJudgements {

  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(FlowJudgements.class);

  /** The version of the significance prompt. */
  static final String SIGNIFICANCE_VERSION = "8";

  /** The version of the narrative prompt (4 was the prompt of ADR 0058). */
  static final String NARRATIVE_VERSION = "9";

  public static final String SIGNIFICANCE_TASK = DescribeRole.prompt("flow-significance.task.txt");
  public static final String SIGNIFICANCE_SCHEMA =
      DescribeRole.prompt("flow-significance.schema.json");
  public static final String NARRATIVE_TASK = DescribeRole.prompt("flow-narrative.task.txt");
  public static final String NARRATIVE_SCHEMA = DescribeRole.prompt("flow-narrative.schema.json");

  /** The scope a significance item is about, at most this many candidates, else in two passes. */
  static final int ONE_PASS = 60;

  /** The flows a unit's first-pass item keeps for the system's item. */
  public static final int KEPT_PER_UNIT = 15;

  static final io.lantern.harness.Contract.Range REASON_WORDS =
      io.lantern.harness.Contract.of("flow-significance").acceptedWords("reason");
  static final io.lantern.harness.Contract.Range TITLE_WORDS =
      io.lantern.harness.Contract.of("flow-narrative").acceptedWords("title");
  static final io.lantern.harness.Contract.Range WALKTHROUGH_WORDS =
      io.lantern.harness.Contract.of("flow-narrative").acceptedWords("walkthrough");
  static final io.lantern.harness.Contract.Range PRECONDITION_WORDS =
      io.lantern.harness.Contract.of("flow-narrative").acceptedWords("preconditions");
  static final io.lantern.harness.Contract.Range POSTCONDITION_WORDS =
      io.lantern.harness.Contract.of("flow-narrative").acceptedWords("postconditions");

  /** The repair turn when no flow is significant while some cross a unit boundary (the epic's). */
  public static final String NONE_SIGNIFICANT =
      "No flow is marked significant, yet some cross a unit boundary. Mark the ones that are, or"
          + " answer the same again to confirm.";

  /**
   * The output room of a significance answer for each flow it judges: a reason with its citations.
   */
  static final int TOKENS_PER_JUDGED_FLOW = 250;

  /**
   * The output room of a scope's significance answer: about 200 tokens a flow as measured, with
   * room to spare, and its order (#730). The model's thinking headroom comes on top.
   */
  static int significanceOutputTokens(int flows) {
    return Math.max(1_500, TOKENS_PER_JUDGED_FLOW * flows + 1_000);
  }

  /** The significance role: a judgement on the reasoning tier, with thinking. */
  public static final RoleSpec SIGNIFICANCE =
      RoleSpec.single(
              "flow-significance",
              Role.FLOW_SIGNIFICANCE,
              Prompts.version(
                  SIGNIFICANCE_VERSION,
                  "common.system.txt",
                  "flow-significance.task.txt",
                  "flow-significance.schema.json"),
              "flows-and-purpose",
              RunMode.INTERACTIVE,
              1_500)
          .withThinking(4_300);

  /** The narrative role: one flow a request, a long output. */
  public static final RoleSpec NARRATIVE =
      RoleSpec.single(
              "flow-narrative",
              Role.COMPOSE,
              Prompts.version(
                  NARRATIVE_VERSION,
                  "common.system.txt",
                  "flow-narrative.task.txt",
                  "flow-narrative.schema.json"),
              "flows-and-purpose",
              RunMode.BULK,
              750)
          .withThinking(2_300);

  static final String TITLE = "title";
  static final String WALKTHROUGH = "walkthrough";
  static final String PRECONDITIONS = "preconditions";
  static final String POSTCONDITIONS = "postconditions";
  static final List<String> NARRATIVE_FIELDS =
      List.of(TITLE, WALKTHROUGH, PRECONDITIONS, POSTCONDITIONS);

  static final List<String> CATEGORIES =
      List.of(
          "cross-service",
          "async-handoff",
          "failure-handling",
          "entry-by-people",
          "lifecycle",
          "scheduled",
          "data-read",
          "other");

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final JsonNode SIGNIFICANCE_ITEM_SCHEMA =
      optionalReason(JsonSchema.itemSchema(JSON.readTree(SIGNIFICANCE_SCHEMA)));
  private static final JsonNode NARRATIVE_ITEM_SCHEMA =
      JsonSchema.itemSchema(JSON.readTree(NARRATIVE_SCHEMA));
  private static final Set<Character> CITABLE = Set.of('F', 'A');

  private FlowJudgements() {}

  /**
   * The schema an answer is checked against: the epic's, with a flow's reason optional, so a flow
   * whose rejected reason is withdrawn in the repair turn stays judged (the prompt shows the
   * epic's).
   */
  private static JsonNode optionalReason(JsonNode itemSchema) {
    JsonNode copy = itemSchema.deepCopy();
    JsonNode flow = copy.path("properties").path("flows").path("additionalProperties");
    if (flow.path("required") instanceof ArrayNode required) {
      for (int i = required.size() - 1; i >= 0; i--) {
        if (required.get(i).asString().equals("reason")) {
          required.remove(i);
        }
      }
    }
    return copy;
  }

  // ---------------------------------------------------------------------------------------------
  // Items

  /**
   * An item with what its references stand for.
   *
   * @param item the harness item
   * @param refs its references: {@code F} facts, {@code A} earlier answers ({@link
   *     DescribeRole.Answered})
   * @param flows each flow it shows, by its key {@code W..} (significance items only)
   * @param crossing the keys of the flows that cross a unit boundary
   * @param containers the containers the item's steps name, which its texts may name without citing
   *     a fact that does (#685)
   * @param ranked the keys of its flows by rank, best first, which a repaired order follows (#744)
   */
  public record Asked(
      Item item,
      Refs refs,
      Map<String, ScopeFlows.Candidate> flows,
      Set<String> crossing,
      Set<String> containers,
      List<String> ranked) {

    /** An item whose flows are ranked as listed. */
    public Asked(
        Item item,
        Refs refs,
        Map<String, ScopeFlows.Candidate> flows,
        Set<String> crossing,
        Set<String> containers) {
      this(
          item,
          refs,
          flows,
          crossing,
          containers,
          flows == null ? List.of() : List.copyOf(flows.keySet()));
    }
  }

  /**
   * The significance item of a scope: each candidate flow with its trigger, containers, steps and
   * the facts behind them, its asynchronous hand-offs, and the responsibilities of the units the
   * flows cross.
   *
   * @param scope the scope's key, {@link io.lantern.derive.ArchitectureModel#SYSTEM_SCOPE} or a
   *     repository's name
   * @param title how the item names the scope, such as {@code the system}
   * @param candidates the flows, one for each shape
   * @param responsibilities the units' responsibility sentences, Describe's
   */
  static Asked significance(
      String scope,
      String title,
      List<ScopeFlows.Candidate> candidates,
      Map<EntityId, DescribeRole.Answered> responsibilities,
      java.util.function.Function<EntityId, String> names) {
    return significance(scope, title, candidates, candidates, responsibilities, names);
  }

  /**
   * As {@link #significance(String, String, List, Map, java.util.function.Function)}, with the
   * candidates' rank, best first, where they are listed in another order (#744).
   */
  static Asked significance(
      String scope,
      String title,
      List<ScopeFlows.Candidate> candidates,
      List<ScopeFlows.Candidate> byRank,
      Map<EntityId, DescribeRole.Answered> responsibilities,
      java.util.function.Function<EntityId, String> names) {
    Refs refs = new Refs();
    List<FingerprintInput> inputs = new ArrayList<>();
    StringBuilder text = new StringBuilder();
    text.append("Scope: ").append(title).append('\n');
    text.append("Flows, one for each different shape:\n");
    Map<String, ScopeFlows.Candidate> flows = new LinkedHashMap<>();
    Set<String> crossing = new LinkedHashSet<>();
    Set<EntityId> crossed = new LinkedHashSet<>();
    for (ScopeFlows.Candidate candidate : candidates) {
      String ref = "W" + (flows.size() + 1);
      flows.put(ref, candidate);
      if (candidate.crossesUnits()) {
        crossing.add(ref);
      }
      crossed.addAll(candidate.crossed());
      text.append('\n')
          .append('[')
          .append(Refs.token(ref))
          .append("] Starts when ")
          .append(FactLines.oneLine(candidate.starts()))
          .append('\n');
      appendFacts(text, refs, inputs, List.of(candidate.entry()), "  ");
      text.append("  Containers, in order: ")
          .append(String.join(", ", candidate.containers()))
          .append('\n');
      text.append("  Steps:\n");
      appendSteps(text, refs, inputs, candidate.steps(), Map.of());
      if (!candidate.async().isEmpty()) {
        text.append("  Hands work on asynchronously through: ")
            .append(String.join(", ", candidate.async()))
            .append('\n');
      }
    }
    List<String> said = new ArrayList<>();
    for (EntityId unit : crossed) {
      DescribeRole.Answered answered = responsibilities.get(unit);
      if (answered != null) {
        String sentence = FactLines.oneLine(answered.text());
        said.add(
            "- "
                + FactLines.oneLine(names.apply(unit))
                + ": ["
                + refs.add('A', unit.toString(), answered, sentence)
                + "] "
                + sentence);
        inputs.add(
            FingerprintInput.answer(
                Fingerprints.sha256(sentence), List.of(DescribeRole.UNITS.name() + " " + unit)));
      }
    }
    if (!said.isEmpty()) {
      text.append("\nWhat the units these flows cross do:\n");
      said.forEach(line -> text.append(line).append('\n'));
    }
    Item item = new Item(scope, scope, inputs, text.toString());
    Set<String> containers = new java.util.TreeSet<>();
    candidates.forEach(c -> containers.addAll(c.containers()));
    List<String> ranked = new ArrayList<>();
    for (ScopeFlows.Candidate candidate : byRank) {
      flows.forEach(
          (ref, listed) -> {
            if (listed == candidate && !ranked.contains(ref)) {
              ranked.add(ref);
            }
          });
    }
    flows.keySet().stream().filter(ref -> !ranked.contains(ref)).forEach(ranked::add);
    return new Asked(item, refs, flows, crossing, containers, List.copyOf(ranked));
  }

  /**
   * The first pass of a scope with more than {@value #ONE_PASS} candidate flows: one significance
   * item for the flows that start in each unit; none for a smaller scope, which is judged whole.
   */
  public static List<Asked> firstPass(
      String scope,
      List<ScopeFlows.Candidate> candidates,
      Map<EntityId, DescribeRole.Answered> responsibilities,
      java.util.function.Function<EntityId, String> names) {
    if (candidates.size() <= ONE_PASS) {
      return List.of();
    }
    Map<EntityId, List<ScopeFlows.Candidate>> byUnit = new java.util.TreeMap<>(Knowledge.BY_ID);
    candidates.forEach(c -> byUnit.computeIfAbsent(c.unit(), u -> new ArrayList<>()).add(c));
    List<Asked> items = new ArrayList<>();
    byUnit.forEach(
        (unit, flows) ->
            items.add(
                significance(
                    scope + "/" + unit,
                    "the flows that start in " + names.apply(unit),
                    flows,
                    responsibilities,
                    names)));
    return items;
  }

  /**
   * The flows of a first-pass item that go on to the second: those judged significant, in the
   * model's order, or, unjudged, as listed; at most {@value #KEPT_PER_UNIT}.
   *
   * @param order the IDs of the flows judged significant, in order, or null if not judged
   */
  public static List<ScopeFlows.Candidate> kept(Asked unitItem, List<String> order) {
    List<ScopeFlows.Candidate> flows = new ArrayList<>(unitItem.flows().values());
    if (order != null) {
      flows.removeIf(c -> !order.contains(c.flow().toString()));
      flows.sort(java.util.Comparator.comparingInt(c -> order.indexOf(c.flow().toString())));
    }
    return List.copyOf(flows.subList(0, Math.min(KEPT_PER_UNIT, flows.size())));
  }

  /**
   * The narrative item of a flow: its trigger, its steps with the facts behind each, and the
   * responsibilities and intents already worded for the units and interactions it crosses.
   */
  static Asked narrative(
      Narrated narrated,
      Map<EntityId, DescribeRole.Answered> responsibilities,
      Map<String, Describer.Phrase> phrases,
      java.util.function.Function<String, Fact> factsById,
      java.util.function.Function<EntityId, String> names) {
    ScopeFlows.Candidate flow = narrated.candidate();
    Refs refs = new Refs();
    List<FingerprintInput> inputs = new ArrayList<>();
    StringBuilder text = new StringBuilder();
    text.append("It starts when ").append(FactLines.oneLine(flow.starts())).append(".\n");
    appendFacts(text, refs, inputs, List.of(flow.entry()), "");
    text.append("Its steps, in order:\n");
    Map<String, String> intents = new LinkedHashMap<>();
    appendSteps(text, refs, inputs, flow.steps(), intents);
    List<String> earlier = new ArrayList<>();
    for (EntityId unit : flow.crossed()) {
      DescribeRole.Answered answered = responsibilities.get(unit);
      if (answered != null) {
        String sentence = FactLines.oneLine(answered.text());
        earlier.add(
            "- "
                + FactLines.oneLine(names.apply(unit))
                + ": ["
                + refs.add('A', unit.toString(), answered, sentence)
                + "] "
                + sentence);
        inputs.add(
            FingerprintInput.answer(
                Fingerprints.sha256(sentence), List.of(DescribeRole.UNITS.name() + " " + unit)));
      }
    }
    {
      for (var s : flow.steps()) {
        for (Fact fact : s.facts()) {
          Describer.Phrase phrase = phrases.get(fact.id());
          if (phrase != null && !intents.containsKey(fact.id())) {
            List<Fact> cited = new ArrayList<>();
            phrase
                .facts()
                .forEach(id -> Optional.ofNullable(factsById.apply(id)).ifPresent(cited::add));
            if (cited.isEmpty()) {
              cited.add(fact);
            }
            // The step by its ends, without its label: the intent says what it is for.
            String step = FactLines.oneLine(s.text());
            int label = step.indexOf(": ");
            String line =
                (label < 0 ? step : step.substring(0, label))
                    + ": "
                    + FactLines.oneLine(phrase.text());
            intents.put(fact.id(), line);
            earlier.add(
                "- ["
                    + refs.add(
                        'A',
                        "intent " + fact.id(),
                        new DescribeRole.Answered(phrase.text(), cited),
                        line)
                    + "] "
                    + line);
            inputs.add(FingerprintInput.answer(Fingerprints.sha256(line)));
          }
        }
      }
    }
    if (!earlier.isEmpty()) {
      text.append("What its units do, and what its interactions are for:\n");
      earlier.forEach(line -> text.append(line).append('\n'));
    }
    Item item = new Item(narrated.key(), flow.unit().toString(), inputs, text.toString());
    return new Asked(
        item, refs, Map.of(), Set.of(), new java.util.TreeSet<>(narrated.candidate().containers()));
  }

  private static void appendSteps(
      StringBuilder text,
      Refs refs,
      List<FingerprintInput> inputs,
      List<ScopeFlows.StepLine> steps,
      Map<String, String> unused) {
    for (ScopeFlows.StepLine step : steps) {
      String indent = "  ".repeat(step.depth() + 1);
      text.append(indent).append("- ").append(FactLines.oneLine(step.text())).append('\n');
      appendFacts(text, refs, inputs, step.facts(), indent + "  ");
    }
  }

  /**
   * Writes each fact's line under its reference; a fact the item showed before is written by its
   * reference alone, so a fact many steps or flows rest on is shown once.
   */
  private static void appendFacts(
      StringBuilder text,
      Refs refs,
      List<FingerprintInput> inputs,
      List<Fact> facts,
      String indent) {
    for (Fact fact : FactLines.sorted(facts)) {
      String key = fact.claimId() != null ? fact.claimId() : fact.id();
      boolean shown = refs.shown('F', key);
      String line = FactLines.line(fact);
      text.append(indent).append('[').append(refs.add('F', key, fact, line)).append("] ");
      text.append(shown ? "(as above)" : line).append('\n');
      if (!shown) {
        inputs.add(DescribeRole.input(fact));
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Asking

  /** Asks one item alone, through a role laid out for it, and returns its outcome. */
  static Outcome ask(RoleSession session, Asked asked, boolean narrative) {
    OneItem role = new OneItem(asked, narrative);
    return session.askPackets(List.of(asked.item()), role).get(asked.item().subject());
  }

  /**
   * The layout, checks and storage of one item. A significance item may be confirmed: an answer
   * that marks nothing significant while some flows cross a unit boundary is sent back once, and
   * the same answer again is taken.
   */
  static final class OneItem implements PacketRole {

    private final Asked asked;
    private final boolean narrative;
    private final AtomicInteger reads = new AtomicInteger();
    private final Map<ItemAnswer, Integer> attempts =
        Collections.synchronizedMap(new IdentityHashMap<>());

    OneItem(Asked asked, boolean narrative) {
      this.asked = asked;
      this.narrative = narrative;
    }

    @Override
    public PromptLayout layout(List<Item> packet, List<String> ids) {
      Refs.Resolved resolved =
          Refs.resolve(List.of(asked.item().rendering()), List.of(asked.refs()));
      String block =
          narrative
              ? NARRATIVE_TASK + DescribeRole.SCHEMA_HEADING + NARRATIVE_SCHEMA
              : SIGNIFICANCE_TASK + DescribeRole.SCHEMA_HEADING + SIGNIFICANCE_SCHEMA;
      return new PromptLayout(
          CommonPrompt.SYSTEM, block, "", "", false, Blocks.items(ids, resolved.texts()));
    }

    @Override
    public int maxOutputTokens(int items) {
      return narrative ? 1_500 : significanceOutputTokens(asked.flows().size());
    }

    @Override
    public CheckInputs inputs(List<Item> packet, List<String> ids) {
      return new CheckInputs(
          narrative ? NARRATIVE_ITEM_SCHEMA : SIGNIFICANCE_ITEM_SCHEMA,
          asked.refs().lines(),
          CITABLE,
          false,
          (repo, path) -> Optional.empty(),
          Map.of(),
          Set.of(),
          asked.containers());
    }

    @Override
    public Pipeline pipeline() {
      if (narrative) {
        return Pipeline.STANDARD;
      }
      List<Check> checks = new ArrayList<>(Checks.STANDARD);
      checks.add(new SignificanceCheck());
      return new Pipeline(checks);
    }

    @Override
    public ItemAnswer read(String id, Item item, JsonNode entry) {
      if (!narrative) {
        entry = withOrderRepaired(entry);
      }
      List<CheckedText> texts = new ArrayList<>();
      if (entry != null && entry.isObject() && !entry.has("omitted")) {
        if (narrative) {
          for (String field : NARRATIVE_FIELDS) {
            text(entry.get(field))
                .ifPresent(
                    t ->
                        texts.add(
                            new CheckedText(
                                field,
                                CheckedText.Kind.PROSE,
                                t.text(),
                                t.cites(),
                                switch (field) {
                                  case TITLE -> TITLE_WORDS;
                                  case WALKTHROUGH -> WALKTHROUGH_WORDS;
                                  case PRECONDITIONS -> PRECONDITION_WORDS;
                                  default -> POSTCONDITION_WORDS;
                                },
                                "",
                                Set.of())));
          }
        } else {
          JsonNode flows = entry.path("flows");
          for (String ref : asked.flows().keySet()) {
            text(flows.path(ref).get("reason"))
                .ifPresent(
                    t ->
                        texts.add(
                            new CheckedText(
                                // Only the reason: a flow whose reason is withdrawn stays judged.
                                "flows." + ref + ".reason",
                                CheckedText.Kind.PROSE,
                                t.text(),
                                t.cites(),
                                REASON_WORDS,
                                "",
                                Set.of())));
          }
        }
      }
      ItemAnswer answer = new ItemAnswer(id, entry, texts, List.of());
      attempts.put(answer, reads.incrementAndGet());
      return answer;
    }

    /**
     * A narrative keeps its wording while what it cites is unchanged; a significance reason does
     * not, since it explains a verdict the model may change (#722, ADR 0126).
     */
    @Override
    public boolean carries(CheckedText text) {
      return narrative;
    }

    /**
     * The fields the item asks for, which the run record counts when left out (#741): a narrative's
     * four parts, or a reason for each flow judged.
     */
    @Override
    public List<String> askedFields(Item item) {
      if (narrative) {
        return NARRATIVE_FIELDS;
      }
      return asked.flows().keySet().stream().map(ref -> "flows." + ref + ".reason").toList();
    }

    /**
     * The accepted entry: a significance answer with each flow and the order by its reference in
     * the item, which stands for the same flow whenever the item is shown the same; a narrative as
     * it was written. Citations are the item's own, as it is asked alone.
     */
    @Override
    public String store(Item item, ItemAnswer accepted) {
      if (narrative) {
        ObjectNode stored = JSON.createObjectNode();
        for (CheckedText text : accepted.texts()) {
          ObjectNode value = stored.putObject(text.field());
          value.put("text", text.text());
          ArrayNode cites = value.putArray("cites");
          text.cites().forEach(cites::add);
        }
        return stored.toString();
      }
      JsonNode raw = accepted.raw();
      ObjectNode stored = JSON.createObjectNode();
      ObjectNode flows = stored.putObject("flows");
      asked
          .flows()
          .forEach(
              (ref, candidate) -> {
                JsonNode answer = raw.path("flows").path(ref);
                ObjectNode flow = flows.putObject(ref);
                flow.put("significant", answer.path("significant").asBoolean(false));
                flow.put("category", answer.path("category").asString("other"));
                accepted.texts().stream()
                    .filter(t -> t.field().equals("flows." + ref + ".reason"))
                    .findFirst()
                    .ifPresent(
                        t -> {
                          ObjectNode reason = flow.putObject("reason");
                          reason.put("text", t.text());
                          ArrayNode cites = reason.putArray("cites");
                          t.cites().forEach(cites::add);
                        });
              });
      ArrayNode order = stored.putArray("order");
      for (JsonNode ref : raw.path("order")) {
        String clean = cleanRef(ref.asString());
        if (asked.flows().containsKey(clean)) {
          order.add(clean);
        }
      }
      return stored.toString();
    }

    /**
     * The entry with its order holding exactly the flows it marks significant (#744, ADR 0108): an
     * entry not marked significant is removed, and a significant flow the order leaves out is added
     * after the others, by the scope's rank. A mechanical fix needs no repair turn.
     */
    private JsonNode withOrderRepaired(JsonNode entry) {
      if (!(entry instanceof ObjectNode object) || !entry.path("flows").isObject()) {
        return entry;
      }
      List<String> significant = new ArrayList<>();
      for (String ref : asked.flows().keySet()) {
        if (entry.path("flows").path(ref).path("significant").asBoolean(false)) {
          significant.add(ref);
        }
      }
      List<String> written = new ArrayList<>();
      entry.path("order").forEach(ref -> written.add(cleanRef(ref.asString(""))));
      List<String> order = new ArrayList<>();
      for (String ref : written) {
        if (significant.contains(ref) && !order.contains(ref)) {
          order.add(ref);
        }
      }
      for (String ref : asked.ranked()) {
        if (significant.contains(ref) && !order.contains(ref)) {
          order.add(ref);
        }
      }
      if (order.equals(written) && entry.path("order").isArray()) {
        return entry;
      }
      LOG.info(
          "Repaired the significance order of {} from {} to {}",
          asked.item().subject(),
          written,
          order);
      ObjectNode repaired = object.deepCopy();
      ArrayNode array = repaired.putArray("order");
      order.forEach(array::add);
      return repaired;
    }

    /**
     * The epic's checks of a significance answer: every flow answered, the order holding exactly
     * the significant flows, and, once, none significant while some cross a unit boundary.
     */
    private final class SignificanceCheck implements Check {

      @Override
      public String name() {
        return "Significance";
      }

      @Override
      public List<Rejection> check(ItemAnswer answer, CheckInputs inputs) {
        List<Rejection> found = new ArrayList<>();
        JsonNode raw = answer.raw();
        if (raw == null || !raw.isObject()) {
          return found;
        }
        JsonNode flows = raw.path("flows");
        List<String> missing = new ArrayList<>();
        Set<String> significant = new LinkedHashSet<>();
        for (String ref : asked.flows().keySet()) {
          JsonNode flow = flows.get(ref);
          if (flow == null || !flow.isObject() || !flow.path("significant").isBoolean()) {
            missing.add(ref);
          } else if (flow.path("significant").asBoolean()) {
            significant.add(ref);
          }
        }
        if (!missing.isEmpty()) {
          found.add(
              reject(
                  answer,
                  "Answers no flow for "
                      + String.join(", ", missing)
                      + "; answer every flow, significant or not."));
        }
        Set<String> ordered = new LinkedHashSet<>();
        List<String> strays = new ArrayList<>();
        for (JsonNode ref : raw.path("order")) {
          String clean = cleanRef(ref.asString());
          if (!significant.contains(clean)) {
            strays.add(clean);
          }
          ordered.add(clean);
        }
        Set<String> unordered = new LinkedHashSet<>(significant);
        unordered.removeAll(ordered);
        if (!strays.isEmpty() || !unordered.isEmpty()) {
          found.add(
              reject(
                  answer,
                  "The order must hold exactly the flows marked significant"
                      + (strays.isEmpty() ? "" : "; it holds " + String.join(", ", strays))
                      + (unordered.isEmpty()
                          ? ""
                          : "; it leaves out " + String.join(", ", unordered))
                      + "."));
        }
        int attempt = attempts.getOrDefault(answer, 1);
        if (missing.isEmpty()
            && significant.isEmpty()
            && !asked.crossing().isEmpty()
            && attempt == 1) {
          found.add(reject(answer, NONE_SIGNIFICANT));
        }
        return found;
      }

      private Rejection reject(ItemAnswer answer, String reason) {
        return new Rejection(answer.item(), "", name(), reason);
      }
    }
  }

  /** A reference as the model may write it ({@code [w3]}). */
  static String cleanRef(String ref) {
    String clean = ref == null ? "" : ref.strip();
    if (clean.startsWith("[") && clean.endsWith("]")) {
      clean = clean.substring(1, clean.length() - 1).strip();
    }
    return clean.matches("(?i)[wfa][0-9]+") ? clean.toUpperCase(java.util.Locale.ROOT) : clean;
  }

  private static Optional<DescribeRole.Text> text(JsonNode node) {
    if (node == null || !node.isObject() || !node.path("text").isString()) {
      return Optional.empty();
    }
    List<String> cites = new ArrayList<>();
    node.path("cites").forEach(c -> cites.add(cleanRef(c.asString())));
    return Optional.of(new DescribeRole.Text(node.path("text").stringValue().strip(), cites));
  }

  // ---------------------------------------------------------------------------------------------
  // Reading answers back

  /** A text of a stored answer with the facts it cites, directly or through an earlier answer. */
  record Cited(String text, List<Fact> facts) {}

  static Optional<Cited> cited(Asked asked, JsonNode node) {
    if (node == null || !node.isObject() || !node.path("text").isString()) {
      return Optional.empty();
    }
    List<Fact> facts = new ArrayList<>();
    for (JsonNode cite : node.path("cites")) {
      Optional<Object> thing = asked.refs().thing(cite.asString());
      if (thing.isEmpty()) {
        continue;
      }
      if (thing.get() instanceof Fact fact) {
        if (!facts.contains(fact)) {
          facts.add(fact);
        }
      } else if (thing.get() instanceof DescribeRole.Answered answered) {
        answered.cited().stream().filter(f -> !facts.contains(f)).forEach(facts::add);
      }
    }
    return facts.isEmpty()
        ? Optional.empty()
        : Optional.of(new Cited(node.path("text").stringValue(), facts));
  }

  /**
   * A scope's significance, read from its stored answer: the flows significant in its order, and a
   * {@code flow-significance} claim for every flow it judged.
   */
  record Judged(List<String> order, List<Fact> facts) {}

  static Judged judged(
      Asked asked,
      String stored,
      String scope,
      String model,
      String fingerprint,
      Knowledge knowledge,
      long snapshot) {
    JsonNode answer = JSON.readTree(stored);
    List<String> order = order(asked, stored);
    List<Fact> facts = new ArrayList<>();
    asked
        .flows()
        .forEach(
            (ref, candidate) -> {
              JsonNode flow = answer.path("flows").path(ref);
              if (!flow.isObject()) {
                return;
              }
              Optional<Cited> reason = cited(asked, flow.get("reason"));
              ObjectNode attributes = FactJson.newObject();
              boolean significant = flow.path("significant").asBoolean(false);
              attributes.put("significant", significant);
              attributes.put("category", flow.path("category").asString("other"));
              int rank = order.indexOf(candidate.flow().toString());
              if (significant && rank >= 0) {
                attributes.put("rank", rank + 1);
              }
              List<Fact> cited = reason.map(Cited::facts).orElse(List.of(candidate.entry()));
              reason.ifPresent(r -> attributes.put("text", r.text()));
              ArrayNode cites = attributes.putArray("cites");
              new LinkedHashSet<>(cited).forEach(f -> cites.add(f.id()));
              attributes.put("basis", LlmFacts.Basis.INFERRED.jsonName());
              attributes.put("model", model);
              attributes.put(LlmFacts.ANSWER, fingerprint);
              FlowModel.content(knowledge, candidate.flow())
                  .ifPresent(c -> attributes.put("content", c));
              facts.add(
                  fact(
                      candidate.flow(),
                      Types.FLOW_SIGNIFICANCE,
                      scope,
                      attributes,
                      cited,
                      snapshot));
            });
    return new Judged(order, facts);
  }

  /** The IDs of the flows a stored significance answer judged significant, in its order. */
  static List<String> order(Asked asked, String stored) {
    List<String> order = new ArrayList<>();
    for (JsonNode ref : JSON.readTree(stored).path("order")) {
      ScopeFlows.Candidate candidate = asked.flows().get(ref.asString());
      if (candidate != null) {
        order.add(candidate.flow().toString());
      }
    }
    return order;
  }

  /** A flow's narrative, read from its stored answer, as the composer shows it and as a claim. */
  record Narrative(Flow flow, Fact fact) {}

  static Optional<Narrative> narrative(
      Asked asked,
      String stored,
      EntityId flow,
      String model,
      String fingerprint,
      Knowledge knowledge,
      long snapshot) {
    JsonNode answer = JSON.readTree(stored);
    Map<String, Cited> texts = new LinkedHashMap<>();
    for (String field : NARRATIVE_FIELDS) {
      cited(asked, answer.get(field)).ifPresent(c -> texts.put(field, c));
    }
    if (texts.isEmpty()) {
      return Optional.empty();
    }
    ObjectNode attributes = FactJson.newObject();
    List<Fact> all = new ArrayList<>();
    ObjectNode fieldCites = FactJson.newObject();
    texts.forEach(
        (field, cited) -> {
          attributes.put(field, cited.text());
          cited.facts().stream().filter(f -> !all.contains(f)).forEach(all::add);
          ArrayNode own = fieldCites.putArray(field);
          new LinkedHashSet<>(cited.facts()).forEach(f -> own.add(f.id()));
        });
    ArrayNode cites = attributes.putArray("cites");
    all.forEach(f -> cites.add(f.id()));
    // Each text cites its own facts, as the document shows them beside it.
    attributes.set("fieldCites", fieldCites);
    attributes.put("basis", LlmFacts.Basis.INFERRED.jsonName());
    attributes.put("model", model);
    attributes.put(LlmFacts.ANSWER, fingerprint);
    FlowModel.content(knowledge, flow).ifPresent(c -> attributes.put("content", c));
    Cited title = texts.get(TITLE);
    Flow wording =
        new Flow(
            marked(texts.get(WALKTHROUGH)),
            marked(texts.get(PRECONDITIONS)),
            marked(texts.get(POSTCONDITIONS)),
            title == null ? null : title.text().replaceAll("[.\\s]+$", ""));
    return Optional.of(
        new Narrative(
            wording, fact(flow, Types.FLOW_NARRATIVE, LlmFacts.C4, attributes, all, snapshot)));
  }

  /** A text with the citation markers documents read, {@code [fact:<fact ID>]}. */
  private static String marked(Cited cited) {
    if (cited == null) {
      return null;
    }
    StringBuilder text = new StringBuilder(cited.text());
    cited.facts().forEach(f -> text.append(" [fact:").append(f.id()).append(']'));
    return text.toString();
  }

  /**
   * A flow the narrative role is asked about: one a scope shows.
   *
   * @param key the key its wording is found under, from how it is traced
   * @param candidate what it shows
   */
  record Narrated(String key, ScopeFlows.Candidate candidate) {}

  /**
   * The flows a scope shows, for the narrative role, in the order shown.
   *
   * @param judged the flows the model judged significant for the scope, in its order, by ID; null
   *     when it did not judge them
   */
  static List<Narrated> narrated(ScopeFlows flows, List<String> judged) {
    return flows.chosen(judged).stream()
        .map(t -> new Narrated(flowKey(question(t).user()), flows.candidate(t)))
        .toList();
  }

  /**
   * The prompt asking the LLM to word the flow (ADR 0058): what starts it and its steps by the
   * facts behind them, without the LLM's intent phrases, so it rests on facts only and does not
   * change with them.
   */
  private static WordingPrompt.Question question(ScopeFlows.Trace trace) {
    ScopeFlows.Entry entry = trace.entry();
    ScopeFlows.Heading heading = trace.heading();
    return WordingPrompt.flow(
        entry.method(),
        heading.title(),
        heading.starts() + " (" + ScopeFlows.methodName(entry.method()) + ")",
        entry.fact(),
        trace.outline());
  }

  private static Fact fact(
      EntityId flow,
      String predicate,
      String object,
      ObjectNode attributes,
      List<Fact> cited,
      long snapshot) {
    RoleSpec role = predicate.equals(Types.FLOW_SIGNIFICANCE) ? SIGNIFICANCE : NARRATIVE;
    Fact fact =
        Fact.create(
            flow,
            predicate,
            FactObject.string(object),
            attributes,
            LlmFacts.origin(role),
            LlmFacts.evidence(cited),
            LlmFacts.CONFIDENCE,
            Validity.from(snapshot));
    List<String> lineage =
        cited.stream().map(Fact::claimId).filter(java.util.Objects::nonNull).distinct().toList();
    return lineage.isEmpty() ? fact : fact.withLineage(lineage);
  }

  /**
   * A flow's walkthrough and conditions, each with citation markers {@code [fact:<fact ID>]}, or
   * {@code null} where the model left it out.
   *
   * @param walkthrough two to four sentences of what the flow does
   * @param preconditions what must hold before it starts
   * @param postconditions what has changed when it ends
   * @param title the scenario the flow is, a few words without citation markers
   */
  public record Flow(
      String walkthrough, String preconditions, String postconditions, String title) {

    public Flow(String walkthrough, String preconditions, String postconditions) {
      this(walkthrough, preconditions, postconditions, null);
    }
  }

  /**
   * The key of a flow's wording: the SHA-256 of the user turn of the prompt about it, which shows
   * no fact IDs.
   */
  static String flowKey(String prompt) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(prompt.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}

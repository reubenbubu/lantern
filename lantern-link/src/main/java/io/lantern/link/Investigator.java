package io.lantern.link;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.types.Types;
import io.lantern.harness.FileRead;
import io.lantern.harness.FingerprintInput;
import io.lantern.harness.Fingerprints;
import io.lantern.harness.Harness;
import io.lantern.harness.Item;
import io.lantern.harness.LlmFacts;
import io.lantern.harness.Outcome;
import io.lantern.harness.Prompts;
import io.lantern.harness.RoleSession;
import io.lantern.harness.RoleSpec;
import io.lantern.harness.RunMode;
import io.lantern.harness.checks.CommonPrompt;
import io.lantern.llm.Capability;
import io.lantern.llm.ConversationLimits;
import io.lantern.llm.ModelConfig;
import io.lantern.llm.Role;
import io.lantern.llm.Tokens;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Investigate role (knowledge-build epic, section 11.2; ADR 0110), on the LLM harness: the LLM
 * finds what static analysis misses, every finding cited to file ranges Lantern verifies.
 *
 * <ul>
 *   <li><b>Mode A</b> checks the leads derivation stored ({@code lead-trigger} facts, #524): each
 *       lead is an item showing why it fired, what to judge, its facts and the code they were read
 *       from; a unit's leads go four to a conversation, with {@code read_file}, {@code
 *       find_in_code} and {@code facts_about}, at most 6 tool turns. Each gets a verdict,
 *       confirmed, refuted or unclear; a lead left unanswered is asked again alone, once, and is
 *       unclear if it is unanswered again.
 *   <li><b>Mode B</b> explores each deployable unit, after its leads, for what no lead covers: one
 *       conversation each, with every read tool, at most 16 tool turns and 120,000 input tokens.
 *   <li><b>Mode C</b> explores the whole system, after every unit, for risks and connections across
 *       units: one conversation, with the same tools and caps.
 * </ul>
 *
 * <p>A verdict is a {@code lead-verdict} claim of the lead, with lineage to it; a confirmed lead,
 * and a risk Mode B or C found, an {@code investigated-risk} of its first unit, keyed by the lead's
 * claim, else by its names; a risk that restates a finding a fixed check raised is that finding's
 * {@code finding-wording} instead; a connection an {@code INVESTIGATED_CONNECTION} from its unit,
 * keyed by its ends and interaction. All are of origin {@code llm/investigate}, their evidence the
 * ranges they cite. A unit or the system a budget, a failure or a model without tool use left out
 * is {@code not-investigated}.
 *
 * <p>Every item is cached by what the model sees, and reused while every file its conversation read
 * or cites keeps its blob, so a rerun over unchanged facts and code makes no call. It never fails
 * the run.
 */
public final class Investigator {

  /** The origin name of its facts. */
  public static final String NAME = "investigate";

  /** The lead kind a finding's kind is about, for whether a risk restates it. */
  static final Map<String, String> FINDING_LEADS =
      Map.of(
          "version-skew", "shared-type-skew",
          "shared-jwt-secret", "plaintext-secret",
          "unrouted-endpoint", "contract-mismatch");

  /** The confidence of a fact it stores, by the model's own (ADR 0084). */
  static final Map<String, Double> CONFIDENCE = Map.of("high", 0.6, "medium", 0.45, "low", 0.3);

  /** The most tokens a turn's answer may take, thinking aside. */
  static final int MAX_OUTPUT_TOKENS = 4_000;

  /**
   * The most leads one Mode A conversation holds by default: 1, the setting in use ({@code
   * lantern.harness.items-per-request.investigate-leads=1}, ADR 0110, amendment for #529). The
   * epic's starting size was 4 (knowledge-build epic, section 11.2); the calibration kept 1.
   */
  static final int LEADS_PER_PACKET = 1;

  /**
   * The caps of a Mode A conversation (#892): small tool answers keep each turn a small increment
   * on a cached conversation, so turns are cheap and a safety cap of 100 is enough (the owner's
   * decision of 5 Nov 2026); the guards are its size, 60,000 tokens a turn, well under Haiku's
   * 100,000-token price line, and its cost, 200,000 input tokens in fresh-token equivalents. Near a
   * guard the model is told to answer, and has 3 turns in which only check and submit run.
   */
  static final ConversationLimits LEAD_LIMITS =
      new ConversationLimits(100, 200_000, MAX_OUTPUT_TOKENS)
          .withContextTokens(60_000)
          .withAnswerTurns(3, Set.of("submit", "check"))
          .withNudge(
              "You are close to what this task allows. Stop exploring: from now on only check and"
                  + " submit work. Give your answer with what you have found; use the verdict"
                  + " unclear if it does not settle the lead.");

  /** The caps of a Mode B or C conversation: 16 tool turns, 120,000 input tokens. */
  static final ConversationLimits EXPLORE_LIMITS =
      new ConversationLimits(16, 120_000, MAX_OUTPUT_TOKENS);

  /** Mode A as the harness runs it. */
  public static final RoleSpec LEADS =
      RoleSpec.single(
              "investigate-leads",
              Role.INVESTIGATE,
              Prompts.version(
                  InvestigatePrompt.VERSION,
                  "common.system.txt",
                  "investigate-leads.task.txt",
                  "investigate.schema.json"),
              NAME,
              RunMode.INTERACTIVE,
              1_500)
          .withPacketSize(LEADS_PER_PACKET)
          .withTools(List.of(), LEAD_LIMITS)
          .withThinking(210)
          .withTurns(6.2, 1_800);

  /** Mode B as the harness runs it. */
  public static final RoleSpec UNITS =
      RoleSpec.single(
              "investigate-units",
              Role.INVESTIGATE,
              Prompts.version(
                  InvestigatePrompt.VERSION,
                  "common.system.txt",
                  "investigate-explore.task.txt",
                  "investigate.schema.json"),
              NAME,
              RunMode.INTERACTIVE,
              3_000)
          .withTools(List.of(), EXPLORE_LIMITS)
          .withThinking(560)
          .withTurns(14.8, 2_150);

  /** Mode C as the harness runs it. */
  public static final RoleSpec SYSTEM_ROLE =
      RoleSpec.single(
              "investigate-system",
              Role.INVESTIGATE,
              Prompts.version(
                  InvestigatePrompt.VERSION,
                  "common.system.txt",
                  "investigate-explore.task.txt",
                  "investigate.schema.json"),
              NAME,
              RunMode.INTERACTIVE,
              3_000)
          .withTools(List.of(), EXPLORE_LIMITS)
          .withThinking(560)
          .withTurns(15, 3_000);

  private static final Origin ORIGIN = new Origin(Origin.Kind.LLM, NAME, InvestigatePrompt.VERSION);

  private static final Logger LOG = LoggerFactory.getLogger(Investigator.class);

  private static final Pattern RANGE =
      Pattern.compile("^([^:\\s]+):([^:\\s]+):(\\d+)(?:-(\\d+))?$");

  /**
   * What a run of Investigate did.
   *
   * @param facts the facts it records, all of origin {@code llm/investigate}
   * @param asked how many items the model was asked in this run
   * @param reused how many answers came from the cache
   * @param confirmed how many leads it confirmed
   * @param notInvestigated the units, and the system, it left out
   */
  public record Result(
      List<Fact> facts, int asked, int reused, int confirmed, List<String> notInvestigated) {

    public Result {
      facts = List.copyOf(facts);
      notInvestigated = List.copyOf(notInvestigated);
    }

    /** The facts by their origin, as they are recorded. */
    public Map<Origin, List<Fact>> byOrigin() {
      Map<Origin, List<Fact>> byOrigin = new LinkedHashMap<>();
      byOrigin.put(ORIGIN, facts);
      return byOrigin;
    }
  }

  private final Harness harness;
  private final GraphTools.RepoFiles files;
  private final Map<String, String> judges;

  /**
   * @param harness the LLM harness
   * @param files the snapshot's files, which the tools read and the citations are checked against
   * @param judges what the model judges for each kind of lead (lantern-derive's {@code
   *     LeadRules.judges()})
   */
  public Investigator(Harness harness, GraphTools.RepoFiles files, Map<String, String> judges) {
    this.harness = Objects.requireNonNull(harness, "harness");
    this.files = Objects.requireNonNull(files, "files");
    this.judges = Map.copyOf(judges);
  }

  /**
   * Whether a fact is one Investigate reads: a static fact, a summary or a resolved link, the LLM
   * facts made before it in a run. What Describe and the wordings stored in an earlier run, and its
   * own earlier facts, are left out, so a rerun over the same commits asks nothing again.
   */
  private static boolean before(Fact fact) {
    return fact.origin().kind() != Origin.Kind.LLM
        || fact.predicate().equals(Types.SUMMARY)
        || fact.origin().name().equals(LinkResolver.NAME);
  }

  /** What the snapshot's facts lead Investigate to, what it reads only (see before). */
  public Result investigate(Collection<Fact> all, long snapshot) {
    return investigate(all, snapshot, lead -> true, true);
  }

  /**
   * Only the leads {@code chosen} keeps, each checked as a run checks it (Mode A), with no unit or
   * system explored: how the Investigate test measures it on a fixed panel of leads (#890).
   *
   * @param chosen whether to check a lead, by its ID
   */
  public Result leads(Collection<Fact> all, long snapshot, Predicate<String> chosen) {
    return investigate(all, snapshot, chosen, false);
  }

  private Result investigate(
      Collection<Fact> all, long snapshot, Predicate<String> chosen, boolean explore) {
    List<Fact> facts = all.stream().filter(Investigator::before).toList();
    Model model = new Model(facts);
    model.leads.removeIf(lead -> !chosen.test(lead.id()));
    if (model.units.isEmpty()) {
      return new Result(List.of(), 0, 0, 0, List.of());
    }
    GraphTools.Graph graph = new GraphTools.Graph(facts, files);
    ModelConfig config = harness.model(Role.INVESTIGATE);
    String modelName = config.provider() + "/" + config.model();
    Validity validity = Validity.from(snapshot);
    Run run = new Run(model, graph, modelName, validity);
    if (!config.supports(Capability.TOOL_USE)) {
      String reason = "the model cannot use tools, which Investigate answers through";
      model.units.keySet().forEach(unit -> run.notInvestigated(unit, reason));
      run.notInvestigated(EntityId.SYSTEM.toString(), reason);
      return run.result();
    }
    run.leads();
    if (explore) {
      run.units();
      run.system();
    }
    return run.result();
  }

  /** A proposal's key: a risk's names sorted, a connection's ends and interaction. */
  static String proposalKey(JsonNode proposal) {
    if ("connection".equals(proposal.path("kind").asString(""))) {
      return "connection "
          + proposal.path("from").asString("")
          + " "
          + proposal.path("to").asString("")
          + " "
          + proposal.path("interaction").asString("");
    }
    return "risk " + String.join(",", sortedNames(proposal));
  }

  private static List<String> sortedNames(JsonNode entry) {
    Set<String> names = new TreeSet<>();
    for (JsonNode name : entry.path("names")) {
      if (name.isString()) {
        names.add(name.stringValue().strip());
      }
    }
    return List.copyOf(names);
  }

  // ---------------------------------------------------------------------------------------------
  // What the snapshot's facts say

  /** A lead derivation stored. */
  record Lead(
      Fact fact,
      String id,
      String kind,
      String trigger,
      List<String> units,
      List<String> code,
      List<Fact> facts) {

    /** The unit its conversation is about: its first, or none for the system. */
    String unit() {
      return units.isEmpty() ? EntityId.SYSTEM.toString() : units.getFirst();
    }

    String claim() {
      return fact.claimId() != null ? fact.claimId() : fact.id();
    }
  }

  /** The units, links, elements, leads and findings Investigate reads. */
  static final class Model {
    final Map<String, LinkIndex.Unit> units = new TreeMap<>();
    final Map<String, Set<String>> repos = new TreeMap<>();
    final Map<String, List<Fact>> about = new LinkedHashMap<>();
    final List<String> links = new ArrayList<>();
    final Set<String> linked = new LinkedHashSet<>();
    final Set<String> elements = new LinkedHashSet<>();
    final List<Lead> leads = new ArrayList<>();
    final List<Fact> findings = new ArrayList<>();
    final List<Fact> summaries = new ArrayList<>();
    final LinkIndex index;

    Model(List<Fact> facts) {
      index = new LinkIndex(facts);
      index.units.forEach((id, unit) -> units.put(id.toString(), unit));
      Map<String, Fact> byId = new LinkedHashMap<>();
      Map<String, Set<String>> reposOf = new LinkedHashMap<>();
      for (Fact fact : facts) {
        byId.putIfAbsent(fact.id(), fact);
        about.computeIfAbsent(fact.subject().toString(), s -> new ArrayList<>()).add(fact);
        reposOf
            .computeIfAbsent(fact.subject().toString(), s -> new TreeSet<>())
            .add(fact.evidence().repo());
      }
      for (Map.Entry<String, LinkIndex.Unit> unit : units.entrySet()) {
        Set<String> unitRepos = new TreeSet<>();
        if (unit.getValue().fact() != null) {
          unitRepos.add(unit.getValue().fact().evidence().repo());
        }
        for (EntityId module : unit.getValue().modules()) {
          unitRepos.addAll(reposOf.getOrDefault(module.toString(), Set.of()));
        }
        repos.put(unit.getKey(), unitRepos);
      }
      for (Fact fact : facts) {
        if (link(fact) && fact.origin().kind() != Origin.Kind.LLM
            || link(fact) && fact.origin().name().equals(LinkResolver.NAME)) {
          String from = fact.subject().toString();
          String to = fact.object().value();
          linked.add(InvestigateRole.pair(from, to));
          elements.add(to);
          if (units.containsKey(from) && units.containsKey(to)) {
            links.add(from + " -> " + to + " [" + fact.predicate() + "]");
          }
        }
        if (fact.predicate().equals(Types.FINDING_RAISED)) {
          findings.add(fact);
        }
        if (fact.predicate().equals(Types.SUMMARY)) {
          summaries.add(fact);
        }
      }
      findings.sort(java.util.Comparator.comparing(Fact::id));
      elements.addAll(units.keySet());
      for (Fact fact : facts) {
        if (fact.predicate().equals(Types.LEAD_TRIGGER)
            && fact.origin().kind() != Origin.Kind.LLM) {
          List<Fact> cited = new ArrayList<>();
          for (String id : strings(fact, "facts")) {
            Fact one = byId.get(id);
            if (one != null) {
              cited.add(one);
            }
          }
          List<String> leadUnits =
              strings(fact, "units").stream().filter(units::containsKey).toList();
          leads.add(
              new Lead(
                  fact,
                  fact.subject().toString(),
                  fact.object().value(),
                  Claims.json(fact).path("trigger").asString(""),
                  leadUnits,
                  strings(fact, "code"),
                  cited));
        }
      }
      leads.sort(java.util.Comparator.comparing(Lead::id));
      links.sort(String::compareTo);
    }

    /** A relation between entities that draws a line: an upper-case predicate. */
    static boolean link(Fact fact) {
      return fact.object().kind() == FactObject.Kind.ENTITY
          && fact.predicate().equals(fact.predicate().toUpperCase(Locale.ROOT))
          && !fact.predicate().equals(Types.INVESTIGATED_CONNECTION)
          && !fact.predicate().equals(Types.CONTAINS);
    }

    /** A unit's facts: about it, and the links from and to it. */
    List<Fact> unitFacts(String unit) {
      List<Fact> shown = new ArrayList<>(about.getOrDefault(unit, List.of()));
      for (List<Fact> facts : about.values()) {
        for (Fact fact : facts) {
          if (link(fact) && fact.object().value().equals(unit) && !shown.contains(fact)) {
            shown.add(fact);
          }
        }
      }
      return shown;
    }

    /** The summaries of a unit's modules and repositories. */
    List<Fact> summaries(String unit) {
      Set<String> modules = new TreeSet<>();
      LinkIndex.Unit found = units.get(unit);
      if (found != null) {
        found.modules().forEach(m -> modules.add(m.toString()));
      }
      Set<String> unitRepos = repos.getOrDefault(unit, Set.of());
      return summaries.stream()
          .filter(
              f ->
                  modules.contains(f.subject().toString())
                      || (f.subject().type().equals(Types.REPO)
                          && unitRepos.contains(f.evidence().repo())))
          .toList();
    }

    List<String> allRepos() {
      Set<String> all = new TreeSet<>();
      repos.values().forEach(all::addAll);
      return List.copyOf(all);
    }
  }

  private static List<String> strings(Fact fact, String name) {
    List<String> values = new ArrayList<>();
    for (JsonNode value : Claims.json(fact).path(name)) {
      if (value.isString()) {
        values.add(value.stringValue());
      }
    }
    return values;
  }

  // ---------------------------------------------------------------------------------------------
  // One run

  /** One run's state: what was asked, and the facts its answers give. */
  private final class Run {
    private final Model model;
    private final GraphTools.Graph graph;
    private final String modelName;
    private final Validity validity;
    private final String systemMap;
    private final List<Fact> facts = new ArrayList<>();
    private final Map<String, List<String>> answers = new TreeMap<>();

    /**
     * The answers of leads that name a unit other than first, by that unit: shown to its
     * exploration only, so the system conversation still sees each lead once, under its first unit
     * (#747).
     */
    private final Map<String, List<String>> touched = new TreeMap<>();

    private final Set<String> notInvestigated = new TreeSet<>();
    private final Set<String> keys = new LinkedHashSet<>();
    private int asked;
    private int reused;
    private int confirmed;

    Run(Model model, GraphTools.Graph graph, String modelName, Validity validity) {
      this.model = model;
      this.graph = graph;
      this.modelName = modelName;
      this.validity = validity;
      this.systemMap = InvestigatePrompt.systemMap(List.copyOf(model.units.keySet()), model.links);
    }

    Result result() {
      for (String subject : notInvestigated) {
        ObjectNode attributes = FactJson.newObject();
        attributes.put("reason", reasons.getOrDefault(subject, "not asked"));
        LinkIndex.Unit unit = model.units.get(subject);
        Evidence evidence =
            unit != null && unit.fact() != null
                ? unit.fact().evidence()
                : Evidence.of(model.allRepos().getFirst(), ".", null, null);
        facts.add(
            Fact.create(
                EntityId.parse(subject),
                Types.NOT_INVESTIGATED,
                FactObject.string(NAME),
                attributes,
                ORIGIN,
                evidence,
                LlmFacts.CONFIDENCE,
                validity));
      }
      return new Result(facts, asked, reused, confirmed, List.copyOf(notInvestigated));
    }

    private final Map<String, String> reasons = new LinkedHashMap<>();

    void notInvestigated(String subject, String reason) {
      if (notInvestigated.add(subject)) {
        reasons.put(subject, reason);
        LOG.info("Not investigated: {}, {}", subject, reason);
      }
    }

    private void count(Outcome outcome) {
      if (outcome == null) {
        return;
      }
      switch (outcome.kind()) {
        case CACHED -> reused++;
        case ANSWERED, OMITTED, DROPPED -> asked++;
        default -> {}
      }
    }

    // --- Mode A ------------------------------------------------------------------------------

    void leads() {
      if (model.leads.isEmpty()) {
        return;
      }
      RoleSession session =
          harness.session(
              LEADS.withTools(
                  graph.tools().tools().stream()
                      .filter(t -> InvestigateRole.LEAD_TOOLS.contains(t.name()))
                      .toList(),
                  LEAD_LIMITS));
      Map<String, Lead> bySubject = new LinkedHashMap<>();
      Map<String, List<Item>> byUnit = new TreeMap<>();
      for (Lead lead : model.leads) {
        Item item = item(lead, false);
        bySubject.put(item.subject(), lead);
        byUnit.computeIfAbsent(lead.unit(), u -> new ArrayList<>()).add(item);
      }
      List<Item> all = byUnit.values().stream().flatMap(List::stream).toList();
      session.plan(all, prefix(true));
      Map<String, Outcome> outcomes = ask(session, byUnit, Mode.A);
      // A lead left unanswered is asked again alone, once.
      Map<String, List<Item>> again = new TreeMap<>();
      Set<String> retrying = new LinkedHashSet<>();
      for (Map.Entry<String, Lead> e : bySubject.entrySet()) {
        Outcome outcome = outcomes.get(e.getKey());
        if (outcome != null && unanswered(outcome)) {
          Item retry = item(e.getValue(), true);
          again.computeIfAbsent(e.getValue().unit(), k -> new ArrayList<>()).add(retry);
          retrying.add(e.getKey());
        }
      }
      Map<String, Outcome> retried = Map.of();
      if (!again.isEmpty()) {
        // Each retry is a new item, planned like the first, so the run's record counts it once.
        session.plan(again.values().stream().flatMap(List::stream).toList(), prefix(true));
        retried = ask(session, again, Mode.A_ALONE);
      }
      for (Map.Entry<String, Lead> e : bySubject.entrySet()) {
        Lead lead = e.getValue();
        Outcome outcome = outcomes.get(e.getKey());
        count(outcome);
        if (retrying.contains(e.getKey())) {
          outcome = retried.get(retryKey(e.getKey()));
          count(outcome);
        }
        verdict(lead, outcome);
      }
    }

    private boolean unanswered(Outcome outcome) {
      return outcome.kind() == Outcome.Kind.OMITTED
          || (outcome.kind() == Outcome.Kind.CACHED && outcome.answer() == null);
    }

    private void verdict(Lead lead, Outcome outcome) {
      Outcome.Kind kind = outcome == null ? Outcome.Kind.FAILED : outcome.kind();
      // The item's fingerprint: the key of the answer it comes from in the answer cache (#753).
      String fp = outcome == null ? null : outcome.answerKey();
      switch (kind) {
        case CACHED, ANSWERED -> {
          if (outcome.answer() == null) {
            unclear(lead, "not answered", fp);
            return;
          }
          JsonNode entry = FactJson.parse(outcome.answer());
          String verdict = entry.path("verdict").asString("unclear");
          facts.add(verdictFact(lead, verdict, entry, null, fp));
          String line = lead.id() + " " + verdict;
          if (verdict.equals("confirmed")) {
            confirmed++;
            line += ": " + entry.path("title").asString("");
            risk(entry, lead, InvestigateRole.Mode.LEADS, null, fp);
          }
          answered(lead, line);
        }
        case OMITTED -> unclear(lead, "not answered", fp);
        case DROPPED -> unclear(lead, "its answer was rejected twice", fp);
        // A lead not asked is unclear with the reason; its unit is still explored (Mode B), so only
        // what Mode B or C leaves out is marked not investigated.
        case NOT_ASKED, FAILED ->
            unclear(
                lead,
                "not asked: "
                    + (outcome == null || outcome.reason() == null ? "failed" : outcome.reason()),
                fp);
      }
    }

    private void unclear(Lead lead, String reason, String fp) {
      facts.add(verdictFact(lead, "unclear", FactJson.newObject(), reason, fp));
      answered(lead, lead.id() + " unclear");
    }

    /**
     * Notes a lead's answer for the exploration of every unit it touches, not only the unit its
     * conversation is about, so exploring a unit sees every lead checked on it (#747).
     */
    private void answered(Lead lead, String line) {
      answers.computeIfAbsent(lead.unit(), u -> new ArrayList<>()).add(line);
      new LinkedHashSet<>(lead.units())
          .stream()
              .filter(unit -> !unit.equals(lead.unit()))
              .forEach(unit -> touched.computeIfAbsent(unit, u -> new ArrayList<>()).add(line));
    }

    private Fact verdictFact(Lead lead, String verdict, JsonNode entry, String reason, String fp) {
      ObjectNode attributes = FactJson.newObject();
      if (entry.path("title").isString() && verdict.equals("confirmed")) {
        attributes.put("title", entry.get("title").stringValue());
      }
      // Every verdict keeps its justification (knowledge-build epic, section 11.2).
      if (entry.path("finding").isString()) {
        attributes.put("finding", entry.get("finding").stringValue().strip());
      }
      copyList(entry, "names", attributes);
      copyList(entry, "cites", attributes);
      provenance(attributes, fp);
      if (reason != null) {
        attributes.put("reason", reason);
      }
      Fact fact =
          Fact.create(
              lead.fact().subject(),
              Types.LEAD_VERDICT,
              FactObject.string(verdict),
              attributes,
              ORIGIN,
              evidence(entry, lead.fact().evidence()),
              confidence(entry),
              validity);
      return fact.withLineage(List.of(lead.claim()));
    }

    /** A lead as an item; asked again alone, it has an input of its own, so it is asked anew. */
    private Item item(Lead lead, boolean again) {
      List<FingerprintInput> inputs = new ArrayList<>();
      inputs.add(FingerprintInput.of(lead.fact()));
      lead.facts().forEach(f -> inputs.add(FingerprintInput.of(f)));
      // What it asks the model to judge: a changed judge text asks the lead anew.
      inputs.add(
          FingerprintInput.answer(
              Fingerprints.sha256(Objects.requireNonNullElse(judges.get(lead.kind()), "")),
              List.of("lead-catalog")));
      if (again) {
        inputs.add(FingerprintInput.answer(Fingerprints.sha256("asked again alone")));
      }
      String text =
          InvestigatePrompt.lead(
              lead.id(),
              lead.kind(),
              lead.trigger(),
              judges.get(lead.kind()),
              lead.units(),
              lead.code(),
              lead.facts(),
              graph::lines);
      return new Item(
          again ? retryKey(lead.id()) : lead.id(),
          lead.unit(),
          List.copyOf(new LinkedHashSet<>(inputs)),
          text);
    }

    private String retryKey(String subject) {
      return subject + " (again)";
    }

    // --- Modes B and C -----------------------------------------------------------------------

    void units() {
      RoleSession session =
          harness.session(UNITS.withTools(graph.tools().exploreTools(), EXPLORE_LIMITS));
      Map<String, List<Item>> byUnit = new TreeMap<>();
      for (String unit : model.units.keySet()) {
        if (notInvestigated.contains(unit)) {
          continue;
        }
        List<Fact> shown = new ArrayList<>(model.unitFacts(unit));
        model.index.of(Types.UNRESOLVED_LINK).stream()
            .filter(f -> f.subject().toString().equals(unit))
            .forEach(shown::add);
        shown.addAll(model.summaries(unit));
        List<String> checked = new ArrayList<>(answers.getOrDefault(unit, List.of()));
        checked.addAll(touched.getOrDefault(unit, List.of()));
        List<FingerprintInput> inputs = new ArrayList<>();
        shown.forEach(f -> inputs.add(FingerprintInput.of(f)));
        // From the leads' verdicts this run (#722).
        inputs.add(
            FingerprintInput.answer(
                Fingerprints.sha256(String.join("\n", checked)), List.of(LEADS.name())));
        String context =
            InvestigatePrompt.facts(shown)
                + (checked.isEmpty() ? "" : "\n" + InvestigatePrompt.answers(checked));
        Item item =
            new Item(
                unit,
                unit,
                List.copyOf(new LinkedHashSet<>(inputs)),
                InvestigatePrompt.unit(
                    unit, List.copyOf(model.repos.getOrDefault(unit, Set.of()))));
        contexts.put(unit, context);
        byUnit.put(unit, List.of(item));
      }
      if (byUnit.isEmpty()) {
        return;
      }
      session.plan(byUnit.values().stream().flatMap(List::stream).toList(), prefix(false));
      Map<String, Outcome> outcomes = ask(session, byUnit, Mode.B);
      for (String unit : byUnit.keySet()) {
        proposals(unit, outcomes.get(unit), InvestigateRole.Mode.UNIT);
      }
    }

    void system() {
      RoleSession session =
          harness.session(SYSTEM_ROLE.withTools(graph.tools().exploreTools(), EXPLORE_LIMITS));
      List<String> risks = new ArrayList<>();
      // Every unit's accepted risks: its confirmed leads and the risks exploring it found.
      answers.forEach(
          (unit, lines) ->
              lines.stream()
                  .filter(line -> line.startsWith("risk: ") || line.contains(" confirmed: "))
                  .forEach(line -> risks.add(unit + ": " + line)));
      String context = InvestigatePrompt.answers(risks);
      List<FingerprintInput> inputs = new ArrayList<>();
      // The map and the risks come from the units' pass (#722).
      inputs.add(FingerprintInput.answer(Fingerprints.sha256(systemMap), List.of(UNITS.name())));
      inputs.add(
          FingerprintInput.answer(
              Fingerprints.sha256(String.join("\n", risks)), List.of(UNITS.name())));
      String subject = EntityId.SYSTEM.toString();
      Item item = new Item(subject, subject, inputs, InvestigatePrompt.system(model.allRepos()));
      contexts.put(subject, context);
      session.plan(List.of(item), prefix(false));
      Map<String, Outcome> outcomes = ask(session, Map.of(subject, List.of(item)), Mode.C);
      proposals(null, outcomes.get(subject), InvestigateRole.Mode.SYSTEM);
    }

    private final Map<String, String> contexts = new ConcurrentHashMap<>();

    private void proposals(String unit, Outcome outcome, InvestigateRole.Mode mode) {
      String subject = unit == null ? EntityId.SYSTEM.toString() : unit;
      Outcome.Kind kind = outcome == null ? Outcome.Kind.FAILED : outcome.kind();
      count(outcome);
      switch (kind) {
        case CACHED, ANSWERED -> {
          if (outcome.answer() == null) {
            return;
          }
          for (JsonNode proposal :
              FactJson.parse(outcome.answer()).path(InvestigatePrompt.PROPOSALS)) {
            if ("connection".equals(proposal.path("kind").asString(""))) {
              connection(proposal, unit, mode, outcome.answerKey());
            } else {
              risk(proposal, null, mode, unit, outcome.answerKey());
            }
          }
        }
        case OMITTED, DROPPED -> {}
        case NOT_ASKED, FAILED ->
            notInvestigated(
                subject, outcome == null || outcome.reason() == null ? "failed" : outcome.reason());
      }
    }

    // --- Asking ------------------------------------------------------------------------------

    /**
     * Asks each group's items, a group's in conversations of the role's packet size, in parallel.
     */
    private Map<String, Outcome> ask(
        RoleSession session, Map<String, List<Item>> groups, Mode mode) {
      Map<String, Outcome> outcomes = new ConcurrentHashMap<>();
      List<InvestigateRole> roles = new ArrayList<>();
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        List<Future<?>> futures = new ArrayList<>();
        for (Map.Entry<String, List<Item>> group : groups.entrySet()) {
          List<Item> items = new ArrayList<>(group.getValue());
          items.sort(java.util.Comparator.comparing(session::sampleFingerprint));
          // Mode A's packets are its role's size (a calibration's, ADR 0112); B and C ask one.
          int size = mode == Mode.A ? session.spec().packetSize() : 1;
          for (int i = 0; i < items.size(); i += size) {
            List<Item> packet = items.subList(i, Math.min(items.size(), i + size));
            InvestigateRole role = role(mode, group.getKey(), packet);
            roles.add(role);
            Supplier<List<FileRead>> read = role::filesRead;
            futures.add(
                executor.submit(
                    () -> outcomes.putAll(session.converse(packet, role, role.toolBox(), read))));
          }
        }
        for (Future<?> future : futures) {
          future.get();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while investigating", e);
      } catch (ExecutionException e) {
        if (e.getCause() instanceof RuntimeException runtime) {
          throw runtime;
        }
        throw new IllegalStateException(e.getCause());
      } finally {
        // The injection screen reads every file a tool showed (ADR 0101).
        for (InvestigateRole role : roles) {
          role.tools()
              .textsShown()
              .forEach(f -> harness.showed(f.repo(), f.path(), f.blob(), f.text()));
        }
      }
      return outcomes;
    }

    private InvestigateRole role(Mode mode, String group, List<Item> packet) {
      boolean system = group.equals(EntityId.SYSTEM.toString());
      boolean leads = mode == Mode.A || mode == Mode.A_ALONE;
      // A lead's conversation is shown the units and only the links and facts that mention the
      // lead; it looks up the rest with its tools (#860).
      List<String> words = leads ? LeadWords.of(packet.stream().map(Item::subject).toList()) : null;
      String context =
          switch (mode) {
            case A, A_ALONE ->
                InvestigatePrompt.facts(
                    LeadWords.about(words, system ? List.of() : model.unitFacts(group)));
            case B, C -> contexts.getOrDefault(group, "");
          };
      String map =
          leads
              ? InvestigatePrompt.systemMap(
                  List.copyOf(model.units.keySet()), LeadWords.aboutLines(words, model.links), true)
              : systemMap;
      InvestigateRole.Mode roleMode =
          switch (mode) {
            case A, A_ALONE -> InvestigateRole.Mode.LEADS;
            case B -> InvestigateRole.Mode.UNIT;
            case C -> InvestigateRole.Mode.SYSTEM;
          };
      return new InvestigateRole(
          roleMode,
          map,
          context,
          graph,
          system ? null : group,
          model.units.keySet(),
          model.elements::contains,
          model.linked);
    }

    private long prefix(boolean leads) {
      return Tokens.estimate(CommonPrompt.SYSTEM + InvestigatePrompt.role(leads) + systemMap);
    }

    // --- Storing -----------------------------------------------------------------------------

    /**
     * A risk: the finding it restates, worded; else an {@code investigated-risk}. A confirmed lead
     * of the kind and units of a risk already held joins that risk, and a risk found exploring
     * whose cited lines overlap a risk naming its unit restates it, so a problem is one risk with
     * every place it occurs (#747, owner's decision of 4 Nov 2026).
     */
    private void risk(
        JsonNode entry, Lead lead, InvestigateRole.Mode mode, String conversation, String fp) {
      List<String> units = units(entry, lead, conversation);
      // A lead's risk is keyed on its kind and its lead's units, sorted: the leads of one kind on
      // the same units are one problem, and leads of one kind on other units are other problems.
      String key = lead != null ? leadKey(lead) : String.join(",", sortedNames(entry));
      if (lead == null && !keys.add("risk " + units.getFirst() + " " + key)) {
        return;
      }
      ObjectNode attributes = FactJson.newObject();
      for (String name : List.of("title", "finding", "consequence", "impact", "confidence")) {
        if (entry.path(name).isString()) {
          attributes.put(name, entry.get(name).stringValue().strip());
        }
      }
      ArrayNode list = attributes.putArray("units");
      units.forEach(list::add);
      if (lead != null) {
        attributes.put("lead", lead.id());
      }
      copyList(entry, "names", attributes);
      copyList(entry, "cites", attributes);
      attributes.put("mode", mode.jsonName());
      provenance(attributes, fp);
      Optional<Fact> restated = restated(entry, lead, units);
      String group = key;
      // Only a risk of its own gathers the leads of its kind: a lead restating no finding joins it,
      // and never a fixed check's finding it does not restate.
      if (lead != null && restated.isEmpty()) {
        Integer held = byKind.get(group);
        if (held != null) {
          facts.set(held, joined(facts.get(held), entry, lead, units));
          return;
        }
      }
      // A finding is worded once: a second risk restating it says nothing the first did not.
      if (restated.isPresent() && !worded.add(restated.get().id())) {
        return;
      }
      if (lead == null && restated.isEmpty()) {
        Optional<Fact> overlapped = overlapped(entry, units.getFirst());
        if (overlapped.isPresent()) {
          LOG.info(
              "Investigate: \"{}\" restates the risk {} of {}, so it is not stored again",
              entry.path("title").asString(""),
              overlapped.get().object().value(),
              units.getFirst());
          answers
              .computeIfAbsent(units.getFirst(), u -> new ArrayList<>())
              .add("risk: " + entry.path("title").asString(""));
          return;
        }
      }
      List<String> lineage = new ArrayList<>();
      if (restated.isPresent()) {
        lineage.add(claim(restated.get()));
      }
      if (lead != null) {
        lineage.add(lead.claim());
      }
      Fact fact =
          Fact.create(
              restated.map(Fact::subject).orElse(EntityId.parse(units.getFirst())),
              restated.isPresent() ? Types.FINDING_WORDING : Types.INVESTIGATED_RISK,
              FactObject.string(key),
              attributes,
              ORIGIN,
              evidence(entry, null),
              confidence(entry),
              validity);
      if (lead != null && restated.isEmpty()) {
        byKind.putIfAbsent(group, facts.size());
      }
      facts.add(lineage.isEmpty() ? fact : fact.withLineage(lineage));
      if (lead == null) {
        answers
            .computeIfAbsent(units.getFirst(), u -> new ArrayList<>())
            .add("risk: " + entry.path("title").asString(""));
      }
    }

    /**
     * The units a risk concerns: those it names, else its lead's, else its conversation's unit; a
     * risk of none of them, such as a confirmed lead of no unit, is the system's.
     */
    private List<String> units(JsonNode entry, Lead lead, String conversation) {
      List<String> units = new ArrayList<>();
      for (JsonNode unit : entry.path("units")) {
        if (unit.isString() && model.units.containsKey(unit.stringValue().strip())) {
          units.add(unit.stringValue().strip());
        }
      }
      if (units.isEmpty() && lead != null) {
        units.addAll(lead.units());
      }
      if (units.isEmpty() && conversation != null) {
        units.add(conversation);
      }
      if (units.isEmpty()) {
        units.add(EntityId.SYSTEM.toString());
      }
      return units.stream().distinct().toList();
    }

    /**
     * The finding a risk restates (knowledge-build epic, section 11.2, Merging with static
     * findings): one about one of its units (or, a finding about nothing, in one of their
     * repositories); for a lead's risk, one of the lead's kind which rests on what the lead shows
     * (its facts or their files) or whose evidence a cited range overlaps; for a risk found
     * exploring, one whose evidence a cited range overlaps. The findings are walked by ID, so the
     * same risk restates the same finding in every run.
     */
    private Optional<Fact> restated(JsonNode entry, Lead lead, List<String> units) {
      for (Fact finding : model.findings) {
        String about = Claims.json(finding).path("about").asString("");
        boolean sameUnit =
            about.isEmpty()
                ? units.stream()
                    .anyMatch(
                        u ->
                            model
                                .repos
                                .getOrDefault(u, Set.of())
                                .contains(finding.evidence().repo()))
                : units.contains(about);
        if (!sameUnit) {
          continue;
        }
        String findingKind = finding.object().value();
        // A lead's risk is of its lead's kind, so it restates only a finding of that kind; a risk
        // found exploring, one whose evidence it cites.
        boolean match =
            lead != null
                ? lead.kind().equals(FINDING_LEADS.get(findingKind))
                    && (restsOnTheLead(finding, lead) || citesItsEvidence(entry, finding))
                : citesItsEvidence(entry, finding);
        if (match) {
          return Optional.of(finding);
        }
      }
      return Optional.empty();
    }

    private final Set<String> worded = new LinkedHashSet<>();

    /** Where in the facts the risk of each lead kind and set of units is, by its key. */
    private final Map<String, Integer> byKind = new LinkedHashMap<>();

    /**
     * A risk with another confirmed lead of its kind joined: the first lead's wording kept, the
     * highest impact and confidence of the two, the lead's names, citations, units and evidence
     * added, and the lead added to its lineage, which so lists every lead the risk gathers.
     */
    private Fact joined(Fact risk, JsonNode entry, Lead lead, List<String> units) {
      ObjectNode attributes = Claims.json(risk);
      for (String list : List.of("names", "cites")) {
        Set<String> values = new LinkedHashSet<>();
        Claims.json(risk).path(list).forEach(v -> values.add(v.asString()));
        for (JsonNode v : entry.path(list)) {
          if (v.isString()) {
            values.add(v.stringValue().strip());
          }
        }
        ArrayNode joined = attributes.putArray(list);
        values.forEach(joined::add);
      }
      Set<String> allUnits = new LinkedHashSet<>();
      Claims.json(risk).path("units").forEach(v -> allUnits.add(v.asString()));
      allUnits.addAll(units);
      ArrayNode unitList = attributes.putArray("units");
      allUnits.forEach(unitList::add);
      for (String name : List.of("impact", "confidence")) {
        String held = Claims.json(risk).path(name).asString("");
        String other = entry.path(name).asString("").strip();
        if (RANK.getOrDefault(other.toLowerCase(Locale.ROOT), 0)
            > RANK.getOrDefault(held.toLowerCase(Locale.ROOT), 0)) {
          attributes.put(name, other);
        }
      }
      List<Evidence.Location> locations = new ArrayList<>(risk.evidence().locations());
      for (Evidence.Location location : evidence(entry, risk.evidence()).locations()) {
        if (!locations.contains(location)) {
          locations.add(location);
        }
      }
      List<String> lineage = new ArrayList<>(risk.lineage());
      lineage.add(lead.claim());
      return Fact.create(
              risk.subject(),
              risk.predicate(),
              risk.object(),
              attributes,
              ORIGIN,
              new Evidence(locations),
              Math.max(risk.confidence(), confidence(entry)),
              validity)
          .withLineage(lineage);
    }

    /** How a stated impact or confidence ranks: high above medium above low. */
    private static final Map<String, Integer> RANK = Map.of("high", 3, "medium", 2, "low", 1);

    /**
     * A risk already stored about the unit, its own or a finding it worded, whose cited lines every
     * one of the entry's ranges overlaps: the same places, so the same problem said again. A risk
     * that also cites lines of its own is a risk of its own.
     */
    private Optional<Fact> overlapped(JsonNode entry, String unit) {
      for (Fact fact : facts) {
        boolean about =
            switch (fact.predicate()) {
              case Types.INVESTIGATED_RISK ->
                  fact.subject().toString().equals(unit) || namesUnit(fact, unit);
              case Types.FINDING_WORDING -> namesUnit(fact, unit);
              default -> false;
            };
        if (about && citesOnlyItsEvidence(entry, fact)) {
          return Optional.of(fact);
        }
      }
      return Optional.empty();
    }

    /**
     * Whether a stored risk or wording names the unit: among the units its answer named, or, for a
     * lead's, among its leads' units, which its value holds after the kind (#747).
     */
    private static boolean namesUnit(Fact fact, String unit) {
      for (JsonNode named : Claims.json(fact).path("units")) {
        if (named.isString() && named.stringValue().equals(unit)) {
          return true;
        }
      }
      String value = fact.object().value();
      int space = value.indexOf(' ');
      return Claims.json(fact).path("lead").isString()
          && space >= 0
          && List.of(value.substring(space + 1).split(",")).contains(unit);
    }

    /**
     * A lead's risk key: its kind, a space and its units sorted and joined by commas; its kind
     * alone for a lead of no unit.
     */
    static String leadKey(Lead lead) {
      List<String> sorted = lead.units().stream().distinct().sorted().toList();
      return sorted.isEmpty() ? lead.kind() : lead.kind() + " " + String.join(",", sorted);
    }

    /** Whether a finding rests on a fact the lead shows, or on a file one of them names. */
    private static boolean restsOnTheLead(Fact finding, Lead lead) {
      Set<String> shown = new LinkedHashSet<>();
      Set<String> files = new LinkedHashSet<>();
      shown.add(lead.claim());
      shown.add(lead.fact().id());
      for (Fact fact : lead.facts()) {
        shown.add(fact.id());
        shown.add(claim(fact));
        for (Evidence.Location location : fact.evidence().locations()) {
          files.add(location.repo() + ':' + location.path());
        }
      }
      for (String id : finding.lineage()) {
        if (shown.contains(id)) {
          return true;
        }
      }
      for (JsonNode id : Claims.json(finding).path("facts")) {
        if (shown.contains(id.asString(""))) {
          return true;
        }
      }
      for (Evidence.Location location : finding.evidence().locations()) {
        if (files.contains(location.repo() + ':' + location.path())) {
          return true;
        }
      }
      return false;
    }

    /** Whether every one of a risk's cited ranges, and at least one, overlaps a fact's evidence. */
    private static boolean citesOnlyItsEvidence(JsonNode entry, Fact fact) {
      List<String> cites = InvestigateRole.cites(entry);
      if (cites.isEmpty()) {
        return false;
      }
      for (String cite : cites) {
        ObjectNode one = FactJson.newObject();
        one.putArray("cites").add(cite);
        if (!citesItsEvidence(one, fact)) {
          return false;
        }
      }
      return true;
    }

    /** Whether one of a risk's cited ranges overlaps a finding's evidence. */
    private static boolean citesItsEvidence(JsonNode entry, Fact finding) {
      for (String cite : InvestigateRole.cites(entry)) {
        Matcher range = RANGE.matcher(cite);
        if (!range.matches()) {
          continue;
        }
        int from = Integer.parseInt(range.group(3));
        int to = Integer.parseInt(range.group(4) == null ? range.group(3) : range.group(4));
        for (Evidence.Location location : finding.evidence().locations()) {
          if (location.repo().equals(range.group(1))
              && location.path().equals(range.group(2))
              && location.lines() != null
              && location.lines().start() <= to
              && location.lines().end() >= from) {
            return true;
          }
        }
      }
      return false;
    }

    private void connection(JsonNode entry, String unit, InvestigateRole.Mode mode, String fp) {
      String from = entry.path("from").asString(unit == null ? "" : unit).strip();
      String to = entry.path("to").asString("").strip();
      String interaction = entry.path("interaction").asString("other");
      if (from.isEmpty()
          || to.isEmpty()
          || !keys.add("connection " + from + " " + to + " " + interaction)) {
        return;
      }
      ObjectNode attributes = FactJson.newObject();
      attributes.put("interaction", interaction);
      for (String name : List.of("description", "technology", "confidence")) {
        if (entry.path(name).isString()) {
          attributes.put(name, entry.get(name).stringValue().strip());
        }
      }
      copyList(entry, "names", attributes);
      copyList(entry, "cites", attributes);
      attributes.put("mode", mode.jsonName());
      provenance(attributes, fp);
      facts.add(
          Fact.create(
              EntityId.parse(from),
              Types.INVESTIGATED_CONNECTION,
              FactObject.entity(EntityId.parse(to)),
              attributes,
              ORIGIN,
              evidence(entry, null),
              confidence(entry),
              validity));
    }

    /**
     * Who wrote a fact and from what (#753): the model, the item's fingerprint in the answer cache,
     * and its basis, always inferred, since Investigate reads code and cites its lines.
     */
    private void provenance(ObjectNode attributes, String fp) {
      attributes.put("model", modelName);
      if (fp != null) {
        attributes.put(LlmFacts.ANSWER, fp);
      }
      attributes.put("basis", LlmFacts.Basis.INFERRED.jsonName());
    }

    /** The ranges an entry cites as evidence, each with its file's blob; else the fallback. */
    private Evidence evidence(JsonNode entry, Evidence fallback) {
      List<Evidence.Location> locations = new ArrayList<>();
      for (String cite : InvestigateRole.cites(entry)) {
        Matcher range = RANGE.matcher(cite);
        if (!range.matches()) {
          continue;
        }
        String repo = range.group(1);
        String path = range.group(2);
        int from = Integer.parseInt(range.group(3));
        int to = Integer.parseInt(range.group(4) == null ? range.group(3) : range.group(4));
        String blob = graph.fileRead(repo, path).map(FileRead::blob).orElse(null);
        locations.add(
            new Evidence.Location(
                repo,
                path,
                new Evidence.LineRange(Math.max(1, from), Math.max(Math.max(1, from), to)),
                blob,
                null,
                Evidence.Role.DECLARED));
      }
      if (locations.isEmpty()) {
        return fallback != null
            ? fallback
            : Evidence.of(model.allRepos().getFirst(), ".", null, null);
      }
      return new Evidence(locations);
    }
  }

  /** The modes as asked: Mode A, its leads asked again alone, and Modes B and C. */
  private enum Mode {
    A,
    A_ALONE,
    B,
    C
  }

  private static double confidence(JsonNode entry) {
    return CONFIDENCE.getOrDefault(
        entry.path("confidence").asString("low").toLowerCase(Locale.ROOT), 0.3);
  }

  private static String claim(Fact fact) {
    return fact.claimId() != null ? fact.claimId() : fact.id();
  }

  private static void copyList(JsonNode entry, String name, ObjectNode attributes) {
    ArrayNode list = attributes.putArray(name);
    for (JsonNode value : entry.path(name)) {
      if (value.isString()) {
        list.add(value.stringValue().strip());
      }
    }
  }
}

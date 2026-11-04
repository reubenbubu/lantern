package io.lantern.link;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.harness.Item;
import io.lantern.harness.PacketRole;
import io.lantern.harness.PromptLayout;
import io.lantern.harness.checks.CommonPrompt;
import io.lantern.harness.render.Blocks;
import io.lantern.harness.render.FactLines;
import io.lantern.harness.render.Refs;
import io.lantern.harness.verify.Check;
import io.lantern.harness.verify.CheckInputs;
import io.lantern.harness.verify.CheckedText;
import io.lantern.harness.verify.Checks;
import io.lantern.harness.verify.ItemAnswer;
import io.lantern.harness.verify.Pipeline;
import io.lantern.harness.verify.Rejection;
import io.lantern.link.ResolvePrompt.Candidate;
import io.lantern.link.ResolvePrompt.Question;
import io.lantern.llm.LlmTool;
import io.lantern.llm.LlmToolCall;
import io.lantern.llm.LlmToolResult;
import io.lantern.llm.ToolBox;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Resolve's conversations as the harness runs them (knowledge-build epic, section 11.1): one per
 * packet of a calling unit's open links. It lays out the request (block 3 the system map, block 4
 * the caller's address facts, then the items), gives each conversation its own tools, whose facts
 * are numbered after the request's, reads each submitted entry into the checks' form, adds the
 * role's own checks to the standard pipeline, and stores an accepted entry with what it cites by
 * claim ID, so a stored answer does not depend on how its request numbered its references.
 *
 * <p>One instance serves the conversations of one unit, one at a time.
 */
final class ResolveRole implements PacketRole {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final Pattern RANGE =
      Pattern.compile("^([^:\\s]+):([^:\\s]+):(\\d+)(?:-(\\d+))?$");

  private final Map<String, Question> questions;
  private final String systemMap;
  private final GraphTools.Graph graph;
  private final Function<EntityId, Set<EntityId>> unitsOf;
  private final List<GraphTools> used = new ArrayList<>();

  // The conversation being laid out or checked: its items, references and tools.
  private List<Question> packet = List.of();
  private List<String> ids = List.of();
  private Map<String, Fact> block4 = Map.of();
  private List<Map<String, Fact>> itemRefs = List.of();
  private GraphTools tools;
  private final Map<String, io.lantern.harness.FileRead> citedFiles = new LinkedHashMap<>();

  /**
   * @param questions the items' questions, by item subject
   * @param systemMap block 3
   * @param graph the snapshot's facts and files the tools show
   * @param unitsOf the units an entity's code runs in, for which side a fact a tool showed is on
   */
  ResolveRole(
      Map<String, Question> questions,
      String systemMap,
      GraphTools.Graph graph,
      Function<EntityId, Set<EntityId>> unitsOf) {
    this.questions = questions;
    this.systemMap = systemMap;
    this.graph = graph;
    this.unitsOf = unitsOf;
  }

  private Question question(Item item) {
    Question question = questions.get(item.subject());
    if (question == null) {
      throw new IllegalStateException("No Resolve item for " + item.subject());
    }
    return question;
  }

  @Override
  public synchronized PromptLayout layout(List<Item> items, List<String> localIds) {
    packet = items.stream().map(this::question).toList();
    ids = List.copyOf(localIds);
    Refs packetRefs = new Refs();
    Set<Fact> address = new LinkedHashSet<>();
    packet.forEach(q -> address.addAll(q.address()));
    String context = ResolvePrompt.packet(packet.getFirst().caller(), packetRefs, address);
    List<String> renderings = new ArrayList<>();
    List<Refs> refs = new ArrayList<>();
    renderings.add(context);
    refs.add(packetRefs);
    for (Question question : packet) {
      renderings.add(question.rendering());
      refs.add(question.refs());
    }
    Refs.Resolved resolved = Refs.resolve(renderings, refs);
    block4 = facts(resolved, 0, packetRefs);
    List<Map<String, Fact>> perItem = new ArrayList<>();
    for (int i = 0; i < packet.size(); i++) {
      perItem.add(facts(resolved, i + 1, packet.get(i).refs()));
    }
    itemRefs = perItem;
    int factsShown = block4.size() + perItem.stream().mapToInt(Map::size).sum();
    tools = graph.tools(factsShown);
    used.add(tools);
    // A range of an excerpt the request gives counts as shown, as one a tool read does (#715).
    tools.supplied(systemMap);
    resolved.texts().forEach(tools::supplied);
    List<String> texts = new ArrayList<>();
    for (int i = 1; i < resolved.texts().size(); i++) {
      texts.add(resolved.texts().get(i).stripTrailing());
    }
    return new PromptLayout(
        CommonPrompt.SYSTEM,
        ResolvePrompt.ROLE,
        systemMap,
        resolved.texts().getFirst(),
        false,
        Blocks.items(ids, texts));
  }

  /** The facts of one of a request's renderings, by the references the request shows. */
  private static Map<String, Fact> facts(Refs.Resolved resolved, int index, Refs refs) {
    Map<String, Fact> facts = new LinkedHashMap<>();
    resolved
        .toLocal()
        .get(index)
        .forEach(
            (shown, local) ->
                refs.thing(local)
                    .filter(Fact.class::isInstance)
                    .ifPresent(thing -> facts.put(shown, (Fact) thing)));
    return facts;
  }

  /** The tools of the conversation being laid out, which the harness offers it. */
  ToolBox toolBox() {
    return new ToolBox() {
      @Override
      public List<LlmTool> tools() {
        return graph.tools().tools();
      }

      @Override
      public LlmToolResult run(LlmToolCall call) {
        GraphTools current;
        synchronized (ResolveRole.this) {
          current = tools;
        }
        return current.run(call);
      }
    };
  }

  /** Every conversation's tools so far, for the files they read and showed. */
  synchronized List<GraphTools> used() {
    return List.copyOf(used);
  }

  /**
   * The files the current conversation's tools read and those its answers cite ranges of, recorded
   * with its answers, so a cached answer holds only while each keeps its blob.
   */
  synchronized List<io.lantern.harness.FileRead> filesRead() {
    Map<String, io.lantern.harness.FileRead> all = new LinkedHashMap<>();
    if (tools != null) {
      tools.filesRead().forEach(f -> all.putIfAbsent(f.repo() + '\u0000' + f.path(), f));
    }
    citedFiles.forEach(all::putIfAbsent);
    return List.copyOf(all.values());
  }

  /** The facts the current conversation's tools showed, by reference. */
  synchronized Map<String, Fact> shown() {
    return tools == null ? Map.of() : tools.shown();
  }

  @Override
  public int maxOutputTokens(int items) {
    return LinkResolver.MAX_OUTPUT_TOKENS;
  }

  @Override
  public CheckInputs inputs(List<Item> items, List<String> localIds) {
    return inputs(items, localIds, 0);
  }

  @Override
  public synchronized CheckInputs inputs(List<Item> items, List<String> localIds, int index) {
    Map<String, String> references = new LinkedHashMap<>();
    block4.forEach((ref, fact) -> references.put(ref, FactLines.line(fact)));
    if (index < itemRefs.size()) {
      itemRefs.get(index).forEach((ref, fact) -> references.put(ref, FactLines.line(fact)));
    }
    shown().forEach((ref, fact) -> references.put(ref, FactLines.line(fact)));
    return new CheckInputs(
        ResolvePrompt.ITEM_SCHEMA, references, Set.of('F'), true, graph::lines, Map.of(), Set.of());
  }

  @Override
  public JsonNode submitSchema() {
    return ResolvePrompt.SUBMIT_SCHEMA;
  }

  @Override
  public Pipeline pipeline() {
    List<Check> checks = new ArrayList<>(Checks.STANDARD);
    checks.add(new ShownRanges(this::tools, entry -> Map.of("cites", cites(entry))));
    checks.add(new Sides(this));
    return new Pipeline(checks);
  }

  @Override
  public ItemAnswer read(String id, Item item, JsonNode entry) {
    if (entry != null) {
      for (String cite : cites(entry)) {
        Matcher range = RANGE.matcher(cite);
        if (range.matches()) {
          String key = range.group(1) + '\u0000' + range.group(2);
          synchronized (this) {
            if (!citedFiles.containsKey(key)) {
              graph
                  .fileRead(range.group(1), range.group(2))
                  .ifPresent(file -> citedFiles.put(key, file));
            }
          }
        }
      }
    }
    List<CheckedText> texts = new ArrayList<>();
    if (entry != null && entry.isObject() && entry.path("reason").isString()) {
      texts.add(
          new CheckedText(
              "reason",
              CheckedText.Kind.PROSE,
              entry.get("reason").stringValue(),
              cites(entry),
              ResolvePrompt.REASON_WORDS,
              "",
              Set.of()));
    }
    return new ItemAnswer(id, entry, texts, List.of());
  }

  /** The tools of the conversation being laid out or checked. */
  private synchronized GraphTools tools() {
    return tools;
  }

  /** An entry's cites, as the checks read them. */
  static List<String> cites(JsonNode entry) {
    List<String> cites = new ArrayList<>();
    for (JsonNode cite : entry.path("cites")) {
      if (cite.isString()) {
        cites.add(ResolvePrompt.cite(cite.stringValue()));
      }
    }
    return cites;
  }

  /**
   * The fields the item asks for, which the run record counts when left out (#741): the reason a
   * pick, or "unknown", gives.
   */
  @Override
  public List<String> askedFields(Item item) {
    return List.of("reason");
  }

  /**
   * An accepted entry as stored: the unit it picks (or {@code unknown}), its confidence and reason,
   * and what it cites, a fact by its claim ID (else its fact ID) and a file range as written.
   */
  @Override
  public synchronized String store(Item item, ItemAnswer accepted) {
    Question question = question(item);
    JsonNode entry = accepted.raw();
    String pick = entry.path("pick").asString("").strip();
    Candidate candidate =
        pick.equalsIgnoreCase(ResolvePrompt.UNKNOWN) ? null : question.candidate(pick);
    ObjectNode stored = JSON.createObjectNode();
    stored.put("pick", candidate == null ? ResolvePrompt.UNKNOWN : candidate.unit().toString());
    stored.put("confidence", entry.path("confidence").asString(""));
    stored.put("reason", entry.path("reason").asString("").strip());
    var cites = stored.putArray("cites");
    int index = packet.indexOf(question);
    for (String cite : cites(entry)) {
      Fact fact = fact(cite, index);
      cites.add(fact != null ? ResolvePrompt.key(fact) : cite);
    }
    return stored.toString();
  }

  /** The fact a reference of the current request stands for for the item at the index, or null. */
  private Fact fact(String ref, int index) {
    Fact fact = block4.get(ref);
    if (fact == null && index >= 0 && index < itemRefs.size()) {
      fact = itemRefs.get(index).get(ref);
    }
    return fact != null ? fact : shown().get(ref);
  }

  /** The question of a local item ID of the current request, or null. */
  private synchronized int index(String id) {
    return ids.indexOf(id);
  }

  private synchronized Question at(int index) {
    return index < 0 || index >= packet.size() ? null : packet.get(index);
  }

  /** Whether a cited fact or range is on the caller's side. */
  private boolean caller(Question question, int index, String cite) {
    Matcher range = RANGE.matcher(cite);
    if (range.matches()) {
      return question.callerRepos().contains(range.group(1));
    }
    Fact fact;
    synchronized (this) {
      if (block4.containsKey(cite)) {
        return true;
      }
      fact = fact(cite, index);
    }
    if (fact == null) {
      return false;
    }
    return question.about().contains(fact)
        || question.address().contains(fact)
        || fact.subject().equals(question.caller())
        || unitsOf.apply(fact.subject()).contains(question.caller());
  }

  /** Whether a cited fact or range is on a candidate's side. */
  private boolean candidate(Candidate candidate, int index, String cite) {
    Matcher range = RANGE.matcher(cite);
    if (range.matches()) {
      return candidate.repos().contains(range.group(1));
    }
    Fact fact;
    synchronized (this) {
      fact = fact(cite, index);
    }
    if (fact == null) {
      return false;
    }
    return candidate.facts().contains(fact)
        || fact.subject().equals(candidate.unit())
        || unitsOf.apply(fact.subject()).contains(candidate.unit());
  }

  // ---------------------------------------------------------------------------------------------
  // The role's own checks

  /**
   * The pick is one of the item's candidates or {@code unknown}, and the cites include the caller's
   * side and the candidate's side: the picked candidate's, or for {@code unknown} any candidate's.
   */
  static final class Sides implements Check {

    private final ResolveRole role;

    Sides(ResolveRole role) {
      this.role = role;
    }

    @Override
    public String name() {
      return "Sides";
    }

    @Override
    public List<Rejection> check(ItemAnswer answer, CheckInputs inputs) {
      if (answer.omitted() || answer.raw() == null) {
        return List.of();
      }
      int index = role.index(answer.item());
      Question question = role.at(index);
      if (question == null) {
        return List.of();
      }
      JsonNode pickNode = answer.raw().get("pick");
      if (pickNode == null || !pickNode.isString()) {
        // The Schema check says what is missing.
        return List.of();
      }
      String pick = pickNode.stringValue().strip();
      List<Rejection> found = new ArrayList<>();
      Candidate picked = null;
      if (!pick.equalsIgnoreCase(ResolvePrompt.UNKNOWN)) {
        picked = question.candidate(pick);
        if (picked == null) {
          found.add(
              new Rejection(
                  answer.item(),
                  "pick",
                  "Pick",
                  "Picks "
                      + pick
                      + ", which is not one of this item's candidates ("
                      + String.join(
                          ", ", question.candidates().stream().map(Candidate::ref).toList())
                      + "); pick one of them or answer unknown."));
          return found;
        }
      }
      List<String> cites = cites(answer.raw());
      // A range in a repository of neither side, a library both bundle say, is evidence of neither
      // (#715).
      for (String cite : cites) {
        Matcher range = RANGE.matcher(cite);
        if (range.matches()
            && !question.callerRepos().contains(range.group(1))
            && question.candidates().stream().noneMatch(c -> c.repos().contains(range.group(1)))) {
          found.add(
              new Rejection(
                  answer.item(),
                  "cites",
                  name(),
                  "Cites "
                      + cite
                      + ", in "
                      + range.group(1)
                      + ", a repository on neither side of the link: neither the calling unit's"
                      + " own nor a candidate's own. Cite the caller's and the candidate's own"
                      + " facts or files."));
        }
      }
      if (!found.isEmpty()) {
        return found;
      }
      List<String> callerSide =
          cites.stream().filter(cite -> role.caller(question, index, cite)).toList();
      if (callerSide.isEmpty()) {
        found.add(
            new Rejection(
                answer.item(),
                "cites",
                name(),
                "Cites nothing on the caller's side; cite the item's facts, the calling unit's"
                    + " facts or file ranges you read in its repository."));
        return found;
      }
      // Each side needs a citation of its own: one fact or range never counts for both, as code
      // or a file both units share would.
      Candidate side = picked;
      List<Candidate> sides = side != null ? List.of(side) : question.candidates();
      boolean candidateSide =
          sides.stream()
              .anyMatch(
                  c ->
                      cites.stream()
                          .filter(cite -> role.candidate(c, index, cite))
                          .anyMatch(
                              cite -> callerSide.stream().anyMatch(other -> !other.equals(cite))));
      if (!candidateSide) {
        found.add(
            new Rejection(
                answer.item(),
                "cites",
                name(),
                side != null
                    ? "Cites nothing on the side of "
                        + side.ref()
                        + ", the candidate it picks, apart from what it cites on the caller's;"
                        + " cite the facts listed under it or file ranges you read in its"
                        + " repository."
                    : "Cites nothing on a candidate's side apart from what it cites on the"
                        + " caller's; cite the facts listed under the candidates or file ranges"
                        + " you read in their repositories."));
      }
      return found;
    }
  }

  /** What a stored answer cites, for a cached answer's test that every cited fact still holds. */
  static List<String> storedCites(String stored) {
    List<String> cites = new ArrayList<>();
    JsonNode node = JSON.readTree(stored);
    for (JsonNode cite : node.path("cites")) {
      cites.add(cite.asString());
    }
    return cites;
  }

  /** A stored answer's pick: the unit, or empty for {@code unknown}. */
  static Optional<EntityId> storedPick(String stored) {
    String pick = JSON.readTree(stored).path("pick").asString(ResolvePrompt.UNKNOWN);
    return pick.equals(ResolvePrompt.UNKNOWN)
        ? Optional.empty()
        : Optional.of(EntityId.parse(pick));
  }
}

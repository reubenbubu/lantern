package io.lantern.link;

import io.lantern.core.fact.FactJson;
import io.lantern.harness.FileRead;
import io.lantern.harness.Item;
import io.lantern.harness.PacketRole;
import io.lantern.harness.PromptLayout;
import io.lantern.harness.checks.CommonPrompt;
import io.lantern.harness.render.Blocks;
import io.lantern.harness.verify.Check;
import io.lantern.harness.verify.CheckInputs;
import io.lantern.harness.verify.CheckedText;
import io.lantern.harness.verify.Checks;
import io.lantern.harness.verify.ItemAnswer;
import io.lantern.harness.verify.Pipeline;
import io.lantern.harness.verify.Rejection;
import io.lantern.llm.LlmTool;
import io.lantern.llm.LlmToolCall;
import io.lantern.llm.LlmToolResult;
import io.lantern.llm.ToolBox;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * One Investigate conversation as the harness runs it (knowledge-build epic, section 11.2): Mode A
 * checks a packet of one unit's leads, each an item; Mode B explores one unit and Mode C the whole
 * system, each one item whose answer is the proposals it submits. It lays out the request, offers
 * the mode's tools, turns the epic's submit schema (a list of entries) into the harness's envelope,
 * reads each entry into the checks' form and adds Investigate's own checks to the standard
 * pipeline, whose Ranges and Coverage checks verify every citation against the snapshot's files. In
 * Modes B and C each proposal is kept or rejected on its own, and the model may submit more than
 * once.
 *
 * <p>One instance serves one conversation.
 */
final class InvestigateRole implements PacketRole {

  /** The three modes. */
  enum Mode {
    /** Mode A: confirm, refute or leave unclear each lead. */
    LEADS("leads"),
    /** Mode B: explore one deployable unit. */
    UNIT("unit"),
    /** Mode C: explore the whole system. */
    SYSTEM("system");

    private final String jsonName;

    Mode(String jsonName) {
      this.jsonName = jsonName;
    }

    String jsonName() {
      return jsonName;
    }
  }

  /** The tools Mode A may use (knowledge-build epic, section 11.2). */
  static final Set<String> LEAD_TOOLS =
      Set.of(GraphTools.READ_FILE, GraphTools.FIND_IN_CODE, GraphTools.FACTS_ABOUT);

  /** The verdicts of a lead. */
  static final Set<String> VERDICTS = Set.of("confirmed", "refuted", "unclear");

  /** The most proposals a Mode B or C conversation keeps. */
  static final int MAX_PROPOSALS =
      io.lantern.harness.Contract.of("investigate").count("proposals").max();

  /** What Investigate proposes no connection to: infrastructure, not a business dependency. */
  static final List<String> INFRASTRUCTURE =
      io.lantern.harness.Contract.of("investigate").list("infrastructure");

  /** The most words of a title, a finding, a consequence and a description: their contract's. */
  static final io.lantern.harness.Contract.Range TITLE_WORDS =
      io.lantern.harness.Contract.of("investigate").acceptedWords("title");

  static final io.lantern.harness.Contract.Range FINDING_WORDS =
      io.lantern.harness.Contract.of("investigate").acceptedWords("finding");

  static final io.lantern.harness.Contract.Range CONSEQUENCE_WORDS =
      io.lantern.harness.Contract.of("investigate").acceptedWords("consequence");

  static final io.lantern.harness.Contract.Range DESCRIPTION_WORDS =
      io.lantern.harness.Contract.of("investigate").acceptedWords("description");

  /** A proposal's number in a Mode B or C entry, such as {@code proposals.P3}. */
  private static final Pattern PROPOSAL =
      Pattern.compile("^(" + InvestigatePrompt.PROPOSALS + "\\.P\\d+)");

  /** Words no name may be: keywords and words that hold on almost any line. */
  static final Set<String> NOT_NAMES =
      Set.of(
          "abstract",
          "boolean",
          "break",
          "case",
          "catch",
          "class",
          "const",
          "continue",
          "default",
          "double",
          "else",
          "enum",
          "extends",
          "final",
          "finally",
          "float",
          "for",
          "if",
          "implements",
          "import",
          "instanceof",
          "int",
          "interface",
          "long",
          "new",
          "null",
          "package",
          "private",
          "protected",
          "public",
          "return",
          "short",
          "static",
          "super",
          "switch",
          "synchronized",
          "this",
          "throw",
          "throws",
          "try",
          "void",
          "while",
          "true",
          "false",
          "var",
          "string",
          "object",
          "list",
          "map",
          "get",
          "set",
          "the",
          "and",
          "value");

  private static final Pattern RANGE =
      Pattern.compile("^([^:\\s]+):([^:\\s]+):(\\d+)(?:-(\\d+))?$");

  private final Mode mode;
  private final String systemMap;
  private final String context;
  private final GraphTools.Graph graph;
  private final GraphTools tools;
  private final String unit;
  private final Set<String> units;
  private final Function<String, Boolean> element;
  private final Set<String> linked;
  private final Map<String, FileRead> citedFiles = new LinkedHashMap<>();
  private int proposals;
  // A lead's ID by its subject, so an entry naming its lead by either is placed under its item.
  private final Map<String, String> idsBySubject = new LinkedHashMap<>();

  /**
   * @param mode the conversation's mode
   * @param systemMap block 3
   * @param context block 4
   * @param graph the snapshot's facts and files the tools show
   * @param unit the unit a Mode A or B conversation is about, a proposal's default unit; null for
   *     the system
   * @param units the deployable units, by ID
   * @param element whether an entity ID is a model element a connection may end at
   * @param linked the pairs of ends a static or resolved link already joins, as {@link #pair}
   */
  InvestigateRole(
      Mode mode,
      String systemMap,
      String context,
      GraphTools.Graph graph,
      String unit,
      Set<String> units,
      Function<String, Boolean> element,
      Set<String> linked) {
    this.mode = mode;
    this.systemMap = systemMap;
    this.context = context;
    this.graph = graph;
    this.tools = graph.tools();
    this.unit = unit;
    this.units = Set.copyOf(units);
    this.element = element;
    this.linked = Set.copyOf(linked);
  }

  /** Two ends as {@link #linked} holds them, whichever way the link runs. */
  static String pair(String a, String b) {
    return a.compareTo(b) <= 0 ? a + " " + b : b + " " + a;
  }

  Mode mode() {
    return mode;
  }

  @Override
  public synchronized PromptLayout layout(List<Item> packet, List<String> ids) {
    for (int i = 0; i < packet.size() && i < ids.size(); i++) {
      idsBySubject.put(packet.get(i).subject(), ids.get(i));
    }
    // A range of an excerpt the request gives counts as shown, as one a tool read does (#715).
    tools.supplied(systemMap);
    tools.supplied(context);
    packet.forEach(item -> tools.supplied(item.rendering()));
    return new PromptLayout(
        CommonPrompt.SYSTEM,
        InvestigatePrompt.role(mode == Mode.LEADS),
        systemMap,
        context,
        false,
        Blocks.items(ids, packet.stream().map(Item::rendering).toList()));
  }

  /** The mode's tools, which the harness offers the conversation. */
  ToolBox toolBox() {
    return new ToolBox() {
      @Override
      public List<LlmTool> tools() {
        return mode == Mode.LEADS
            ? tools.tools().stream().filter(t -> LEAD_TOOLS.contains(t.name())).toList()
            : tools.exploreTools();
      }

      @Override
      public LlmToolResult run(LlmToolCall call) {
        if (mode == Mode.LEADS && !LEAD_TOOLS.contains(call.name())) {
          return LlmToolResult.error(call, "This task offers no tool " + call.name() + ".");
        }
        return tools.run(call);
      }
    };
  }

  /** The conversation's tools, for the files they read and showed. */
  GraphTools tools() {
    return tools;
  }

  /**
   * The files the tools read and those the answers cite ranges of, recorded with the answers, so a
   * cached answer holds only while each keeps its blob.
   */
  synchronized List<FileRead> filesRead() {
    Map<String, FileRead> all = new LinkedHashMap<>();
    tools.filesRead().forEach(f -> all.putIfAbsent(f.repo() + '\u0000' + f.path(), f));
    citedFiles.forEach(all::putIfAbsent);
    return List.copyOf(all.values());
  }

  @Override
  public int maxOutputTokens(int items) {
    return Investigator.MAX_OUTPUT_TOKENS;
  }

  @Override
  public CheckInputs inputs(List<Item> packet, List<String> ids) {
    return new CheckInputs(
        mode == Mode.LEADS ? InvestigatePrompt.LEAD_SCHEMA : InvestigatePrompt.PROPOSALS_SCHEMA,
        Map.of(),
        Set.of(),
        true,
        graph::lines,
        Map.of(),
        Set.of());
  }

  @Override
  public JsonNode submitSchema() {
    return InvestigatePrompt.SUBMIT_SCHEMA;
  }

  @Override
  public String submitDescription() {
    return "Submit what you found: {\"items\": [...]}, each entry as the answer schema in your"
        + " instructions says. The result says which entries were accepted and why the others"
        + " were rejected; submit corrected ones again, or leave them out."
        + (mode == Mode.LEADS
            ? " Give every item its lead, the item's ID."
            : " Your proposals are numbered P1, P2, ... in the order you submit them, across"
                + " submissions.");
  }

  @Override
  public boolean partial() {
    return mode != Mode.LEADS;
  }

  /**
   * The epic's submission, a list of entries, as the harness's envelope: in Mode A each entry under
   * the item its {@code lead} names; in Modes B and C every entry a proposal of the one item,
   * {@code I1}, numbered after those submitted before.
   */
  @Override
  public synchronized JsonNode envelope(JsonNode submitted) {
    JsonNode list = submitted == null ? null : submitted.get("items");
    if (list == null || !list.isArray()) {
      return submitted;
    }
    ObjectNode envelope = FactJson.newObject();
    ObjectNode items = envelope.putObject("items");
    if (mode == Mode.LEADS) {
      for (JsonNode entry : list) {
        if (!entry.isObject() || !entry.path("lead").isString()) {
          continue;
        }
        String lead = entry.get("lead").stringValue().strip().replaceAll("^\\[|\\]$", "");
        ObjectNode copy = ((ObjectNode) entry).deepCopy();
        copy.remove("lead");
        String id = idsBySubject.get(lead);
        items.set(id != null ? id : lead.toUpperCase(Locale.ROOT), copy);
      }
    } else {
      ObjectNode item = items.putObject("I1");
      ObjectNode proposed = item.putObject(InvestigatePrompt.PROPOSALS);
      for (JsonNode entry : list) {
        proposed.set("P" + (++proposals), entry.deepCopy());
      }
    }
    return envelope;
  }

  @Override
  public Pipeline pipeline() {
    List<Check> checks = new ArrayList<>(Checks.STANDARD);
    checks.add(new Proposals(this));
    checks.add(new ShownRanges(() -> tools, this::citesByField));
    if (mode == Mode.LEADS) {
      return new Pipeline(checks);
    }
    // A rejection of any part of a proposal rejects the proposal: it is kept or dropped whole.
    return new Pipeline(List.of(new ByProposal(checks)));
  }

  @Override
  public ItemAnswer read(String id, Item item, JsonNode entry) {
    List<CheckedText> texts = new ArrayList<>();
    if (entry != null && entry.isObject()) {
      if (mode == Mode.LEADS) {
        texts(entry, "", texts);
      } else {
        for (var proposal : entry.path(InvestigatePrompt.PROPOSALS).properties()) {
          texts(proposal.getValue(), InvestigatePrompt.PROPOSALS + "." + proposal.getKey(), texts);
        }
      }
    }
    return new ItemAnswer(id, entry, texts, List.of());
  }

  /**
   * An entry's texts: its citations and names as one text the Coverage check verifies, then its
   * title, finding, consequence or description, each held by the Names check to what it cites.
   */
  private void texts(JsonNode entry, String field, List<CheckedText> texts) {
    if (!entry.isObject()) {
      return;
    }
    List<String> cites = cites(entry);
    remember(cites);
    Set<String> names = new LinkedHashSet<>();
    for (JsonNode name : entry.path("names")) {
      if (name.isString()) {
        names.add(name.stringValue());
      }
    }
    texts.add(
        new CheckedText(
            at(field, "cites"),
            CheckedText.Kind.PROSE,
            "",
            cites,
            io.lantern.harness.Contract.Range.ANY,
            "",
            names));
    text(entry, field, "title", TITLE_WORDS, cites, texts);
    text(entry, field, "finding", FINDING_WORDS, cites, texts);
    text(entry, field, "consequence", CONSEQUENCE_WORDS, cites, texts);
    text(entry, field, "description", DESCRIPTION_WORDS, cites, texts);
  }

  private static void text(
      JsonNode entry,
      String field,
      String name,
      io.lantern.harness.Contract.Range words,
      List<String> cites,
      List<CheckedText> texts) {
    if (entry.path(name).isString()) {
      texts.add(
          new CheckedText(
              at(field, name),
              CheckedText.Kind.PROSE,
              entry.get(name).stringValue(),
              cites,
              words,
              "",
              Set.of()));
    }
  }

  private static String at(String field, String name) {
    return field.isEmpty() ? name : field + "." + name;
  }

  /** The files of the cited ranges, recorded with the answer. */
  private synchronized void remember(List<String> cites) {
    for (String cite : cites) {
      Matcher range = RANGE.matcher(cite);
      if (range.matches()) {
        String key = range.group(1) + '\u0000' + range.group(2);
        if (!citedFiles.containsKey(key)) {
          graph.fileRead(range.group(1), range.group(2)).ifPresent(f -> citedFiles.put(key, f));
        }
      }
    }
  }

  /** An answer's cites by the field a rejection names: the entry's, or each proposal's. */
  private Map<String, List<String>> citesByField(JsonNode entry) {
    Map<String, List<String>> byField = new java.util.LinkedHashMap<>();
    if (mode == Mode.LEADS) {
      byField.put("cites", cites(entry));
    } else {
      for (var proposal : entry.path(InvestigatePrompt.PROPOSALS).properties()) {
        byField.put(
            at(InvestigatePrompt.PROPOSALS + "." + proposal.getKey(), "cites"),
            cites(proposal.getValue()));
      }
    }
    return byField;
  }

  /** An entry's cites as written, brackets and spaces around them removed. */
  static List<String> cites(JsonNode entry) {
    List<String> cites = new ArrayList<>();
    for (JsonNode cite : entry.path("cites")) {
      if (cite.isString()) {
        String text = cite.stringValue().strip();
        if (text.startsWith("[") && text.endsWith("]")) {
          text = text.substring(1, text.length() - 1).strip();
        }
        cites.add(text);
      }
    }
    return cites;
  }

  /**
   * An accepted entry as stored: in Mode A the entry; in Modes B and C its proposals as a list, in
   * the order submitted, each once, at most {@link #MAX_PROPOSALS}.
   */
  @Override
  public String store(Item item, ItemAnswer accepted) {
    JsonNode entry = accepted.raw();
    if (mode == Mode.LEADS) {
      return entry.toString();
    }
    ObjectNode stored = FactJson.newObject();
    ArrayNode list = stored.putArray(InvestigatePrompt.PROPOSALS);
    List<Map.Entry<Integer, JsonNode>> numbered = new ArrayList<>();
    for (var proposal : entry.path(InvestigatePrompt.PROPOSALS).properties()) {
      numbered.add(
          Map.entry(Integer.parseInt(proposal.getKey().substring(1)), proposal.getValue()));
    }
    numbered.sort(Map.Entry.comparingByKey());
    Set<String> keys = new LinkedHashSet<>();
    for (Map.Entry<Integer, JsonNode> proposal : numbered) {
      if (list.size() < MAX_PROPOSALS && keys.add(Investigator.proposalKey(proposal.getValue()))) {
        list.add(proposal.getValue());
      }
    }
    return stored.toString();
  }

  // ---------------------------------------------------------------------------------------------
  // The role's own checks

  /**
   * What the standard checks do not hold: a lead's verdict and the fields a confirmed one needs; a
   * risk's fields; units that are deployable units; names that are identifiers; a connection's ends
   * (a unit, and a model element or a new external system or database) that no link joins yet.
   */
  static final class Proposals implements Check {

    private final InvestigateRole role;

    Proposals(InvestigateRole role) {
      this.role = role;
    }

    @Override
    public String name() {
      return "Proposals";
    }

    @Override
    public List<Rejection> check(ItemAnswer answer, CheckInputs inputs) {
      List<Rejection> found = new ArrayList<>();
      if (answer.omitted() || answer.raw() == null || !answer.raw().isObject()) {
        return found;
      }
      if (role.mode == Mode.LEADS) {
        check(answer.item(), "", answer.raw(), found);
      } else {
        for (var proposal : answer.raw().path(InvestigatePrompt.PROPOSALS).properties()) {
          check(
              answer.item(),
              InvestigatePrompt.PROPOSALS + "." + proposal.getKey(),
              proposal.getValue(),
              found);
        }
      }
      return found;
    }

    private void check(String item, String field, JsonNode entry, List<Rejection> found) {
      if (!entry.isObject()) {
        return;
      }
      if (role.mode != Mode.LEADS
          && field.startsWith(InvestigatePrompt.PROPOSALS + ".P")
          && Integer.parseInt(field.substring(InvestigatePrompt.PROPOSALS.length() + 2))
              > MAX_PROPOSALS) {
        found.add(
            reject(
                item,
                field,
                "A conversation keeps at most " + MAX_PROPOSALS + " proposals; this one is over."));
        return;
      }
      String kind = entry.path("kind").asString("");
      if (role.mode == Mode.LEADS) {
        String verdict = entry.path("verdict").asString("");
        if (!VERDICTS.contains(verdict)) {
          found.add(reject(item, field, "Give the lead a verdict: confirmed, refuted or unclear."));
          return;
        }
        if (!kind.equals("risk")) {
          found.add(reject(item, field, "A lead's entry is of kind risk."));
        }
        if (verdict.equals("confirmed")) {
          riskFields(item, field, entry, found);
        }
        if (verdict.equals("refuted") && cites(entry).isEmpty()) {
          found.add(
              reject(item, field, "A refuted lead cites where the code shows it cannot happen."));
        }
      } else if (kind.equals("risk")) {
        riskFields(item, field, entry, found);
        if (role.mode == Mode.SYSTEM
            && (!entry.path("units").isArray() || entry.path("units").isEmpty())) {
          found.add(reject(item, field, "A risk of the system names the units it concerns."));
        }
      } else if (kind.equals("connection")) {
        connection(item, field, entry, found);
      }
      for (JsonNode unit : entry.path("units")) {
        if (!unit.isString() || !role.units.contains(unit.stringValue().strip())) {
          found.add(
              reject(
                  item,
                  field,
                  "Names the unit "
                      + unit.asString()
                      + ", which is not a deployable unit; name units by their IDs in the map."));
        }
      }
      for (JsonNode name : entry.path("names")) {
        String text = name.asString("").strip();
        if (text.length() < 3
            || !text.matches(".*\\p{L}.*")
            || NOT_NAMES.contains(text.toLowerCase(Locale.ROOT))) {
          found.add(
              reject(
                  item,
                  field,
                  "Gives the name \""
                      + text
                      + "\", which is not an identifier the code writes; give class, method,"
                      + " property, path, topic or host names."));
        }
      }
    }

    private void riskFields(String item, String field, JsonNode entry, List<Rejection> found) {
      List<String> missing = new ArrayList<>();
      for (String name : List.of("title", "finding", "consequence", "impact", "confidence")) {
        if (!entry.path(name).isString() || entry.get(name).stringValue().isBlank()) {
          missing.add(name);
        }
      }
      if (!missing.isEmpty()) {
        found.add(reject(item, field, "A risk needs its " + String.join(", ", missing) + "."));
      }
    }

    private void connection(String item, String field, JsonNode entry, List<Rejection> found) {
      String from = entry.path("from").asString(role.unit == null ? "" : role.unit).strip();
      String to = entry.path("to").asString("").strip();
      if (!role.units.contains(from)) {
        found.add(
            reject(
                item,
                field,
                "A connection runs from a deployable unit; "
                    + (from.isEmpty() ? "give its from" : from + " is not one")
                    + "."));
        return;
      }
      boolean created = to.startsWith("external-system:") || to.startsWith("database:");
      if (to.isEmpty() || to.equals(from) || !(created || role.element.apply(to))) {
        found.add(
            reject(
                item,
                field,
                "A connection ends at another element of the model, by its ID, or at a new"
                    + " external-system:<name> or database:<name>; "
                    + (to.isEmpty() ? "give its to" : to + " is not one")
                    + "."));
        return;
      }
      if (!entry.path("interaction").isString() || !entry.path("description").isString()) {
        found.add(reject(item, field, "A connection needs its interaction and description."));
      }
      String infrastructure =
          infrastructure(
              to
                  + " "
                  + entry.path("description").asString("")
                  + " "
                  + entry.path("technology").asString(""));
      if (infrastructure != null) {
        found.add(
            reject(
                item,
                field,
                "Connects to infrastructure ("
                    + infrastructure
                    + "): tracing, logging, metrics, a service registry or a config server is not"
                    + " what the unit does for the system; propose no such connection."));
      }
      if (role.linked.contains(pair(from, to))) {
        found.add(
            reject(
                item,
                field,
                "Repeats a link the static analysis already found between "
                    + from
                    + " and "
                    + to
                    + "."));
      }
    }

    /**
     * The first word of the contract's infrastructure list the text names, as a whole word, or null
     * (#749).
     */
    static String infrastructure(String text) {
      String lower = text.toLowerCase(java.util.Locale.ROOT);
      for (String word : INFRASTRUCTURE) {
        if (java.util.regex.Pattern.compile(
                "(?<![a-z0-9])" + java.util.regex.Pattern.quote(word) + "(?![a-z0-9])")
            .matcher(lower)
            .find()) {
          return word;
        }
      }
      return null;
    }

    private Rejection reject(String item, String field, String reason) {
      return new Rejection(item, field, name(), reason);
    }
  }

  /** The checks with each rejection made a rejection of the whole proposal it is in. */
  static final class ByProposal implements Check {

    private final List<Check> checks;

    ByProposal(List<Check> checks) {
      this.checks = List.copyOf(checks);
    }

    @Override
    public String name() {
      return "Proposals";
    }

    @Override
    public List<Rejection> check(ItemAnswer answer, CheckInputs inputs) {
      List<Rejection> found = new ArrayList<>();
      for (Check check : checks) {
        for (Rejection rejection : check.check(answer, inputs)) {
          Matcher proposal = PROPOSAL.matcher(rejection.field());
          found.add(
              proposal.find()
                  ? new Rejection(
                      rejection.item(), proposal.group(1), rejection.check(), rejection.reason())
                  : rejection);
        }
      }
      return found;
    }
  }
}

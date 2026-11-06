package io.lantern.link;

import io.lantern.core.fact.FactJson;
import io.lantern.harness.SingleAnswer;
import io.lantern.llm.LlmTool;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The answer of a unit's or the system's conversation (Modes B and C, #904): the same {@code
 * answer} tool as a lead's, taking TOML with {@code [[risk]]} and {@code [[connection]]} tables,
 * each with its {@code [[risk.evidence]]} or {@code [[connection.evidence]]} pieces as a lead's
 * evidence is written. It is read into the entry the role's checks verify, one proposal a table
 * ({@code proposals.P1}, ...), numbered on across the conversation's answers so a corrected one is
 * a new proposal; each is kept or rejected on its own. An answer with no table says nothing was
 * found, which is a good answer.
 */
final class InvestigateExploreAnswer implements SingleAnswer {

  /** How many answers a unit's or the system's conversation may give. */
  static final int ATTEMPTS = 3;

  static final String DESCRIPTION =
      "Your answer, as TOML in the form your instructions show: every risk and connection you"
          + " found, or none. Each is checked on its own: those that pass are recorded; you get the"
          + " problems of the others back and may answer again with them corrected (three answers"
          + " in all). When nothing fails, the conversation ends.";

  private static final Set<String> RISK_FIELDS =
      Set.of("title", "finding", "consequence", "impact", "confidence");

  private static final Set<String> CONNECTION_FIELDS =
      Set.of("from", "to", "interaction", "description", "technology");

  private static final LlmTool TOOL =
      new LlmTool(
          InvestigateLeadAnswer.NAME,
          DESCRIPTION,
          FactJson.parse(
              "{\"type\":\"object\",\"properties\":{\"toml\":{\"type\":\"string\"}},"
                  + "\"required\":[\"toml\"]}"));

  private int proposals;

  /** Each proposal's key with the words that name it to the model: its kind and title. */
  private final java.util.Map<String, String> labels =
      new java.util.concurrent.ConcurrentHashMap<>();

  @Override
  public LlmTool tool() {
    return TOOL;
  }

  /** The field of a proposal that could not be read, with what to fix; the role rejects it. */
  static final String UNREADABLE = "unreadable";

  // Whether the last answer could not be read at all, so nothing of it was recorded.
  private boolean unread;

  @Override
  public synchronized JsonNode read(JsonNode input) {
    unread = true;
    String text =
        input == null ? "" : input.isString() ? input.asString() : input.path("toml").asString("");
    JsonNode root =
        input != null && input.isObject() && (input.has("risk") || input.has("connection"))
            ? input
            : nothing(text) ? FactJson.newObject() : InvestigateLeadAnswer.parse(text);
    if (!root.isObject()) {
      throw new IllegalArgumentException(
          "The answer is not a TOML table of [[risk]] and [[connection]] entries.");
    }
    if (!root.has("risk") && !root.has("connection") && !root.isEmpty()) {
      // A misspelled table, such as [[risks]], is no answer of nothing found (#904).
      List<String> keys = new ArrayList<>();
      root.propertyNames().forEach(keys::add);
      throw new IllegalArgumentException(
          "The answer has "
              + String.join(", ", keys)
              + " but no [[risk]] or [[connection]] table; write each as [[risk]] or"
              + " [[connection]], or give no table if you found nothing.");
    }
    ObjectNode entry = FactJson.newObject();
    ObjectNode proposed = entry.putObject(InvestigatePrompt.PROPOSALS);
    int[] inAnswer = {0};
    tables(root.path("risk"), "risk", RISK_FIELDS, proposed, inAnswer);
    tables(root.path("connection"), "connection", CONNECTION_FIELDS, proposed, inAnswer);
    unread = false;
    return entry;
  }

  /** Whether an answer holds nothing but blank lines and comments: nothing found. */
  static boolean nothing(String text) {
    return text.lines().map(String::strip).allMatch(line -> line.isEmpty() || line.startsWith("#"));
  }

  /**
   * Reads one kind's tables as proposals. A table that cannot be read, or one past the most an
   * answer gives, is a proposal marked {@link #UNREADABLE} with what to fix, which the role rejects
   * on its own, so the others of the answer are still kept (#904).
   */
  private void tables(
      JsonNode tables, String kind, Set<String> fields, ObjectNode proposed, int[] inAnswer) {
    if (tables.isMissingNode()) {
      return;
    }
    if (tables.isObject()) {
      ArrayNode one = FactJson.newObject().putArray("t");
      one.add(tables);
      tables = one;
    }
    if (!tables.isArray()) {
      String key = "P" + (++proposals);
      labels.put(InvestigatePrompt.PROPOSALS + "." + key, kind);
      proposed
          .putObject(key)
          .put("kind", kind)
          .put(UNREADABLE, "give each " + kind + " as a [[" + kind + "]] table.");
      return;
    }
    for (JsonNode table : tables) {
      String key = "P" + (++proposals);
      labels.put(InvestigatePrompt.PROPOSALS + "." + key, (kind + " " + label(table)).strip());
      ObjectNode proposal = proposed.putObject(key);
      proposal.put("kind", kind);
      for (String field : fields) {
        JsonNode value = table.path(field);
        if (value.isString() && !value.asString().isBlank()) {
          proposal.put(field, value.asString().strip());
        }
      }
      JsonNode units = table.path("units");
      if (units.isArray()) {
        ArrayNode list = proposal.putArray("units");
        units.forEach(u -> list.add(u.asString("").strip()));
      }
      if (++inAnswer[0] > InvestigateRole.MAX_PROPOSALS) {
        proposal.put(
            UNREADABLE,
            "an answer gives at most "
                + InvestigateRole.MAX_PROPOSALS
                + " risks and connections; this one is over.");
        continue;
      }
      try {
        InvestigateLeadAnswer.evidence(table.path("evidence"), proposal);
      } catch (IllegalArgumentException e) {
        proposal.put(UNREADABLE, e.getMessage());
        continue;
      }
      if (proposal.path("evidence").isEmpty()) {
        proposal.put(UNREADABLE, "give at least one [[" + kind + ".evidence]] piece.");
      }
    }
  }

  private static String label(JsonNode table) {
    String title = table.path("title").asString("");
    if (title.isBlank() && !table.path("to").asString("").isBlank()) {
      return "to " + table.path("to").asString();
    }
    return title.isBlank() ? "" : "\"" + title + "\"";
  }

  @Override
  public String passed() {
    return "recorded = true";
  }

  @Override
  public String failed(List<String> problems, int attempt, int attempts) {
    StringBuilder out =
        new StringBuilder(
            unread
                ? "recorded = false\n"
                : "recorded = \"in part: the entries below were not recorded\"\n");
    out.append("problems = [\n");
    for (String problem : problems) {
      out.append("  ").append(io.lantern.harness.Toml.string(named(problem))).append(",\n");
    }
    out.append("]\nanswers_left = ").append(Math.max(0, attempts - attempt)).append('\n');
    return out.toString();
  }

  /**
   * A problem with the proposal's key, which the model never saw, replaced by its kind and title:
   * {@code proposals.P2: ...} as {@code risk "Uploads have no size limit": ...}.
   */
  String named(String problem) {
    java.util.regex.Matcher key =
        java.util.regex.Pattern.compile("^" + InvestigatePrompt.PROPOSALS + "\\.P\\d+")
            .matcher(problem);
    if (!key.find()) {
      return problem;
    }
    String label = labels.get(key.group());
    if (label == null) {
      return problem;
    }
    String rest = problem.substring(key.end());
    return label + (rest.startsWith(".") ? " " + rest.substring(1) : rest);
  }

  @Override
  public int attempts() {
    return ATTEMPTS;
  }

  @Override
  public String reminder() {
    return InvestigateLeadAnswer.REMINDER;
  }

  @Override
  public boolean holdsAnswer(String text) {
    if (text == null || !(text.contains("[[risk") || text.contains("[[connection"))) {
      return false;
    }
    try {
      JsonNode root = InvestigateLeadAnswer.parse(text);
      return root.has("risk") || root.has("connection");
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}

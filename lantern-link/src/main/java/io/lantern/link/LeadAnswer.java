package io.lantern.link;

import io.lantern.core.fact.FactJson;
import io.lantern.harness.SingleAnswer;
import io.lantern.harness.Toml;
import io.lantern.harness.verify.Check;
import io.lantern.harness.verify.CheckInputs;
import io.lantern.harness.verify.ItemAnswer;
import io.lantern.harness.verify.Rejection;
import io.lantern.llm.LlmTool;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A lead's answer (#892): one {@code answer} tool taking TOML, in the form the instructions show: a
 * verdict, a confirmed lead's risk, and evidence pieces that each name a range, quote one line of
 * it exactly and list the names in it the answer uses. It is read into the entry the role's checks
 * verify (its ranges as {@code cites}, the names of all pieces as {@code names}); {@link Quotes}
 * checks each piece against the files as the model saw them. Three answers in all; the reply is
 * TOML.
 */
final class LeadAnswer implements SingleAnswer {

  /** The tool's name. */
  static final String NAME = "answer";

  /** How many answers a lead's conversation may give. */
  static final int ATTEMPTS = 3;

  /** The most pieces of evidence an answer gives. */
  static final int MAX_EVIDENCE = 8;

  static final String DESCRIPTION =
      "Your answer, as TOML in the form your instructions show. If it passes Lantern's checks it is"
          + " recorded and the conversation ends; if not, you get the problems back and may answer"
          + " again (three times in all).";

  static final String REMINDER = "Give your answer through the answer tool.";

  private static final Set<String> VERDICTS = Set.of("confirmed", "refuted", "unclear");

  private static final Set<String> FIELDS =
      Set.of("title", "finding", "consequence", "impact", "confidence", "reason");

  private static final Pattern RANGE = Pattern.compile("^([^:]+):(.+):(\\d+)-(\\d+)$");

  private static final LlmTool TOOL =
      new LlmTool(
          NAME,
          DESCRIPTION,
          FactJson.parse(
              "{\"type\":\"object\",\"properties\":{\"toml\":{\"type\":\"string\"}},"
                  + "\"required\":[\"toml\"]}"));

  /** The entry the checks verify: what the answer's TOML is read into. */
  static final JsonNode ENTRY_SCHEMA =
      FactJson.parse(
          """
          {"type": "object", "required": ["verdict", "kind"], "properties": {
            "verdict": {"enum": ["confirmed", "refuted", "unclear"]},
            "kind": {"enum": ["risk"]},
            "title": {"type": "string", "maxLength": 80},
            "finding": {"type": "string", "maxLength": 400},
            "consequence": {"type": "string", "maxLength": 400},
            "impact": {"enum": ["high", "medium", "low"]},
            "confidence": {"enum": ["high", "medium", "low"]},
            "reason": {"type": "string", "maxLength": 400},
            "names": {"type": "array", "items": {"type": "string"}},
            "cites": {"type": "array", "items": {"type": "string"}},
            "evidence": {"type": "array"}}}
          """);

  @Override
  public LlmTool tool() {
    return TOOL;
  }

  @Override
  public JsonNode read(JsonNode input) {
    JsonNode root;
    if (input != null && input.isObject() && input.has("verdict")) {
      root = input; // the answer's fields given as the tool's input itself
    } else {
      String text =
          input == null
              ? ""
              : input.isString() ? input.asString() : input.path("toml").asString("");
      if (text.isBlank()) {
        throw new IllegalArgumentException(
            "The answer is empty: give its TOML in the answer tool's toml field.");
      }
      try {
        root = Toml.read(text).root();
      } catch (Toml.Fault e) {
        throw new IllegalArgumentException("The answer is not TOML: " + e.getMessage());
      }
    }
    if (!root.isObject()) {
      throw new IllegalArgumentException("The answer is not a TOML table with a verdict.");
    }
    return entry(root);
  }

  /** The answer as the entry the checks verify. */
  static ObjectNode entry(JsonNode root) {
    String verdict = root.path("verdict").asString("").strip().toLowerCase(java.util.Locale.ROOT);
    if (!VERDICTS.contains(verdict)) {
      throw new IllegalArgumentException(
          "verdict: give confirmed, refuted or unclear, as verdict = \"confirmed\".");
    }
    ObjectNode entry = FactJson.newObject();
    entry.put("verdict", verdict);
    entry.put("kind", "risk");
    for (String field : FIELDS) {
      JsonNode value = root.path(field);
      if (value.isString() && !value.asString().isBlank()) {
        entry.put(field, value.asString().strip());
      }
    }
    JsonNode evidence = root.path("evidence");
    if (evidence.isObject()) {
      ArrayNode one = FactJson.newObject().putArray("e");
      one.add(evidence);
      evidence = one;
    }
    List<String> problems = new ArrayList<>();
    ArrayNode pieces = entry.putArray("evidence");
    ArrayNode cites = entry.putArray("cites");
    Set<String> names = new LinkedHashSet<>();
    if (evidence.isArray()) {
      if (evidence.size() > MAX_EVIDENCE) {
        problems.add("evidence: give at most " + MAX_EVIDENCE + " pieces.");
      }
      int n = 0;
      for (JsonNode piece : evidence) {
        n++;
        String lines = piece.path("lines").asString("").strip();
        if (!RANGE.matcher(lines).matches()) {
          problems.add(
              "evidence "
                  + n
                  + ": lines must be repo:path:from-to, such as Shop:src/Orders.java:12-30.");
          continue;
        }
        if (!piece.path("quote").isString()) {
          problems.add("evidence " + n + ": quote one line of " + lines + " exactly.");
        }
        ObjectNode copy = pieces.addObject();
        copy.put("lines", lines);
        copy.put("quote", piece.path("quote").asString(""));
        ArrayNode shows = copy.putArray("shows");
        JsonNode listed = piece.path("shows");
        if (listed.isString()) {
          shows.add(listed.asString());
          names.add(listed.asString());
        } else {
          for (JsonNode name : listed) {
            if (name.isString() && !name.asString().isBlank()) {
              shows.add(name.asString());
              names.add(name.asString());
            }
          }
        }
        cites.add(lines);
      }
    } else if (!evidence.isMissingNode()) {
      problems.add("evidence: give each piece as a [[evidence]] table.");
    }
    if (!problems.isEmpty()) {
      throw new IllegalArgumentException(String.join(" ", problems));
    }
    if (pieces.isEmpty() && !verdict.equals("unclear")) {
      throw new IllegalArgumentException(
          "evidence: a " + verdict + " verdict needs at least one [[evidence]] piece.");
    }
    if (pieces.isEmpty() && !entry.has("reason")) {
      throw new IllegalArgumentException(
          "An unclear verdict needs evidence or a reason = \"what the code does not show\".");
    }
    ArrayNode list = entry.putArray("names");
    names.forEach(list::add);
    return entry;
  }

  @Override
  public boolean holdsAnswer(String text) {
    if (text == null || !text.contains("verdict")) {
      return false;
    }
    try {
      return Toml.read(text).root().has("verdict");
    } catch (Toml.Fault e) {
      return false;
    }
  }

  @Override
  public String passed() {
    return "recorded = true";
  }

  @Override
  public String failed(List<String> problems, int attempt, int attempts) {
    StringBuilder out = new StringBuilder("recorded = false\nproblems = [");
    for (int i = 0; i < problems.size(); i++) {
      out.append(i == 0 ? "\n  " : ",\n  ").append(Toml.string(problems.get(i)));
    }
    out.append("\n]\n");
    if (attempt < attempts) {
      out.append("answers_left = ").append(attempts - attempt);
    } else {
      out.append("ended = \"no verdict is recorded\"");
    }
    return out.toString();
  }

  @Override
  public int attempts() {
    return ATTEMPTS;
  }

  @Override
  public String reminder() {
    return REMINDER;
  }

  // ---------------------------------------------------------------------------------------------

  /**
   * Each evidence piece checked against the files as the model saw them, masked (#892): its quote
   * is one line of its range, character for character, spaces at either end aside; and each name it
   * shows appears in that range or its path ({@link #holds}).
   */
  static final class Quotes implements Check {

    @Override
    public String name() {
      return "quotes";
    }

    @Override
    public List<Rejection> check(ItemAnswer answer, CheckInputs inputs) {
      List<Rejection> found = new ArrayList<>();
      JsonNode entry = answer.raw();
      if (entry == null) {
        return found;
      }
      int n = 0;
      for (JsonNode piece : entry.path("evidence")) {
        n++;
        Matcher m = RANGE.matcher(piece.path("lines").asString(""));
        if (!m.matches()) {
          continue;
        }
        String repo = m.group(1);
        String path = m.group(2);
        int from = Integer.parseInt(m.group(3));
        int to = Integer.parseInt(m.group(4));
        Optional<List<String>> file = inputs.files().lines(repo, path);
        if (file.isEmpty() || from < 1 || to < from || to > file.get().size()) {
          continue; // the Ranges check says what is wrong with the range
        }
        List<String> range = file.get().subList(from - 1, to);
        String quote = piece.path("quote").asString("").strip();
        if (range.stream().noneMatch(line -> line.strip().equals(quote))) {
          found.add(
              reject(
                  answer,
                  n,
                  "the quote is not a line of "
                      + m.group()
                      + closest(range, from, quote)
                      + "; copy one line of the range exactly."));
        }
        String text = normal(String.join("\n", range));
        String where = normal(path);
        for (JsonNode name : piece.path("shows")) {
          if (!holds(name.asString(""), text, where)) {
            found.add(
                reject(
                    answer,
                    n,
                    "\""
                        + name.asString("")
                        + "\" is not in "
                        + m.group()
                        + "; list in shows only names the range holds, or give the range that"
                        + " holds it."));
          }
        }
      }
      return found;
    }

    /**
     * Whether a range shows a name, compared as the other checks compare names, without case or
     * anything but letters and digits: in its lines or its path (a class in its own file), and a
     * dotted name ({@code RefundService.refund}, {@code spring.r2dbc.password}) part by part, since
     * a type's method or a nested setting is written over several places.
     */
    static boolean holds(String name, String text, String where) {
      String whole = normal(name);
      if (whole.isEmpty() || text.contains(whole) || where.contains(whole)) {
        return true;
      }
      String[] parts = name.split("\\.");
      if (parts.length < 2) {
        return false;
      }
      for (String part : parts) {
        String p = normal(part);
        if (!p.isEmpty() && !text.contains(p) && !where.contains(p)) {
          return false;
        }
      }
      return true;
    }

    private static String normal(String text) {
      return text.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static Rejection reject(ItemAnswer answer, int piece, String reason) {
      return new Rejection(answer.item(), "evidence " + piece, "quotes", reason);
    }

    /** The line of the range most like the quote, to show what it reads. */
    private static String closest(List<String> range, int from, String quote) {
      int best = -1;
      int score = -1;
      for (int i = 0; i < range.size(); i++) {
        String line = range.get(i).strip();
        int common = 0;
        while (common < line.length()
            && common < quote.length()
            && line.charAt(common) == quote.charAt(common)) {
          common++;
        }
        if (common > score) {
          score = common;
          best = i;
        }
      }
      if (best < 0 || score < 4) {
        return "";
      }
      String line = range.get(best).strip();
      return "; line "
          + (from + best)
          + " reads: '"
          + (line.length() > 120 ? line.substring(0, 120) + "…" : line)
          + "'";
    }
  }
}

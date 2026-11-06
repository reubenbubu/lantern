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
final class InvestigateLeadAnswer implements SingleAnswer {

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

  /** One line, as {@code repo:path:12}. */
  private static final Pattern ONE_LINE = Pattern.compile("^[^:]+:.+:(\\d+)$");

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
        Matcher single = ONE_LINE.matcher(lines);
        if (single.matches()) {
          lines = lines + "-" + single.group(1); // one line, as repo:path:12, is 12-12
        }
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

  /** What the first message showed of a lead: its kind and the files its evidence is in. */
  record Shown(String kind, Set<String> files) {

    private static final Pattern WHERE = Pattern.compile("(?m)^where = \"([^\"]+)\"$");

    /** From a lead's subject ({@code lead:<kind>/...}) and its first message. */
    static Shown of(String subject, String message) {
      String kind =
          subject.startsWith("lead:") && subject.indexOf('/') > 5
              ? subject.substring(5, subject.indexOf('/'))
              : "";
      Set<String> files = new java.util.LinkedHashSet<>();
      Matcher where = WHERE.matcher(message);
      while (where.find()) {
        files.add(where.group(1));
      }
      return new Shown(kind, Set.copyOf(files));
    }
  }

  /** The kinds whose risk lies in settings or the build, refuted only after looking at both. */
  static final Set<String> BUILT_KINDS =
      Set.of(
          "environment-baked",
          "plaintext-secret",
          "schema-unmanaged",
          "open-cors",
          "single-instance");

  /** A file that says how a unit is built or deployed. */
  static final Pattern BUILD_OR_DEPLOY =
      Pattern.compile(
          "(?i)(^|.*/)(Dockerfile[^/]*|[^/]*\\.dockerfile|Containerfile|docker-compose[^/]*|compose[^/]*\\.ya?ml"
              + "|Chart\\.ya?ml|values[^/]*\\.ya?ml|Jenkinsfile|\\.gitlab-ci\\.ya?ml|azure-pipelines\\.ya?ml"
              + "|skaffold\\.ya?ml|kustomization\\.ya?ml|Procfile|fly\\.toml|app\\.ya?ml)$"
              + "|(?i)(^|.*/)(\\.github/workflows|\\.circleci|ci|\\.ci|deploy|deployment|deployments|k8s"
              + "|kubernetes|helm|charts|manifests|infra|ops|openshift)/.*\\.(ya?ml|json|tpl)$");

  /**
   * A refuted lead of a settings or build kind whose evidence cites no file that builds or deploys
   * a unit, nor any file its first message showed, sent back once so the model looks at how the
   * unit is built and deployed; a second such answer stands, since the rule cannot foresee every
   * correct refutation (#894).
   */
  static final class BuiltAndDeployed implements Check {

    static final String REASON =
        "a refuted verdict on this kind needs evidence of how the unit is built or deployed: look"
            + " at its Dockerfile, CI workflow, compose, Helm or Kubernetes files and cite the lines"
            + " that stop the risk; if nothing there settles it, the verdict is unclear.";

    private final java.util.function.Function<String, Shown> shown;
    private final Set<String> warned;

    /**
     * @param shown what each lead's first message showed, by its item's ID
     * @param warned the items already sent back for this, kept by the role, as each check of an
     *     answer builds its pipeline anew
     */
    BuiltAndDeployed(java.util.function.Function<String, Shown> shown, Set<String> warned) {
      this.shown = shown;
      this.warned = warned;
    }

    @Override
    public String name() {
      return "built-and-deployed";
    }

    @Override
    public List<Rejection> check(ItemAnswer answer, CheckInputs inputs) {
      JsonNode entry = answer.raw();
      Shown lead = shown.apply(answer.item());
      if (entry == null
          || lead == null
          || !entry.path("verdict").asString("").equals("refuted")
          || !BUILT_KINDS.contains(lead.kind())) {
        return List.of();
      }
      for (JsonNode piece : entry.path("evidence")) {
        Matcher m = RANGE.matcher(piece.path("lines").asString(""));
        if (m.matches()
            && (BUILD_OR_DEPLOY.matcher(m.group(2)).matches()
                || lead.files().contains(m.group(1) + ":" + m.group(2)))) {
          return List.of();
        }
      }
      if (!warned.add(answer.item())) {
        return List.of();
      }
      return List.of(new Rejection(answer.item(), "verdict", "built-and-deployed", REASON));
    }
  }

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
        if (normal(quote).isEmpty()) {
          found.add(
              reject(
                  answer,
                  n,
                  "the quote shows nothing; copy one line of "
                      + m.group()
                      + " that holds what the piece shows."));
        } else if (range.stream().noneMatch(line -> quotes(line.strip(), quote))) {
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
     * Whether a quote is the line or, for a long line the first message cut at {@value
     * InvestigateLeadMessage#LINE_WIDTH} characters and ended with "…", its start as shown, with or
     * without the "…": at least half that width, so a quote still holds a line's substance.
     */
    private static boolean quotes(String line, String quote) {
      if (line.equals(quote)) {
        return true;
      }
      String shown = quote.endsWith("…") ? quote.substring(0, quote.length() - 1).strip() : quote;
      return shown.length() >= InvestigateLeadMessage.LINE_WIDTH / 2 && line.startsWith(shown);
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
          + (line.length() > InvestigateLeadMessage.LINE_WIDTH
              ? line.substring(0, InvestigateLeadMessage.LINE_WIDTH - 1) + "…"
              : line)
          + "'";
    }
  }
}

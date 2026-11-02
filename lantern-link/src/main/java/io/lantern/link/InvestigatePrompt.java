package io.lantern.link;

import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.harness.Prompts;
import io.lantern.harness.render.Blocks;
import io.lantern.harness.render.FactLines;
import io.lantern.harness.render.Refs;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.BiFunction;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Investigate prompt (knowledge-build epic, section 11.2): block 1 is the common system prompt,
 * block 2 a mode's instructions and the submit schema, read from {@code prompts/investigate-*};
 * block 3 the system map; block 4 the context of the packet (a unit's facts, or every unit's risks
 * already found); block 5 the items. Investigate cites file ranges only, so facts are shown as fact
 * lines without references, and code as numbered {@code <code>} excerpts without a reference.
 *
 * <p>Change {@link #VERSION} whenever the text of the prompt or the way inputs are written into it
 * changes, so answers cached for the old prompt are not reused. {@code InvestigatePromptTest} fails
 * until it is changed.
 */
final class InvestigatePrompt {

  /** The prompt's version, part of every answer's cache key and the version of the facts. */
  static final String VERSION = "4";

  /** Block 2's instructions of Mode A, checking leads, as the epic writes them. */
  static final String LEADS_TASK = resource("investigate-leads.task.txt");

  /** Block 2's instructions of Modes B and C, exploring, as the epic writes them. */
  static final String EXPLORE_TASK = resource("investigate-explore.task.txt");

  /** The submit schema of every mode, as the epic writes it. */
  static final String SCHEMA = resource("investigate.schema.json");

  /** What separates a role's instructions from its schema in block 2. */
  static final String SCHEMA_HEADING = "\n\nAnswer schema:\n";

  /** The submit tool's input schema. */
  static final JsonNode SUBMIT_SCHEMA = FactJson.parse(SCHEMA);

  /** The schema of one submitted entry: a lead's verdict, a risk or a connection. */
  static final JsonNode ENTRY_SCHEMA = SUBMIT_SCHEMA.path("properties").path("items").path("items");

  /** The schema of a Mode A item's entry: an entry without its lead, which names the item. */
  static final JsonNode LEAD_SCHEMA = withoutLead(ENTRY_SCHEMA);

  /** The schema of a Mode B or C item's entry: the proposals submitted, by their number. */
  static final JsonNode PROPOSALS_SCHEMA = proposals(ENTRY_SCHEMA);

  /** The field of a Mode B or C entry that holds its proposals. */
  static final String PROPOSALS = "proposals";

  /** The most lines of code an item shows. */
  static final int MAX_CODE_LINES = 120;

  /** The most lines one excerpt shows. */
  static final int MAX_EXCERPT_LINES = 40;

  /** Lines shown before what a fact cites, and after it. */
  static final int BEFORE = 3;

  static final int AFTER = 12;

  /** The most facts a block shows. */
  static final int MAX_FACTS = 150;

  private InvestigatePrompt() {}

  /** Block 2 of a mode. */
  static String role(boolean leads) {
    return (leads ? LEADS_TASK : EXPLORE_TASK) + SCHEMA_HEADING + SCHEMA;
  }

  /** Facts as fact lines without references, sorted, at most {@link #MAX_FACTS}, in a block. */
  static String facts(List<Fact> facts) {
    List<String> lines = new ArrayList<>(facts.stream().map(FactLines::line).distinct().toList());
    lines.sort(String::compareTo);
    return Blocks.tagged("facts", lines.subList(0, Math.min(MAX_FACTS, lines.size())));
  }

  /**
   * A lead as an item: its kind and why it fired, what to judge, its units and code, the facts it
   * rests on, and the code they were read from.
   *
   * @param lines the lines of a file of the snapshot as the tools show them, secrets masked
   */
  static String lead(
      String id,
      String kind,
      String trigger,
      String judge,
      List<String> units,
      List<String> code,
      List<Fact> facts,
      BiFunction<String, String, Optional<List<String>>> lines) {
    StringBuilder text = new StringBuilder();
    text.append("Lead ")
        .append(FactLines.oneLine(id))
        .append(", of kind ")
        .append(kind)
        .append(".\n");
    if (trigger != null && !trigger.isBlank()) {
      text.append("Why static analysis flagged it: ")
          .append(FactLines.oneLine(trigger))
          .append(".\n");
    }
    if (judge != null && !judge.isBlank()) {
      text.append("What to judge: ").append(FactLines.oneLine(judge)).append('\n');
    }
    if (!units.isEmpty()) {
      text.append("Units: ").append(String.join(", ", units)).append(".\n");
    }
    if (!code.isEmpty()) {
      text.append("Code it points at: ")
          .append(String.join(", ", code.subList(0, Math.min(10, code.size()))))
          .append(code.size() > 10 ? ", and " + (code.size() - 10) + " more" : "")
          .append(".\n");
    }
    text.append(facts(facts));
    for (String excerpt : excerpts(facts, lines)) {
      text.append('\n').append(excerpt);
    }
    return Refs.clean(text.toString().strip());
  }

  /**
   * The code the facts cite, a few lines around each range, ranges of a file that overlap merged,
   * each excerpt at most {@link #MAX_EXCERPT_LINES} lines and all of them at most {@link
   * #MAX_CODE_LINES}.
   */
  static List<String> excerpts(
      List<Fact> facts, BiFunction<String, String, Optional<List<String>>> lines) {
    Map<String, List<int[]>> ranges = new TreeMap<>();
    for (Fact fact : facts) {
      for (Evidence.Location location : fact.evidence().locations()) {
        if (location.lines() == null) {
          continue;
        }
        ranges
            .computeIfAbsent(location.repo() + '\u0000' + location.path(), k -> new ArrayList<>())
            .add(new int[] {location.lines().start(), location.lines().end()});
      }
    }
    List<String> excerpts = new ArrayList<>();
    int shown = 0;
    for (Map.Entry<String, List<int[]>> file : ranges.entrySet()) {
      String repo = file.getKey().substring(0, file.getKey().indexOf('\u0000'));
      String path = file.getKey().substring(file.getKey().indexOf('\u0000') + 1);
      Optional<List<String>> all = lines.apply(repo, path);
      if (all.isEmpty() || all.get().isEmpty()) {
        continue;
      }
      List<int[]> windows = new ArrayList<>();
      for (int[] range : file.getValue()) {
        int from = Math.max(1, range[0] - BEFORE);
        int to = Math.min(all.get().size(), Math.max(range[1], range[0]) + AFTER);
        to = Math.min(to, from + MAX_EXCERPT_LINES - 1);
        windows.add(new int[] {from, to});
      }
      windows.sort((a, b) -> Integer.compare(a[0], b[0]));
      List<int[]> merged = new ArrayList<>();
      for (int[] window : windows) {
        int[] last = merged.isEmpty() ? null : merged.getLast();
        if (last != null
            && window[0] <= last[1] + 1
            && Math.max(last[1], window[1]) - last[0] < MAX_EXCERPT_LINES) {
          last[1] = Math.max(last[1], window[1]);
        } else {
          merged.add(window);
        }
      }
      for (int[] window : merged) {
        int size = window[1] - window[0] + 1;
        if (shown + size > MAX_CODE_LINES) {
          return excerpts;
        }
        shown += size;
        excerpts.add(code(repo, path, window[0], all.get().subList(window[0] - 1, window[1])));
      }
    }
    return excerpts;
  }

  /** An excerpt with its line numbers, as {@code <code repo="..." path="..." lines="30-58">}. */
  static String code(String repo, String path, int first, List<String> lines) {
    int last = first + Math.max(lines.size(), 1) - 1;
    int width = String.valueOf(last).length();
    StringBuilder out = new StringBuilder();
    out.append("<code repo=\"")
        .append(attribute(repo))
        .append("\" path=\"")
        .append(attribute(path))
        .append("\" lines=\"")
        .append(first)
        .append('-')
        .append(last)
        .append("\">\n");
    for (int i = 0; i < lines.size(); i++) {
      String number = String.valueOf(first + i);
      out.append(" ".repeat(width - number.length())).append(number).append("  ");
      out.append(Refs.clean(lines.get(i))).append('\n');
    }
    return out.append("</code>").toString();
  }

  /** A deployable unit as a Mode B item. */
  static String unit(String unit, List<String> repos) {
    return "The deployable unit "
        + unit
        + (repos.isEmpty() ? "." : ", its code in " + String.join(", ", repos) + ".")
        + " Its facts, links, open links, summaries and the risks already checked are above.";
  }

  /** The system as the Mode C item. */
  static String system(List<String> repos) {
    return "The whole system: every deployable unit and link in the map above, its code in "
        + String.join(", ", repos)
        + ". The risks already found in its units are above; look for risks and connections"
        + " across units.";
  }

  /** The answers block: one line each. */
  static String answers(List<String> lines) {
    return Blocks.tagged("answers", lines.stream().map(FactLines::oneLine).toList());
  }

  /** The system map, block 3: the deployable units, then the links static analysis found. */
  static String systemMap(List<String> units, List<String> links) {
    List<String> lines = new ArrayList<>();
    lines.add("The deployable units of the system, by ID:");
    units.forEach(unit -> lines.add("- " + FactLines.oneLine(unit)));
    if (!links.isEmpty()) {
      lines.add("The links static analysis found between them:");
      links.forEach(link -> lines.add("- " + FactLines.oneLine(link)));
    }
    return Blocks.tagged("facts", lines);
  }

  private static JsonNode withoutLead(JsonNode entry) {
    ObjectNode copy = ((ObjectNode) entry).deepCopy();
    ((ObjectNode) copy.path("properties")).remove("lead");
    return copy;
  }

  private static JsonNode proposals(JsonNode entry) {
    ObjectNode schema = FactJson.newObject();
    schema.put("type", "object");
    schema.putArray("required").add(PROPOSALS);
    ObjectNode proposals = schema.putObject("properties").putObject(PROPOSALS);
    proposals.put("type", "object");
    proposals.set("additionalProperties", withoutLead(entry));
    return schema;
  }

  private static String attribute(String value) {
    return FactLines.oneLine(value).replace("&", "&amp;").replace("\"", "&quot;");
  }

  /** Reads a prompt resource as written. */
  private static String resource(String name) {
    return Prompts.text(name);
  }
}

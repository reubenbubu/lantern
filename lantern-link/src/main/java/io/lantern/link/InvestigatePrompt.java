package io.lantern.link;

import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.harness.Prompts;
import io.lantern.harness.render.Blocks;
import io.lantern.harness.render.FactLines;
import java.util.ArrayList;
import java.util.List;
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
  static final String VERSION = "10";

  /** Block 2's instructions of Mode A, checking leads, as the epic writes them. */
  static final String LEADS_TASK = resource("investigate-leads.task.txt");

  /** Block 2's instructions of Modes B and C, exploring, as the epic writes them. */
  static final String EXPLORE_TASK = resource("investigate-explore.task.txt");

  /** The submit schema of every mode, as the epic writes it. */
  static final String SCHEMA = resource("investigate.schema.json");

  /** What separates a role's instructions from its schema in block 2. */
  static final String SCHEMA_HEADING = "\n\nAnswer schema:\n";

  /** The submit tool's input schema. */
  /** Mode A's own system prompt (#892): the shared one's JSON rules do not apply to it. */
  static final String LEADS_SYSTEM = Prompts.text("investigate-leads.system.txt").strip();

  static final JsonNode SUBMIT_SCHEMA = FactJson.parse(SCHEMA);

  /** The schema of one submitted entry: a lead's verdict, a risk or a connection. */
  static final JsonNode ENTRY_SCHEMA = SUBMIT_SCHEMA.path("properties").path("items").path("items");

  /** The schema of a Mode B or C item's entry: the proposals submitted, by their number. */
  static final JsonNode PROPOSALS_SCHEMA = proposals(ENTRY_SCHEMA);

  /** The field of a Mode B or C entry that holds its proposals. */
  static final String PROPOSALS = "proposals";

  /** The most facts a block shows. */
  static final int MAX_FACTS = 150;

  private InvestigatePrompt() {}

  /** Block 2 of Modes B and C; Mode A has its own instructions and no schema (#892). */
  static String role() {
    return EXPLORE_TASK + SCHEMA_HEADING + SCHEMA;
  }

  /** Facts as fact lines without references, sorted, at most {@link #MAX_FACTS}, in a block. */
  static String facts(List<Fact> facts) {
    List<String> lines = new ArrayList<>(facts.stream().map(FactLines::line).distinct().toList());
    lines.sort(String::compareTo);
    return Blocks.tagged("facts", lines.subList(0, Math.min(MAX_FACTS, lines.size())));
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

  /** Reads a prompt resource as written. */
  private static String resource(String name) {
    return Prompts.text(name);
  }
}

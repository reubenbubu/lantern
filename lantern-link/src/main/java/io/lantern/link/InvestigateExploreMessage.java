package io.lantern.link;

import io.lantern.core.fact.Fact;
import io.lantern.harness.Toml;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The first message of a unit's or the system's conversation (Modes B and C, #904), as TOML like a
 * lead's: {@code [task]} says what to look for and lists the unit IDs and repositories; {@code
 * [unit]} (Mode B) holds the unit's summaries and facts as short sentences, or {@code [[unit]]}
 * (Mode C) one entry a unit, with the links static analysis found counted once; each {@code
 * [[known]]} is a lead already checked or a risk already found, so it is not reported again.
 */
final class InvestigateExploreMessage {

  /** At most this many fact sentences in a unit's message. */
  static final int MAX_FACTS = InvestigatePrompt.MAX_FACTS;

  /** At most this many characters of a summary. */
  static final int MAX_SUMMARY = 600;

  private static final String NEW_ENDS =
      "A connection ends at a unit or element by its ID, or at a system no ID names as"
          + " external-system:<name> or database:<name>, the name without spaces, such as"
          + " external-system:legacy-parts-soap.";

  private static final String ALREADY_KNOWN =
      "Each [[known]] below is a lead already checked or a risk already found; do not report it"
          + " again, nor a link the facts already state.";

  private InvestigateExploreMessage() {}

  /**
   * A unit's first message.
   *
   * @param unit the unit's ID
   * @param repos its repositories
   * @param units every unit's ID
   * @param summaries the summaries of its modules and repositories
   * @param facts its facts, links and open links
   * @param known the leads checked on it and the risks found in it, one line each
   */
  static String unit(
      String unit,
      Set<String> repos,
      Set<String> units,
      List<Fact> summaries,
      List<Fact> facts,
      List<String> known) {
    StringBuilder out = new StringBuilder("[task]\n");
    out.append("what = ")
        .append(
            Toml.string(
                "Find the risks and connections static analysis missed in the deployable unit "
                    + unit
                    + ", reading its code."))
        .append('\n');
    out.append("repositories = ").append(list(repos)).append('\n');
    out.append("units = ").append(list(units)).append('\n');
    out.append("ends = ").append(Toml.string(NEW_ENDS)).append('\n');
    if (!known.isEmpty()) {
      out.append("already_known = ").append(Toml.string(ALREADY_KNOWN)).append('\n');
    }
    out.append("\n[unit]\nid = ").append(Toml.string(unit)).append('\n');
    List<String> about = summaries(summaries);
    if (!about.isEmpty()) {
      out.append("summaries = ").append(lines(about)).append('\n');
    }
    List<String> sentences = new ArrayList<>();
    for (Fact fact : facts) {
      sentences.add(InvestigateLeadMessage.sentence(fact));
    }
    List<String> distinct = new ArrayList<>(new LinkedHashSet<>(sentences));
    distinct.sort(String::compareTo);
    if (!distinct.isEmpty()) {
      out.append("facts = ")
          .append(lines(distinct.subList(0, Math.min(MAX_FACTS, distinct.size()))))
          .append('\n');
    }
    known(out, known);
    return out.toString();
  }

  /**
   * The system's first message.
   *
   * @param repos every repository
   * @param units every unit's ID with its repositories
   * @param summaries each unit's summaries
   * @param links the links static analysis found between units, once each
   * @param known the risks found in the units, one line each
   */
  static String system(
      List<String> repos,
      Map<String, Set<String>> units,
      Map<String, List<Fact>> summaries,
      List<String> links,
      List<String> known) {
    StringBuilder out = new StringBuilder("[task]\n");
    out.append("what = ")
        .append(
            Toml.string(
                "Find the risks and connections static analysis missed across the deployable"
                    + " units of the whole system, reading their code: what one unit does that"
                    + " another relies on."))
        .append('\n');
    out.append("repositories = ").append(list(repos)).append('\n');
    out.append("units = ").append(list(units.keySet())).append('\n');
    out.append("ends = ").append(Toml.string(NEW_ENDS)).append('\n');
    if (!known.isEmpty()) {
      out.append("already_known = ").append(Toml.string(ALREADY_KNOWN)).append('\n');
    }
    List<String> counted = counted(links);
    if (!counted.isEmpty()) {
      out.append("links = ").append(lines(counted)).append('\n');
    }
    units.forEach(
        (unit, unitRepos) -> {
          out.append("\n[[unit]]\nid = ").append(Toml.string(unit)).append('\n');
          out.append("repositories = ").append(list(unitRepos)).append('\n');
          List<String> about = summaries(summaries.getOrDefault(unit, List.of()));
          if (!about.isEmpty()) {
            out.append("summary = ").append(Toml.string(about.getFirst())).append('\n');
          }
        });
    known(out, known);
    return out.toString();
  }

  /** Links counted once each, in their order: {@code a -> b [CALLS_HTTP] x8}. */
  static List<String> counted(List<String> links) {
    Map<String, Integer> counts = new java.util.LinkedHashMap<>();
    links.forEach(link -> counts.merge(link, 1, Integer::sum));
    List<String> out = new ArrayList<>();
    counts.forEach((link, n) -> out.add(n > 1 ? link + " x" + n : link));
    return out;
  }

  private static void known(StringBuilder out, List<String> known) {
    // Two leads of one kind can read alike once their IDs are left out: each is shown once.
    Set<List<String>> shown = new LinkedHashSet<>();
    for (String line : known) {
      String[] parts = known(line);
      if (!shown.add(List.of(parts))) {
        continue;
      }
      out.append("\n[[known]]\n");
      out.append("verdict = ").append(Toml.string(parts[0])).append('\n');
      out.append("what = ").append(Toml.string(parts[1])).append('\n');
    }
  }

  /**
   * A known line as its verdict and what it says: {@code risk: <title>} is a risk found; {@code
   * <lead> <verdict>: <text>} a lead checked, its ID left out.
   */
  static String[] known(String line) {
    if (line.startsWith("risk: ")) {
      return new String[] {"found", line.substring("risk: ".length()).strip()};
    }
    int colon = line.indexOf(": ");
    String head = colon < 0 ? line : line.substring(0, colon);
    String what = colon < 0 ? "" : line.substring(colon + 2).strip();
    int space = head.lastIndexOf(' ');
    String verdict = space < 0 ? head : head.substring(space + 1);
    return new String[] {verdict, what};
  }

  private static List<String> summaries(List<Fact> summaries) {
    List<String> out = new ArrayList<>();
    for (Fact fact : summaries) {
      if (fact.object() == null || fact.object().value() == null) {
        continue;
      }
      String text = io.lantern.harness.render.FactLines.oneLine(fact.object().value()).strip();
      if (text.length() > MAX_SUMMARY) {
        int cut = text.lastIndexOf(' ', MAX_SUMMARY);
        text = text.substring(0, cut > 0 ? cut : MAX_SUMMARY) + " ...";
      }
      if (!text.isEmpty() && !out.contains(text)) {
        out.add(text);
      }
    }
    return out;
  }

  private static String list(java.util.Collection<String> values) {
    return "[" + String.join(", ", values.stream().map(Toml::string).toList()) + "]";
  }

  private static String lines(List<String> values) {
    StringBuilder out = new StringBuilder("[\n");
    for (String value : values) {
      out.append("  ").append(Toml.string(value)).append(",\n");
    }
    return out.append(']').toString();
  }
}

package io.lantern.summarize;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The fact sheet of a module or repository: the facts about the parts it contains, at any depth,
 * that say what it offers and works with, so that its prompt keeps the specifics its parts'
 * summaries may leave out. The sheet lists the parts' entry points, links, data stores and
 * technology, at most {@value #MAX_PER_GROUP} of each, leaving out facts the unit's own facts
 * already list. Types and packages have no fact sheet: their prompts show the facts themselves.
 *
 * <p>The sheet depends only on the facts as the prompt writes them and where they were observed,
 * never on fact IDs first: each group lists its facts by predicate, in the order the group names
 * them, then in the order a unit's facts are listed ({@link SummaryNode#facts}), and keeps the
 * first {@value #MAX_PER_GROUP}.
 */
final class FactSheet {

  /** The most facts a group of the sheet lists. */
  static final int MAX_PER_GROUP = 10;

  /** The groups of a fact sheet, in the order the prompt lists them. */
  enum Group {
    ENTRY_POINTS(
        "Entry points",
        List.of(
            "main-class",
            "endpoint",
            "message-listener",
            "websocket-endpoint",
            "stomp-mapping",
            "scheduled",
            "background-job")),
    LINKS(
        "Links",
        List.of(
            "CALLS_HTTP",
            "SENDS_MAIL",
            "SENDS_MESSAGES_TO",
            "PUBLISHES",
            "CONSUMES",
            "USES_BROKER",
            "USES_INFRASTRUCTURE",
            "http-call",
            "message-send",
            "stomp-send",
            "mail-send",
            "http-client")),
    DATA_STORES(
        "Data stores",
        List.of(
            "USES_DATABASE",
            "ACCESSES_DATA_OF",
            "datasource",
            "MAPS_TO",
            "READS_TABLE",
            "WRITES_TABLE")),
    TECHNOLOGY("Technology", List.of("technology", "deployable-unit", "java-release"));

    private final String title;
    private final List<String> predicates;

    Group(String title, List<String> predicates) {
      this.title = title;
      this.predicates = predicates;
    }

    /** What the prompt calls the group. */
    String title() {
      return title;
    }

    /** The predicates of the group's facts, the ones listed first first. */
    List<String> predicates() {
      return predicates;
    }
  }

  /**
   * One fact of the sheet.
   *
   * @param fact the fact
   * @param heading the heading of its group as the prompt writes it, such as {@code Entry points}
   *     or, when the group lists only some of its facts, {@code Entry points (10 of 34)}
   */
  record Entry(Fact fact, String heading) {}

  private FactSheet() {}

  /** The unit's fact sheet, group by group; empty unless it is a module or repository. */
  static List<Entry> of(SummaryNode node) {
    if (node.level() != SummaryLevel.MODULE && node.level() != SummaryLevel.REPO) {
      return List.of();
    }
    Map<String, Group> groups = new LinkedHashMap<>();
    for (Group group : Group.values()) {
      group.predicates().forEach(predicate -> groups.put(predicate, group));
    }
    Set<Fact> own = new HashSet<>(node.facts());
    List<Fact> found = new ArrayList<>();
    collect(node.children(), groups.keySet(), found, new HashSet<>());
    found.removeIf(own::contains);
    List<Fact> ordered = new ArrayList<>(SummaryNode.inPromptOrder(found));
    List<Entry> sheet = new ArrayList<>();
    for (Group group : Group.values()) {
      // A stable sort keeps the prompt order among facts of the same predicate.
      List<Fact> facts =
          ordered.stream()
              .filter(f -> groups.get(f.predicate()) == group)
              .sorted(Comparator.comparingInt(f -> group.predicates().indexOf(f.predicate())))
              .toList();
      String heading =
          facts.size() > MAX_PER_GROUP
              ? group.title() + " (" + MAX_PER_GROUP + " of " + facts.size() + ")"
              : group.title();
      facts.stream().limit(MAX_PER_GROUP).forEach(f -> sheet.add(new Entry(f, heading)));
    }
    return List.copyOf(sheet);
  }

  /** The facts of the units and everything they contain whose predicate is one of the sheet's. */
  private static void collect(
      List<SummaryNode> units, Set<String> predicates, List<Fact> found, Set<EntityId> seen) {
    for (SummaryNode unit : units) {
      if (!seen.add(unit.entity())) {
        continue;
      }
      unit.facts().stream().filter(f -> predicates.contains(f.predicate())).forEach(found::add);
      collect(unit.children(), predicates, found, seen);
    }
  }

  /** The facts of the sheet, in the order the prompt lists them. */
  static List<Fact> facts(List<Entry> sheet) {
    return sheet.stream().map(Entry::fact).toList();
  }
}

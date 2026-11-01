package io.lantern.link;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The order the linker runs its rules in (ADR 0095): each rule after every other rule that writes a
 * predicate it reads, a rule that reads {@link LinkRule#LINKED} after every rule that does not, and
 * otherwise in the order given. A rule does not read its own facts, so a predicate a rule both
 * reads and writes is read from the other rules that write it.
 */
final class RuleOrder {

  private RuleOrder() {}

  /**
   * The rules in the order they run.
   *
   * @throws IllegalArgumentException if two rules have one name, or the rules read each other's
   *     facts in a cycle, naming the rules in it
   */
  static List<LinkRule> of(List<LinkRule> rules) {
    Set<String> names = new HashSet<>();
    for (LinkRule rule : rules) {
      if (!names.add(rule.info().name())) {
        throw new IllegalArgumentException("Two link rules are named " + rule.info().name());
      }
    }
    Map<LinkRule, Set<LinkRule>> before = new LinkedHashMap<>();
    for (LinkRule reader : rules) {
      Set<LinkRule> writers = new LinkedHashSet<>();
      for (LinkRule writer : rules) {
        if (writer != reader && reads(reader, writer)) {
          writers.add(writer);
        }
      }
      before.put(reader, writers);
    }
    List<LinkRule> order = new ArrayList<>();
    Set<LinkRule> done = new HashSet<>();
    while (order.size() < rules.size()) {
      LinkRule next =
          rules.stream()
              .filter(r -> !done.contains(r) && done.containsAll(before.get(r)))
              .findFirst()
              .orElse(null);
      if (next == null) {
        throw new IllegalArgumentException(
            "Link rules read each other's facts in a cycle: "
                + rules.stream()
                    .filter(r -> !done.contains(r))
                    .map(r -> r.info().name())
                    .collect(Collectors.joining(", ")));
      }
      order.add(next);
      done.add(next);
    }
    return List.copyOf(order);
  }

  /** Whether one rule reads a predicate another writes. */
  static boolean reads(LinkRule reader, LinkRule writer) {
    if (reader.reads().contains(LinkRule.LINKED)) {
      return !writer.reads().contains(LinkRule.LINKED);
    }
    for (String predicate : writer.writes()) {
      if (reader.reads().contains(predicate)) {
        return true;
      }
    }
    return false;
  }
}

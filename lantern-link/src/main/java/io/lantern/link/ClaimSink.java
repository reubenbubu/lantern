package io.lantern.link;

import io.lantern.core.fact.Fact;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Where a link rule puts the facts it derives. A fact must have the rule's origin and one of the
 * predicates the rule writes, so a rule's declared writes are all it can give (ADR 0095).
 */
public final class ClaimSink {

  private final RuleInfo rule;
  private final Set<String> writes;
  private final List<Fact> facts = new ArrayList<>();

  ClaimSink(RuleInfo rule, Set<String> writes) {
    this.rule = Objects.requireNonNull(rule, "rule");
    this.writes = Set.copyOf(writes);
  }

  /**
   * Adds a derived fact.
   *
   * @throws IllegalArgumentException if it has another origin or a predicate the rule does not
   *     write
   */
  public void add(Fact fact) {
    Objects.requireNonNull(fact, "fact");
    if (!fact.origin().name().equals(rule.name()) || fact.origin().kind() != rule.kind()) {
      throw new IllegalArgumentException(
          "Rule " + rule.name() + " gave fact " + fact.id() + " of origin " + fact.origin());
    }
    if (!writes.contains(fact.predicate())) {
      throw new IllegalArgumentException(
          "Rule "
              + rule.name()
              + " gave a "
              + fact.predicate()
              + " fact, which is not one of the predicates it writes");
    }
    facts.add(fact);
  }

  /** The facts added, in the order added. */
  List<Fact> facts() {
    return List.copyOf(facts);
  }
}

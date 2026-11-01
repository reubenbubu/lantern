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
  private final List<String> links = new ArrayList<>();

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
    add(fact, null);
  }

  /**
   * Adds a claim a rule that derives per view found in the view it derives in ({@link
   * LinkRule#perView}): the linker merges the views into one claim per view, a claim found in the
   * default view naming in {@code absentIn} the views it is not found in, one found only in others
   * naming its view in {@code view} (ADR 0095, amendment for #590).
   *
   * @param link what makes it the same claim in every view: the same {@code link} in two views is
   *     one claim; null for a fact that holds whatever the view, which is given as found
   * @throws IllegalArgumentException if it has another origin or a predicate the rule does not
   *     write, or names its view itself
   */
  public void add(Fact fact, String link) {
    Objects.requireNonNull(fact, "fact");
    if (link != null && (Claims.view(fact) != null || Claims.namesAbsence(fact))) {
      throw new IllegalArgumentException(
          "Rule " + rule.name() + " named the view of fact " + fact.id() + " itself");
    }
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
    links.add(link);
  }

  /** The facts added, in the order added. */
  List<Fact> facts() {
    return List.copyOf(facts);
  }

  /** What makes each fact added the same claim in every view, in the order added; null entries. */
  List<String> links() {
    return java.util.Collections.unmodifiableList(links);
  }
}

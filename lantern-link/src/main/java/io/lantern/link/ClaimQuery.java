package io.lantern.link;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The facts a link rule may read, those of the predicates it declares, queried as the generated
 * attribute records of their types (ADR 0090), so a rule reads {@code call.path()} rather than
 * attribute JSON by string key (ADR 0095). Asking for a predicate the rule does not read fails, so
 * a rule's declared reads are all it can see.
 */
public final class ClaimQuery {

  /**
   * A fact with its attributes read as its type's record.
   *
   * @param fact the fact, for its subject, object, evidence and ID
   * @param attributes its declared attributes
   */
  public record Claim<R extends Record>(Fact fact, R attributes) {

    public EntityId subject() {
      return fact.subject();
    }
  }

  private final RuleInfo rule;
  private final Set<String> reads;
  private final Set<String> derived;
  private final List<Fact> facts;
  private final long snapshot;
  private final Map<String, List<Fact>> byPredicate = new HashMap<>();

  /**
   * @param derived the predicates the linker's rules write, which {@link LinkRule#EXTRACTED} does
   *     not cover
   */
  ClaimQuery(
      RuleInfo rule, Set<String> reads, Set<String> derived, List<Fact> facts, long snapshot) {
    this.rule = Objects.requireNonNull(rule, "rule");
    this.reads = Set.copyOf(reads);
    this.derived = Set.copyOf(derived);
    this.facts = List.copyOf(facts);
    this.snapshot = snapshot;
    for (Fact f : this.facts) {
      byPredicate.computeIfAbsent(f.predicate(), p -> new ArrayList<>()).add(f);
    }
  }

  /** The snapshot the rule derives for; its facts hold from it. */
  public long snapshot() {
    return snapshot;
  }

  /** Every fact the rule may read, in the order the linker holds them. */
  public List<Fact> facts() {
    return facts;
  }

  /** The facts of a predicate the rule reads, in the order the linker holds them. */
  public List<Fact> all(String predicate) {
    check(predicate);
    return List.copyOf(byPredicate.getOrDefault(predicate, List.of()));
  }

  /** The facts of a record's type, each with its attributes read as the record. */
  public <R extends Record> List<Claim<R>> all(Class<R> record) {
    List<Claim<R>> claims = new ArrayList<>();
    for (Fact f : all(type(record))) {
      claims.add(new Claim<>(f, Claims.read(record, f.attributes())));
    }
    return List.copyOf(claims);
  }

  /** The facts of a record's type about one subject. */
  public <R extends Record> List<Claim<R>> from(EntityId subject, Class<R> record) {
    Objects.requireNonNull(subject, "subject");
    return all(record).stream().filter(c -> c.subject().equals(subject)).toList();
  }

  private void check(String predicate) {
    Objects.requireNonNull(predicate, "predicate");
    if (!reads.contains(predicate)
        && !reads.contains(LinkRule.LINKED)
        && !(reads.contains(LinkRule.EXTRACTED) && !derived.contains(predicate))) {
      throw new IllegalStateException(
          "Rule " + rule.name() + " reads " + predicate + ", which it does not declare");
    }
  }

  private static String type(Class<? extends Record> record) {
    return Claims.typeOf(record);
  }
}

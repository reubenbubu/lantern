package io.lantern.link;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.node.ObjectNode;

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

  private record RecordType(String type, MethodHandle from) {}

  private static final Map<Class<?>, RecordType> TYPES = new ConcurrentHashMap<>();

  private final RuleInfo rule;
  private final Set<String> reads;
  private final List<Fact> facts;
  private final long snapshot;
  private final Map<String, List<Fact>> byPredicate = new HashMap<>();

  ClaimQuery(RuleInfo rule, Set<String> reads, List<Fact> facts, long snapshot) {
    this.rule = Objects.requireNonNull(rule, "rule");
    this.reads = Set.copyOf(reads);
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
    RecordType type = type(record);
    List<Claim<R>> claims = new ArrayList<>();
    for (Fact f : all(type.type())) {
      claims.add(new Claim<>(f, read(record, type, f)));
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
    if (!reads.contains(predicate) && !reads.contains(LinkRule.EXTRACTED)) {
      throw new IllegalStateException(
          "Rule " + rule.name() + " reads " + predicate + ", which it does not declare");
    }
  }

  private static RecordType type(Class<?> record) {
    return TYPES.computeIfAbsent(
        record,
        r -> {
          try {
            String type = (String) r.getField("TYPE").get(null);
            MethodHandle from =
                MethodHandles.publicLookup()
                    .findStatic(r, "from", MethodType.methodType(r, ObjectNode.class));
            return new RecordType(type, from);
          } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException(r.getName() + " is not an attribute record", e);
          }
        });
  }

  private static <R extends Record> R read(Class<R> record, RecordType type, Fact fact) {
    try {
      return record.cast(type.from().invoke(fact.attributes()));
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable e) {
      throw new IllegalStateException("Cannot read " + fact.id() + " as " + record.getName(), e);
    }
  }
}

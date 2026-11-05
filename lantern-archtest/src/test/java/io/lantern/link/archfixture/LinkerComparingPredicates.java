package io.lantern.link.archfixture;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import java.util.List;

/**
 * Linker code that names a predicate by copying its name, as a constant and as literals, instead of
 * through {@code Types}.
 */
public class LinkerComparingPredicates {

  static final String CALLS_HTTP = "CALLS_HTTP";

  public boolean usesADatabase(Fact fact) {
    // the literal is what the source scan must find
    return fact.predicate().equals("USES_DATABASE");
  }

  public List<Fact> endpoints(Query query) {
    return query.withPredicate("endpoint");
  }

  /** The sends, as a list of predicates. */
  static final List<String> SENDS = List.of("http-call");

  /** A private copy of the snapshot's system, with its type written as a literal. */
  static final EntityId SYSTEM = new EntityId("system", "snapshot");

  public EntityId repo(String name) {
    return new EntityId("repo", name);
  }

  public boolean isAType(Fact fact) {
    return fact.subject().toString().startsWith("type:");
  }

  public String artifact(String group) {
    return "artifact:" + group;
  }

  /** A stand-in for a fact query. */
  public interface Query {
    List<Fact> withPredicate(String predicate);
  }
}

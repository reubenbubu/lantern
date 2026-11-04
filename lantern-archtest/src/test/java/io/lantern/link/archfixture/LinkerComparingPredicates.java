package io.lantern.link.archfixture;

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

  /** A stand-in for a fact query. */
  public interface Query {
    List<Fact> withPredicate(String predicate);
  }
}

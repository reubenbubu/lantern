package io.lantern.core.fact;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Checks a fact against rules beyond the envelope itself, before it is stored, and says which of
 * its attributes are part of its claim.
 */
@FunctionalInterface
public interface FactValidator {

  /**
   * Checks the fact.
   *
   * @throws IllegalArgumentException if the fact is not valid
   */
  void validate(Fact fact);

  /**
   * Checks every fact, against the same rules for all of them. Override it when looking the rules
   * up costs something, so a batch looks them up once.
   *
   * @throws IllegalArgumentException if a fact is not valid
   */
  default void validateAll(Collection<Fact> facts) {
    facts.forEach(this::validate);
  }

  /**
   * Which attributes of a fact are key (ADR 0090): a function, looked up once for a batch of facts,
   * giving a fact's key attribute names. By default every attribute is key, so a store without a
   * vocabulary never merges two things.
   */
  default Function<Fact, Set<String>> keyAttributes() {
    return fact -> new TreeSet<>(fact.attributes().propertyNames());
  }
}

package io.lantern.core.fact;

import java.util.Collection;

/** Checks a fact against rules beyond the envelope itself, before it is stored. */
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
}

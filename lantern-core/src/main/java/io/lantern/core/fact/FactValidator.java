package io.lantern.core.fact;

/** Checks a fact against rules beyond the envelope itself, before it is stored. */
@FunctionalInterface
public interface FactValidator {

  /**
   * Checks the fact.
   *
   * @throws IllegalArgumentException if the fact is not valid
   */
  void validate(Fact fact);
}

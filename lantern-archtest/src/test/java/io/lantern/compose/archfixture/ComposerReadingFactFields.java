package io.lantern.compose.archfixture;

import io.lantern.core.fact.Fact;

/**
 * Composer code that reads a fact's attributes and predicate itself instead of the model's queries.
 */
public class ComposerReadingFactFields {

  public String predicateOf(Fact fact) {
    return fact.predicate();
  }

  public String attributesOf(Fact fact) {
    return fact.attributes().toString();
  }
}

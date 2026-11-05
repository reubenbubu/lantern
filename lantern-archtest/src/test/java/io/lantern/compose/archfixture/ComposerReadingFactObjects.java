package io.lantern.compose.archfixture;

import io.lantern.core.fact.Fact;

/** Composer code that reads a fact's object itself instead of the model's queries. */
public class ComposerReadingFactObjects {

  public String valueOf(Fact fact) {
    return fact.object().value();
  }
}

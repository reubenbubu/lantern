package io.lantern.app.archfixture;

import io.lantern.core.fact.FactRepository;

/** Test fixture: a renderer that wrongly reads facts from the store. */
public class FactReadingRenderer {

  private final FactRepository facts;

  public FactReadingRenderer(FactRepository facts) {
    this.facts = facts;
  }

  /** A nested class of a renderer is the renderer's too. */
  public class Page {

    public String render() {
      return facts.findById("fact").isPresent() ? "found" : "";
    }
  }
}

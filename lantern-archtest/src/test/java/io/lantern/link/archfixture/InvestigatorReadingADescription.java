package io.lantern.link.archfixture;

import io.lantern.summarize.archfixture.FakeRole;

/** Test fixture: Investigate wrongly depending on a later stage, Describe's roles. */
public class InvestigatorReadingADescription {

  public Object investigate() {
    return new FakeRole();
  }
}

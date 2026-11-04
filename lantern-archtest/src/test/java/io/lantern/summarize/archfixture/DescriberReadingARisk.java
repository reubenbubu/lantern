package io.lantern.summarize.archfixture;

import io.lantern.link.archfixture.InvestigatorReadingADescription;

/** Test fixture: a Describe role reading what Investigate found, an earlier stage. */
public class DescriberReadingARisk {

  public Object describe() {
    return InvestigatorReadingADescription.class;
  }
}

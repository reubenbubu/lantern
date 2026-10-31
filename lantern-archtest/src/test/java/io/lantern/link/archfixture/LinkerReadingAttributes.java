package io.lantern.link.archfixture;

import io.lantern.core.fact.Fact;

/** Test fixture: linker code reading a fact's attribute by name, which the rules forbid. */
public class LinkerReadingAttributes {

  public String path(Fact fact) {
    return fact.attributes().path("path").asString();
  }

  /** A method reference is an access too. */
  public java.util.List<Object> all(java.util.List<Fact> facts) {
    return facts.stream().map(Fact::attributes).map(a -> (Object) a).toList();
  }
}

package io.lantern.core.document;

import java.util.List;

/**
 * The facts the preceding text is based on.
 *
 * @param facts the IDs of the facts, at least one; held sorted
 */
public record Citation(List<String> facts) implements Inline {

  public Citation {
    facts = Checks.factIds(facts, "citation");
    if (facts.isEmpty()) {
      throw new IllegalArgumentException("A citation must cite at least one fact");
    }
  }
}

package io.lantern.extract;

import io.lantern.core.fact.Fact;
import java.util.Collection;

/** Takes the facts an extractor emits for one unit (ADR 0092). */
@FunctionalInterface
public interface ClaimSink {

  /**
   * Takes one fact.
   *
   * @throws IllegalArgumentException if the fact is of another origin than the extractor's
   */
  void emit(Fact fact);

  /** Takes every fact, in order. */
  default void emitAll(Collection<Fact> facts) {
    facts.forEach(this::emit);
  }
}

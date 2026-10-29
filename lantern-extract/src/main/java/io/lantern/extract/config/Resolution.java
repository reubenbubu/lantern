package io.lantern.extract.config;

import java.util.Collections;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * A text with its {@code ${...}} placeholders resolved against a module's config.
 *
 * @param value the text with every placeholder that could be resolved replaced; one that could not
 *     is kept as {@code ${KEY}}
 * @param unresolved the keys of the placeholders kept, ordered
 */
public record Resolution(String value, SortedSet<String> unresolved) {

  public Resolution {
    Objects.requireNonNull(value, "value");
    unresolved = Collections.unmodifiableSortedSet(new TreeSet<>(unresolved));
  }

  /** Whether every placeholder was resolved. */
  public boolean resolved() {
    return unresolved.isEmpty();
  }
}

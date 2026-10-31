package io.lantern.link;

import java.util.LinkedHashSet;
import java.util.Set;

/** Helpers for declaring link rules' reads and writes. */
final class Rules {

  private Rules() {}

  /** The predicates of all the sets. */
  @SafeVarargs
  static Set<String> union(Set<String>... sets) {
    Set<String> all = new LinkedHashSet<>();
    for (Set<String> set : sets) {
      all.addAll(set);
    }
    return Set.copyOf(all);
  }
}

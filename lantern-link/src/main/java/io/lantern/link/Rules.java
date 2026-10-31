package io.lantern.link;

import io.lantern.core.vocabulary.VocabularyType;
import java.util.LinkedHashSet;
import java.util.List;
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

  /** The names of the relationship and attribute types among the types. */
  static Set<String> predicates(List<VocabularyType> types) {
    Set<String> predicates = new LinkedHashSet<>();
    for (VocabularyType type : types) {
      if (type.kind().isPredicate()) {
        predicates.add(type.name());
      }
    }
    return Set.copyOf(predicates);
  }
}

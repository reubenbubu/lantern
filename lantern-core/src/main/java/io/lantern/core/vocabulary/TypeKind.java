package io.lantern.core.vocabulary;

import java.util.Locale;

/** What a vocabulary type describes. */
public enum TypeKind {
  /** A kind of thing, used as the type part of entity IDs, for example {@code service}. */
  ENTITY,
  /** A predicate between two entities, for example {@code CALLS}. */
  RELATIONSHIP,
  /** A predicate whose object is a value, for example {@code owner}. */
  ATTRIBUTE;

  /** Whether facts can use types of this kind as their predicate. */
  public boolean isPredicate() {
    return this != ENTITY;
  }

  public String jsonName() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static TypeKind fromJsonName(String name) {
    for (TypeKind kind : values()) {
      if (kind.jsonName().equals(name)) {
        return kind;
      }
    }
    throw new IllegalArgumentException("Unknown type kind: '" + name + "'");
  }
}

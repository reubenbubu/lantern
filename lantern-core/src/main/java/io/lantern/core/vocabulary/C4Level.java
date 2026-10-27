package io.lantern.core.vocabulary;

import java.util.Locale;

/** The C4 model level at which an entity type appears. */
public enum C4Level {
  CONTEXT,
  CONTAINER,
  COMPONENT,
  CODE;

  public String jsonName() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static C4Level fromJsonName(String name) {
    for (C4Level level : values()) {
      if (level.jsonName().equals(name)) {
        return level;
      }
    }
    throw new IllegalArgumentException("Unknown C4 level: '" + name + "'");
  }
}

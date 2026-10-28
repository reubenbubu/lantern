package io.lantern.core.document;

import java.util.Objects;

/** Granularity level of a section, from the executive view to the deep dive. */
public enum Level {
  /** Executive: the system on a page. */
  L0,
  /** Architect, new engineer: groups, services and how they communicate. */
  L1,
  /** Engineer: inside a service. */
  L2,
  /** Deep dive: packages, key classes, flows. */
  L3;

  public String jsonName() {
    return name();
  }

  public static Level fromJsonName(String name) {
    Objects.requireNonNull(name, "level");
    for (Level level : values()) {
      if (level.jsonName().equals(name)) {
        return level;
      }
    }
    throw new IllegalArgumentException("Unknown level: '" + name + "'");
  }
}

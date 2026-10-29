package io.lantern.compose;

import io.lantern.core.document.Level;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * The sections a view definition can ask for. Each produces one top-level section of the document,
 * with a fixed ID, title and granularity levels.
 */
public enum SectionKind {
  /**
   * The repository's summary and what it builds, or the system's repositories. Shown at every
   * level.
   */
  OVERVIEW("Overview", EnumSet.noneOf(Level.class)),
  /**
   * Where to start reading: the entry points of the main flows, where each unit starts, the READMEs
   * and the decision records.
   */
  START_HERE("Start here", EnumSet.of(Level.L1, Level.L2)),
  /** C4 system context: the system, its users and the external systems it calls. */
  CONTEXT("System context", EnumSet.of(Level.L0, Level.L1)),
  /**
   * C4 containers: the deployable units in scope, the stores and queues they use and their
   * neighbours; then links not resolved and decision records.
   */
  CONTAINERS("Containers", EnumSet.of(Level.L1, Level.L2)),
  /** C4 components: inside each deployable unit in scope, its components by role. */
  COMPONENTS("Components", EnumSet.of(Level.L2, Level.L3)),
  /**
   * The main flows as sequence diagrams: traced from entry points and stitched across containers.
   */
  SEQUENCES("Main flows", EnumSet.of(Level.L1, Level.L2)),
  /**
   * Tables of the containers, components, entry points, topics and queues, tables and configuration
   * in scope, each row linked to its facts.
   */
  CATALOG("Catalog", EnumSet.of(Level.L2, Level.L3)),
  /** How to make the common changes, each by an example the code already has. */
  CHANGE_GUIDANCE("Change guidance", EnumSet.of(Level.L2, Level.L3)),
  /** The Maven dependency tree of each module. */
  DEPENDENCIES("Dependencies", EnumSet.of(Level.L2, Level.L3)),
  /** The modules and packages, as a tree. */
  STRUCTURE("Modules and packages", EnumSet.of(Level.L3)),
  /** Headline numbers: what the repository builds, how big it is and how connected. */
  HEADLINES("At a glance", EnumSet.of(Level.L0, Level.L1)),
  /** Risks found by fixed checks of the knowledge, most serious first. */
  RISKS("Risks", EnumSet.of(Level.L0, Level.L1)),
  /**
   * Who depends on the repository's deployable units and what they depend on, from the linker's
   * links.
   */
  BLAST_RADIUS("Blast radius", EnumSet.of(Level.L0, Level.L1)),
  /** Runtime and framework versions against the end of their free support. */
  TECHNOLOGY_CURRENCY("Technology currency", EnumSet.of(Level.L0, Level.L1)),
  /**
   * The test, architecture test and coverage libraries the repository's modules declare, and what
   * is not known: the tests themselves, coverage and quality goals.
   */
  QUALITY_EVIDENCE("Quality evidence", EnumSet.of(Level.L0, Level.L1));

  private final String title;
  private final EnumSet<Level> levels;

  SectionKind(String title, EnumSet<Level> levels) {
    this.title = title;
    this.levels = levels;
  }

  /** The section's title. */
  public String title() {
    return title;
  }

  /** The levels the section is shown at; empty means every level. */
  public Set<Level> levels() {
    return levels.clone();
  }

  /** The name used in JSON, which is also the section's ID in the document. */
  public String jsonName() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static SectionKind fromJsonName(String name) {
    Objects.requireNonNull(name, "section");
    for (SectionKind kind : values()) {
      if (kind.jsonName().equals(name)) {
        return kind;
      }
    }
    throw new IllegalArgumentException("Unknown section: '" + name + "'");
  }
}

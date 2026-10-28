package io.lantern.core.document;

import io.lantern.core.fact.EntityId;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A section of a document: a title, blocks and subsections.
 *
 * @param id the section ID, unique among the document's sections and blocks
 * @param title the section title
 * @param levels the granularity levels at which the section is shown; empty means all levels
 * @param entity the entity the section is about, or {@code null}
 * @param facts the IDs of the facts the section as a whole came from
 * @param blocks the blocks, in reading order
 * @param sections the subsections, in reading order
 */
public record Section(
    String id,
    String title,
    Set<Level> levels,
    EntityId entity,
    List<String> facts,
    List<Block> blocks,
    List<Section> sections) {

  public Section {
    Checks.elementId(id, "section ID");
    Checks.text(title, "section title");
    Objects.requireNonNull(levels, "levels");
    levels =
        Collections.unmodifiableSet(
            levels.isEmpty() ? EnumSet.noneOf(Level.class) : EnumSet.copyOf(levels));
    facts = Checks.factIds(facts, "section facts");
    blocks = Checks.list(blocks, "blocks");
    sections = Checks.list(sections, "sections");
  }
}

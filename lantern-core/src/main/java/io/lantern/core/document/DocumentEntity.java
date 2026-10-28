package io.lantern.core.document;

import io.lantern.core.fact.EntityId;
import java.util.List;
import java.util.Objects;

/**
 * An entity a document refers to, with what a reader sees when selecting it.
 *
 * @param id the entity ID
 * @param name the display name
 * @param parent the entity this one belongs to in the navigation tree, or {@code null}
 * @param description rich text describing the entity; empty when there is none
 * @param facts the IDs of the facts about the entity that the document includes
 */
public record DocumentEntity(
    EntityId id, String name, EntityId parent, List<Inline> description, List<String> facts) {

  public DocumentEntity {
    Objects.requireNonNull(id, "id");
    Checks.text(name, "entity name");
    if (id.equals(parent)) {
      throw new IllegalArgumentException("Entity " + id + " cannot be its own parent");
    }
    description = Checks.list(description, "entity description");
    facts = Checks.factIds(facts, "entity facts");
  }
}

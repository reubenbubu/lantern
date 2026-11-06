package io.lantern.core.document;

import io.lantern.core.fact.EntityId;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * An entity a document refers to, with what a reader sees when selecting it.
 *
 * <p>Its kind and tag are resolved by the composer from the vocabulary (ADR 0114), so renderers
 * draw it without knowing Lantern's types.
 *
 * @param id the entity ID
 * @param name the display name
 * @param parent the entity this one belongs to in the navigation tree, or {@code null}
 * @param description rich text describing the entity; empty when there is none
 * @param facts the IDs of the facts about the entity that the document includes
 * @param kind what its type makes it, whatever diagram shows it, or {@code null} when it is shown
 *     as what the diagram's level makes it (a system, container or component)
 * @param tag the name of its type a reader sees beside its name, such as {@code Topic}, or {@code
 *     null} for none
 */
public record DocumentEntity(
    EntityId id,
    String name,
    EntityId parent,
    List<Inline> description,
    List<String> facts,
    Kind kind,
    String tag,
    EntityId group) {

  public DocumentEntity {
    Objects.requireNonNull(id, "id");
    Checks.text(name, "entity name");
    if (id.equals(parent)) {
      throw new IllegalArgumentException("Entity " + id + " cannot be its own parent");
    }
    description = Checks.list(description, "entity description");
    facts = Checks.factIds(facts, "entity facts");
    Checks.optionalText(tag, "entity tag");
    if (id.equals(group)) {
      throw new IllegalArgumentException("Entity " + id + " cannot be its own group");
    }
  }

  /** An entity in no domain or feature area. */
  public DocumentEntity(
      EntityId id,
      String name,
      EntityId parent,
      List<Inline> description,
      List<String> facts,
      Kind kind,
      String tag) {
    this(id, name, parent, description, facts, kind, tag, null);
  }

  /** An entity whose kind follows from the level it is shown at, with no tag. */
  public DocumentEntity(
      EntityId id, String name, EntityId parent, List<Inline> description, List<String> facts) {
    this(id, name, parent, description, facts, null, null, null);
  }

  /** What an entity's type makes it, whatever diagram shows it. */
  public enum Kind {
    /** A data store. */
    DATABASE,
    /** A topic, queue or exchange. */
    QUEUE,
    /** A software system outside the one documented. */
    EXTERNAL,
    /** A person, or a kind of person. */
    PERSON,
    /** A decision record. */
    DECISION;

    public String jsonName() {
      return name().toLowerCase(Locale.ROOT);
    }

    public static Kind fromJsonName(String name) {
      for (Kind kind : values()) {
        if (kind.jsonName().equals(name)) {
          return kind;
        }
      }
      throw new IllegalArgumentException("Unknown entity kind: '" + name + "'");
    }
  }
}

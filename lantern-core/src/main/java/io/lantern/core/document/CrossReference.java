package io.lantern.core.document;

import io.lantern.core.fact.EntityId;
import java.util.Locale;
import java.util.Objects;

/**
 * A cross-reference to a section, block or entity of the same document. The document checks that
 * the target exists.
 *
 * @param text the text shown for the reference, not empty
 * @param target what the reference points to
 * @param citation whether the target is a source of the text before the reference, as a summary's
 *     {@code [summary:...]} marker says, so templates show it as a citation marker rather than as
 *     words in the text
 */
public record CrossReference(String text, Target target, boolean citation) implements Inline {

  public CrossReference {
    Checks.nonEmpty(text, "text");
    Objects.requireNonNull(target, "target");
  }

  /** A cross-reference that is part of the text. */
  public CrossReference(String text, Target target) {
    this(text, target, false);
  }

  /**
   * What a cross-reference points to.
   *
   * @param kind whether the target is a section, a block or an entity
   * @param id the section or block ID, or the entity ID
   */
  public record Target(Kind kind, String id) {

    public Target {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(id, "id");
      if (kind == Kind.ENTITY) {
        EntityId.parse(id);
      } else {
        Checks.elementId(id, kind.jsonName() + " ID");
      }
    }

    public static Target section(String id) {
      return new Target(Kind.SECTION, id);
    }

    public static Target block(String id) {
      return new Target(Kind.BLOCK, id);
    }

    public static Target entity(EntityId id) {
      return new Target(Kind.ENTITY, id.toString());
    }

    /** What kind of element a cross-reference points to. */
    public enum Kind {
      SECTION,
      BLOCK,
      ENTITY;

      public String jsonName() {
        return name().toLowerCase(Locale.ROOT);
      }

      public static Kind fromJsonName(String name) {
        Objects.requireNonNull(name, "kind");
        for (Kind kind : values()) {
          if (kind.jsonName().equals(name)) {
            return kind;
          }
        }
        throw new IllegalArgumentException("Unknown cross-reference target kind: '" + name + "'");
      }
    }
  }
}

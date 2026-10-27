package io.lantern.core.fact;

import java.util.Locale;
import java.util.Objects;

/**
 * Where a fact came from.
 *
 * @param kind the kind of source
 * @param name the extractor, model, user or rule that produced the fact
 * @param version the extractor, prompt or rule version, or {@code null} when there is none (for
 *     example a human)
 */
public record Origin(Kind kind, String name, String version) {

  /** The kind of source a fact came from. */
  public enum Kind {
    STATIC,
    LLM,
    HUMAN,
    README,
    RULE;

    /** The lowercase name used in JSON. */
    public String jsonName() {
      return name().toLowerCase(Locale.ROOT);
    }

    public static Kind fromJsonName(String name) {
      for (Kind kind : values()) {
        if (kind.jsonName().equals(name)) {
          return kind;
        }
      }
      throw new IllegalArgumentException("Unknown origin kind: '" + name + "'");
    }
  }

  public Origin {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(name, "name");
    if (name.isBlank()) {
      throw new IllegalArgumentException("Origin name must not be blank");
    }
    if (version != null && version.isBlank()) {
      throw new IllegalArgumentException("Origin version must be null or not blank");
    }
  }
}

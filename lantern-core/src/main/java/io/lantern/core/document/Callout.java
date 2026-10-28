package io.lantern.core.document;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Text set apart from the flow: a note, a warning or a risk.
 *
 * @param id the block ID
 * @param variant what kind of callout it is
 * @param title the title, or {@code null}
 * @param content the text, not empty
 * @param facts the IDs of the facts the callout came from
 */
public record Callout(
    String id, Variant variant, String title, List<Inline> content, List<String> facts)
    implements Block {

  public Callout {
    Checks.elementId(id, "block ID");
    Objects.requireNonNull(variant, "variant");
    Checks.optionalText(title, "callout title");
    content = Checks.content(content, "callout content");
    facts = Checks.factIds(facts, "callout facts");
  }

  /** What kind of callout it is. */
  public enum Variant {
    NOTE,
    WARNING,
    RISK;

    public String jsonName() {
      return name().toLowerCase(Locale.ROOT);
    }

    public static Variant fromJsonName(String name) {
      Objects.requireNonNull(name, "variant");
      for (Variant variant : values()) {
        if (variant.jsonName().equals(name)) {
          return variant;
        }
      }
      throw new IllegalArgumentException("Unknown callout variant: '" + name + "'");
    }
  }
}

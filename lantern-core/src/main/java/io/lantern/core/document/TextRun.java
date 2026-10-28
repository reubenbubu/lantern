package io.lantern.core.document;

import java.util.Locale;
import java.util.Objects;

/**
 * A run of text.
 *
 * @param text the text, not empty; may be only whitespace, as between other inline elements
 * @param style how the text is emphasized, or {@code null} for plain text
 */
public record TextRun(String text, Style style) implements Inline {

  public TextRun {
    Checks.nonEmpty(text, "text");
  }

  /** Plain text. */
  public static TextRun of(String text) {
    return new TextRun(text, null);
  }

  /** How a run of text is emphasized. */
  public enum Style {
    STRONG,
    EMPHASIS,
    CODE;

    public String jsonName() {
      return name().toLowerCase(Locale.ROOT);
    }

    public static Style fromJsonName(String name) {
      Objects.requireNonNull(name, "style");
      for (Style style : values()) {
        if (style.jsonName().equals(name)) {
          return style;
        }
      }
      throw new IllegalArgumentException("Unknown text style: '" + name + "'");
    }
  }
}

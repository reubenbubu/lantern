package io.lantern.core.document;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Text set apart from the flow: a note, a warning or a risk.
 *
 * @param id the block ID
 * @param variant what kind of callout it is
 * @param severity how serious a risk is, or {@code null}; only a risk may have one
 * @param title the title, or {@code null}
 * @param content the text, not empty
 * @param facts the IDs of the facts the callout came from
 * @param consequence what a risk means for the reader if nothing is done, or {@code null}; only a
 *     risk may have one
 * @param confidence how certain a risk is, from 0 to 1, or {@code null}; only a risk may have one
 */
public record Callout(
    String id,
    Variant variant,
    Severity severity,
    String title,
    List<Inline> content,
    List<String> facts,
    String consequence,
    BigDecimal confidence)
    implements Block {

  /** A callout without a consequence or confidence. */
  public Callout(
      String id,
      Variant variant,
      Severity severity,
      String title,
      List<Inline> content,
      List<String> facts) {
    this(id, variant, severity, title, content, facts, null, null);
  }

  public Callout {
    Checks.elementId(id, "block ID");
    Objects.requireNonNull(variant, "variant");
    if (severity != null && variant != Variant.RISK) {
      throw new IllegalArgumentException("Only a risk callout may have a severity");
    }
    Checks.optionalText(title, "callout title");
    content = Checks.content(content, "callout content");
    facts = Checks.factIds(facts, "callout facts");
    Checks.optionalText(consequence, "callout consequence");
    if (consequence != null && variant != Variant.RISK) {
      throw new IllegalArgumentException("Only a risk callout may have a consequence");
    }
    if (confidence != null) {
      if (variant != Variant.RISK) {
        throw new IllegalArgumentException("Only a risk callout may have a confidence");
      }
      if (confidence.signum() < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
        throw new IllegalArgumentException("Confidence must be between 0 and 1: " + confidence);
      }
    }
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

  /** How serious a risk is, most serious first. */
  public enum Severity {
    HIGH,
    MEDIUM,
    LOW;

    public String jsonName() {
      return name().toLowerCase(Locale.ROOT);
    }

    public static Severity fromJsonName(String name) {
      Objects.requireNonNull(name, "severity");
      for (Severity severity : values()) {
        if (severity.jsonName().equals(name)) {
          return severity;
        }
      }
      throw new IllegalArgumentException("Unknown risk severity: '" + name + "'");
    }
  }
}

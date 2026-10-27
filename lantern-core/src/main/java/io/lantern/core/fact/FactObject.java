package io.lantern.core.fact;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;

/**
 * The object of a fact: an entity ID or a typed value. The value is kept in its canonical text
 * form, so two equal objects always have the same text.
 */
public record FactObject(Kind kind, String value) {

  /** What the object holds. */
  public enum Kind {
    ENTITY,
    STRING,
    INTEGER,
    DECIMAL,
    BOOLEAN;

    /** The lowercase name used in JSON and in the database. */
    public String jsonName() {
      return name().toLowerCase(Locale.ROOT);
    }

    public static Kind fromJsonName(String name) {
      for (Kind kind : values()) {
        if (kind.jsonName().equals(name)) {
          return kind;
        }
      }
      throw new IllegalArgumentException("Unknown object kind: '" + name + "'");
    }
  }

  public FactObject {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(value, "value");
    String canonical =
        switch (kind) {
          case ENTITY -> EntityId.parse(value).toString();
          case STRING -> value;
          case INTEGER -> Long.toString(Long.parseLong(value));
          case DECIMAL -> canonicalDecimal(new BigDecimal(value));
          case BOOLEAN -> canonicalBoolean(value);
        };
    if (!canonical.equals(value)) {
      throw new IllegalArgumentException(
          "Value '" + value + "' is not in canonical " + kind.jsonName() + " form");
    }
  }

  public static FactObject entity(EntityId id) {
    return new FactObject(Kind.ENTITY, id.toString());
  }

  public static FactObject string(String value) {
    return new FactObject(Kind.STRING, value);
  }

  public static FactObject integer(long value) {
    return new FactObject(Kind.INTEGER, Long.toString(value));
  }

  public static FactObject decimal(BigDecimal value) {
    return new FactObject(Kind.DECIMAL, canonicalDecimal(value));
  }

  public static FactObject bool(boolean value) {
    return new FactObject(Kind.BOOLEAN, Boolean.toString(value));
  }

  /** The entity ID, if this object is an entity. */
  public EntityId entityId() {
    if (kind != Kind.ENTITY) {
      throw new IllegalStateException("Object is a " + kind.jsonName() + ", not an entity");
    }
    return EntityId.parse(value);
  }

  /** Decimals with a larger exponent than this are rejected, so their plain text stays small. */
  private static final int MAX_SCALE = 1000;

  private static String canonicalDecimal(BigDecimal value) {
    BigDecimal stripped = value.stripTrailingZeros();
    if (Math.abs(stripped.scale()) > MAX_SCALE) {
      throw new IllegalArgumentException("Decimal exponent out of range: " + value);
    }
    if (stripped.signum() == 0) {
      return "0";
    }
    return stripped.toPlainString();
  }

  private static String canonicalBoolean(String value) {
    if (!value.equals("true") && !value.equals("false")) {
      throw new IllegalArgumentException("Not a boolean: '" + value + "'");
    }
    return value;
  }
}

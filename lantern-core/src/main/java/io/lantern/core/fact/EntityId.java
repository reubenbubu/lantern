package io.lantern.core.fact;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Stable semantic ID of an entity, written {@code <type>:<key>}, for example {@code
 * service:orders}, {@code java:com.acme.Foo} or {@code topic:kafka:order-created}.
 *
 * <p>The type is a vocabulary type name: lowercase letters, digits and hyphens, starting with a
 * letter. The key is everything after the first colon. It must not be empty and must not contain
 * whitespace or control characters; it may contain further colons.
 */
public record EntityId(String type, String key) {

  private static final Pattern TYPE = Pattern.compile("[a-z][a-z0-9-]*");
  private static final Pattern KEY =
      Pattern.compile("[^\\s\\p{Cntrl}]+", Pattern.UNICODE_CHARACTER_CLASS);

  public EntityId {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(key, "key");
    if (!TYPE.matcher(type).matches()) {
      throw new IllegalArgumentException("Invalid entity type: '" + type + "'");
    }
    if (!KEY.matcher(key).matches()) {
      throw new IllegalArgumentException("Invalid entity key: '" + key + "'");
    }
  }

  /** Parses the {@code <type>:<key>} form. The inverse of {@link #toString()}. */
  public static EntityId parse(String value) {
    Objects.requireNonNull(value, "value");
    int colon = value.indexOf(':');
    if (colon < 0) {
      throw new IllegalArgumentException("Entity ID has no ':' separator: '" + value + "'");
    }
    return new EntityId(value.substring(0, colon), value.substring(colon + 1));
  }

  /** The canonical {@code <type>:<key>} form. */
  @Override
  public String toString() {
    return type + ":" + key;
  }
}

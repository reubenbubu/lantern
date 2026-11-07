package io.lantern.core.fact;

import io.lantern.core.types.Types;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Stable semantic ID of an entity, written {@code <type>:<key>}, for example {@code
 * service:orders}, {@code java:com.acme.Foo} or {@code topic:kafka:order-created}.
 *
 * <p>The type is a vocabulary type name: lowercase letters, digits and hyphens, starting with a
 * letter. The key is everything after the first colon. It must not be empty and must not contain
 * whitespace or control characters (the same set the JSON Schema excludes); it may contain further
 * colons.
 */
public record EntityId(String type, String key) {

  private static final Pattern TYPE = Pattern.compile("[a-z][a-z0-9-]*");

  /**
   * The software system a snapshot describes, the one entity every stage names the same way: the
   * subject of system-wide findings and the scope of a system document (#763).
   */
  public static final EntityId SYSTEM = new EntityId(Types.SYSTEM, "snapshot");

  public EntityId {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(key, "key");
    if (!TYPE.matcher(type).matches()) {
      throw new IllegalArgumentException("Invalid entity type: '" + type + "'");
    }
    if (!isValidKey(key)) {
      throw new IllegalArgumentException("Invalid entity key: '" + key + "'");
    }
  }

  /** Whether the text is a valid entity type name, the form entity types use. */
  public static boolean isValidType(String type) {
    return type != null && TYPE.matcher(type).matches();
  }

  /**
   * Whether the text is a valid key: not empty, with no whitespace or control character, the
   * characters the JSON Schema excludes. A loop rather than a regular expression, since every
   * parsed ID is checked and the expression was most of a run's CPU time (#948).
   */
  static boolean isValidKey(String key) {
    if (key.isEmpty()) {
      return false;
    }
    for (int i = 0; i < key.length(); i++) {
      char c = key.charAt(i);
      if (c <= '\u0020'
          || (c >= '\u007f' && c <= '\u00a0')
          || c == '\u1680'
          || (c >= '\u2000' && c <= '\u200a')
          || c == '\u2028'
          || c == '\u2029'
          || c == '\u202f'
          || c == '\u205f'
          || c == '\u3000'
          || c == '\ufeff') {
        return false;
      }
    }
    return true;
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

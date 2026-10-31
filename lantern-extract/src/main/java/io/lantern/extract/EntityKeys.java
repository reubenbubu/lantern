package io.lantern.extract;

import io.lantern.core.fact.EntityId;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Entity IDs whose keys are taken from code as written, such as a URL or a destination's name. */
public final class EntityKeys {

  /** The characters an entity key may not hold, as {@link EntityId} refuses them. */
  private static final Pattern NOT_IN_KEY =
      Pattern.compile(
          "[\\u0000-\\u0020\\u007f-\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]");

  private EntityKeys() {}

  /** An entity ID, with the characters an entity key may not hold percent-encoded. */
  public static EntityId of(String type, String key) {
    StringBuilder b = new StringBuilder();
    key.codePoints()
        .forEach(
            cp -> {
              String c = new String(Character.toChars(cp));
              if (NOT_IN_KEY.matcher(c).matches()) {
                for (byte x : c.getBytes(StandardCharsets.UTF_8)) {
                  b.append('%').append(String.format("%02X", x & 0xff));
                }
              } else {
                b.append(c);
              }
            });
    return new EntityId(type, b.toString());
  }
}

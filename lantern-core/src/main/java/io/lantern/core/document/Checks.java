package io.lantern.core.document;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Argument checks shared by the document model types. */
final class Checks {

  private static final Pattern ELEMENT_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]*");

  /** A character that is not whitespace in the sense of JSON Schema's {@code \S} (ECMAScript). */
  private static final Pattern NON_BLANK =
      Pattern.compile(
          "[^\\t\\n\\u000b\\f\\r \\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]");

  private static final Pattern FACT_ID = Pattern.compile("[0-9a-f]{64}");

  private Checks() {}

  /** Text that must be present and not blank. */
  static String text(String value, String what) {
    Objects.requireNonNull(value, what);
    if (!isNonBlank(value)) {
      throw new IllegalArgumentException(what + " must not be blank");
    }
    return value;
  }

  /** Whether the text has a character other than whitespace, by the schema's rule. */
  static boolean isNonBlank(String value) {
    return NON_BLANK.matcher(value).find();
  }

  /** Text that may be absent ({@code null}) but not blank. */
  static String optionalText(String value, String what) {
    return value == null ? null : text(value, what);
  }

  /** Text that must be present and not empty; whitespace is allowed, as between inline runs. */
  static String nonEmpty(String value, String what) {
    Objects.requireNonNull(value, what);
    if (value.isEmpty()) {
      throw new IllegalArgumentException(what + " must not be empty");
    }
    return value;
  }

  static String elementId(String value, String what) {
    Objects.requireNonNull(value, what);
    if (!isElementId(value)) {
      throw new IllegalArgumentException("Invalid " + what + ": '" + value + "'");
    }
    return value;
  }

  static boolean isElementId(String value) {
    return ELEMENT_ID.matcher(value).matches();
  }

  /** An immutable copy of a list, which must not hold nulls. */
  static <T> List<T> list(List<T> values, String what) {
    Objects.requireNonNull(values, what);
    return List.copyOf(values);
  }

  /** Rich text: an immutable copy of a list of inline elements, which must not be empty. */
  static List<Inline> content(List<Inline> content, String what) {
    List<Inline> copy = list(content, what);
    if (copy.isEmpty()) {
      throw new IllegalArgumentException(what + " must not be empty");
    }
    return copy;
  }

  /** Fact IDs, checked for form and duplicates, sorted so equal sets compare equal. */
  static List<String> factIds(List<String> ids, String what) {
    List<String> sorted = new ArrayList<>(list(ids, what));
    Set<String> seen = new HashSet<>();
    for (String id : sorted) {
      if (!FACT_ID.matcher(id).matches()) {
        throw new IllegalArgumentException("Invalid fact ID in " + what + ": '" + id + "'");
      }
      if (!seen.add(id)) {
        throw new IllegalArgumentException("Duplicate fact ID in " + what + ": " + id);
      }
    }
    sorted.sort(null);
    return List.copyOf(sorted);
  }
}

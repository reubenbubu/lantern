package io.lantern.extract.config;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Resolves Spring's {@code ${key}} and {@code ${key:default}} placeholders as Spring does: a
 * placeholder is replaced by the key's value, itself resolved; a key without a value takes the
 * default if there is one; placeholders may nest, in keys, defaults and values. A placeholder that
 * cannot be resolved (a key nothing sets and without a default, or a key whose value leads back to
 * itself) is kept as written, {@code ${key}}, and its key is reported.
 */
final class Placeholders {

  /** How deep values may lead to further values before the rest is treated as unresolved. */
  private static final int MAX_DEPTH = 32;

  private static final String PREFIX = "${";
  private static final char SUFFIX = '}';
  private static final char SEPARATOR = ':';

  private final Function<String, String> lookup;
  private final SortedSet<String> unresolved = new TreeSet<>();
  private final Deque<String> resolving = new ArrayDeque<>();

  private Placeholders(Function<String, String> lookup) {
    this.lookup = lookup;
  }

  /**
   * Resolves the placeholders in a text.
   *
   * @param text the text, such as {@code ${clients.payments.url}/api/payments}
   * @param lookup a key's value as written, or null if nothing sets the key
   */
  static Resolution resolve(String text, Function<String, String> lookup) {
    Objects.requireNonNull(text, "text");
    Objects.requireNonNull(lookup, "lookup");
    Placeholders p = new Placeholders(lookup);
    String value = p.substitute(text);
    return new Resolution(value, p.unresolved);
  }

  private String substitute(String text) {
    StringBuilder out = new StringBuilder();
    int i = 0;
    while (i < text.length()) {
      int start = text.indexOf(PREFIX, i);
      if (start < 0) {
        out.append(text, i, text.length());
        break;
      }
      int end = closing(text, start);
      if (end < 0) {
        out.append(text, i, text.length());
        break;
      }
      out.append(text, i, start);
      out.append(placeholder(text.substring(start + PREFIX.length(), end)));
      i = end + 1;
    }
    return out.toString();
  }

  /** The replacement of one placeholder, given what is between its braces. */
  private String placeholder(String inner) {
    int separator = separator(inner);
    String rawKey = separator < 0 ? inner : inner.substring(0, separator);
    String fallback = separator < 0 ? null : inner.substring(separator + 1);
    String key = substitute(rawKey);
    if (resolving.size() >= MAX_DEPTH || resolving.contains(key)) {
      // A value that leads back to itself, as Spring reports a circular placeholder.
      unresolved.add(key);
      return PREFIX + key + SUFFIX;
    }
    String value = lookup.apply(key);
    if (value != null) {
      resolving.push(key);
      try {
        return substitute(value);
      } finally {
        resolving.pop();
      }
    }
    if (fallback != null) {
      return substitute(fallback);
    }
    unresolved.add(key);
    return PREFIX + key + SUFFIX;
  }

  /** The index of the brace closing the placeholder that starts at {@code start}, or -1. */
  private static int closing(String text, int start) {
    int depth = 0;
    int i = start + PREFIX.length();
    while (i < text.length()) {
      if (text.startsWith(PREFIX, i)) {
        depth++;
        i += PREFIX.length();
        continue;
      }
      if (text.charAt(i) == SUFFIX) {
        if (depth == 0) {
          return i;
        }
        depth--;
      }
      i++;
    }
    return -1;
  }

  /** The index of the first separator outside nested placeholders, or -1. */
  private static int separator(String inner) {
    int depth = 0;
    int i = 0;
    while (i < inner.length()) {
      if (inner.startsWith(PREFIX, i)) {
        depth++;
        i += PREFIX.length();
        continue;
      }
      char c = inner.charAt(i);
      if (c == SUFFIX && depth > 0) {
        depth--;
      } else if (c == SEPARATOR && depth == 0) {
        return i;
      }
      i++;
    }
    return -1;
  }
}

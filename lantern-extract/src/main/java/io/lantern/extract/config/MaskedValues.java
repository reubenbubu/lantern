package io.lantern.extract.config;

import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * The values masking took out of a text before an LLM role is shown it (ADR 0054, ADR 0100,
 * amendment for #645): line by line, the part of the original line that the shown line replaced or
 * dropped, and the credentials in it a URL or option held. The LLM harness rejects an answer that
 * repeats one, so a secret the masking found cannot reach a document through a model that read it
 * elsewhere.
 */
public final class MaskedValues {

  /** What separates the user, password and host of a URL's credentials, or a key and its value. */
  private static final Pattern PARTS = Pattern.compile("[\"'`:@=;,&?/]+");

  private MaskedValues() {}

  /**
   * Hands the sink each value the shown text masked or removed from the original. Masking keeps
   * line breaks, so the lines are compared one by one; texts whose line counts differ are compared
   * whole.
   *
   * @param original the text as read
   * @param shown the same text as the roles are shown it
   * @param sink takes each value, stripped, once per line it was taken from
   */
  public static void taken(String original, String shown, Consumer<String> sink) {
    if (original == null || shown == null || original.equals(shown)) {
      return;
    }
    String[] before = original.split("\n", -1);
    String[] after = shown.split("\n", -1);
    if (before.length != after.length) {
      line(original, shown, sink);
      return;
    }
    for (int i = 0; i < before.length; i++) {
      if (!before[i].equals(after[i])) {
        line(before[i], after[i], sink);
      }
    }
  }

  /** The part of a line between what it shares with its shown line at the start and the end. */
  private static void line(String before, String after, Consumer<String> sink) {
    int start = 0;
    int max = Math.min(before.length(), after.length());
    while (start < max && before.charAt(start) == after.charAt(start)) {
      start++;
    }
    int end = 0;
    while (end < max - start
        && before.charAt(before.length() - 1 - end) == after.charAt(after.length() - 1 - end)) {
      end++;
    }
    String taken = before.substring(start, before.length() - end).strip();
    if (taken.isEmpty()) {
      return;
    }
    sink.accept(taken);
    if (taken.chars().anyMatch(Character::isWhitespace)) {
      // Masked prose, such as a message under a secret's name: its words are no secrets.
      return;
    }
    // A credential removed from a URL leaves its user and password; each may be repeated alone.
    for (String part : PARTS.split(taken)) {
      if (!part.isEmpty() && !part.equals(taken)) {
        sink.accept(part);
      }
    }
  }
}

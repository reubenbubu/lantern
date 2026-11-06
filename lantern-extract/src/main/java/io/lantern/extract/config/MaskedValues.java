package io.lantern.extract.config;

import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The values masking took out of a text before an LLM role is shown it (ADR 0054, ADR 0100,
 * amendment for #645): line by line, the part of the original line that the shown line replaced or
 * dropped, and the password a URL in it held. The LLM harness rejects an answer that repeats one,
 * so a secret the masking found cannot reach a document through a model that read it elsewhere.
 */
public final class MaskedValues {

  /**
   * The password in a URL's credentials, {@code scheme://user:password@host}, or the {@code
   * user:password@} masking removed from one. Only it is a secret: the scheme, user, host and
   * database name are ordinary words an answer may well repeat.
   */
  private static final Pattern PASSWORD = Pattern.compile("(?:://|^)[^:/@\\s]*:([^@/\\s]+)@");

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
    // A value written over several lines is also handed whole: the parts its continuation lines,
    // masked to nothing but ******, took out, joined to the part of the line above (#917).
    String joined = null;
    for (int i = 0; i < before.length; i++) {
      if (before[i].equals(after[i])) {
        // A blank line inside a YAML value folds into it; any other line ends the value.
        if (!before[i].isBlank()) {
          joined = null;
        }
        continue;
      }
      String taken = line(before[i], after[i], sink);
      if (taken == null) {
        joined = null;
      } else if (joined != null && after[i].strip().equals("******")) {
        boolean backslash = joined.endsWith("\\");
        joined =
            backslash ? joined.substring(0, joined.length() - 1) + taken : joined + " " + taken;
        sink.accept(joined.strip());
      } else {
        joined = taken;
      }
    }
  }

  /**
   * The part of a line between what it shares with its shown line at the start and the end, handed
   * to the sink; {@code null} when there is none.
   */
  private static String line(String before, String after, Consumer<String> sink) {
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
      return null;
    }
    sink.accept(taken);
    // A URL masked for its credentials holds a password that may be repeated alone, even when the
    // span also holds other text the line masked.
    Matcher password = PASSWORD.matcher(taken);
    while (password.find()) {
      sink.accept(password.group(1));
    }
    return taken;
  }
}

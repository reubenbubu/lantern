package io.lantern.extract.config;

import java.util.regex.Pattern;

/**
 * A secret's value with the placeholders that say where it comes from kept and everything written
 * in it masked (#912): {@code ${BFF_JWT_SIGNING_KEY:dev-key}} is shown as {@code
 * ${BFF_JWT_SIGNING_KEY:******}}, so a model can tell a value taken from the environment from one
 * committed in the file, and never sees the default. Spring's {@code ${name:default}} and the
 * shell's {@code ${NAME:-default}}, {@code :?}, {@code :=} forms are read, nested ones too; text
 * outside a placeholder is masked. Anything else is masked whole by the caller.
 */
final class SecretPlaceholders {

  /**
   * A name kept as written: an environment variable, or a Spring property, which alone may hold a
   * hyphen since it holds a dot; so the shell's {@code ${NAME-default}} never passes its default
   * off as part of a name.
   */
  private static final Pattern NAME =
      Pattern.compile("[A-Za-z_][A-Za-z0-9_]*|[A-Za-z0-9_\\-]+(\\.[A-Za-z0-9_\\-\\[\\]]+)+");

  /**
   * The value with its placeholders' names kept and every literal masked, its quotes kept; {@code
   * null} when it holds no placeholder or one that cannot be read, to be masked whole.
   */
  static String kept(String value) {
    String quote = "";
    String body = value;
    if (body.length() >= 2
        && (body.charAt(0) == '"' || body.charAt(0) == '\'')
        && body.charAt(body.length() - 1) == body.charAt(0)) {
      quote = body.substring(0, 1);
      body = body.substring(1, body.length() - 1);
    }
    String kept = placeholders(body);
    return kept == null ? null : quote + kept + quote;
  }

  private static String placeholders(String text) {
    StringBuilder out = new StringBuilder();
    boolean any = false;
    int i = 0;
    while (i < text.length()) {
      int open = text.indexOf("${", i);
      if (open < 0) {
        literal(out, text.substring(i));
        break;
      }
      literal(out, text.substring(i, open));
      int close = close(text, open);
      if (close < 0) {
        return null;
      }
      String placeholder = placeholder(text.substring(open + 2, close));
      if (placeholder == null) {
        return null;
      }
      out.append(placeholder);
      any = true;
      i = close + 1;
    }
    return any ? out.toString() : null;
  }

  /** The inside of one placeholder, its name kept and its default masked, or null. */
  private static String placeholder(String inner) {
    int separator = separator(inner);
    String name = separator < 0 ? inner : inner.substring(0, separator);
    if (!NAME.matcher(name).matches()) {
      return null;
    }
    if (separator < 0) {
      return "${" + name + "}";
    }
    int end = separator + 1;
    if (end < inner.length() && "-?=+".indexOf(inner.charAt(end)) >= 0) {
      end++;
    }
    String fallback = inner.substring(end);
    String shown;
    if (fallback.isEmpty()) {
      shown = "";
    } else {
      String nested = placeholders(fallback);
      shown = nested == null ? ConfigExtractor.MASK : nested;
    }
    return "${" + inner.substring(0, end) + shown + "}";
  }

  /** Where the first {@code :} outside a nested placeholder is, or -1. */
  private static int separator(String inner) {
    int depth = 0;
    for (int j = 0; j < inner.length(); j++) {
      if (inner.startsWith("${", j)) {
        depth++;
        j++;
      } else if (inner.charAt(j) == '}') {
        depth--;
      } else if (inner.charAt(j) == ':' && depth == 0) {
        return j;
      }
    }
    return -1;
  }

  /** The {@code }} that closes the placeholder opened at {@code open}, or -1. */
  private static int close(String text, int open) {
    int depth = 0;
    for (int j = open; j < text.length(); j++) {
      if (text.startsWith("${", j)) {
        depth++;
        j++;
      } else if (text.charAt(j) == '}' && --depth == 0) {
        return j;
      }
    }
    return -1;
  }

  /** Text outside a placeholder: spaces kept, anything else masked. */
  private static void literal(StringBuilder out, String text) {
    if (!text.isEmpty()) {
      out.append(text.isBlank() ? text : ConfigExtractor.MASK);
    }
  }

  private SecretPlaceholders() {}
}

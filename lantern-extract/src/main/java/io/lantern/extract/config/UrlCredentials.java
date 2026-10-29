package io.lantern.extract.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes credentials from database and other connection URLs in a config value, so that a value
 * can be recorded without them. See {@code docs/adr/0036-persistence-extractor.md} and {@code
 * docs/adr/0032-config-extractor.md}.
 *
 * <p>A URL starts with {@code jdbc:}, {@code r2dbc:} or any {@code scheme://} ({@code amqp://},
 * {@code redis://}, {@code mongodb+srv://}, {@code https://}, ...) and ends at whitespace or a
 * comma before another URL, so a text may hold several. Removed: user info before the host ({@code
 * //user:password@host}), Oracle's {@code thin:user/password@}, and every property naming a user or
 * a secret ({@code ?password=...}, {@code &api_key=...}, {@code ;user=...}). Placeholders are kept
 * whole, except those in a removed part, so a default such as {@code ${DB_PASSWORD:changeme}} in
 * the password goes with it; a URL inside a placeholder's default is cleaned the same way.
 */
public final class UrlCredentials {

  /**
   * Property names that hold a user name; secret ones are those {@link ConfigExtractor#isSecret}.
   */
  private static final Set<String> USER_PROPERTIES =
      Set.of("user", "username", "userid", "uid", "user id", "user name", "login");

  /** Where a URL starts in a text. */
  private static final Pattern URL_START =
      Pattern.compile(
          "(?<![A-Za-z0-9])(?:(?:jdbc|r2dbc):|[A-Za-z][A-Za-z0-9+.\\-]*://)",
          Pattern.CASE_INSENSITIVE);

  /** Where the authority starts: after {@code scheme:[sub:]...//}. */
  private static final Pattern AUTHORITY =
      Pattern.compile("^[A-Za-z][A-Za-z0-9+.\\-]*:(?:[A-Za-z0-9+.\\-]*:)*//");

  /**
   * Oracle's {@code thin:user/password@}, the password possibly quoted and holding an {@code @}.
   */
  private static final Pattern ORACLE_CREDENTIALS =
      Pattern.compile(
          "^(jdbc:oracle:(?:thin|oci8?):)(?:\"[^\"]*\"|[^@\"])+@", Pattern.CASE_INSENSITIVE);

  /** A credential left in a form not understood. */
  private static final Pattern LEFTOVER =
      Pattern.compile("(?i)(password|passwd|pwd|secret|token)\\s*=");

  /** Marks a placeholder taken out of the text while its structure is read. */
  private static final char OPEN = '\u0001';

  private static final char CLOSE = '\u0002';

  private UrlCredentials() {}

  /**
   * The text with the credentials of the URLs in it removed.
   *
   * @param text a config value, such as {@code jdbc:postgresql://user:${DB_PASSWORD}@db/shop} or
   *     {@code ${DB_URL:jdbc:oracle:thin:scott/tiger@//db:1521/X}}
   */
  public static String withoutCredentials(String text) {
    List<String> placeholders = new ArrayList<>();
    String protectedText = protect(text, placeholders);
    Matcher start = URL_START.matcher(protectedText);
    StringBuilder cleaned = new StringBuilder();
    int from = 0;
    while (start.find(from)) {
      cleaned.append(protectedText, from, start.start());
      int end = end(protectedText, start.end());
      cleaned.append(cleanOne(protectedText.substring(start.start(), end)));
      from = end;
    }
    cleaned.append(protectedText, from, protectedText.length());
    return restore(cleaned.toString(), placeholders);
  }

  /** Whether a text holds a URL, possibly inside a placeholder's default. */
  public static boolean containsUrl(String text) {
    return URL_START.matcher(text).find();
  }

  /**
   * Where a URL whose start ends at {@code from} ends: at whitespace, or at a comma followed by
   * another URL ({@code amqp://a,amqp://b}; a comma between hosts stays in the URL).
   */
  private static int end(String text, int from) {
    for (int i = from; i < text.length(); i++) {
      char c = text.charAt(i);
      if (Character.isWhitespace(c)) {
        return i;
      }
      if (c == ',' && URL_START.matcher(text).region(i + 1, text.length()).lookingAt()) {
        return i;
      }
    }
    return text.length();
  }

  /**
   * Whether a text may still hold a credential in a form {@link #withoutCredentials} does not
   * understand, such as MySQL's {@code address=(host=db)(password=x)}.
   */
  public static boolean mayHoldCredential(String text) {
    return LEFTOVER.matcher(text).find();
  }

  /**
   * Replaces each placeholder, {@code ${...}} with its nested braces, by a marker holding its
   * index, so that the characters inside it ({@code ? ; @ / :}) are not read as the URL's.
   */
  private static String protect(String text, List<String> placeholders) {
    StringBuilder out = new StringBuilder();
    int i = 0;
    while (i < text.length()) {
      if (text.startsWith("${", i)) {
        int depth = 0;
        int j = i;
        int end = -1;
        while (j < text.length()) {
          if (text.startsWith("${", j)) {
            depth++;
            j += 2;
            continue;
          }
          if (text.charAt(j) == '}' && --depth == 0) {
            end = j;
            break;
          }
          j++;
        }
        if (end < 0) {
          out.append(text, i, text.length());
          break;
        }
        out.append(OPEN).append(placeholders.size()).append(CLOSE);
        placeholders.add(text.substring(i + 2, end));
        i = end + 1;
      } else {
        out.append(text.charAt(i));
        i++;
      }
    }
    return out.toString();
  }

  /** Puts the placeholders left back, each with the URLs in its default cleaned. */
  private static String restore(String text, List<String> placeholders) {
    StringBuilder out = new StringBuilder();
    int i = 0;
    while (i < text.length()) {
      char c = text.charAt(i);
      int close = c == OPEN ? text.indexOf(CLOSE, i) : -1;
      if (close > 0) {
        int index = Integer.parseInt(text.substring(i + 1, close));
        out.append("${").append(withoutCredentials(placeholders.get(index))).append('}');
        i = close + 1;
      } else {
        out.append(c);
        i++;
      }
    }
    return out.toString();
  }

  /** Cleans one URL, its placeholders taken out. */
  private static String cleanOne(String url) {
    Matcher oracle = ORACLE_CREDENTIALS.matcher(url);
    String result = oracle.find() ? oracle.group(1) + "@" + url.substring(oracle.end()) : url;
    Matcher authority = AUTHORITY.matcher(result);
    if (authority.find()) {
      String rest = result.substring(authority.end());
      // The user info ends at the last '@' before the query or fragment, so a password holding
      // '/' or ';' goes too.
      int query = firstOf(rest, "?#");
      int at = rest.lastIndexOf('@', query - 1);
      if (at >= 0) {
        result = result.substring(0, authority.end()) + rest.substring(at + 1);
      }
    }
    return withoutProperties(result);
  }

  /**
   * Removes user and secret properties from the {@code ?a=b&c=d} and {@code ;a=b} parts, keeping a
   * {@code #fragment}.
   */
  private static String withoutProperties(String url) {
    int start = firstOf(url, "?;#");
    int fragment = start + firstOf(url.substring(start), "#");
    StringBuilder out = new StringBuilder(url.substring(0, start));
    boolean query = false;
    int i = start;
    while (i < fragment) {
      char separator = url.charAt(i);
      int next = i + 1;
      while (next < fragment && "&;?".indexOf(url.charAt(next)) < 0) {
        next++;
      }
      String property = url.substring(i + 1, next);
      int eq = property.indexOf('=');
      String name = (eq < 0 ? property : property.substring(0, eq)).strip();
      if (!property.isEmpty() && !isCredential(name)) {
        if (separator == ';') {
          out.append(';');
        } else {
          out.append(query ? '&' : '?');
          query = true;
        }
        out.append(property);
      }
      i = next;
    }
    return out.append(url, fragment, url.length()).toString();
  }

  private static boolean isCredential(String name) {
    String lower = name.toLowerCase(Locale.ROOT);
    return USER_PROPERTIES.contains(lower) || ConfigExtractor.isSecret(lower);
  }

  private static int firstOf(String text, String chars) {
    for (int i = 0; i < text.length(); i++) {
      if (chars.indexOf(text.charAt(i)) >= 0) {
        return i;
      }
    }
    return text.length();
  }
}

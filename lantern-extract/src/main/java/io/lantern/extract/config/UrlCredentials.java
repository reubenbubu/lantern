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
 *
 * <p>Outside URLs, {@code user:password@host} tokens lose their user info, and options whose name a
 * config key would be secret by ({@link ConfigExtractor#isSecret}) are removed, as in Kafka's
 * {@code sasl.jaas.config=... required username="app" password="x";}, which keeps {@code ...
 * required username="app";}. See {@code docs/adr/0032-config-extractor.md}.
 */
public final class UrlCredentials {

  /** URL property names that hold a user name, lower case. */
  private static final Set<String> USER_PROPERTIES =
      Set.of("user", "username", "userid", "uid", "user id", "user name", "login");

  /**
   * URL property names that hold a secret, lower case without {@code -}, {@code _} and spaces,
   * besides those {@link ConfigExtractor#isSecret} judges secret and those naming a password,
   * secret, credential, signature or token.
   */
  private static final Set<String> SECRET_PROPERTIES =
      Set.of("pass", "pw", "sig", "key", "auth", "authorization", "passphrase");

  private static final Pattern SECRET_PROPERTY =
      Pattern.compile(".*(password|passwd|secret|credential|signature|token).*");

  /**
   * A scheme or subprotocol: letters, digits, {@code + . -}, or a placeholder's marker, as in
   * {@code jdbc:${DB_VENDOR:postgresql}://} or {@code ${PROTO:https}://}.
   */
  private static final String SCHEME = "[A-Za-z\u0001][A-Za-z0-9+.\\-\u0001\u0002]*";

  /** Where a URL starts in a text whose placeholders are taken out. */
  private static final Pattern URL_START =
      Pattern.compile(
          "(?<![A-Za-z0-9\u0001\u0002])(?:(?:jdbc|r2dbc):|" + SCHEME + "://)",
          Pattern.CASE_INSENSITIVE);

  /** Where the authority starts: after {@code scheme:[sub:]...//}. */
  private static final Pattern AUTHORITY =
      Pattern.compile("^" + SCHEME + ":(?:[A-Za-z0-9+.\\-\u0001\u0002]*:)*//");

  /**
   * User info without a scheme, {@code user:password@host} as in {@code
   * spring.rabbitmq.addresses=guest:guest@r1,guest:guest@r2}: a user, a colon, and a password up to
   * the last {@code @} before the host, the token starting the text or after whitespace, a comma,
   * {@code = ; (} or a quote.
   */
  private static final Pattern SCHEMELESS_USER_INFO =
      Pattern.compile(
          "(?<![^\\s,=;(\"'])[A-Za-z0-9._~%+\\-\u0001\u0002]*:[^\\s,]*@"
              + "(?=[A-Za-z0-9\\[\u0001][^\\s,@]*(?:[\\s,]|$))");

  /**
   * Oracle's {@code thin:user/password@}, the password possibly quoted and holding an {@code @}.
   */
  private static final Pattern ORACLE_CREDENTIALS =
      Pattern.compile(
          "^(jdbc:oracle:(?:thin|oci8?):)(?:\"[^\"]*\"|[^@\"])+@", Pattern.CASE_INSENSITIVE);

  /** A credential left in a form not understood. */
  private static final Pattern LEFTOVER =
      Pattern.compile(
          "(?i)(?:password|passwd|secret|token|signature"
              + "|(?<![a-z0-9])(?:pwd|pass|sig|key|apikey|api_key))\\s*=");

  /**
   * An option {@code name=value} outside URLs: the value is double or single quoted, a backslash
   * escaping the next character, or runs up to whitespace or one of {@code ; , & )} or a quote. The
   * name starts the text or follows a character that cannot be in a name, so {@code a.password=x}
   * is read as the option {@code a.password}.
   */
  private static final Pattern OPTION =
      Pattern.compile(
          "(?<![A-Za-z0-9_.\\-\u0001\u0002])([A-Za-z_\\-][A-Za-z0-9_.\\-]*)\\s*=\\s*"
              + "(\"(?:\\\\.|[^\"\\\\])+\"|'(?:\\\\.|[^'\\\\])+'|[^\\s;,&)\"']+)");

  /** A placeholder's marker, the whole of an option's value if it is only placeholders. */
  private static final Pattern MARKERS = Pattern.compile("(?:\u0001(\\d+)\u0002)+");

  private static final Pattern MARKER = Pattern.compile("\u0001(\\d+)\u0002");

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
      cleaned.append(outsideUrls(protectedText.substring(from, start.start()), placeholders));
      int end = end(protectedText, start.end());
      cleaned.append(cleanOne(protectedText.substring(start.start(), end)));
      from = end;
    }
    cleaned.append(outsideUrls(protectedText.substring(from), placeholders));
    return restore(cleaned.toString(), placeholders);
  }

  /**
   * Text outside URLs without secret options and without the user info of {@code
   * user:password@host} tokens.
   */
  private static String outsideUrls(String text, List<String> placeholders) {
    return SCHEMELESS_USER_INFO.matcher(withoutSecretOptions(text, placeholders)).replaceAll("");
  }

  /**
   * Removes each option {@code name=value} whose name a config key would be secret by, with one
   * separator ({@code ,} or {@code &}) or the whitespace next to it, so the options around it stay
   * apart. An option whose value is only placeholders without defaults ({@code
   * password="${KAFKA_PASSWORD}"}) is kept: it says where the secret comes from, as a secret key's
   * value does.
   */
  private static String withoutSecretOptions(String text, List<String> placeholders) {
    Matcher option = OPTION.matcher(text);
    StringBuilder out = new StringBuilder();
    int from = 0;
    while (option.find()) {
      if (!ConfigExtractor.isSecret(option.group(1))
          || onlyPlaceholdersWithoutDefaults(unquoted(option.group(2)), placeholders)) {
        continue;
      }
      int start = option.start();
      int end = option.end();
      int before = start;
      while (before > from && Character.isWhitespace(text.charAt(before - 1))) {
        before--;
      }
      int after = end;
      while (after < text.length() && Character.isWhitespace(text.charAt(after))) {
        after++;
      }
      if (before > from && ",&".indexOf(text.charAt(before - 1)) >= 0) {
        // "a=1, password=x" loses ", password=x".
        start = before - 1;
      } else if (after < text.length() && ",&".indexOf(text.charAt(after)) >= 0) {
        // "password=x, a=1" loses "password=x, ".
        end = after + 1;
        while (end < text.length() && Character.isWhitespace(text.charAt(end))) {
          end++;
        }
      } else if (before > 0 && before < start) {
        // "required user=u password=x;" loses " password=x".
        start = before;
      } else {
        end = after;
      }
      out.append(text, from, start);
      from = end;
    }
    return out.append(text, from, text.length()).toString();
  }

  private static String unquoted(String value) {
    char first = value.charAt(0);
    return (first == '"' || first == '\'') && value.length() > 1
        ? value.substring(1, value.length() - 1)
        : value;
  }

  private static boolean onlyPlaceholdersWithoutDefaults(String value, List<String> placeholders) {
    if (!MARKERS.matcher(value).matches()) {
      return false;
    }
    Matcher marker = MARKER.matcher(value);
    while (marker.find()) {
      String placeholder = placeholders.get(Integer.parseInt(marker.group(1)));
      if (placeholder.contains(":") || placeholder.contains("${")) {
        return false;
      }
    }
    return true;
  }

  /**
   * Whether a text holds a URL or user info without a scheme, possibly inside a placeholder's
   * default.
   */
  public static boolean containsUrl(String text) {
    String protectedText = protect(text, new ArrayList<>());
    return URL_START.matcher(text).find()
        || URL_START.matcher(protectedText).find()
        || SCHEMELESS_USER_INFO.matcher(text).find();
  }

  /**
   * Where a URL whose start ends at {@code from} ends: at whitespace, or at a comma followed by
   * another URL or by {@code user:password@host} ({@code amqp://a,amqp://b}, {@code
   * amqp://u:p@a,u:p@b}; a comma between hosts stays in the URL).
   */
  private static int end(String text, int from) {
    for (int i = from; i < text.length(); i++) {
      char c = text.charAt(i);
      if (Character.isWhitespace(c)) {
        return i;
      }
      if (c == ','
          && (URL_START.matcher(text).region(i + 1, text.length()).lookingAt()
              || SCHEMELESS_USER_INFO.matcher(text).region(i + 1, text.length()).lookingAt())) {
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
      int at = userInfoEnd(rest);
      if (at >= 0) {
        result = result.substring(0, authority.end()) + rest.substring(at + 1);
      }
    }
    return withoutProperties(result);
  }

  /**
   * The {@code @} that ends the user info at the start of an authority, or -1 if there is none.
   *
   * <p>There is user info if an {@code @} comes before the first {@code / ? #}, or if the authority
   * starts with {@code user:} and what follows the colon up to the first {@code / ? #} is not a
   * port: a password may hold {@code / ? # ;} ({@code u:p#ss@rabbit}), while {@code
   * medium.com/@team} and {@code host:8080/@team} have none. The user info then ends at the last
   * {@code @} before the first {@code / ? # ,} after its first {@code @}, so a password holding an
   * {@code @} goes too, and further hosts after a comma stay.
   */
  private static int userInfoEnd(String rest) {
    int first = rest.indexOf('@');
    if (first < 0) {
      return -1;
    }
    int delimiter = firstOf(rest, "/?#");
    if (first > delimiter) {
      int colon = rest.indexOf(':');
      if (colon < 0 || colon > delimiter || firstOf(rest.substring(0, colon), "@") < colon) {
        return -1;
      }
      String afterColon = rest.substring(colon + 1, delimiter);
      if (afterColon.isEmpty() || afterColon.chars().allMatch(Character::isDigit)) {
        return -1;
      }
    }
    int after = first + 1 + firstOf(rest.substring(first + 1), "/?#,");
    return rest.lastIndexOf('@', after - 1);
  }

  /**
   * Removes user and secret properties from the {@code ?a=b&c=d} and {@code ;a=b} parts, keeping a
   * {@code #fragment}. Separators are copied as written; only when the property after the {@code ?}
   * is removed does the next {@code &} become the {@code ?}. A URL without such properties is
   * returned as is.
   */
  private static String withoutProperties(String url) {
    int start = firstOf(url, "?;#");
    int fragment = start + firstOf(url.substring(start), "#");
    StringBuilder out = new StringBuilder(url.substring(0, start));
    boolean queryRemoved = false;
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
      if (!property.isEmpty() && isCredential(name)) {
        queryRemoved |= separator == '?';
      } else {
        if (separator == '&' && queryRemoved) {
          separator = '?';
        }
        if (separator != ';') {
          queryRemoved = false;
        }
        out.append(separator).append(property);
      }
      i = next;
    }
    return out.append(url, fragment, url.length()).toString();
  }

  /** Whether a URL property's name names a user or a secret. */
  private static boolean isCredential(String name) {
    String lower = name.toLowerCase(Locale.ROOT);
    if (USER_PROPERTIES.contains(lower) || ConfigExtractor.isSecret(lower)) {
      return true;
    }
    String plain = lower.replace("-", "").replace("_", "").replace(" ", "");
    return SECRET_PROPERTIES.contains(plain) || SECRET_PROPERTY.matcher(plain).matches();
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

package io.lantern.extract.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Removes secrets from Java source code before it is shown to an LLM, with the rules the config
 * extractor uses for config values. See {@code docs/adr/0054-secrets-in-source-files.md}.
 *
 * <p>A string literal or text block is replaced by {@link ConfigExtractor#MASK} when a name a
 * config key would be secret by ({@link ConfigExtractor#isSecret}, or {@code authorization} or
 * {@code passphrase}) gives it its meaning: the variable, field, constant or annotation element it
 * is assigned to ({@code String apiKey = "..."}, {@code password = "..."}), the method or
 * constructor it is an argument of ({@code setPassword("...")}, {@code .token("...")}), the literal
 * or constant key it follows ({@code put("db.password", "...")}, {@code
 * set(HttpHeaders.AUTHORIZATION, "...")}), the array it initializes, or the variable it is compared
 * with ({@code password.equals("...")}). Literals joined with {@code +} count as one. A literal
 * that is empty or only placeholders without defaults ({@code "${DB_PASSWORD}"}) is kept, since it
 * says where the secret comes from.
 *
 * <p>Any other literal, and the text of every comment, goes through {@link UrlCredentials}:
 * credentials in URLs, {@code user:password@host} and secret options ({@code password=x}) are
 * removed. The default of a placeholder naming a secret ({@code ${db.password:changeme}}) is
 * masked, and a literal holding a PEM private key, or a URL that still looks like it holds a
 * credential, is masked whole.
 *
 * <p>Masking a text block keeps its line breaks, so the file keeps its line numbers.
 */
public final class SourceSecrets {

  /** Names that hold a secret besides those {@link ConfigExtractor#isSecret} judges secret. */
  private static final Set<String> SECRET_NAMES = Set.of("authorization", "passphrase");

  /** Methods comparing their receiver with their argument. */
  private static final Set<String> COMPARISONS =
      Set.of("equals", "equalsIgnoreCase", "contentEquals");

  /** A value that is only placeholders without defaults, which says where a secret comes from. */
  private static final Pattern ONLY_PLACEHOLDERS = Pattern.compile("(\\$\\{[^:{}]+})+");

  /** The header of a PEM private key, of any kind. */
  private static final Pattern PRIVATE_KEY =
      Pattern.compile("-----BEGIN [A-Z0-9 ]*PRIVATE KEY( BLOCK)?-----");

  private SourceSecrets() {}

  /** What a token of Java source is. */
  private enum Kind {
    IDENTIFIER,
    STRING,
    TEXT_BLOCK,
    COMMENT,
    OTHER
  }

  /**
   * A token of Java source.
   *
   * @param start where it starts in the source
   * @param end where it ends
   * @param contentStart where a literal's content starts (after its opening quotes)
   * @param contentEnd where a literal's content ends (before its closing quotes)
   */
  private record Token(Kind kind, int start, int end, int contentStart, int contentEnd) {

    boolean isLiteral() {
      return kind == Kind.STRING || kind == Kind.TEXT_BLOCK;
    }
  }

  /**
   * The source with its secrets masked or removed; a source without any is returned unchanged.
   *
   * @param source Java source code
   */
  public static String scrub(String source) {
    List<Token> all = tokens(source);
    List<Token> code = new ArrayList<>();
    for (Token token : all) {
      if (token.kind() != Kind.COMMENT) {
        code.add(token);
      }
    }
    StringBuilder out = new StringBuilder();
    int from = 0;
    int index = 0;
    for (Token token : all) {
      String replacement = null;
      if (token.kind() == Kind.COMMENT) {
        replacement = cleaned(content(source, token));
      } else if (token.isLiteral()) {
        replacement = literal(source, code, index);
      }
      if (token.kind() != Kind.COMMENT) {
        index++;
      }
      if (replacement != null) {
        out.append(source, from, token.contentStart()).append(replacement);
        from = token.contentEnd();
      }
    }
    return out.append(source, from, source.length()).toString();
  }

  /** The new content of the literal at {@code index} of the code tokens. */
  private static String literal(String source, List<Token> code, int index) {
    Token token = code.get(index);
    String content = source.substring(token.contentStart(), token.contentEnd());
    if (content.isBlank() || ONLY_PLACEHOLDERS.matcher(content.strip()).matches()) {
      return content;
    }
    if (secretNamed(source, code, index) || PRIVATE_KEY.matcher(content).find()) {
      return mask(content);
    }
    return cleaned(content);
  }

  /**
   * A literal's or comment's content with the defaults of placeholders naming a secret masked and
   * credentials removed; masked whole if a URL in it still looks like it holds a credential.
   */
  private static String cleaned(String content) {
    String cleaned = UrlCredentials.withoutCredentials(placeholderDefaultsMasked(content));
    if (UrlCredentials.containsUrl(cleaned) && UrlCredentials.mayHoldCredential(cleaned)) {
      return mask(content);
    }
    return cleaned;
  }

  /** The mask in place of a literal's content, keeping its line breaks. */
  private static String mask(String content) {
    long breaks = content.chars().filter(c -> c == '\n').count();
    if (breaks == 0) {
      return ConfigExtractor.MASK;
    }
    if (content.startsWith("\n")) {
      // A text block's content starts with a line break; the mask goes on the line after it.
      return "\n" + ConfigExtractor.MASK + "\n".repeat((int) breaks - 1);
    }
    return ConfigExtractor.MASK + "\n".repeat((int) breaks);
  }

  /** Whether a name a config key would be secret by gives the literal at {@code index} meaning. */
  private static boolean secretNamed(String source, List<Token> code, int index) {
    // "a" + "b" counts as one literal: what comes before the first decides.
    int first = index;
    while (first >= 2 && is(source, code, first - 1, "+") && code.get(first - 2).isLiteral()) {
      first -= 2;
    }
    int last = index;
    while (last + 2 < code.size()
        && is(source, code, last + 1, "+")
        && code.get(last + 2).isLiteral()) {
      last += 2;
    }
    // "secret".equals(password)
    if (is(source, code, last + 1, ".")
        && comparison(source, code, last + 2)
        && is(source, code, last + 3, "(")
        && secretIdentifier(source, code, last + 4)
        && is(source, code, last + 5, ")")) {
      return true;
    }
    int before = first - 1;
    if (before < 0) {
      return false;
    }
    if (is(source, code, before, "=") || is(source, code, before, "+=")) {
      return secretIdentifier(source, code, before - 1);
    }
    if (is(source, code, before, ",")) {
      // put("db.password", "x"): the literal before it is a secret key.
      if (before >= 1
          && code.get(before - 1).isLiteral()
          && (is(source, code, before - 2, "(") || is(source, code, before - 2, ","))
          && isSecretName(content(source, code.get(before - 1)))) {
        return true;
      }
      // headers.set(HttpHeaders.AUTHORIZATION, "x"): a constant before it names a secret.
      int key = before - 1;
      if (key >= 0 && code.get(key).kind() == Kind.IDENTIFIER) {
        int start = key;
        while (start >= 2
            && is(source, code, start - 1, ".")
            && code.get(start - 2).kind() == Kind.IDENTIFIER) {
          start -= 2;
        }
        if ((is(source, code, start - 1, "(") || is(source, code, start - 1, ","))
            && isSecretName(text(source, code.get(key)))) {
          return true;
        }
      }
      return openerNamesSecret(source, code, opener(source, code, before));
    }
    if (is(source, code, before, "(") || is(source, code, before, "{")) {
      return openerNamesSecret(source, code, before);
    }
    return false;
  }

  /**
   * Whether the bracket at {@code opener} belongs to a call of a method named as a secret, to a
   * comparison with a variable named as a secret, or to an array initializer of one.
   */
  private static boolean openerNamesSecret(String source, List<Token> code, int opener) {
    if (opener < 1) {
      return false;
    }
    if (is(source, code, opener, "(")) {
      // password.equals("x")
      if (comparison(source, code, opener - 1) && is(source, code, opener - 2, ".")) {
        return secretIdentifier(source, code, opener - 3);
      }
      return secretIdentifier(source, code, opener - 1);
    }
    // String[] passwords = {"a", "b"} and @Annotation(secrets = {"a", "b"})
    return is(source, code, opener, "{")
        && is(source, code, opener - 1, "=")
        && secretIdentifier(source, code, opener - 2);
  }

  /** The unclosed bracket before the token at {@code at}, or -1 if there is none. */
  private static int opener(String source, List<Token> code, int at) {
    int depth = 0;
    for (int i = at - 1; i >= 0; i--) {
      if (code.get(i).kind() != Kind.OTHER) {
        continue;
      }
      char c = source.charAt(code.get(i).start());
      if (c == ')' || c == ']' || c == '}') {
        depth++;
      } else if (c == '(' || c == '[' || c == '{') {
        if (depth == 0) {
          return i;
        }
        depth--;
      } else if (c == ';' && depth == 0) {
        return -1;
      }
    }
    return -1;
  }

  private static boolean comparison(String source, List<Token> code, int at) {
    return at >= 0
        && at < code.size()
        && code.get(at).kind() == Kind.IDENTIFIER
        && COMPARISONS.contains(text(source, code.get(at)));
  }

  private static boolean secretIdentifier(String source, List<Token> code, int at) {
    return at >= 0
        && at < code.size()
        && code.get(at).kind() == Kind.IDENTIFIER
        && isSecretName(text(source, code.get(at)));
  }

  /** Whether a name, an identifier or a literal key, is one a config key would be secret by. */
  private static boolean isSecretName(String name) {
    if (name.isBlank()) {
      return false;
    }
    String lower = name.toLowerCase(Locale.ROOT);
    String last = lower.substring(lower.lastIndexOf('.') + 1).replace("-", "").replace("_", "");
    return ConfigExtractor.isSecret(name) || SECRET_NAMES.contains(last);
  }

  private static boolean is(String source, List<Token> code, int at, String text) {
    return at >= 0
        && at < code.size()
        && code.get(at).kind() == Kind.OTHER
        && text.equals(text(source, code.get(at)));
  }

  private static String text(String source, Token token) {
    return source.substring(token.start(), token.end());
  }

  private static String content(String source, Token token) {
    return source.substring(token.contentStart(), token.contentEnd());
  }

  /**
   * The content with the default of each placeholder naming a secret masked: {@code
   * ${db.password:changeme}} becomes {@code ${db.password:******}}. Other placeholders' defaults
   * are looked at in turn.
   */
  private static String placeholderDefaultsMasked(String content) {
    StringBuilder out = new StringBuilder();
    int i = 0;
    while (i < content.length()) {
      int open = content.indexOf("${", i);
      if (open < 0) {
        break;
      }
      int depth = 0;
      int close = -1;
      int colon = -1;
      for (int j = open; j < content.length(); j++) {
        if (content.startsWith("${", j)) {
          depth++;
          j++;
        } else if (content.charAt(j) == '}' && --depth == 0) {
          close = j;
          break;
        } else if (content.charAt(j) == ':' && depth == 1 && colon < 0) {
          colon = j;
        }
      }
      if (close < 0) {
        break;
      }
      out.append(content, i, open);
      if (colon < 0) {
        out.append(content, open, close + 1);
      } else if (isSecretName(content.substring(open + 2, colon))) {
        out.append(content, open, colon + 1).append(ConfigExtractor.MASK).append('}');
      } else {
        out.append(content, open, colon + 1)
            .append(placeholderDefaultsMasked(content.substring(colon + 1, close)))
            .append('}');
      }
      i = close + 1;
    }
    return out.append(content, i, content.length()).toString();
  }

  /**
   * The tokens of Java source: identifiers (with keywords and numbers), string literals, text
   * blocks, comments, and every other character on its own except the operators ending in {@code
   * =}, which are one token. Character literals are tokens of the kind {@code OTHER}.
   */
  private static List<Token> tokens(String source) {
    List<Token> tokens = new ArrayList<>();
    int n = source.length();
    int i = 0;
    while (i < n) {
      char c = source.charAt(i);
      if (Character.isWhitespace(c)) {
        i++;
      } else if (source.startsWith("//", i)) {
        int end = source.indexOf('\n', i);
        end = end < 0 ? n : end;
        tokens.add(new Token(Kind.COMMENT, i, end, i + 2, end));
        i = end;
      } else if (source.startsWith("/*", i)) {
        int close = source.indexOf("*/", i + 2);
        int end = close < 0 ? n : close + 2;
        tokens.add(new Token(Kind.COMMENT, i, end, i + 2, close < 0 ? n : close));
        i = end;
      } else if (source.startsWith("\"\"\"", i)) {
        int contentStart = i + 3;
        int j = contentStart;
        while (j < n && !source.startsWith("\"\"\"", j)) {
          j += source.charAt(j) == '\\' ? 2 : 1;
        }
        int contentEnd = Math.min(j, n);
        int end = Math.min(contentEnd + 3, n);
        tokens.add(new Token(Kind.TEXT_BLOCK, i, end, contentStart, contentEnd));
        i = end;
      } else if (c == '"' || c == '\'') {
        int j = i + 1;
        while (j < n && source.charAt(j) != c && source.charAt(j) != '\n') {
          j += source.charAt(j) == '\\' ? 2 : 1;
        }
        int contentEnd = Math.min(j, n);
        int end = contentEnd < n && source.charAt(contentEnd) == c ? contentEnd + 1 : contentEnd;
        Kind kind = c == '"' ? Kind.STRING : Kind.OTHER;
        tokens.add(new Token(kind, i, end, i + 1, contentEnd));
        i = end;
      } else if (Character.isJavaIdentifierPart(c)) {
        int j = i + 1;
        while (j < n && Character.isJavaIdentifierPart(source.charAt(j))) {
          j++;
        }
        tokens.add(new Token(Kind.IDENTIFIER, i, j, i, j));
        i = j;
      } else {
        int end =
            i + 1 < n && source.charAt(i + 1) == '=' && "=!<>+-*/%&|^".indexOf(c) >= 0
                ? i + 2
                : i + 1;
        tokens.add(new Token(Kind.OTHER, i, end, i, end));
        i = end;
      }
    }
    return tokens;
  }
}

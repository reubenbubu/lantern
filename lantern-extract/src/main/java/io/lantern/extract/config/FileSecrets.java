package io.lantern.extract.config;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks the secrets of any file of a repository before an LLM role is shown its text (ADR 0054, ADR
 * 0081): Java and other code through {@link SourceSecrets}, config, environment and XML files line
 * by line, and private keys wherever they are. Keys, keystores and binary files are never shown.
 * Line breaks are kept, so line numbers hold. Every role that shows a file's lines, the
 * investigator's tools and the support judge alike, masks them here.
 */
public final class FileSecrets {

  /** A key and its value on one line of a config file: {@code key: value} or {@code key=value}. */
  private static final Pattern KEY_VALUE =
      Pattern.compile(
          "^(\\s*(?:-\\s+)?(?:export\\s+)?[\"']?)([A-Za-z0-9_.\\-\\[\\]]+)([\"']?\\s*[:=]\\s*)(.+)$");

  private static final Pattern ONLY_PLACEHOLDERS = Pattern.compile("[\"']?(\\$\\{[^}:]+})+[\"']?");

  /** File names and extensions of keys and keystores, which are never shown. */
  private static final Pattern KEY_FILE =
      Pattern.compile(
          "(?i)(^|/)(id_(rsa|dsa|ecdsa|ed25519)|[^/]*\\.(pem|key|p8|p12|pfx|jks|jceks|keystore|truststore|der|gpg|asc|kdbx))$");

  /** Source languages whose secrets are masked as Java's are (ADR 0054). */
  private static final Pattern CODE_FILE =
      Pattern.compile("(?i).*\\.(java|kt|kts|groovy|gradle|scala)$");

  /** A private key's body, between its PEM lines. */
  private static final Pattern PEM =
      Pattern.compile(
          "(-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----)(.*?)(-----END [A-Z0-9 ]*PRIVATE KEY-----)",
          Pattern.DOTALL);

  /** An XML element whose text is its whole content: {@code <password>x</password>}. */
  private static final Pattern XML_ELEMENT =
      Pattern.compile("<([A-Za-z0-9_.:\\-]+)(\\s[^>]*)?>([^<]*)</\\1>");

  /** An XML attribute: {@code password="x"}. */
  private static final Pattern XML_ATTRIBUTE =
      Pattern.compile("([A-Za-z0-9_.:\\-]+)(\\s*=\\s*)([\"'])([^\"']*)\\3");

  /** A Spring-style {@code name="db.password" value="x"} pair. */
  private static final Pattern NAME_VALUE =
      Pattern.compile(
          "(name\\s*=\\s*[\"']([^\"']+)[\"'][^>]*?value\\s*=\\s*[\"'])([^\"']*)([\"'])");

  /**
   * A file's text as the model may see it, its secrets masked; {@code null} for a binary file or a
   * key or keystore, which are never shown. Line breaks are kept, so line numbers hold.
   */
  public static String masked(String path, byte[] content) {
    return masked(path, content, Set.of());
  }

  /** As {@link #masked(String, byte[])}, also masking values under the known secret keys. */
  public static String masked(String path, byte[] content, Set<String> secretKeys) {
    if (KEY_FILE.matcher(path).find()) {
      return null;
    }
    for (byte b : content) {
      if (b == 0) {
        return null;
      }
    }
    String text = new String(content, StandardCharsets.UTF_8);
    text = withoutPrivateKeys(text);
    if (CODE_FILE.matcher(path).matches()) {
      return SourceSecrets.scrub(text, secretKeys);
    }
    StringBuilder out = new StringBuilder();
    int blockIndent = -1;
    for (String line : text.split("\n", -1)) {
      int indent = indent(line);
      if (blockIndent >= 0) {
        // The lines of a block scalar under a secret key (password: |) are its value.
        if (line.isBlank() || indent > blockIndent) {
          out.append(line.isBlank() ? line : " ".repeat(indent) + "******").append('\n');
          continue;
        }
        blockIndent = -1;
      }
      Matcher kv = KEY_VALUE.matcher(line);
      String shown;
      if (kv.matches() && ConfigExtractor.isSecret(kv.group(2), secretKeys)) {
        String value = kv.group(4).strip();
        if (value.matches("[|>][-+0-9]*")) {
          blockIndent = indent;
          shown = line;
        } else if (ONLY_PLACEHOLDERS.matcher(value).matches()) {
          shown = line;
        } else {
          shown = kv.group(1) + kv.group(2) + kv.group(3) + "******";
        }
      } else {
        shown = xmlMasked(UrlCredentials.withoutCredentials(line), secretKeys);
      }
      out.append(shown).append('\n');
    }
    out.setLength(out.length() - 1);
    return out.toString();
  }

  private static int indent(String line) {
    int i = 0;
    while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
      i++;
    }
    return i;
  }

  /** A private key's body masked line by line, so line numbers hold. */
  private static String withoutPrivateKeys(String text) {
    Matcher pem = PEM.matcher(text);
    StringBuilder out = new StringBuilder();
    while (pem.find()) {
      String body = pem.group(2).replaceAll("[^\\r\\n]+", "******");
      pem.appendReplacement(out, Matcher.quoteReplacement(pem.group(1) + body + pem.group(3)));
    }
    pem.appendTail(out);
    return out.toString();
  }

  /** The values of secret XML elements and attributes masked. */
  private static String xmlMasked(String line, Set<String> secretKeys) {
    Matcher element = XML_ELEMENT.matcher(line);
    StringBuilder out = new StringBuilder();
    while (element.find()) {
      String name = element.group(1).substring(element.group(1).indexOf(':') + 1);
      String replacement =
          ConfigExtractor.isSecret(name, secretKeys) && !element.group(3).isBlank()
              ? "<"
                  + element.group(1)
                  + (element.group(2) == null ? "" : element.group(2))
                  + ">******</"
                  + element.group(1)
                  + ">"
              : element.group();
      element.appendReplacement(out, Matcher.quoteReplacement(replacement));
    }
    element.appendTail(out);
    Matcher pair = NAME_VALUE.matcher(out.toString());
    out = new StringBuilder();
    while (pair.find()) {
      String replacement =
          ConfigExtractor.isSecret(pair.group(2), secretKeys)
              ? pair.group(1) + "******" + pair.group(4)
              : pair.group();
      pair.appendReplacement(out, Matcher.quoteReplacement(replacement));
    }
    pair.appendTail(out);
    Matcher attribute = XML_ATTRIBUTE.matcher(out.toString());
    out = new StringBuilder();
    while (attribute.find()) {
      String replacement =
          ConfigExtractor.isSecret(attribute.group(1), secretKeys) && !attribute.group(4).isEmpty()
              ? attribute.group(1)
                  + attribute.group(2)
                  + attribute.group(3)
                  + "******"
                  + attribute.group(3)
              : attribute.group();
      attribute.appendReplacement(out, Matcher.quoteReplacement(replacement));
    }
    attribute.appendTail(out);
    return out.toString();
  }

  private FileSecrets() {}
}

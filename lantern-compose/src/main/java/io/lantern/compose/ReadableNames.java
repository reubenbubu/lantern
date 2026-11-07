package io.lantern.compose;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A service's or frontend's name in words, for a reader (#933): "edge-gateway" is "Edge gateway",
 * "customer-portal-bff" is "Customer portal BFF". The C4 report draws it so, the build's name small
 * beneath; title-casing is the fallback until a readable name comes with the description.
 */
public final class ReadableNames {

  /** Acronyms a build's name may hold, written in capitals. */
  private static final Set<String> ACRONYMS =
      Set.of(
          "api", "bff", "ui", "ux", "sms", "sla", "crm", "erp", "iot", "sso", "id", "db", "mq",
          "ai", "ml", "http", "sql", "jms", "kpi", "pos", "hr", "b2b", "b2c", "qa", "pdf", "otp",
          "gps");

  private static final Pattern WORD = Pattern.compile("[a-z][a-z0-9]*");

  private static final Pattern TECHNICAL = Pattern.compile("[a-z0-9]+(?:[-_.][a-z0-9]+)+");

  private ReadableNames() {}

  /** The name in words, or as it is if it already is, or is not a build's name of words. */
  public static String of(String name) {
    if (name != null && WORD.matcher(name).matches()) {
      return ACRONYMS.contains(name)
          ? name.toUpperCase(Locale.ROOT)
          : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
    if (name == null || !TECHNICAL.matcher(name).matches()) {
      return name;
    }
    StringBuilder out = new StringBuilder();
    for (String word : name.split("[-_.]")) {
      if (word.isEmpty()) {
        continue;
      }
      String shown = ACRONYMS.contains(word) ? word.toUpperCase(Locale.ROOT) : word;
      if (out.isEmpty()) {
        shown = Character.toUpperCase(shown.charAt(0)) + shown.substring(1);
      } else {
        out.append(' ');
      }
      out.append(shown);
    }
    return out.toString();
  }
}

package io.lantern.link;

import io.lantern.core.fact.Fact;
import io.lantern.link.Deployments.Container;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Values in a deployment environment, the part of value resolution that takes a deployment
 * container's environment as a config overlay (epic {@code docs/epics/001-knowledge-build.md},
 * section 8, Views; ADR 0094, amendment for #588): an environment variable sets the config key it
 * binds to as Spring Boot's relaxed binding does ({@code AUTH_DATASOURCE_HOST} sets {@code
 * auth.datasource.host}), before the unit's config files and a placeholder's default.
 *
 * <p>Value resolution writes what an expression resolves to in each deployment that runs its unit
 * ({@link Literals}); address matching and datasource placing read values in a deployment through
 * this class, so no other part of linking fills placeholders from an environment.
 */
final class EnvironmentValues {

  private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?}");

  private EnvironmentValues() {}

  /** A value filled in from a container's environment, and the config facts it also used. */
  record Filled(String value, Set<Fact> config) {}

  /** The value a container's environment gives a config key, by relaxed binding, or null. */
  static String env(Container container, String key) {
    String wanted = key.replace('.', '_').replace("-", "").toUpperCase(Locale.ROOT);
    for (Map.Entry<String, String> e : container.env().entrySet()) {
      String name = e.getKey();
      if (name.equalsIgnoreCase(key) || name.toUpperCase(Locale.ROOT).equals(wanted)) {
        return e.getValue();
      }
    }
    return null;
  }

  /**
   * A value as written with what a container's environment sets filled in, as Spring Boot resolves
   * it: an environment variable, by relaxed binding, comes before the config and a placeholder's
   * default; a key the environment does not set takes the config's value (resolved again from what
   * it was written as). Null when the environment sets nothing the value uses, or a secret goes
   * into it.
   *
   * @param config the config fact of a key in the view the deployment overlays, or null
   */
  static Filled filled(Container c, String written, Function<String, Fact> config) {
    boolean[] used = {false};
    boolean[] secret = {false};
    Set<Fact> facts = new LinkedHashSet<>();
    Function<String, String> lookup =
        key -> {
          String value = env(c, key);
          if (value != null && !value.isBlank()) {
            used[0] = true;
            return value;
          }
          Fact f = config.apply(key);
          if (f == null) {
            return null;
          }
          if (Claims.masked(f)) {
            secret[0] = true;
          }
          facts.add(f);
          String w = Claims.written(f);
          return w != null ? w : f.object().value();
        };
    String filled = LinkText.substitute(written, lookup);
    return used[0] && !secret[0] ? new Filled(filled, facts) : null;
  }

  /**
   * A text of config with each placeholder the container's environment sets replaced by its value;
   * a value that is itself an unset compose variable ({@code ${NAME}}) is put in as written, so it
   * still says what it is. Null when the environment sets none of its placeholders.
   */
  static String fill(Container container, String text) {
    if (text == null) {
      return null;
    }
    Matcher m = PLACEHOLDER.matcher(text);
    StringBuilder b = new StringBuilder();
    boolean any = false;
    while (m.find()) {
      String value = env(container, m.group(1));
      if (value == null || value.isEmpty()) {
        value = m.group(2) != null ? m.group(2) : m.group();
      } else {
        any = true;
      }
      m.appendReplacement(b, Matcher.quoteReplacement(value));
    }
    m.appendTail(b);
    return any ? b.toString() : null;
  }
}

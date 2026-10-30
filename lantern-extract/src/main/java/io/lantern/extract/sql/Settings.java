package io.lantern.extract.sql;

import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.config.ConfigValue;
import io.lantern.extract.config.ModuleConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a module's Spring Boot config says about how its migrations run: the database they run on,
 * Flyway's placeholders and Liquibase's contexts and parameters. The config of the {@code prod} or
 * {@code production} profile is read where the module has one, since the schema described is the
 * one the deployed application runs on; otherwise the default config.
 *
 * @param technology the database's name as {@link JdbcUrl} gives it, such as {@code PostgreSQL}, or
 *     null if the config does not say
 * @param flywayPlaceholders Flyway's placeholders by name, or null if replacement is off
 * @param flywayPrefix the text that opens a Flyway placeholder
 * @param flywaySuffix the text that closes it
 * @param flywaySchema Flyway's default schema, or null if the config does not set one
 * @param contexts the Liquibase contexts the application runs with, lower case; empty if none are
 *     set, when Liquibase runs every change set
 * @param parameters the Liquibase changelog parameters the config sets
 * @param scriptLocations the classpath locations of the SQL scripts Spring runs, such as {@code
 *     db/h2/schema.sql}, if the config names them; empty if it does not, or names a pattern
 */
record Settings(
    String technology,
    Map<String, String> flywayPlaceholders,
    String flywayPrefix,
    String flywaySuffix,
    String flywaySchema,
    List<String> contexts,
    Map<String, String> parameters,
    List<String> scriptLocations) {

  /** The settings of a module without config: Flyway's and Liquibase's defaults. */
  static final Settings NONE =
      new Settings(null, Map.of(), "${", "}", null, List.of(), Map.of(), List.of());

  private static final List<String> URL_KEYS =
      List.of(
          "spring.flyway.url",
          "spring.liquibase.url",
          "spring.datasource.url",
          "spring.datasource.jdbc-url",
          "spring.datasource.hikari.jdbc-url",
          "spring.r2dbc.url");

  private static final String FLYWAY_PLACEHOLDERS = relaxed("spring.flyway.placeholders.");
  private static final String LIQUIBASE_PARAMETERS = relaxed("spring.liquibase.parameters.");

  /** Flyway's built-in placeholder for its default schema, followed by the dot that qualifies. */
  private static final String DEFAULT_SCHEMA = "flyway:defaultSchema";

  private static final Pattern WRITTEN_PLACEHOLDER = Pattern.compile("\\$\\{([^}:]+)");

  private static final Pattern LIQUIBASE_PROPERTY = Pattern.compile("\\$\\{([^}$]+)}");

  Settings {
    flywayPlaceholders = flywayPlaceholders == null ? null : Map.copyOf(flywayPlaceholders);
    contexts = List.copyOf(contexts);
    parameters = Map.copyOf(parameters);
    scriptLocations = List.copyOf(scriptLocations);
  }

  /**
   * Reads a module's settings.
   *
   * @param config the module's config, or null if it has none
   */
  static Settings of(ModuleConfig config) {
    if (config == null) {
      return NONE;
    }
    String profile =
        config.profiles().contains("prod")
            ? "prod"
            : config.profiles().contains("production") ? "production" : null;
    SortedMap<String, ConfigValue> values = config.values(profile);
    Map<String, ConfigValue> byKey = new LinkedHashMap<>();
    for (ConfigValue v : values.values()) {
      byKey.putIfAbsent(relaxed(v.key()), v);
    }
    String technology = null;
    String driver = value(byKey, "spring.datasource.driver-class-name");
    for (String key : URL_KEYS) {
      String url = value(byKey, key);
      if (url != null) {
        technology = JdbcUrl.of(url, driver).technology();
        if (technology != null) {
          break;
        }
      }
    }
    if (technology == null) {
      technology = Vendors.technology(value(byKey, "spring.sql.init.platform"));
    }

    Map<String, String> placeholders = new LinkedHashMap<>();
    Map<String, String> parameters = new LinkedHashMap<>();
    for (ConfigValue v : values.values()) {
      String key = relaxed(v.key());
      if (!v.resolved()
          || ConfigExtractor.isSecret(v.key())
          || v.used().stream().anyMatch(ConfigExtractor::isSecret)
          || namesSecret(v.written())) {
        continue;
      }
      if (key.startsWith(FLYWAY_PLACEHOLDERS)) {
        placeholders.put(suffix(v.key(), "placeholders."), v.value());
      } else if (key.startsWith(LIQUIBASE_PARAMETERS)) {
        parameters.put(suffix(v.key(), "parameters."), v.value());
      }
    }
    String schema = value(byKey, "spring.flyway.default-schema");
    String schemas = list(byKey, "spring.flyway.schemas");
    if (schema == null && schemas != null) {
      schema = schemas.split(",")[0].strip();
    }
    String prefix = value(byKey, "spring.flyway.placeholder-prefix");
    String suffix = value(byKey, "spring.flyway.placeholder-suffix");
    boolean replace =
        !"false".equalsIgnoreCase(value(byKey, "spring.flyway.placeholder-replacement"));
    List<String> contexts = new ArrayList<>();
    String written = list(byKey, "spring.liquibase.contexts");
    if (written != null) {
      for (String context : written.split(",")) {
        if (!context.isBlank()) {
          contexts.add(context.strip().toLowerCase(Locale.ROOT));
        }
      }
    }
    return new Settings(
        technology,
        replace ? placeholders : null,
        prefix == null ? "${" : prefix,
        suffix == null ? "}" : suffix,
        schema == null || schema.isEmpty() ? null : schema,
        contexts,
        parameters,
        locations(list(byKey, "spring.sql.init.schema-locations")));
  }

  /**
   * A Flyway migration with its placeholders replaced. {@code ${flyway:defaultSchema}} followed by
   * a dot is dropped with it, so a table it qualifies is named as the unqualified tables of the
   * same schema are; elsewhere it becomes the configured default schema. Placeholders without a
   * value, or whose value spans lines, are left as written.
   */
  String flyway(String script) {
    if (flywayPlaceholders == null || !script.contains(flywayPrefix)) {
      return script;
    }
    StringBuilder out = new StringBuilder();
    int i = 0;
    while (i < script.length()) {
      int open = script.indexOf(flywayPrefix, i);
      int close = open < 0 ? -1 : script.indexOf(flywaySuffix, open + flywayPrefix.length());
      if (close < 0) {
        out.append(script, i, script.length());
        break;
      }
      String name = script.substring(open + flywayPrefix.length(), close);
      int end = close + flywaySuffix.length();
      String value = name.contains("\n") ? null : placeholder(name);
      out.append(script, i, open);
      if (name.equals(DEFAULT_SCHEMA) && script.startsWith(".", end)) {
        // Unqualified names land in the default schema too, so a qualified one is written the same
        // way, keeping one name per table (the schema of unqualified names is #468).
        end++;
      } else if (value != null && !value.contains("\n")) {
        out.append(value);
      } else {
        out.append(script, open, end);
      }
      i = end;
    }
    return out.toString();
  }

  private String placeholder(String name) {
    if (name.equals(DEFAULT_SCHEMA)) {
      return flywaySchema;
    }
    String value = flywayPlaceholders.get(name);
    if (value != null) {
      return value;
    }
    // A name written in another case in the config, as relaxed binding allows.
    for (Map.Entry<String, String> e : flywayPlaceholders.entrySet()) {
      if (e.getKey().equalsIgnoreCase(name)) {
        return e.getValue();
      }
    }
    return null;
  }

  /**
   * A text of a Liquibase changelog with its {@code ${...}} properties replaced, each by its first
   * value; properties without one are left as written, as Liquibase leaves them.
   */
  static String expand(String text, Map<String, String> properties) {
    if (text == null || !text.contains("${")) {
      return text;
    }
    Matcher m = LIQUIBASE_PROPERTY.matcher(text);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String value = properties.get(m.group(1).strip());
      m.appendReplacement(
          out, Matcher.quoteReplacement(value == null || value.contains("\n") ? m.group() : value));
    }
    m.appendTail(out);
    return out.toString();
  }

  /**
   * The classpath locations in a list of Spring resource locations, or none if one of them is not a
   * plain classpath file, since what it names cannot be told from the list alone.
   */
  private static List<String> locations(String written) {
    if (written == null) {
      return List.of();
    }
    List<String> locations = new ArrayList<>();
    for (String entry : written.split(",")) {
      String location = entry.strip();
      for (String prefix : List.of("optional:", "classpath*:", "classpath:")) {
        if (location.startsWith(prefix)) {
          location = location.substring(prefix.length());
        }
      }
      if (location.isEmpty()) {
        continue;
      }
      if (location.contains("*") || location.contains("${") || location.contains(":")) {
        return List.of();
      }
      locations.add(location.replaceFirst("^/+", ""));
    }
    return locations;
  }

  /** Whether a file is one of the script locations: on the classpath at that path. */
  boolean isScriptLocation(String path) {
    return scriptLocations.stream().anyMatch(l -> path.endsWith("src/main/resources/" + l));
  }

  /**
   * Whether a value as written names a secret in a placeholder, such as {@code
   * ${DB_PASSWORD:changeme}}, whose default may then stand in for the secret.
   */
  private static boolean namesSecret(String written) {
    Matcher m = WRITTEN_PLACEHOLDER.matcher(written);
    while (m.find()) {
      if (ConfigExtractor.isSecret(m.group(1).strip())) {
        return true;
      }
    }
    return false;
  }

  /** A list setting, written as one comma-separated value or as a YAML list, joined by commas. */
  private static String list(Map<String, ConfigValue> byKey, String key) {
    String value = value(byKey, key);
    if (value != null) {
      return value;
    }
    List<String> items = new ArrayList<>();
    for (int i = 0; value(byKey, key + "[" + i + "]") != null; i++) {
      items.add(value(byKey, key + "[" + i + "]"));
    }
    return items.isEmpty() ? null : String.join(",", items);
  }

  private static String value(Map<String, ConfigValue> byKey, String key) {
    ConfigValue v = byKey.get(relaxed(key));
    return v == null || !v.resolved() || v.value().isBlank() ? null : v.value().strip();
  }

  /** The part of a key after a marker, such as a placeholder's name, as written. */
  private static String suffix(String key, String marker) {
    int at = key.toLowerCase(Locale.ROOT).indexOf(marker);
    String name = at < 0 ? key : key.substring(at + marker.length());
    if (name.startsWith("[") && name.endsWith("]")) {
      name = name.substring(1, name.length() - 1);
    }
    return name;
  }

  /** A key as Spring's relaxed binding compares it: lower case, without dashes or underscores. */
  private static String relaxed(String key) {
    return key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
  }
}

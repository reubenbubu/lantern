package io.lantern.extract.messaging;

import io.lantern.extract.RepoBuild;
import io.lantern.extract.config.CodeConfig;
import io.lantern.extract.config.CodeConfig.CodeValue;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.config.ConfigValue;
import io.lantern.sandbox.BuildModule;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * What a module's config says about its Spring Cloud Stream bindings, in one view: the functions
 * bound, the binder (and so the broker) of each binding, and the binding's properties. Keys are
 * looked up as written in kebab case or in camel case, as Spring Boot binds either. See {@code
 * docs/adr/0040-spring-cloud-stream.md}.
 *
 * <p>Plain settings (a binder's name, an exchange type, a flag) are returned only when recording
 * them exposes no secret. Values recorded as a fact's object or named attributes are returned as
 * {@link ConfigValue}s instead, so the caller resolves and masks them like any other value.
 */
final class StreamConfig {

  static final String BINDINGS = "spring.cloud.stream.bindings.";

  /** Binder types, with the broker each talks to. Other binders are not covered. */
  private static final Map<String, String> BINDER_TYPES =
      Map.of(
          "kafka", MessagingExtractor.KAFKA,
          "kstream", MessagingExtractor.KAFKA,
          "ktable", MessagingExtractor.KAFKA,
          "globalktable", MessagingExtractor.KAFKA,
          "rabbit", MessagingExtractor.RABBITMQ);

  /** Prefixes of config keys that only one binder's applications set, with that binder's broker. */
  private static final Map<String, String> HINTS =
      Map.of(
          "spring.cloud.stream.kafka.", MessagingExtractor.KAFKA,
          "spring.kafka.", MessagingExtractor.KAFKA,
          "spring.cloud.stream.rabbit.", MessagingExtractor.RABBITMQ,
          "spring.rabbitmq.", MessagingExtractor.RABBITMQ);

  private final CodeConfig.View config;
  private final List<String> jars;

  /**
   * @param config the module's config
   * @param repo the module's repository, with its build if any
   * @param directory the module's directory
   */
  StreamConfig(CodeConfig.View config, RepoBuild repo, String directory) {
    this.config = config;
    List<String> names = new ArrayList<>();
    if (repo.build() != null) {
      for (BuildModule m : repo.build().modules()) {
        if (m.path().equals(directory)) {
          for (String entry : m.classpath()) {
            names.add(entry.substring(entry.lastIndexOf('/') + 1));
          }
        }
      }
    }
    this.jars = List.copyOf(names);
  }

  /**
   * Whether the module uses Spring Cloud Stream: its config sets it up, or it is on the classpath.
   */
  boolean inUse(String profile) {
    for (String key : config.values(profile).keySet()) {
      if (key.startsWith("spring.cloud.stream.") || key.startsWith("spring.cloud.function.")) {
        return true;
      }
    }
    return jars.stream().anyMatch(j -> j.startsWith("spring-cloud-stream-"));
  }

  /**
   * The function definition: each function bound, as the functions it composes ({@code a|b}), or
   * null when none is set.
   */
  List<List<String>> definition(String profile) {
    String written =
        setting(
            profile, "spring.cloud.function.definition", "spring.cloud.stream.function.definition");
    if (written == null) {
      return null;
    }
    List<List<String>> functions = new ArrayList<>();
    for (String item : written.split("[;,]")) {
      List<String> composed =
          Arrays.stream(item.split("\\|")).map(String::trim).filter(s -> !s.isEmpty()).toList();
      if (!composed.isEmpty()) {
        functions.add(composed);
      }
    }
    return functions;
  }

  /** A function binding's name, or the name it is given instead ({@code function.bindings}). */
  String alias(String profile, String binding) {
    String alias =
        setting(
            profile,
            "spring.cloud.stream.function.bindings." + binding,
            "spring.cloud.stream.function.bindings[" + binding + "]");
    return alias == null || alias.isBlank() ? binding : alias.trim();
  }

  /**
   * The indexes of a function's input or output bindings the config sets properties for, such as 1
   * for {@code process-in-1}.
   */
  SortedSet<Integer> indexes(String profile, String function, boolean input) {
    String prefix = BINDINGS + function + (input ? "-in-" : "-out-");
    SortedSet<Integer> indexes = new TreeSet<>();
    for (String key : config.values(profile).keySet()) {
      if (key.startsWith(prefix)) {
        String rest = key.substring(prefix.length());
        int dot = rest.indexOf('.');
        String digits = dot < 0 ? rest : rest.substring(0, dot);
        if (digits.matches("\\d{1,3}")) {
          indexes.add(Integer.parseInt(digits));
        }
      }
    }
    return indexes;
  }

  /**
   * The broker a binding goes to: through the binder given (by {@code StreamBridge}), else the
   * binding's own binder, else the default binder, else the one binder configured, on the
   * classpath, or that the config's keys point to. Null if none can be told or it is not Kafka or
   * RabbitMQ.
   *
   * @param binding the binding's name, or null if not known
   * @param binder a binder's name given in code, or null
   */
  String broker(String profile, String binding, String binder) {
    if (binder != null) {
      String broker = brokerOf(profile, binder);
      if (broker != null) {
        return broker;
      }
    }
    String named = binding == null ? null : setting(profile, BINDINGS + binding + ".binder");
    if (named == null) {
      named =
          setting(
              profile, "spring.cloud.stream.default-binder", "spring.cloud.stream.defaultBinder");
    }
    if (named != null) {
      return brokerOf(profile, named);
    }
    SortedSet<String> configured = new TreeSet<>();
    for (String key : config.values(profile).keySet()) {
      if (key.startsWith("spring.cloud.stream.binders.") && key.endsWith(".type")) {
        String type = setting(profile, key);
        configured.add(type == null ? "" : BINDER_TYPES.getOrDefault(type.trim(), ""));
      }
    }
    if (!configured.isEmpty()) {
      return configured.size() == 1 && !configured.contains("") ? configured.first() : null;
    }
    Set<String> found = new TreeSet<>();
    for (String jar : jars) {
      if (jar.startsWith("spring-cloud-stream-binder-kafka")) {
        found.add(MessagingExtractor.KAFKA);
      } else if (jar.startsWith("spring-cloud-stream-binder-rabbit")) {
        found.add(MessagingExtractor.RABBITMQ);
      }
    }
    if (found.isEmpty()) {
      for (String key : config.values(profile).keySet()) {
        HINTS.forEach(
            (prefix, broker) -> {
              if (key.startsWith(prefix)) {
                found.add(broker);
              }
            });
      }
    }
    return found.size() == 1 ? found.iterator().next() : null;
  }

  /** The broker of a binder named in config, or of the binder type it names, or null. */
  private String brokerOf(String profile, String binder) {
    String name = binder.trim();
    String type = setting(profile, "spring.cloud.stream.binders." + name + ".type");
    return BINDER_TYPES.get(type != null ? type.trim() : name);
  }

  /**
   * A binding's property ({@code spring.cloud.stream.bindings.<binding>.<property>}), else the
   * default for all bindings ({@code spring.cloud.stream.default.<property>}) if it has one, as
   * config holds it; null if not set.
   *
   * @param property the property in kebab case
   * @param withDefault whether the default for all bindings applies
   */
  ConfigValue property(String profile, String binding, String property, boolean withDefault) {
    ConfigValue v = value(profile, BINDINGS + binding + ".", property);
    return v != null || !withDefault ? v : value(profile, "spring.cloud.stream.default.", property);
  }

  /**
   * A binder-specific property of a binding ({@code spring.cloud.stream.<binder>.bindings.<binding>
   * .<side>.<property>}), else its default for all bindings, as config holds it; null if not set.
   *
   * @param binder {@code kafka} or {@code rabbit}
   * @param side {@code consumer} or {@code producer}
   * @param property the property in kebab case
   */
  ConfigValue binderProperty(
      String profile, String binder, String binding, String side, String property) {
    String base = "spring.cloud.stream." + binder + ".";
    ConfigValue v = value(profile, base + "bindings." + binding + "." + side + ".", property);
    return v != null ? v : value(profile, base + "default." + side + ".", property);
  }

  /** A property's value as a plain setting: null if not set or recording it exposes a secret. */
  static String plain(ConfigValue v) {
    return v == null || exposesSecret(v) ? null : v.value();
  }

  private ConfigValue value(String profile, String prefix, String kebab) {
    Map<String, ConfigValue> values = config.values(profile);
    ConfigValue v = values.get(prefix + kebab);
    if (v == null) {
      v = values.get(prefix + camel(kebab));
    }
    return v == null || ConfigExtractor.isSecret(v.key()) ? null : v;
  }

  /** The first of the keys set, as a plain setting. */
  private String setting(String profile, String... keys) {
    Map<String, ConfigValue> values = config.values(profile);
    for (String key : keys) {
      ConfigValue v = values.get(key);
      if (v != null) {
        return plain(v);
      }
    }
    return null;
  }

  private static boolean exposesSecret(ConfigValue v) {
    return ConfigExtractor.isSecret(v.key())
        || CodeValue.of(v.value(), v.written(), v.unresolved(), v.used()).masked();
  }

  private static String camel(String kebab) {
    StringBuilder out = new StringBuilder();
    boolean upper = false;
    for (char c : kebab.toCharArray()) {
      if (c == '-') {
        upper = true;
      } else {
        out.append(upper ? Character.toUpperCase(c) : c);
        upper = false;
      }
    }
    return out.toString();
  }
}

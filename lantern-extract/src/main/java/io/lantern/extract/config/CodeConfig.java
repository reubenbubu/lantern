package io.lantern.extract.config;

import io.lantern.extract.RepoBuild;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * The config that resolves placeholders written in code, for the extractors that read code (Spring,
 * HTTP clients), each repository's config read once. A module's code uses its own config; a module
 * without any (a library) uses the config of the one module of its repository that has config, if
 * there is exactly one, else none (see {@code docs/adr/0035-spring-extractor.md}).
 */
public final class CodeConfig {

  private final Map<RepoBuild, List<ModuleConfig>> configs = new HashMap<>();
  private final Set<String> known;

  /** Config where only the key name decides what is secret. */
  public CodeConfig() {
    this(Set.of());
  }

  /**
   * Config where the given keys are secret too.
   *
   * @param secretKeys config keys known to hold a secret, such as {@link
   *     ConfigExtractor#jwtSecretKeys}
   */
  public CodeConfig(Set<String> secretKeys) {
    this.known = ConfigExtractor.normalizedKeys(secretKeys);
  }

  /** Config for one module's code, or for none. */
  public final class View {

    private final ModuleConfig config;

    private View(ModuleConfig config) {
      this.config = config;
    }

    /** Whether a key names a secret, by its name or because the code uses it as one. */
    public boolean isSecret(String key) {
      return ConfigExtractor.isSecret(key, known);
    }

    /** A value read from this config as it may be recorded: see {@link CodeValue}. */
    public CodeValue value(
        String value, String written, SortedSet<String> unresolved, SortedSet<String> used) {
      return CodeValue.of(value, written, unresolved, used, known);
    }

    /** A resolved text as it may be recorded: see {@link CodeValue}. */
    public CodeValue value(Resolution r, String written) {
      return value(r.value(), written, r.unresolved(), r.used());
    }

    /** The views to resolve placeholders in: the default view (null), then each profile. */
    public List<String> views() {
      List<String> views = new ArrayList<>();
      views.add(null);
      if (config != null) {
        views.addAll(config.profiles());
      }
      return views;
    }

    /**
     * The config's values with a profile active (null for the default view), empty without config.
     * They are not masked: see {@link ModuleConfig#values}.
     */
    public SortedMap<String, ConfigValue> values(String profile) {
      return config == null ? Collections.emptySortedMap() : config.values(profile);
    }

    /**
     * Resolves the placeholders in a text, in the default view (null) or a profile's; without
     * config only defaults apply. The value is not masked: record it through {@link CodeValue}.
     */
    public Resolution resolve(String profile, String text) {
      return config == null
          ? ConfigExtractor.resolveWithoutConfig(text)
          : config.resolve(profile, text);
    }
  }

  /** The config that applies to the code of a module of a repository. */
  public View forModule(RepoBuild repo, String directory) {
    List<ModuleConfig> all = configs.computeIfAbsent(repo, ConfigExtractor::read);
    for (ModuleConfig c : all) {
      if (c.directory().equals(directory)) {
        return new View(c);
      }
    }
    return new View(all.size() == 1 ? all.getFirst() : null);
  }

  /**
   * A value from code with its placeholders resolved, as it may be recorded: a value that a secret
   * was substituted into (a key {@link ConfigExtractor#isSecret} names) is kept as written instead,
   * and marked masked.
   *
   * @param value the value to record
   * @param written the value as written in the code
   * @param unresolved the keys of the placeholders kept, none when masked
   * @param masked whether a secret was substituted into it
   */
  public record CodeValue(
      String value, String written, SortedSet<String> unresolved, boolean masked) {

    public static CodeValue of(Resolution r, String written) {
      return of(r.value(), written, r.unresolved(), r.used());
    }

    public static CodeValue of(
        String value, String written, SortedSet<String> unresolved, SortedSet<String> used) {
      return of(value, written, unresolved, used, Set.of());
    }

    /**
     * As {@link #of(String, String, SortedSet, SortedSet)}, with keys known to hold a secret too.
     *
     * @param known the keys, from {@link ConfigExtractor#normalizedKeys}
     */
    public static CodeValue of(
        String value,
        String written,
        SortedSet<String> unresolved,
        SortedSet<String> used,
        Set<String> known) {
      if (used.stream().anyMatch(k -> ConfigExtractor.isSecret(k, known))) {
        return new CodeValue(written, written, new TreeSet<>(), true);
      }
      return new CodeValue(value, written, new TreeSet<>(unresolved), false);
    }
  }
}

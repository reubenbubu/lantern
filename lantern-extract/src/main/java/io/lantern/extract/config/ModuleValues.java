package io.lantern.extract.config;

import io.lantern.core.fact.EntityId;
import java.util.List;
import java.util.SortedMap;

/**
 * One module's config values in each view, as the readings that run in linking take them (ADR
 * 0094): read by linking from the {@code config-value} facts, or, in the extractors' tests, from
 * the module's files ({@link #of(ModuleConfig)}).
 */
public interface ModuleValues {

  /** The module. */
  EntityId module();

  /** The name of the module's repository. */
  String repo();

  /** The profiles the config differs in. */
  List<String> profiles();

  /** The values with a profile active (null for the default view), resolved, by key. */
  SortedMap<String, ConfigValue> values(String view);

  /** A module's config as read from its files. */
  static ModuleValues of(ModuleConfig config) {
    return new ModuleValues() {
      @Override
      public EntityId module() {
        return config.module();
      }

      @Override
      public String repo() {
        return config.repo().repo().name();
      }

      @Override
      public List<String> profiles() {
        return List.copyOf(config.profiles());
      }

      @Override
      public SortedMap<String, ConfigValue> values(String view) {
        return config.values(view);
      }
    };
  }
}

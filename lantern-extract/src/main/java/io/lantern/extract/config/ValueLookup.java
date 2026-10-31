package io.lantern.extract.config;

import io.lantern.extract.config.CodeConfig.CodeValue;
import java.util.List;
import java.util.SortedMap;

/**
 * One module's config as code's values are resolved against it, in each view: read from the
 * module's files ({@link CodeConfig.View}), or by linking from the config facts (ADR 0094).
 */
public interface ValueLookup {

  /** The views: the default view (null), then each profile. */
  List<String> views();

  /** The config's values in a view (null for the default view), resolved, by key; not masked. */
  SortedMap<String, ConfigValue> values(String view);

  /** A text from code resolved in a view, as it may be recorded (masked if a secret is in it). */
  CodeValue value(String view, String written);

  /** A config value as it may be recorded: see {@link CodeValue}. */
  CodeValue value(ConfigValue v);

  /** Whether a key names a secret, by its name or because the code uses it as one. */
  boolean isSecret(String key);
}

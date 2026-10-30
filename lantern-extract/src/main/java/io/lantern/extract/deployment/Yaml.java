package io.lantern.extract.deployment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/** Loose reading of YAML descriptors: what is not the shape asked for is null or empty. */
final class Yaml {

  private Yaml() {}

  /** One YAML document, or null when it does not parse. Merge keys ({@code <<}) are applied. */
  static Object load(String text) {
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(true);
    options.setMaxAliasesForCollections(200);
    try {
      return new org.yaml.snakeyaml.Yaml(new SafeConstructor(options)).load(text);
    } catch (YAMLException | ClassCastException e) {
      return null;
    }
  }

  /** The value as a map with string keys, or null when it is not a map. */
  @SuppressWarnings("unchecked")
  static Map<String, Object> map(Object value) {
    if (!(value instanceof Map<?, ?> m)) {
      return null;
    }
    Map<String, Object> out = new LinkedHashMap<>();
    m.forEach((k, v) -> out.put(String.valueOf(k), v));
    return out;
  }

  /** The value as a list, or an empty list when it is not one. */
  @SuppressWarnings("unchecked")
  static List<Object> list(Object value) {
    return value instanceof List<?> l ? (List<Object>) l : List.of();
  }

  /** A scalar's text, or null for a map, a list or null. */
  static String string(Object value) {
    if (value == null || value instanceof Map<?, ?> || value instanceof List<?>) {
      return null;
    }
    return String.valueOf(value);
  }

  /** The values of {@code over} merged into a copy of {@code base}, maps merged key by key. */
  static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> over) {
    Map<String, Object> out = new LinkedHashMap<>(base);
    over.forEach(
        (k, v) -> {
          Map<String, Object> a = map(out.get(k));
          Map<String, Object> b = map(v);
          out.put(k, a != null && b != null ? merge(a, b) : v);
        });
    return out;
  }
}

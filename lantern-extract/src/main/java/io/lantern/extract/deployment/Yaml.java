package io.lantern.extract.deployment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

/** Loose reading of YAML descriptors: what is not the shape asked for is null or empty. */
final class Yaml {

  private Yaml() {}

  /**
   * YAML 1.1's implicit types without its base-60 integers, so a compose port {@code 22:22} stays
   * the text it is and is not read as the number 1342.
   */
  private static final class Yaml11WithoutSexagesimal extends Resolver {

    private static final Pattern INT =
        Pattern.compile(
            "^(?:[-+]?0b[0-1_]+|[-+]?0[0-7_]+|[-+]?(?:0|[1-9][0-9_]*)|[-+]?0x[0-9a-fA-F_]+)$");

    @Override
    protected void addImplicitResolvers() {
      addImplicitResolver(Tag.BOOL, BOOL, "yYnNtTfFoO");
      addImplicitResolver(Tag.INT, INT, "-+0123456789");
      addImplicitResolver(Tag.FLOAT, FLOAT, "-+0123456789.");
      addImplicitResolver(Tag.MERGE, MERGE, "<");
      addImplicitResolver(Tag.NULL, NULL, "~nN\0");
      addImplicitResolver(Tag.NULL, EMPTY, null);
    }
  }

  /** One YAML document, or null when it does not parse. Merge keys ({@code <<}) are applied. */
  static Object load(String text) {
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(true);
    options.setMaxAliasesForCollections(200);
    try {
      DumperOptions dumper = new DumperOptions();
      return new org.yaml.snakeyaml.Yaml(
              new SafeConstructor(options),
              new Representer(dumper),
              dumper,
              options,
              new Yaml11WithoutSexagesimal())
          .load(text);
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

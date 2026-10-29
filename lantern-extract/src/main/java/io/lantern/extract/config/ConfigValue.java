package io.lantern.extract.config;

import java.util.Collections;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * One configuration property of a module as Spring Boot would see it with a profile active.
 *
 * @param key the property key in Spring Boot's flattened form, such as {@code
 *     spring.application.name} or {@code shop.hosts[0]}
 * @param value the value with its placeholders resolved; a placeholder that cannot be resolved is
 *     kept as written, {@code ${KEY}}
 * @param written the value as the file writes it
 * @param unresolved the keys of the placeholders that could not be resolved, ordered
 * @param used the keys whose values were substituted into the value, directly or through other
 *     values, ordered
 * @param where the file and line that set the value
 */
public record ConfigValue(
    String key,
    String value,
    String written,
    SortedSet<String> unresolved,
    SortedSet<String> used,
    Where where) {

  /**
   * Where a value is written.
   *
   * @param path the file's path relative to the top of the repository
   * @param contentHash the SHA-256 of the file's content
   * @param line the 1-based line of the value, or 0 if unknown
   */
  public record Where(String path, String contentHash, int line) {

    public Where {
      Objects.requireNonNull(path, "path");
      Objects.requireNonNull(contentHash, "contentHash");
    }
  }

  public ConfigValue {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(value, "value");
    Objects.requireNonNull(written, "written");
    Objects.requireNonNull(where, "where");
    unresolved = Collections.unmodifiableSortedSet(new TreeSet<>(unresolved));
    used = Collections.unmodifiableSortedSet(new TreeSet<>(used));
  }

  /** Whether every placeholder in the value was resolved. */
  public boolean resolved() {
    return unresolved.isEmpty();
  }
}

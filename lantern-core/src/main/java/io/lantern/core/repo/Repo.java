package io.lantern.core.repo;

import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A registered repository.
 *
 * @param name the repository name, used in fact evidence and snapshots; letters, digits, {@code .},
 *     {@code _} and {@code -}, starting with a letter or digit
 * @param path the absolute path of the repository's working tree on the host
 * @param settings the per-repository build settings
 */
public record Repo(String name, Path path, RepoSettings settings) {

  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

  public Repo {
    checkName(name);
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(settings, "settings");
    if (!path.isAbsolute()) {
      throw new IllegalArgumentException("Repository path must be absolute: " + path);
    }
  }

  static void checkName(String name) {
    Objects.requireNonNull(name, "name");
    if (!NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("Invalid repository name: '" + name + "'");
    }
  }
}

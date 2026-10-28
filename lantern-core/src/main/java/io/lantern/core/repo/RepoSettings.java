package io.lantern.core.repo;

import java.util.List;
import java.util.Objects;

/**
 * Optional per-repository build settings.
 *
 * @param jdk the JDK version to build with, overriding detection, or {@code null} to detect it
 * @param mavenProfiles Maven profiles to activate, in order
 * @param skipModules Maven modules to leave out of the build, by artifact ID or relative path
 */
public record RepoSettings(Integer jdk, List<String> mavenProfiles, List<String> skipModules) {

  /** No overrides: detect the JDK, default profiles, build every module. */
  public static final RepoSettings DEFAULT = new RepoSettings(null, List.of(), List.of());

  public RepoSettings {
    mavenProfiles = checked("Maven profile", mavenProfiles);
    skipModules = checked("Skipped module", skipModules);
    if (jdk != null && jdk < 1) {
      throw new IllegalArgumentException("JDK version must be positive: " + jdk);
    }
  }

  private static List<String> checked(String what, List<String> values) {
    List<String> copy = List.copyOf(Objects.requireNonNullElse(values, List.of()));
    for (String value : copy) {
      if (value.isBlank() || !value.strip().equals(value)) {
        throw new IllegalArgumentException(
            what + " must not be blank or have surrounding whitespace: '" + value + "'");
      }
    }
    if (copy.stream().distinct().count() != copy.size()) {
      throw new IllegalArgumentException(what + "s must not repeat: " + copy);
    }
    return copy;
  }
}

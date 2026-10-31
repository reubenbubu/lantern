package io.lantern.extract;

import java.util.Objects;

/**
 * An extractor's name and version: the origin name and version of its facts.
 *
 * @param name the extractor's name, such as {@code maven}
 * @param version its version, raised when what it extracts changes
 */
public record ExtractorInfo(String name, String version) {

  public ExtractorInfo {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(version, "version");
    if (name.isBlank() || version.isBlank()) {
      throw new IllegalArgumentException("An extractor needs a name and a version");
    }
  }
}

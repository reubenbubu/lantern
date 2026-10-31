package io.lantern.extract;

import java.util.List;
import java.util.Objects;

/**
 * One unit an extractor extracts (ADR 0092).
 *
 * @param repos the repositories of the unit: those the extractor applies to for a snapshot, or one
 * @param path the file, for a file unit; {@code null} otherwise
 */
public record ExtractionUnit(List<RepoBuild> repos, String path) {

  public ExtractionUnit {
    repos = List.copyOf(Objects.requireNonNull(repos, "repos"));
    if (path != null && repos.size() != 1) {
      throw new IllegalArgumentException("A file unit has one repository");
    }
  }

  /** The repository of a repository or file unit. */
  public RepoBuild repo() {
    if (repos.size() != 1) {
      throw new IllegalStateException("A snapshot unit has no single repository");
    }
    return repos.getFirst();
  }
}

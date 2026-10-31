package io.lantern.core.fact;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * What a fact was observed in: one or more locations (ADR 0091). The first is the one the fact is
 * declared in, so the repository a fact belongs to is that location's, and {@link #repo}, {@link
 * #path}, {@link #lines}, {@link #blob} and {@link #contentHash} are its. Evidence names no commit:
 * the commit of a sighting comes from the snapshot's {@code repo@commit} pairs, so a file that is
 * unchanged in a later commit gives the same evidence.
 *
 * @param locations where the fact was observed, at least one
 */
public record Evidence(List<Location> locations) {

  public Evidence {
    locations = List.copyOf(Objects.requireNonNull(locations, "locations"));
    if (locations.isEmpty()) {
      throw new IllegalArgumentException("Evidence must have at least one location");
    }
  }

  /** Evidence of one declared location, as an extractor gives it. */
  public static Evidence of(String repo, String path, LineRange lines, String contentHash) {
    return new Evidence(List.of(new Location(repo, path, lines, null, contentHash, Role.DECLARED)));
  }

  /** Evidence of the given locations, the first being the declared one. */
  public static Evidence of(Location first, Location... more) {
    List<Location> all = new ArrayList<>();
    all.add(first);
    all.addAll(List.of(more));
    return new Evidence(all);
  }

  /** The location the fact is declared in. */
  public Location declared() {
    return locations.get(0);
  }

  /** The repository name of the declared location. */
  public String repo() {
    return declared().repo();
  }

  /** The file path of the declared location, or {@code null} for a repository-wide fact. */
  public String path() {
    return declared().path();
  }

  /** The line range of the declared location, or {@code null}. */
  public LineRange lines() {
    return declared().lines();
  }

  /** The git blob SHA of the declared location's file, or {@code null}. */
  public String blob() {
    return declared().blob();
  }

  /**
   * The SHA-256 of the declared location's file, kept only when it has no blob, or {@code null}.
   */
  public String contentHash() {
    return declared().contentHash();
  }

  /** A copy whose locations are replaced, in order, by the given function's results. */
  public Evidence map(UnaryOperator<Location> function) {
    return new Evidence(locations.stream().map(function).toList());
  }

  /**
   * One place a fact was observed in.
   *
   * @param repo the repository name
   * @param path the file path relative to the repository root, or {@code null} for a
   *     repository-wide fact
   * @param lines the line range in the file, or {@code null}; requires a path
   * @param blob the git blob SHA of the file (40 or 64 lowercase hex characters), or {@code null};
   *     requires a path
   * @param contentHash the SHA-256 of the file content as 64 lowercase hex characters, or {@code
   *     null}; requires a path, and is given only while the location has no blob
   * @param role what the location shows of the fact
   */
  public record Location(
      String repo, String path, LineRange lines, String blob, String contentHash, Role role) {

    private static final Pattern BLOB = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public Location {
      Objects.requireNonNull(repo, "repo");
      Objects.requireNonNull(role, "role");
      if (repo.isBlank()) {
        throw new IllegalArgumentException("Evidence repo must not be blank");
      }
      if (path != null && path.isBlank()) {
        throw new IllegalArgumentException("Evidence path must be null or not blank");
      }
      if (path == null && (lines != null || blob != null || contentHash != null)) {
        throw new IllegalArgumentException("Evidence lines, blob and content hash require a path");
      }
      if (blob != null && !BLOB.matcher(blob).matches()) {
        throw new IllegalArgumentException("Invalid blob SHA: '" + blob + "'");
      }
      if (contentHash != null && !SHA256.matcher(contentHash).matches()) {
        throw new IllegalArgumentException("Invalid content hash: '" + contentHash + "'");
      }
      if (blob != null && contentHash != null) {
        throw new IllegalArgumentException("Evidence with a blob keeps no content hash");
      }
    }

    /** This location with the file's blob instead of its content hash. */
    public Location withBlob(String blob) {
      return new Location(repo, path, lines, Objects.requireNonNull(blob, "blob"), null, role);
    }
  }

  /** What a location shows of the fact. */
  public enum Role {
    /** Where the fact is declared. */
    DECLARED,
    /** Where a value the fact holds is written. */
    VALUE,
    /** Config the fact's value was resolved against. */
    CONFIG,
    /** A place the fact cites. */
    CITED;

    /** The lowercase name used in JSON. */
    public String jsonName() {
      return name().toLowerCase(Locale.ROOT);
    }

    public static Role fromJsonName(String name) {
      for (Role role : values()) {
        if (role.jsonName().equals(name)) {
          return role;
        }
      }
      throw new IllegalArgumentException("Unknown evidence role: '" + name + "'");
    }
  }

  /** An inclusive range of 1-based line numbers. */
  public record LineRange(int start, int end) {

    public LineRange {
      if (start < 1 || end < start) {
        throw new IllegalArgumentException("Invalid line range: " + start + "-" + end);
      }
    }
  }
}

package io.lantern.compose;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * What part of the knowledge a document covers: one repository, or the system all repositories of
 * the snapshot form. The auto-grouped and custom scopes of the design come with later phases.
 *
 * @param kind the kind of scope
 * @param repo the repository, for a per-repository scope; {@code null} for the system scope
 */
public record Scope(Kind kind, String repo) {

  /** Repository names as the repository registry allows them. */
  private static final Pattern REPO = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

  public Scope {
    Objects.requireNonNull(kind, "kind");
    if (kind == Kind.SYSTEM && repo != null) {
      throw new IllegalArgumentException("A system scope names no repository");
    }
    if (kind == Kind.REPO) {
      if (repo == null) {
        throw new IllegalArgumentException("A repository scope names its repository");
      }
      if (!REPO.matcher(repo).matches()) {
        throw new IllegalArgumentException("Invalid repository name: '" + repo + "'");
      }
    }
  }

  /** Every repository of the snapshot, as one system. */
  public static Scope system() {
    return new Scope(Kind.SYSTEM, null);
  }

  /** One repository. */
  public static Scope repo(String repo) {
    return new Scope(Kind.REPO, repo);
  }

  /** The kind of scope. */
  public enum Kind {
    /** One repository. */
    REPO,
    /** Every repository of the snapshot, as one software system. */
    SYSTEM;

    public String jsonName() {
      return name().toLowerCase(Locale.ROOT);
    }

    public static Kind fromJsonName(String name) {
      Objects.requireNonNull(name, "kind");
      for (Kind kind : values()) {
        if (kind.jsonName().equals(name)) {
          return kind;
        }
      }
      throw new IllegalArgumentException("Unknown scope kind: '" + name + "'");
    }
  }
}

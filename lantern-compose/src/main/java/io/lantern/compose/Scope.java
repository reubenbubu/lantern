package io.lantern.compose;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * What part of the knowledge a document covers. Only the per-repository scope exists so far; the
 * system, auto-grouped and custom scopes of the design come with later phases.
 *
 * @param kind the kind of scope
 * @param repo the repository, for a per-repository scope
 */
public record Scope(Kind kind, String repo) {

  /** Repository names as the repository registry allows them. */
  private static final Pattern REPO = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

  public Scope {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(repo, "repo");
    if (!REPO.matcher(repo).matches()) {
      throw new IllegalArgumentException("Invalid repository name: '" + repo + "'");
    }
  }

  /** One repository. */
  public static Scope repo(String repo) {
    return new Scope(Kind.REPO, repo);
  }

  /** The kind of scope. */
  public enum Kind {
    /** One repository. */
    REPO;

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

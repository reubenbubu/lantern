package io.lantern.extract.maven;

import io.lantern.core.repo.Repo;
import io.lantern.sandbox.BuildResult;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One repository of a snapshot as the Maven extractor sees it: the repository at a commit, and its
 * build in the sandbox if there is one.
 *
 * @param repo the repository; its POMs are read from git at the commit
 * @param commit the full commit hash
 * @param build the stored build of the repository at the commit, or null if there is none
 */
public record RepoBuild(Repo repo, String commit, BuildResult build) {

  private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");

  public RepoBuild {
    Objects.requireNonNull(repo, "repo");
    Objects.requireNonNull(commit, "commit");
    if (!COMMIT.matcher(commit).matches()) {
      throw new IllegalArgumentException("Not a full commit hash: '" + commit + "'");
    }
    if (build != null && (!build.repo().equals(repo.name()) || !build.commit().equals(commit))) {
      throw new IllegalArgumentException(
          "Build of "
              + build.repo()
              + "@"
              + build.commit()
              + " does not belong to "
              + repo.name()
              + "@"
              + commit);
    }
  }
}

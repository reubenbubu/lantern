package io.lantern.sandbox;

import io.lantern.core.repo.Repo;
import java.util.Objects;

/**
 * The build of another repository of the snapshot, whose artifacts a build may use (ADR 0041).
 *
 * @param repo the repository that was built
 * @param build its stored build, at the commit of the snapshot; only a successful one is used
 */
public record UpstreamBuild(Repo repo, BuildResult build) {

  public UpstreamBuild {
    Objects.requireNonNull(repo, "repo");
    Objects.requireNonNull(build, "build");
    if (!repo.name().equals(build.repo())) {
      throw new IllegalArgumentException(
          "The build of " + build.repo() + " is not one of " + repo.name());
    }
  }
}

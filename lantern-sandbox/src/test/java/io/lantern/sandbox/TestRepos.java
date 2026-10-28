package io.lantern.sandbox;

import io.lantern.core.repo.Git;
import io.lantern.core.repo.Repo;
import io.lantern.core.repo.RepoSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/** Turns directories of test files into git repositories with one commit. */
final class TestRepos {

  private TestRepos() {}

  /** A repository holding a copy of the source directory, committed. */
  static Repo create(Path source, Path target, String name, RepoSettings settings)
      throws IOException {
    Files.createDirectories(target);
    try (Stream<Path> paths = Files.walk(source)) {
      for (Path from : paths.toList()) {
        Path to = target.resolve(source.relativize(from).toString());
        if (Files.isDirectory(from)) {
          Files.createDirectories(to);
        } else {
          Files.copy(from, to);
        }
      }
    }
    git(target, "init", "--quiet");
    git(target, "add", "--all");
    git(
        target,
        "-c",
        "user.name=Lantern Test",
        "-c",
        "user.email=test@lantern.invalid",
        "commit",
        "--quiet",
        "--message",
        "Fixture");
    return new Repo(name, target.toRealPath(), settings);
  }

  /** The full hash of the commit checked out in the repository. */
  static String head(Repo repo) {
    return git(repo.path(), "rev-parse", "HEAD");
  }

  private static String git(Path dir, String... args) {
    Git.Result result = Git.run(dir, args);
    if (!result.ok()) {
      throw new IllegalStateException("git " + String.join(" ", args) + ": " + result.err());
    }
    return result.out();
  }
}

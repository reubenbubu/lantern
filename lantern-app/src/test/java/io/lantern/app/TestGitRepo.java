package io.lantern.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** A temporary git repository for tests, driven with the git command line tool. */
final class TestGitRepo {

  private final Path dir;

  private TestGitRepo(Path dir) {
    this.dir = dir;
  }

  /** Creates an empty repository in the directory, on a branch named {@code main}. */
  static TestGitRepo init(Path dir) {
    TestGitRepo repo = new TestGitRepo(dir);
    repo.git("init", "--quiet", "--initial-branch=main");
    return repo;
  }

  Path dir() {
    return dir;
  }

  /** Writes a file and commits it, returning the new commit's full hash. */
  String commit(String file, String content) {
    try {
      Path path = dir.resolve(file);
      Files.createDirectories(path.getParent());
      Files.writeString(path, content);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    git("add", file);
    git("commit", "--quiet", "-m", "Change " + file);
    return git("rev-parse", "HEAD");
  }

  /** Runs git in the repository and returns its trimmed output. */
  String git(String... args) {
    List<String> command =
        new ArrayList<>(
            List.of(
                "git",
                "-C",
                dir.toString(),
                "-c",
                "user.name=Test",
                "-c",
                "user.email=test@example.invalid",
                "-c",
                "commit.gpgsign=false",
                "-c",
                "tag.gpgsign=false",
                "-c",
                "core.hooksPath=/dev/null"));
    command.addAll(List.of(args));
    try {
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (process.waitFor() != 0) {
        throw new IllegalStateException(command + " failed: " + out);
      }
      return out.strip();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}

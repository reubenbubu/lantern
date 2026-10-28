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
    repo.git("init", "--quiet");
    repo.git("symbolic-ref", "HEAD", "refs/heads/main");
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
      ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
      // Never touch another repository (such as the Lantern checkout) or the user's git config.
      for (String variable :
          List.of(
              "GIT_DIR",
              "GIT_WORK_TREE",
              "GIT_INDEX_FILE",
              "GIT_OBJECT_DIRECTORY",
              "GIT_ALTERNATE_OBJECT_DIRECTORIES",
              "GIT_COMMON_DIR",
              "GIT_NAMESPACE")) {
        builder.environment().remove(variable);
      }
      builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
      builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
      Process process = builder.start();
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

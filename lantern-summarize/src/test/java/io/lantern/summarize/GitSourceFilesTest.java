package io.lantern.summarize;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.repo.Git;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitSourceFilesTest {

  @TempDir static Path repo;
  private static String first;
  private static GitSourceFiles files;

  private static String git(String... args) {
    Git.Result result = Git.run(repo, args);
    assertThat(result.ok()).as(result.err()).isTrue();
    return result.out();
  }

  @BeforeAll
  static void commit() throws Exception {
    git("init", "--quiet");
    Files.writeString(repo.resolve("A.java"), "class A {}\n");
    Files.write(repo.resolve("big.txt"), new byte[1024 * 1024 + 1]);
    git("add", "--all");
    String[] commit = {
      "-c",
      "user.name=Test",
      "-c",
      "user.email=test@lantern.invalid",
      "-c",
      "commit.gpgsign=false",
      "commit",
      "--quiet",
      "--message",
      "First"
    };
    git(commit);
    first = git("rev-parse", "HEAD");
    // The working tree now differs from the commit; reads are at the commit asked for.
    Files.writeString(repo.resolve("A.java"), "class A { int changed; }\n");
    files = new GitSourceFiles(name -> name.equals("r") ? Optional.of(repo) : Optional.empty());
  }

  @Test
  void readsAFileAtTheCommitWithItsTrailingNewline() {
    assertThat(files.read("r", first, "A.java"))
        .hasValueSatisfying(
            content ->
                assertThat(new String(content, StandardCharsets.UTF_8)).isEqualTo("class A {}\n"));
  }

  @Test
  void readsNothingItCannotOrShouldNot() {
    assertThat(files.read("r", first, "Missing.java")).isEmpty();
    assertThat(files.read("other", first, "A.java")).isEmpty();
    assertThat(files.read("r", first, "big.txt")).isEmpty();
    assertThat(files.read("r", first, "-p")).isEmpty();
    assertThat(files.read("r", first, "x:A.java")).isEmpty();
    assertThat(files.read("r", "b".repeat(40), "A.java")).isEmpty();
  }
}

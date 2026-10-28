package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lantern.core.repo.Repo;
import io.lantern.core.repo.RepoRegistry;
import io.lantern.core.repo.RepoSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Registers temporary git repositories and resolves their commits. The database is shared with
 * other tests, so each test uses repository names of its own.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RepoRegistryIT {

  @Autowired RepoRegistry registry;

  @TempDir Path temp;

  static String unique(String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
  }

  @Test
  void registersARepositoryWithItsSettings() throws Exception {
    TestGitRepo git = TestGitRepo.init(Files.createDirectory(temp.resolve("orders")));
    git.commit("README.md", "orders");
    String name = unique("orders");
    RepoSettings settings = new RepoSettings(17, List.of("ci", "fast"), List.of("orders-it"));

    Repo repo = registry.register(name, git.dir(), settings);

    assertThat(repo.path()).isEqualTo(git.dir().toRealPath());
    assertThat(registry.find(name)).contains(repo);
    assertThat(registry.all()).contains(repo);
    assertThat(registry.find(unique("missing"))).isEmpty();
  }

  @Test
  void registeringAgainUpdatesPathAndSettings() throws Exception {
    TestGitRepo first = TestGitRepo.init(Files.createDirectory(temp.resolve("first")));
    TestGitRepo second = TestGitRepo.init(Files.createDirectory(temp.resolve("second")));
    String name = unique("billing");

    registry.register(name, first.dir(), new RepoSettings(21, List.of(), List.of()));
    Repo updated = registry.register(name, second.dir(), RepoSettings.DEFAULT);

    assertThat(updated.settings()).isEqualTo(RepoSettings.DEFAULT);
    assertThat(registry.find(name)).contains(updated);
    assertThat(registry.find(name).orElseThrow().path()).isEqualTo(second.dir().toRealPath());
  }

  @Test
  void refusesPathsThatAreNotTheTopOfAGitWorkingTree() throws Exception {
    Path plain = Files.createDirectory(temp.resolve("plain"));
    TestGitRepo git = TestGitRepo.init(Files.createDirectory(temp.resolve("repo")));
    Path inside = Files.createDirectory(git.dir().resolve("sub"));
    String name = unique("bad");

    assertThatThrownBy(() -> registry.register(name, plain, RepoSettings.DEFAULT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not a git working tree");
    assertThatThrownBy(() -> registry.register(name, inside, RepoSettings.DEFAULT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("top of its working tree");
    assertThatThrownBy(() -> registry.register(name, temp.resolve("nowhere"), RepoSettings.DEFAULT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not exist");
    assertThatThrownBy(() -> registry.register("has space", git.dir(), RepoSettings.DEFAULT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid repository name");
    assertThat(registry.find(name)).isEmpty();
  }

  @Test
  void resolvesHeadBranchesTagsAndCommits() throws Exception {
    TestGitRepo git = TestGitRepo.init(Files.createDirectory(temp.resolve("shipping")));
    String first = git.commit("a.txt", "one");
    git.git("tag", "v1");
    String second = git.commit("a.txt", "two");
    String name = unique("shipping");
    registry.register(name, git.dir(), RepoSettings.DEFAULT);

    assertThat(registry.resolve(name, RepoRegistry.HEAD)).isEqualTo(second).hasSize(40);
    assertThat(registry.resolve(name, "main")).isEqualTo(second);
    assertThat(registry.resolve(name, "v1")).isEqualTo(first);
    assertThat(registry.resolve(name, first)).isEqualTo(first);
    assertThat(registry.resolve(name, first.substring(0, 10))).isEqualTo(first);

    git.git("checkout", "--quiet", first);
    assertThat(registry.resolve(name, RepoRegistry.HEAD)).isEqualTo(first);
  }

  @Test
  void refusesUnknownRevisionsAndRepositories() throws Exception {
    TestGitRepo git = TestGitRepo.init(Files.createDirectory(temp.resolve("empty")));
    String name = unique("empty");
    registry.register(name, git.dir(), RepoSettings.DEFAULT);

    assertThatThrownBy(() -> registry.resolve(name, RepoRegistry.HEAD))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not a commit");
    git.commit("a.txt", "one");
    assertThatThrownBy(() -> registry.resolve(name, "no-such-branch"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not a commit");
    assertThatThrownBy(() -> registry.resolve(name, "--all"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid revision");
    assertThatThrownBy(() -> registry.resolve(unique("unregistered"), RepoRegistry.HEAD))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not registered");
  }
}

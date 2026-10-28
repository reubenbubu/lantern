package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lantern.core.repo.RepoRegistry;
import io.lantern.core.repo.RepoSettings;
import io.lantern.core.snapshot.Snapshot;
import io.lantern.core.snapshot.SnapshotStore;
import io.lantern.core.vocabulary.VocabularyRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Creates snapshots of temporary git repositories. The database is shared with other tests and
 * snapshots cannot be deleted, so tests compare snapshot numbers relative to each other.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SnapshotStoreIT {

  @Autowired SnapshotStore snapshots;
  @Autowired RepoRegistry repos;
  @Autowired VocabularyRegistry vocabulary;

  @Value("${lantern.version}")
  String toolVersion;

  @TempDir Path temp;

  private String register(String prefix) throws Exception {
    String name = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    TestGitRepo git = TestGitRepo.init(Files.createDirectory(temp.resolve(name)));
    git.commit("pom.xml", "<project/>");
    repos.register(name, git.dir(), RepoSettings.DEFAULT);
    return name;
  }

  @Test
  void recordsCommitsVocabularyRevisionAndToolVersion() throws Exception {
    TestGitRepo orders = TestGitRepo.init(Files.createDirectory(temp.resolve("orders")));
    String old = orders.commit("a.txt", "one");
    String head = orders.commit("a.txt", "two");
    TestGitRepo billing = TestGitRepo.init(Files.createDirectory(temp.resolve("billing")));
    String billingHead = billing.commit("b.txt", "one");
    String ordersName = "orders-" + UUID.randomUUID().toString().substring(0, 8);
    String billingName = "billing-" + UUID.randomUUID().toString().substring(0, 8);
    repos.register(ordersName, orders.dir(), RepoSettings.DEFAULT);
    repos.register(billingName, billing.dir(), RepoSettings.DEFAULT);

    Snapshot atHead =
        snapshots.create(Map.of(ordersName, RepoRegistry.HEAD, billingName, RepoRegistry.HEAD));
    Snapshot atOld =
        snapshots.create(Map.of(ordersName, old.substring(0, 12), billingName, "main"));

    assertThat(atHead.commits()).isEqualTo(Map.of(ordersName, head, billingName, billingHead));
    assertThat(atOld.commits()).isEqualTo(Map.of(ordersName, old, billingName, billingHead));
    assertThat(atHead.vocabularyRevision()).isEqualTo(vocabulary.current().revision());
    assertThat(atHead.toolVersion()).isEqualTo(toolVersion).isNotBlank().doesNotContain("@");
    assertThat(atOld.number()).isGreaterThan(atHead.number());
    assertThat(snapshots.find(atHead.number())).contains(atHead);
    assertThat(snapshots.latest().orElseThrow().number()).isGreaterThanOrEqualTo(atOld.number());
    assertThat(snapshots.find(Long.MAX_VALUE)).isEmpty();
  }

  @Test
  void failedCreationStoresNothing() throws Exception {
    String good = register("good");
    long before = snapshots.create(Map.of(good, RepoRegistry.HEAD)).number();

    assertThatThrownBy(() -> snapshots.create(Map.of(good, "no-such-branch")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> snapshots.create(Map.of("never-registered", RepoRegistry.HEAD)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> snapshots.create(Map.of()))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(snapshots.create(Map.of(good, RepoRegistry.HEAD)).number()).isEqualTo(before + 1);
  }

  @Test
  void concurrentCreationsGetDistinctConsecutiveNumbers() throws Exception {
    String repo = register("busy");
    int count = 8;
    List<Long> numbers = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(count)) {
      List<Future<Snapshot>> futures = new ArrayList<>();
      for (int i = 0; i < count; i++) {
        futures.add(pool.submit(() -> snapshots.create(Map.of(repo, RepoRegistry.HEAD))));
      }
      for (Future<Snapshot> future : futures) {
        numbers.add(future.get().number());
      }
    }

    assertThat(numbers).doesNotHaveDuplicates();
    long min = numbers.stream().mapToLong(Long::longValue).min().orElseThrow();
    assertThat(numbers).allSatisfy(n -> assertThat(n).isBetween(min, min + count - 1));
  }
}

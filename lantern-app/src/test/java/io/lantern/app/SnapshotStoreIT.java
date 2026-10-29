package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lantern.core.repo.RepoRegistry;
import io.lantern.core.repo.RepoSettings;
import io.lantern.core.repo.SystemRegistry;
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
 * snapshots cannot be deleted, so tests compare snapshot numbers relative to each other. That
 * assumes no other test creates snapshots at the same time; integration tests run one at a time.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SnapshotStoreIT {

  @Autowired SnapshotStore snapshots;
  @Autowired RepoRegistry repos;
  @Autowired SystemRegistry systems;
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

  /** A new system with the repositories in it. */
  private String system(String... members) {
    String name = "system-" + UUID.randomUUID().toString().substring(0, 8);
    systems.define(name, null);
    systems.join(name, List.of(members));
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
  void carriesForwardEveryRepositoryAtItsLastAnalyzedCommit() throws Exception {
    String ordersName = "orders-" + UUID.randomUUID().toString().substring(0, 8);
    TestGitRepo orders = TestGitRepo.init(Files.createDirectory(temp.resolve(ordersName)));
    String first = orders.commit("a.txt", "one");
    repos.register(ordersName, orders.dir(), RepoSettings.DEFAULT);
    String billing = register("billing");
    String system = system(ordersName, billing);
    analyzed(
        snapshots.createCarryingForward(system, Map.of(ordersName, RepoRegistry.HEAD)), ordersName);
    Snapshot billingOnly =
        snapshots.createCarryingForward(system, Map.of(billing, RepoRegistry.HEAD));
    analyzed(billingOnly, billing);
    String billingHead = billingOnly.commits().get(billing);
    String second = orders.commit("a.txt", "two");

    // orders moved on, but is not named: it stays at the commit it was last analyzed at.
    Snapshot withBilling =
        snapshots.createCarryingForward(system, Map.of(billing, RepoRegistry.HEAD));
    assertThat(withBilling.commits())
        .containsEntry(ordersName, first)
        .containsEntry(billing, billingHead);
    analyzed(withBilling, billing);

    Snapshot withOrders =
        snapshots.createCarryingForward(system, Map.of(ordersName, RepoRegistry.HEAD));
    assertThat(withOrders.commits())
        .containsEntry(ordersName, second)
        .containsEntry(billing, billingHead);
    assertThat(snapshots.find(withOrders.number())).contains(withOrders);
    analyzed(withOrders, ordersName);
    // create names only what it is given.
    assertThat(snapshots.create(Map.of(billing, RepoRegistry.HEAD)).commits())
        .containsOnlyKeys(billing);
  }

  @Test
  void carriesForwardOnlyCommitsMarkedAnalyzed() throws Exception {
    String ordersName = "orders-" + UUID.randomUUID().toString().substring(0, 8);
    TestGitRepo orders = TestGitRepo.init(Files.createDirectory(temp.resolve(ordersName)));
    String first = orders.commit("a.txt", "one");
    repos.register(ordersName, orders.dir(), RepoSettings.DEFAULT);
    String billing = register("billing");
    String never = register("never");
    String system = system(ordersName, billing, never);
    analyzed(
        snapshots.createCarryingForward(system, Map.of(ordersName, RepoRegistry.HEAD)), ordersName);

    // A run at a new commit of orders stops before its facts are stored, so it marks nothing.
    String second = orders.commit("a.txt", "two");
    Snapshot failed =
        snapshots.createCarryingForward(system, Map.of(ordersName, RepoRegistry.HEAD));
    assertThat(failed.commits()).containsEntry(ordersName, second);

    // The next run carries orders forward at the commit last analyzed, not the failed run's.
    Snapshot withBilling =
        snapshots.createCarryingForward(system, Map.of(billing, RepoRegistry.HEAD));
    assertThat(withBilling.commits()).containsEntry(ordersName, first).containsKey(billing);

    // A repository no run analyzed is not carried forward; billing was not marked either.
    snapshots.createCarryingForward(system, Map.of(never, RepoRegistry.HEAD));
    assertThat(
            snapshots
                .createCarryingForward(system, Map.of(ordersName, RepoRegistry.HEAD))
                .commits())
        .containsEntry(ordersName, second)
        .doesNotContainKeys(never, billing);

    // Marking a repository the snapshot does not have is refused and marks nothing.
    analyzed(withBilling, billing);
    String missing = "zz-missing";
    assertThatThrownBy(() -> snapshots.markAnalyzed(failed.number(), List.of(ordersName, missing)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(missing);
    assertThat(snapshots.createCarryingForward(system, Map.of(never, RepoRegistry.HEAD)).commits())
        .containsEntry(ordersName, first)
        .containsEntry(billing, withBilling.commits().get(billing));

    // Once marked (marking again changes nothing), the commit is carried forward.
    analyzed(failed, ordersName, ordersName);
    assertThat(snapshots.createCarryingForward(system, Map.of(never, RepoRegistry.HEAD)).commits())
        .containsEntry(ordersName, second);
  }

  @Test
  void carriesForwardOnlyTheSystemsRepositories() throws Exception {
    String a = register("a");
    String b = register("b");
    String other = register("other");
    String loose = register("loose");
    String system = system(a, b);
    String otherSystem = system(other);
    analyzed(
        snapshots.createCarryingForward(system, Map.of(a, RepoRegistry.HEAD, b, "HEAD")), a, b);
    analyzed(snapshots.createCarryingForward(otherSystem, Map.of(other, RepoRegistry.HEAD)), other);
    analyzed(snapshots.create(Map.of(loose, RepoRegistry.HEAD)), loose);

    // Another system's and no system's repositories are not carried forward.
    Snapshot ofSystem = snapshots.createCarryingForward(system, Map.of(a, RepoRegistry.HEAD));
    assertThat(ofSystem.commits()).containsOnlyKeys(a, b);
    assertThat(snapshots.system(ofSystem.number())).contains(system);
    assertThat(snapshots.system(snapshots.create(Map.of(a, RepoRegistry.HEAD)).number())).isEmpty();

    // Nor is a commit analyzed only in another system's snapshot or in none: a repository that
    // joins a system is carried forward once a snapshot of that system analyzed it (ADR 0071).
    systems.remove(otherSystem, List.of(other));
    systems.join(system, List.of(other));
    systems.join(system, List.of(loose));
    assertThat(snapshots.createCarryingForward(system, Map.of(a, RepoRegistry.HEAD)).commits())
        .containsOnlyKeys(a, b);

    // A removed repository is not carried forward either.
    systems.remove(system, List.of(b));
    assertThat(systems.members(system)).containsExactly(a, loose, other);
    assertThat(snapshots.createCarryingForward(system, Map.of(a, RepoRegistry.HEAD)).commits())
        .containsOnlyKeys(a);
  }

  @Test
  void aRepositoryBelongsToOneSystem() throws Exception {
    String repo = register("shared");
    String first = system(repo);
    String second = system();

    assertThatThrownBy(() -> systems.join(second, List.of(repo)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("belongs to the system " + first);
    assertThatThrownBy(() -> systems.remove(second, List.of(repo)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not belong to the system " + second);
    assertThatThrownBy(() -> systems.join(second, List.of("never-registered")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not registered");
    assertThat(systems.systemOf(repo)).contains(first);

    systems.remove(first, List.of(repo));
    systems.join(second, List.of(repo));
    assertThat(systems.systemOf(repo)).contains(second);
  }

  @Test
  void storesKeepsAndClearsATitle() {
    String system = system();
    assertThat(systems.title(system)).isEmpty();
    systems.define(system, " APA CRM ");
    systems.define(system, null);
    assertThat(systems.title(system)).contains("APA CRM");
    systems.define(system, "");
    assertThat(systems.title(system)).isEmpty();
    assertThatThrownBy(() -> systems.define("no spaces", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** Marks the repositories analyzed in the snapshot, as a run does once their facts are stored. */
  private void analyzed(Snapshot snapshot, String... names) {
    snapshots.markAnalyzed(snapshot.number(), List.of(names));
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

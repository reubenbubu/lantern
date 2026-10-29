package io.lantern.extract.test;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.vocabulary.VocabularyType;
import io.lantern.extract.GitFiles;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.maven.MavenExtractor;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns test sources into facts: the test classes of each module, how many tests each declares, and
 * which test the code's architecture. The sources are read from git at the commit and parsed, never
 * compiled or run. See {@code docs/adr/0064-test-evidence.md}.
 *
 * <p>Entity IDs: {@code test-class:<groupId>:<artifactId>:<binary name>}, keyed by module like a
 * package, since test classes of the same name are common across modules.
 */
public final class TestExtractor {

  /** The extractor's name, recorded as the origin of its facts. */
  public static final String NAME = "test";

  /** The extractor's version; change it whenever the facts it produces change. */
  public static final String VERSION = "1";

  /** The source recorded on the vocabulary revision that adds this extractor's types. */
  public static final String VOCABULARY_SOURCE = "test-extractor";

  /** Entity type of a test class. */
  public static final String TEST_CLASS = "test-class";

  /** Relationship: a test class tests the code of a module. */
  public static final String TESTS = "TESTS";

  /** Confidence of the facts: the annotations are matched by their imports, not resolved. */
  static final double CONFIDENCE = 0.9;

  private static final String RESOURCE = "/vocabulary/test-types.json";
  private static final long MAX_FILE_BYTES = 4L * 1024 * 1024;

  /** How many test files are read from git at once, each by its own git process. */
  private static final int PARALLEL_READS =
      Math.max(2, Runtime.getRuntime().availableProcessors() * 2);

  private static final Semaphore READS = new Semaphore(PARALLEL_READS);

  private static final Origin ORIGIN = new Origin(Origin.Kind.STATIC, NAME, VERSION);

  /** The vocabulary types this extractor's facts use besides the built-in ones. */
  public static List<VocabularyType> vocabulary() {
    try (InputStream in = TestExtractor.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Missing resource " + RESOURCE);
      }
      JsonNode array = FactJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
      List<VocabularyType> types = new ArrayList<>();
      for (JsonNode node : array) {
        types.add(VocabularyType.fromJson(node));
      }
      return List.copyOf(types);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + RESOURCE, e);
    }
  }

  /**
   * Extracts the test classes of a snapshot's repositories. Each repository is read on its own,
   * from git at its commit; builds are not used.
   *
   * @param repos the repositories of the snapshot, each at its commit
   * @param snapshot the snapshot number the facts hold from
   * @return the facts, without duplicates, ordered by subject, predicate, object and ID
   * @throws UncheckedIOException if git cannot be run
   * @throws IllegalStateException if git fails or times out
   */
  public List<Fact> extract(List<RepoBuild> repos, long snapshot) {
    Objects.requireNonNull(repos, "repos");
    Validity validity = Validity.from(snapshot);
    Map<String, Fact> facts = new LinkedHashMap<>();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (RepoBuild repo : repos) {
        extract(repo, validity, facts, executor);
      }
    }
    return facts.values().stream()
        .sorted(
            Comparator.comparing((Fact f) -> f.subject().toString())
                .thenComparing(Fact::predicate)
                .thenComparing(f -> f.object().value())
                .thenComparing(Fact::id))
        .toList();
  }

  /** A test source file read from git, with what it declares. */
  private record Read(String path, String hash, List<TestSources.TestClass> classes) {}

  private void extract(
      RepoBuild repo, Validity validity, Map<String, Fact> facts, ExecutorService executor) {
    SortedMap<String, EntityId> modules = MavenExtractor.moduleDirectories(repo);
    if (modules.isEmpty()) {
      return;
    }
    SortedMap<String, String> roots = MavenExtractor.testSourceDirectories(repo);
    List<String> paths = GitFiles.list(repo, "");
    Set<EntityId> seen = new HashSet<>();
    for (Map.Entry<String, EntityId> module : modules.entrySet()) {
      String root = roots.get(module.getKey());
      if (root == null) {
        continue;
      }
      List<Future<Read>> reads = new ArrayList<>();
      for (String path : paths) {
        if (path.startsWith(root + "/") && path.endsWith(".java") && !isInfo(path)) {
          reads.add(executor.submit(() -> readLimited(repo, path)));
        }
      }
      for (Future<Read> future : reads) {
        Read read = join(future);
        if (read == null) {
          continue;
        }
        for (TestSources.TestClass c : read.classes()) {
          EntityId subject = entity(module.getValue(), c.binaryName());
          // Two files declaring the same class (in two test source roots) give one test class.
          if (subject == null || !seen.add(subject)) {
            continue;
          }
          ObjectNode attributes = FactJson.newObject();
          attributes.put("tests", c.tests());
          if (c.architecture()) {
            attributes.put("architecture", true);
          }
          Fact fact =
              Fact.create(
                  subject,
                  TESTS,
                  FactObject.entity(module.getValue()),
                  attributes,
                  ORIGIN,
                  new Evidence(
                      repo.repo().name(),
                      repo.commit(),
                      read.path(),
                      new Evidence.LineRange(c.startLine(), c.endLine()),
                      read.hash()),
                  CONFIDENCE,
                  validity);
          facts.putIfAbsent(fact.id(), fact);
        }
      }
    }
  }

  private static boolean isInfo(String path) {
    return path.endsWith("/package-info.java") || path.endsWith("/module-info.java");
  }

  /** Reads a file, with at most {@link #PARALLEL_READS} git processes at a time. */
  private static Read readLimited(RepoBuild repo, String path) throws InterruptedException {
    READS.acquire();
    try {
      return read(repo, path);
    } finally {
      READS.release();
    }
  }

  private static Read read(RepoBuild repo, String path) {
    byte[] bytes = GitFiles.read(repo, path, MAX_FILE_BYTES);
    if (bytes == null) {
      return null;
    }
    return new Read(
        path, sha256(bytes), TestSources.read(new String(bytes, StandardCharsets.UTF_8)));
  }

  private static Read join(Future<Read> future) {
    try {
      return future.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while reading test sources", e);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof RuntimeException r) {
        throw r;
      }
      throw new IllegalStateException(e.getCause());
    }
  }

  /** The test class's entity ID, or null if the key is not a valid entity key. */
  private static EntityId entity(EntityId module, String binaryName) {
    try {
      return new EntityId(TEST_CLASS, module.key() + ":" + binaryName);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}

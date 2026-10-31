package io.lantern.extract.test;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.vocabulary.VocabularyType;
import io.lantern.extract.CacheUnit;
import io.lantern.extract.ClaimSink;
import io.lantern.extract.ExtractionContext;
import io.lantern.extract.ExtractionUnit;
import io.lantern.extract.Extractor;
import io.lantern.extract.ExtractorInfo;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns test sources into facts: the test classes of each module, how many tests each declares, and
 * which test the code's architecture. The sources are read from git at the commit and parsed, never
 * compiled or run. See {@code docs/adr/0064-test-evidence.md}.
 *
 * <p>Entity IDs: {@code test-class:<groupId>:<artifactId>:<binary name>}, keyed by module like a
 * package, since test classes of the same name are common across modules.
 */
public final class TestExtractor implements Extractor {

  /** The extractor's name, recorded as the origin of its facts. */
  public static final String NAME = "test";

  /** The extractor's version; change it whenever the facts it produces change. */
  public static final String VERSION = "2";

  /** The source recorded on the vocabulary revision that adds this extractor's types. */
  public static final String VOCABULARY_SOURCE = "test-extractor";

  /** Entity type of a test class. */
  public static final String TEST_CLASS = "test-class";

  /** Relationship: a test class tests the code of a module. */
  public static final String TESTS = "TESTS";

  /** Attribute: a container a module's tests start, such as a Testcontainers database. */
  public static final String TEST_CONTAINER = "test-container";

  /** Attribute: an architecture rule a module's tests declare with ArchUnit's library rules. */
  public static final String ARCHITECTURE_RULE = "architecture-rule";

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

  /** A test source file read from git and parsed. */
  private record Read(String path, String hash, TestSources.Parsed parsed) {}

  private void extract(
      RepoBuild repo, Validity validity, Map<String, Fact> facts, ExecutorService executor) {
    SortedMap<String, EntityId> modules = MavenExtractor.moduleDirectories(repo);
    if (modules.isEmpty()) {
      return;
    }
    SortedMap<String, String> roots = MavenExtractor.testSourceDirectories(repo);
    List<String> paths = GitFiles.list(repo, "");
    Set<EntityId> seen = new HashSet<>();
    Map<EntityId, List<Future<Read>>> reads = new LinkedHashMap<>();
    for (Map.Entry<String, EntityId> module : modules.entrySet()) {
      String root = roots.get(module.getKey());
      if (root == null) {
        continue;
      }
      List<Future<Read>> moduleReads = new ArrayList<>();
      for (String path : paths) {
        if (path.startsWith(root + "/") && path.endsWith(".java") && !isInfo(path)) {
          moduleReads.add(executor.submit(() -> readLimited(repo, path)));
        }
      }
      reads.put(module.getValue(), moduleReads);
    }
    Map<EntityId, List<Read>> files = new LinkedHashMap<>();
    List<TestSources.Parsed> all = new ArrayList<>();
    reads.forEach(
        (module, futures) -> {
          List<Read> read = new ArrayList<>();
          for (Future<Read> future : futures) {
            Read r = join(future);
            if (r != null) {
              read.add(r);
              all.add(r.parsed());
            }
          }
          files.put(module, read);
        });
    // The repository's own test annotations, composed of JUnit's, count wherever it uses them.
    Set<String> composed = TestSources.composed(all);
    for (Map.Entry<EntityId, List<Read>> module : files.entrySet()) {
      for (Read read : module.getValue()) {
        for (TestSources.Container c : read.parsed().containers()) {
          ObjectNode attributes = FactJson.newObject();
          if (c.className() != null) {
            attributes.put("class", c.className());
          }
          if (c.image() != null) {
            attributes.put("image", c.image());
          }
          if (c.technology() != null) {
            attributes.put("technology", c.technology());
          }
          if (c.role() != null) {
            attributes.put("role", c.role());
          }
          if (c.serviceConnection()) {
            attributes.put("serviceConnection", true);
          }
          if (c.className() == null) {
            attributes.put("embedded", true);
          }
          if (!c.topics().isEmpty()) {
            ArrayNode topics = attributes.putArray("topics");
            c.topics().forEach(topics::add);
          }
          add(
              facts,
              repo,
              validity,
              module.getKey(),
              TEST_CONTAINER,
              c.value(),
              attributes,
              read,
              c.line(),
              c.line());
        }
        for (TestSources.Rule r : read.parsed().rules()) {
          ObjectNode attributes = FactJson.newObject();
          if (!r.layers().isEmpty()) {
            // An array, so the layers keep the rule's order, top to bottom.
            ArrayNode layers = attributes.putArray("layers");
            r.layers()
                .forEach(
                    (name, packages) -> {
                      ObjectNode layer = layers.addObject();
                      layer.put("name", name);
                      packages.forEach(layer.putArray("packages")::add);
                    });
          }
          if (!r.access().isEmpty()) {
            ObjectNode access = attributes.putObject("access");
            r.access().forEach((name, rule) -> rule.forEach(access.putArray(name)::add));
          }
          if (r.slices() != null) {
            attributes.put("slices", r.slices());
          }
          if (!r.checks().isEmpty()) {
            ArrayNode checks = attributes.putArray("checks");
            r.checks().forEach(checks::add);
          }
          if (r.because() != null) {
            attributes.put("because", r.because());
          }
          if (r.frozen()) {
            attributes.put("frozen", true);
          }
          if (r.ignored()) {
            attributes.put("ignored", true);
          }
          if (r.unresolved()) {
            attributes.put("unresolved", true);
          }
          add(
              facts,
              repo,
              validity,
              module.getKey(),
              ARCHITECTURE_RULE,
              r.kind(),
              attributes,
              read,
              r.startLine(),
              r.endLine());
        }
      }
    }
    for (Map.Entry<EntityId, List<Read>> module : files.entrySet()) {
      for (Read read : module.getValue()) {
        for (TestSources.TestClass c : read.parsed().classes(composed)) {
          EntityId subject = entity(module.getKey(), c.binaryName());
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
                  FactObject.entity(module.getKey()),
                  attributes,
                  ORIGIN,
                  Evidence.of(
                      repo.repo().name(),
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

  /** Records an attribute fact of a module, citing lines of a test source file. */
  private static void add(
      Map<String, Fact> facts,
      RepoBuild repo,
      Validity validity,
      EntityId module,
      String predicate,
      String value,
      ObjectNode attributes,
      Read read,
      int start,
      int end) {
    Fact fact =
        Fact.create(
            module,
            predicate,
            FactObject.string(value),
            attributes,
            ORIGIN,
            Evidence.of(
                repo.repo().name(), read.path(), new Evidence.LineRange(start, end), read.hash()),
            CONFIDENCE,
            validity);
    facts.putIfAbsent(fact.id(), fact);
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
    TestSources.Parsed parsed = TestSources.parse(new String(bytes, StandardCharsets.UTF_8));
    return parsed == null ? null : new Read(path, sha256(bytes), parsed);
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

  @Override
  public ExtractorInfo info() {
    return new ExtractorInfo(NAME, VERSION);
  }

  @Override
  public CacheUnit unit() {
    return CacheUnit.REPOSITORY;
  }

  @Override
  public void extract(ExtractionContext context, ExtractionUnit unit, ClaimSink sink) {
    sink.emitAll(extract(unit.repos(), context.snapshot()));
  }
}

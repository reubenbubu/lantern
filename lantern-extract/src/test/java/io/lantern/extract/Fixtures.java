package io.lantern.extract;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.repo.Git;
import io.lantern.core.repo.Repo;
import io.lantern.core.repo.RepoSettings;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.core.vocabulary.Vocabulary;
import io.lantern.core.vocabulary.VocabularyType;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.docs.DocsExtractor;
import io.lantern.extract.gateway.GatewayExtractor;
import io.lantern.extract.http.HttpClientExtractor;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.extract.maven.MavenExtractor;
import io.lantern.extract.messaging.MessagingExtractor;
import io.lantern.extract.persistence.PersistenceExtractor;
import io.lantern.extract.spring.SpringExtractor;
import io.lantern.extract.sql.SqlExtractor;
import io.lantern.extract.test.TestExtractor;
import io.lantern.sandbox.MavenMirror;
import io.lantern.sandbox.SandboxSettings;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/** Test repositories made from the fixture projects, and golden fact files to compare with. */
public final class Fixtures {

  private Fixtures() {}

  /**
   * The default sandbox settings, with builds downloading through the Maven mirror set in the
   * Lantern properties file that the system property {@code lantern.test.properties}, or else the
   * environment variable {@code LANTERN_TEST_PROPERTIES}, names, and from Maven Central if there is
   * none (ADR 0030).
   */
  public static SandboxSettings sandboxSettings(Path buildsDirectory) {
    String file = System.getProperty("lantern.test.properties");
    if (file == null || file.isBlank()) {
      file = System.getenv("LANTERN_TEST_PROPERTIES");
    }
    SandboxSettings settings = SandboxSettings.defaults(buildsDirectory);
    if (file == null || file.isBlank()) {
      return settings;
    }
    try {
      return settings.withMavenMirror(MavenMirror.from(Path.of(file)));
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read the test properties " + file, e);
    }
  }

  /** The fixture directory, such as {@code multi-module} or {@code lib-and-service/lib}. */
  public static Path fixture(String name) {
    try {
      return Path.of(Fixtures.class.getResource("/fixtures/" + name).toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  /** A git repository holding a copy of the fixture, committed. */
  public static Repo repo(String fixture, Path target, String name) throws IOException {
    Path source = fixture(fixture);
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
    return commit(target, name);
  }

  private static Repo commit(Path target, String name) throws IOException {
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
    return new Repo(name, target.toRealPath(), RepoSettings.DEFAULT);
  }

  /** A git repository holding the given files (path to content), committed. */
  public static Repo repo(Path target, String name, Map<String, String> files) throws IOException {
    for (Map.Entry<String, String> file : files.entrySet()) {
      Path path = target.resolve(file.getKey());
      Files.createDirectories(path.getParent());
      Files.writeString(path, file.getValue());
    }
    return commit(target, name);
  }

  public static String head(Repo repo) {
    return git(repo.path(), "rev-parse", "HEAD");
  }

  private static String git(Path dir, String... args) {
    Git.Result result = Git.run(dir, args);
    if (!result.ok()) {
      throw new IllegalStateException("git " + String.join(" ", args) + ": " + result.err());
    }
    return result.out();
  }

  /**
   * The vocabulary with the built-in types and the extractors', applied as the app applies them.
   */
  public static Vocabulary vocabulary() {
    Map<String, VocabularyType> types = new TreeMap<>();
    BuiltInTypes.definitions().forEach(t -> types.put(t.name(), t));
    Vocabulary vocabulary = new Vocabulary(1, types.values());
    for (List<VocabularyType> extractor :
        List.of(
            MavenExtractor.vocabulary(),
            JavaExtractor.vocabulary(),
            ConfigExtractor.vocabulary(),
            DocsExtractor.vocabulary(),
            SqlExtractor.vocabulary(),
            SpringExtractor.vocabulary(),
            PersistenceExtractor.vocabulary(),
            HttpClientExtractor.vocabulary(),
            MessagingExtractor.vocabulary(),
            GatewayExtractor.vocabulary(),
            TestExtractor.vocabulary())) {
      vocabulary.changes(extractor).forEach(t -> types.put(t.name(), t));
      vocabulary = new Vocabulary(vocabulary.revision() + 1, types.values());
    }
    return vocabulary;
  }

  /**
   * Checks what every fact must satisfy whatever the fixture: it matches the vocabulary, comes from
   * the extractor, and its evidence names the repository's commit and, for a file in the
   * repository, the file's hash.
   */
  public static void checkEnvelope(
      List<Fact> facts, List<Repo> repos, long snapshot, String extractor, String version)
      throws IOException {
    Vocabulary vocabulary = vocabulary();
    for (Fact fact : facts) {
      vocabulary.validate(fact);
      assertThat(fact.origin().name()).isEqualTo(extractor);
      assertThat(fact.origin().version()).isEqualTo(version);
      assertThat(fact.validity().from()).isEqualTo(snapshot);
      assertThat(fact.validity().to()).isNull();
      Repo repo =
          repos.stream().filter(r -> r.name().equals(fact.evidence().repo())).findFirst().get();
      assertThat(fact.evidence().commit()).isEqualTo(head(repo));
      if (fact.evidence().contentHash() != null) {
        assertThat(fact.evidence().contentHash())
            .isEqualTo(sha256(Files.readAllBytes(repo.path().resolve(fact.evidence().path()))));
      }
    }
  }

  /** One line per fact: what golden files hold, leaving out the commit, hash and fact ID. */
  public static String render(List<Fact> facts) {
    List<String> lines = new ArrayList<>();
    for (Fact f : facts) {
      String where = f.evidence().repo() + ":" + f.evidence().path();
      if (f.evidence().lines() != null) {
        where += "#" + f.evidence().lines().start() + "-" + f.evidence().lines().end();
      }
      lines.add(
          f.subject()
              + " "
              + f.predicate()
              + " "
              + f.object().value()
              + (f.attributes().isEmpty() ? "" : " " + f.attributes())
              + " @"
              + where
              + " "
              + f.confidence());
    }
    return String.join("\n", lines) + "\n";
  }

  /**
   * Checks the facts against a golden file in {@code src/test/resources/golden}, named by its path
   * there, such as {@code maven/lombok.txt}. On a mismatch the actual facts are written to {@code
   * target/golden-actual/<name>}, to compare with or copy over the golden file once checked.
   */
  public static void assertGolden(String name, List<Fact> facts) throws IOException {
    String actual = render(facts);
    String expected = golden(name);
    if (!actual.equals(expected)) {
      Path out = Path.of("target", "golden-actual", name);
      Files.createDirectories(out.getParent());
      Files.writeString(out, actual);
    }
    assertThat(actual).as("facts compared with golden file %s", name).isEqualTo(expected);
  }

  private static String golden(String name) throws IOException {
    try (var in = Fixtures.class.getResourceAsStream("/golden/" + name)) {
      if (in == null) {
        throw new IOException("No golden file " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
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

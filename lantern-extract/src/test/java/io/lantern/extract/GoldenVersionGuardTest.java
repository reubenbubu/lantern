package io.lantern.extract;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.extract.docs.DocsExtractor;
import io.lantern.extract.gateway.GatewayExtractor;
import io.lantern.extract.http.HttpClientExtractor;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.extract.maven.MavenExtractor;
import io.lantern.extract.messaging.MessagingExtractor;
import io.lantern.extract.people.PeopleExtractor;
import io.lantern.extract.persistence.PersistenceExtractor;
import io.lantern.extract.redis.RedisExtractor;
import io.lantern.extract.spring.SpringExtractor;
import io.lantern.extract.sql.SqlExtractor;
import java.io.IOException;
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
import org.junit.jupiter.api.Test;

/**
 * Cached static facts are keyed on the extractor's name and version, so an extractor whose output
 * changes must raise its {@code VERSION}. This test records, in {@code golden/versions.txt}, each
 * extractor's version and a hash of its golden files, and fails when the golden files change
 * without the manifest changing. The manifest alone cannot enforce a version bump: a changed hash
 * must come with a changed {@code VERSION}, and review checks that before the manifest line is
 * updated.
 */
class GoldenVersionGuardTest {

  private static final String MANIFEST = "versions.txt";

  /** The extractor of each folder in {@code src/test/resources/golden}: its name and version. */
  private static final Map<String, String[]> EXTRACTORS =
      Map.ofEntries(
          Map.entry("docs", new String[] {DocsExtractor.NAME, DocsExtractor.VERSION}),
          Map.entry("gateway", new String[] {GatewayExtractor.NAME, GatewayExtractor.VERSION}),
          Map.entry("http", new String[] {HttpClientExtractor.NAME, HttpClientExtractor.VERSION}),
          Map.entry("java", new String[] {JavaExtractor.NAME, JavaExtractor.VERSION}),
          Map.entry("maven", new String[] {MavenExtractor.NAME, MavenExtractor.VERSION}),
          Map.entry(
              "messaging", new String[] {MessagingExtractor.NAME, MessagingExtractor.VERSION}),
          Map.entry(
              "persistence",
              new String[] {PersistenceExtractor.NAME, PersistenceExtractor.VERSION}),
          Map.entry("people", new String[] {PeopleExtractor.NAME, PeopleExtractor.VERSION}),
          Map.entry("redis", new String[] {RedisExtractor.NAME, RedisExtractor.VERSION}),
          Map.entry("spring", new String[] {SpringExtractor.NAME, SpringExtractor.VERSION}),
          Map.entry("sql", new String[] {SqlExtractor.NAME, SqlExtractor.VERSION}));

  @Test
  void everyGoldenFolderBelongsToAnExtractor() throws IOException {
    assertThat(goldenFolders())
        .as("folders in src/test/resources/golden; add a new one to EXTRACTORS")
        .containsExactlyInAnyOrderElementsOf(EXTRACTORS.keySet());
  }

  @Test
  void goldenFilesChangeOnlyWithTheExtractorVersion() throws IOException {
    Map<String, String> recorded = new TreeMap<>();
    for (String line : Files.readAllLines(golden().resolve(MANIFEST))) {
      if (!line.isBlank() && !line.startsWith("#")) {
        String[] parts = line.trim().split(" ");
        recorded.put(parts[0], line.trim());
      }
    }
    List<String> problems = new ArrayList<>();
    for (String folder : new TreeMap<>(EXTRACTORS).keySet()) {
      String[] extractor = EXTRACTORS.get(folder);
      String expected =
          folder + " " + extractor[0] + " " + extractor[1] + " " + hash(golden().resolve(folder));
      String actual = recorded.get(folder);
      if (!expected.equals(actual)) {
        problems.add(
            "golden/%s (extractor %s, VERSION %s): the golden files or the version changed from"
                    .formatted(folder, extractor[0], extractor[1])
                + " the recorded \""
                + actual
                + "\". If the extractor's output changed, raise its VERSION (cached facts are keyed"
                + " on it), then set the line in golden/"
                + MANIFEST
                + " to \""
                + expected
                + "\"");
      }
    }
    assertThat(problems).as("golden files and extractor versions").isEmpty();
  }

  private static Path golden() {
    try {
      return Path.of(GoldenVersionGuardTest.class.getResource("/golden").toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  private static List<String> goldenFolders() throws IOException {
    try (Stream<Path> paths = Files.list(golden())) {
      return paths.filter(Files::isDirectory).map(p -> p.getFileName().toString()).toList();
    }
  }

  /** A SHA-256 over the folder's files: each one's relative path and content, in path order. */
  private static String hash(Path folder) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      List<Path> files;
      try (Stream<Path> paths = Files.walk(folder)) {
        files = paths.filter(Files::isRegularFile).sorted().toList();
      }
      for (Path file : files) {
        digest.update(folder.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(Files.readAllBytes(file));
        digest.update((byte) 0);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}

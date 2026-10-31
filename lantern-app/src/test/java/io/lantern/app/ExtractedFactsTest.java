package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExtractedFactsTest {

  private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

  @TempDir Path directory;

  private static Fact fact(String extractor, String subject, String path, int start, int end) {
    return Fact.create(
        EntityId.parse(subject),
        "SUBSCRIBES_TO",
        FactObject.string("orders"),
        FactJson.newObject(),
        new Origin(Origin.Kind.STATIC, extractor, "1"),
        Evidence.of("shop", path, new Evidence.LineRange(start, end), null),
        1.0,
        Validity.from(3));
  }

  @Test
  void writesEachFactOnceALineInTheEnvelopesJsonGzipped() throws IOException {
    List<Fact> facts =
        List.of(
            fact("messaging", "service:orders", "src/main/java/Orders.java", 12, 14),
            fact("config", "service:billing", "src/main/resources/application.yml", 3, 3));

    // The same fact twice is written once.
    Path file =
        ExtractedFacts.write(
            directory.resolve("shop/3"), List.of(facts.get(0), facts.get(1), facts.get(0)));

    assertThat(file.getFileName().toString()).isEqualTo("extracted.jsonl.gz");
    String text;
    try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
      text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    List<String> lines = text.lines().toList();
    assertThat(lines).hasSize(2);
    assertThat(lines.stream().map(FactJson::read).toList()).isEqualTo(facts);
  }

  @Test
  void writesTheLinkersFactsToTheirOwnFileBesideTheExtractedOnes() throws IOException {
    Fact link =
        Fact.create(
            EntityId.parse("service:orders"),
            "CALLS_HTTP",
            FactObject.entity(EntityId.parse("service:billing")),
            FactJson.newObject(),
            new Origin(Origin.Kind.STATIC, "cross-repo-links", "17"),
            Evidence.of("shop", "src/main/java/Orders.java", new Evidence.LineRange(20, 20), null),
            0.9,
            Validity.from(3));
    Path folder = directory.resolve("shop/3");

    Path extracted =
        ExtractedFacts.write(folder, List.of(fact("messaging", "service:orders", "a.java", 1, 1)));
    Path linked = ExtractedFacts.write(folder, ExtractedFacts.LINKED_FILE, List.of(link, link));

    assertThat(linked.getFileName().toString()).isEqualTo("linked.jsonl.gz");
    assertThat(linked.getParent()).isEqualTo(extracted.getParent());
    String text;
    try (InputStream in = new GZIPInputStream(Files.newInputStream(linked))) {
      text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertThat(text.lines().map(FactJson::read).toList()).containsExactly(link);
  }
}

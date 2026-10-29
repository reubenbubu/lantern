package io.lantern.compose;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.document.Document;
import io.lantern.core.document.DocumentJson;
import io.lantern.core.document.TemplateRef;
import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.snapshot.Snapshot;
import io.lantern.llm.LlmRequest;
import io.lantern.summarize.Summary;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Per-repository documents of the fixture projects, compared with golden files in {@code
 * src/test/resources/golden}: {@code <repo>.json} for the Architecture Explorer and {@code
 * <repo>.brief.json} for the Executive Brief, and {@code summary-prompts.txt} with what the
 * summarizer sent the model for them. On a mismatch the actual document is written to {@code
 * target/golden-actual/}, to compare with or copy over the golden file once checked.
 */
class ComposerGoldenTest {

  private static final JsonMapper PRETTY =
      JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
  private static final TemplateRef TEMPLATE = new TemplateRef("architecture-explorer", "1");
  private static final TemplateRef BRIEF = new TemplateRef("executive-brief", "1");

  @TempDir static Path temp;
  private static FixtureSnapshot fixtures;

  @BeforeAll
  static void extract() throws IOException {
    fixtures = FixtureSnapshot.create(temp);
  }

  private static Document compose(String repo, List<Fact> facts, Map<EntityId, Summary> summaries) {
    return new Composer("0.1.0-test")
        .compose(
            ViewDefinition.perRepo(repo, repo, TEMPLATE), fixtures.snapshot(), facts, summaries);
  }

  @ParameterizedTest
  @ValueSource(strings = {"shop", "orders", "greeting-lib", "greeting-service"})
  void matchesGolden(String repo) throws IOException {
    assertMatchesGolden(compose(repo, fixtures.facts(), fixtures.summaries()), repo + ".json");
  }

  @ParameterizedTest
  @ValueSource(strings = {"shop", "orders", "greeting-lib", "greeting-service"})
  void briefMatchesGolden(String repo) throws IOException {
    Document document =
        new Composer("0.1.0-test")
            .compose(
                ViewDefinition.executiveBrief(repo + ".brief", repo, BRIEF),
                fixtures.snapshot(),
                fixtures.facts(),
                fixtures.summaries());
    assertMatchesGolden(document, repo + ".brief.json");
  }

  /**
   * The C4 report of the system the hand-made runtime facts describe, {@code runtime.c4.json}:
   * every element kind and line the views draw, and a README claim the code contradicts, for the C4
   * report renderer's golden tests too.
   */
  @Test
  void systemReportMatchesGolden() throws IOException {
    Snapshot snapshot =
        new Snapshot(
            1,
            Map.of("shop", TestFacts.COMMIT, "base", TestFacts.COMMIT),
            3,
            "test",
            Instant.EPOCH);
    Document document =
        new Composer("0.1.0-test")
            .compose(
                ViewDefinition.c4System(
                    "system:c4", "System", new TemplateRef("c4-model-sequence-diagrams", "1")),
                snapshot,
                TestFacts.runtimeWithContradictedClaim(),
                Map.of());
    assertMatchesGolden(document, "runtime.c4.json");
  }

  /**
   * The system prompt once, then each request's purpose and user turn. Units are summarized in
   * parallel, so requests are listed in the order of the units' summaries (children first), and a
   * unit's own requests in the order sent.
   */
  @Test
  void summaryPromptsMatchGolden() throws IOException {
    List<String> units = fixtures.summaries().keySet().stream().map(EntityId::toString).toList();
    List<LlmRequest> requests = new ArrayList<>(fixtures.requests());
    requests.sort(
        Comparator.comparingInt(request -> units.indexOf(request.purpose().split(" ")[2])));
    StringBuilder prompts = new StringBuilder();
    prompts.append("##### system\n").append(requests.getFirst().system()).append('\n');
    for (LlmRequest request : requests) {
      assertThat(request.system()).isEqualTo(fixtures.requests().getFirst().system());
      prompts.append("\n##### ").append(request.purpose()).append('\n');
      request.messages().forEach(message -> prompts.append(message.text()));
    }
    assertMatchesGolden(prompts.toString(), "summary-prompts.txt");
    // Entity IDs reach the model only in fact lines (and in the code of source files).
    boolean inCode = false;
    for (String line : prompts.toString().lines().toList()) {
      if (line.startsWith("```")) {
        inCode = !inCode;
      } else if (!inCode && !line.startsWith("[F") && !line.startsWith("##### ")) {
        assertThat(line)
            .as("a prompt line outside facts")
            .doesNotContainPattern(
                "\\b(type|package|module|repo|service|library|artifact|method|field):\\S");
      }
    }
  }

  private static void assertMatchesGolden(Document document, String name) throws IOException {
    assertMatchesGolden(PRETTY.writeValueAsString(DocumentJson.toJson(document)) + "\n", name);
    assertThat(DocumentJson.read(DocumentJson.write(document))).isEqualTo(document);
  }

  private static void assertMatchesGolden(String actual, String name) throws IOException {
    String expected = golden(name);
    if (!actual.equals(expected)) {
      Path out = Path.of("target", "golden-actual", name);
      Files.createDirectories(out.getParent());
      Files.writeString(out, actual);
    }
    assertThat(actual).as("compared with golden file %s", name).isEqualTo(expected);
  }

  @ParameterizedTest
  @ValueSource(strings = {"shop", "orders", "greeting-lib", "greeting-service"})
  void doesNotDependOnInputOrder(String repo) {
    List<Fact> shuffled = new ArrayList<>(fixtures.facts());
    Collections.shuffle(shuffled, new Random(42));
    List<Map.Entry<EntityId, Summary>> entries = new ArrayList<>(fixtures.summaries().entrySet());
    Collections.reverse(entries);
    Map<EntityId, Summary> reversed = new LinkedHashMap<>();
    entries.forEach(e -> reversed.put(e.getKey(), e.getValue()));

    String first = DocumentJson.write(compose(repo, fixtures.facts(), fixtures.summaries()));
    assertThat(DocumentJson.write(compose(repo, fixtures.facts(), fixtures.summaries())))
        .isEqualTo(first);
    assertThat(DocumentJson.write(compose(repo, shuffled, reversed))).isEqualTo(first);
  }

  @Test
  void fixtureCommitsAreReproducible(@TempDir Path other) throws IOException {
    assertThat(FixtureSnapshot.create(other).snapshot().commits())
        .isEqualTo(fixtures.snapshot().commits());
  }

  private static String golden(String name) throws IOException {
    try (InputStream in = ComposerGoldenTest.class.getResourceAsStream("/golden/" + name)) {
      if (in == null) {
        return "";
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}

package io.lantern.app;

import io.lantern.core.fact.Fact;
import io.lantern.harness.Harness;
import io.lantern.harness.checks.WordingJudge;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The wording judge's step of a run (ADR 0111), only when {@code lantern.judge.wording} is set: it
 * judges the texts the roles wrote that hold in the snapshot (Describe's responsibilities and
 * intents, the system's key fact, group names and their responsibilities) on each dimension, and
 * writes every text with its verdicts as {@code wording.jsonl.gz} beside the documents, which the
 * scorer and the label set read. It changes no fact and no document.
 */
final class WordingJudgements {

  /** The file name of the verdicts, in the snapshot's documents directory. */
  static final String FILE = "wording.jsonl.gz";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** A text the judge grades, with the fact it comes from. */
  record Judged(Fact fact, WordingJudge.Text text) {}

  private WordingJudgements() {}

  /** The texts of the facts the judge grades, in the order given, each fact once. */
  static List<Judged> texts(List<Fact> llmFacts) {
    Map<String, Judged> texts = new LinkedHashMap<>();
    for (Fact fact : llmFacts) {
      String text = fact.attributes().path("text").asString("");
      if (text.isBlank()) {
        continue;
      }
      kind(fact)
          .ifPresent(
              kind ->
                  texts.putIfAbsent(
                      fact.id(),
                      new Judged(
                          fact,
                          new WordingJudge.Text(fact.id(), kind, about(fact), text.strip()))));
    }
    return List.copyOf(texts.values());
  }

  /** What kind of text a fact holds, as the judge is shown it, or empty for one not judged. */
  static Optional<String> kind(Fact fact) {
    String type = fact.subject().type();
    return switch (fact.predicate()) {
      case "responsibility" ->
          Optional.of(
              type.equals("group")
                  ? "responsibility of a group of components"
                  : "responsibility of a " + type.replace('-', ' '));
      case "intent" -> Optional.of("intent of a line drawn from a " + type.replace('-', ' '));
      case "key-fact" -> Optional.of("the most important fact about the system");
      case "display-name" -> Optional.of("name of a group of components");
      default -> Optional.empty();
    };
  }

  /** The element a text is about, as the judge is shown it. */
  private static String about(Fact fact) {
    return fact.predicate().equals("key-fact") ? "the system" : fact.subject().toString();
  }

  /**
   * Judges the texts on every dimension and writes them with their verdicts.
   *
   * @param judge the judge's model, as {@code provider/model}
   * @return the file written
   */
  static Path judgeAndWrite(Harness harness, List<Fact> llmFacts, String judge, Path directory) {
    List<Judged> texts = texts(llmFacts);
    WordingJudge wordingJudge = new WordingJudge(harness);
    List<WordingJudge.Text> plain = texts.stream().map(Judged::text).toList();
    Map<WordingJudge.Dimension, Map<String, WordingJudge.Verdict>> verdicts = new LinkedHashMap<>();
    for (WordingJudge.Dimension dimension : WordingJudge.Dimension.values()) {
      verdicts.put(dimension, wordingJudge.judge(dimension, plain));
    }
    List<ObjectNode> lines = new ArrayList<>();
    for (Judged judged : texts) {
      for (WordingJudge.Dimension dimension : WordingJudge.Dimension.values()) {
        lines.add(line(judged, dimension, verdicts.get(dimension).get(judged.fact().id()), judge));
      }
    }
    return write(directory, lines);
  }

  /** One text's line for one dimension. */
  static ObjectNode line(
      Judged judged, WordingJudge.Dimension dimension, WordingJudge.Verdict verdict, String judge) {
    Fact fact = judged.fact();
    ObjectNode line = JSON.createObjectNode();
    line.put("fact", fact.id());
    line.put("subject", fact.subject().toString());
    line.put("predicate", fact.predicate());
    line.put("role", fact.origin().name());
    line.put("kind", judged.text().kind());
    line.put("text", judged.text().text());
    line.put("words", judged.text().words());
    line.put("writer", fact.attributes().path("model").asString(""));
    line.put("judge", judge);
    line.put("dimension", dimension.key());
    line.put("promptVersion", dimension.role().promptVersion());
    if (judged.text().words() > WordingJudge.MAX_WORDS) {
      line.put("state", "tooLong");
    } else if (verdict == null) {
      line.put("state", "notAnswered");
    } else {
      line.put("state", "judged");
      line.put("verdict", verdict.verdict());
      line.put("reasoning", verdict.reasoning());
    }
    return line;
  }

  private static Path write(Path directory, List<ObjectNode> lines) {
    try {
      Files.createDirectories(directory);
      Path file = directory.resolve(FILE);
      try (Writer out =
          new OutputStreamWriter(
              new GZIPOutputStream(Files.newOutputStream(file)), StandardCharsets.UTF_8)) {
        for (ObjectNode line : lines) {
          out.write(line.toString());
          out.write('\n');
        }
      }
      return file;
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot write the wording verdicts in " + directory, e);
    }
  }
}

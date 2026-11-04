package io.lantern.app;

import io.lantern.core.fact.Fact;
import io.lantern.core.types.Types;
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
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(WordingJudgements.class);

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
      case Types.RESPONSIBILITY ->
          Optional.of(
              type.equals(Types.GROUP)
                  ? "responsibility of a group of components"
                  : "responsibility of a " + type.replace('-', ' '));
      // Name groups' line intents are in no judged row: only Describe's intents are judged.
      case Types.INTENT ->
          fact.origin().name().equals("name-groups")
              ? Optional.empty()
              : Optional.of("intent of a line drawn from a " + type.replace('-', ' '));
      case Types.KEY_FACT -> Optional.of("the most important fact about the system");
      case Types.DISPLAY_NAME -> Optional.of("name of a group of components");
      default -> Optional.empty();
    };
  }

  /** The element a text is about, as the judge is shown it. */
  private static String about(Fact fact) {
    return fact.predicate().equals(Types.KEY_FACT) ? "the system" : fact.subject().toString();
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
    // A text the judge's own model wrote is not asked: the judge must not grade its own model.
    List<WordingJudge.Text> plain =
        texts.stream().filter(t -> !byJudge(t, judge)).map(Judged::text).toList();
    if (plain.size() < texts.size()) {
      LOG.warn(
          "The wording judge's model {} wrote {} of the {} texts; they are not judged. Serve"
              + " lantern.llm.roles.wording-judge with a model no writing role uses.",
          judge,
          texts.size() - plain.size(),
          texts.size());
    }
    Map<WordingJudge.Dimension, Map<String, WordingJudge.Verdict>> verdicts = new LinkedHashMap<>();
    for (WordingJudge.Dimension dimension : WordingJudge.Dimension.values()) {
      verdicts.put(dimension, wordingJudge.judge(dimension, plain));
    }
    List<ObjectNode> lines = new ArrayList<>();
    for (Judged judged : texts) {
      for (WordingJudge.Dimension dimension : WordingJudge.Dimension.values()) {
        lines.add(
            byJudge(judged, judge)
                ? sameModel(judged, dimension, judge)
                : line(judged, dimension, verdicts.get(dimension).get(judged.fact().id()), judge));
      }
    }
    return write(directory, lines);
  }

  /** Whether the judge's model wrote the text. */
  static boolean byJudge(Judged judged, String judge) {
    return judge.equals(judged.fact().attributes().path("model").asString(""));
  }

  /** The line of a text the judge's own model wrote: not judged. */
  static ObjectNode sameModel(Judged judged, WordingJudge.Dimension dimension, String judge) {
    ObjectNode line = line(judged, dimension, null, judge);
    line.put("state", "sameModel");
    return line;
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

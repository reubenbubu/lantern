package io.lantern.app;

import io.lantern.harness.AnswerStore;
import io.lantern.harness.BlobLookup;
import io.lantern.harness.Harness;
import io.lantern.harness.Prompts;
import io.lantern.harness.RunBudget;
import io.lantern.harness.checks.WordingJudge;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code judge-labels} command (ADR 0113): judges the owner's labelled wording texts ({@code
 * testsets/wording-labels/}) with the wording judge's current prompts, and prints how many of the
 * texts the labels pass the judge passes, how many the labels fail it fails, and the texts it got
 * wrong, so the owner can tune the judge's prompts in the prompts folder without a full run.
 *
 * <p>Usage: {@code judge-labels [--labels=<folder>] [--dimension=role|specific]
 * [--split=tune|test|all]}. By default it judges both dimensions' {@code tune} texts, the ones
 * meant for tuning; the {@code test} texts are held out to validate the judge, which the scorer
 * does with their weights (ADR 0111). It makes real calls on the wording judge's model, reusing
 * stored answers, so a rerun with unchanged prompts costs nothing.
 */
final class JudgeLabelsCommand implements ApplicationRunner, ExitCodeGenerator {

  /** The first argument that selects this command. */
  static final String NAME = "judge-labels";

  /** The labels folder when none is given, relative to the working directory. */
  static final Path LABELS = Path.of("testsets", "wording-labels");

  private static final Logger LOG = LoggerFactory.getLogger(JudgeLabelsCommand.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final LlmAccess llm;
  private final AnswerStore answers;
  private final RunBudget budget;
  private final PrintStream out;
  private volatile int exitCode;

  JudgeLabelsCommand(LlmAccess llm, AnswerStore answers, RunBudget budget, PrintStream out) {
    this.llm = Objects.requireNonNull(llm, "llm");
    this.answers = Objects.requireNonNull(answers, "answers");
    this.budget = Objects.requireNonNull(budget, "budget");
    this.out = Objects.requireNonNull(out, "out");
  }

  /** A labelled text: the judge's input and the owner's label. */
  record Labelled(WordingJudge.Text text, String label, String split) {}

  /** The judge's agreement with the labels on one dimension. */
  record Agreement(int passLabels, int passed, int failLabels, int failed, List<String> wrong) {

    /** The share of the texts the labels pass that the judge passes. */
    double tpr() {
      return passLabels == 0 ? Double.NaN : (double) passed / passLabels;
    }

    /** The share of the texts the labels fail that the judge fails. */
    double tnr() {
      return failLabels == 0 ? Double.NaN : (double) failed / failLabels;
    }
  }

  /** Whether the command line asks for this command. */
  static boolean requested(String... args) {
    for (String arg : args) {
      if (!arg.startsWith("--")) {
        return arg.equals(NAME);
      }
    }
    return false;
  }

  /** The dimension's labelled texts in the split ({@code tune}, {@code test} or {@code all}). */
  static List<Labelled> labelled(Path folder, WordingJudge.Dimension dimension, String split) {
    Path file = folder.resolve(dimension.key() + ".json");
    JsonNode root;
    try {
      root = JSON.readTree(Files.readString(file));
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + file, e);
    }
    List<Labelled> texts = new ArrayList<>();
    for (JsonNode item : root.path("items")) {
      String label = item.path("label").asString(null);
      String itemSplit = item.path("split").asString("");
      if (!("pass".equals(label) || "fail".equals(label))) {
        continue;
      }
      if (!split.equals("all") && !split.equals(itemSplit)) {
        continue;
      }
      texts.add(
          new Labelled(
              new WordingJudge.Text(
                  item.path("id").asString(),
                  item.path("kind").asString(),
                  item.path("about").asString(),
                  item.path("text").asString()),
              label,
              itemSplit));
    }
    return texts;
  }

  /** How the verdicts agree with the labels; a text with no verdict, or "unknown", is wrong. */
  static Agreement agreement(List<Labelled> texts, Map<String, WordingJudge.Verdict> verdicts) {
    int passLabels = 0;
    int passed = 0;
    int failLabels = 0;
    int failed = 0;
    List<String> wrong = new ArrayList<>();
    for (Labelled labelled : texts) {
      WordingJudge.Verdict verdict = verdicts.get(labelled.text().key());
      String said = verdict == null ? "no verdict" : verdict.verdict();
      boolean right = labelled.label().equals(said);
      if (labelled.label().equals("pass")) {
        passLabels++;
        passed += right ? 1 : 0;
      } else {
        failLabels++;
        failed += right ? 1 : 0;
      }
      if (!right) {
        wrong.add(
            "label "
                + labelled.label()
                + ", judge "
                + said
                + ": \""
                + labelled.text().text()
                + "\" ("
                + labelled.text().kind()
                + ")"
                + (verdict == null || verdict.reasoning() == null
                    ? ""
                    : "\n      " + verdict.reasoning()));
      }
    }
    return new Agreement(passLabels, passed, failLabels, failed, wrong);
  }

  @Override
  public void run(ApplicationArguments args) {
    List<String> words = args.getNonOptionArgs();
    if (words.isEmpty() || !words.getFirst().equals(NAME)) {
      return;
    }
    try {
      Path folder = Path.of(Objects.requireNonNullElse(option(args, "labels"), LABELS.toString()));
      String split = Objects.requireNonNullElse(option(args, "split"), "tune");
      if (!Set.of("tune", "test", "all").contains(split)) {
        throw new IllegalArgumentException("--split is tune, test or all: " + split);
      }
      List<WordingJudge.Dimension> dimensions = dimensions(option(args, "dimension"));
      out.println(promptsLine());
      Harness harness = new Harness(llm.client(), answers, budget, BlobLookup.NONE, Set.of());
      WordingJudge judge = new WordingJudge(harness);
      for (WordingJudge.Dimension dimension : dimensions) {
        List<Labelled> texts = labelled(folder, dimension, split);
        Map<String, WordingJudge.Verdict> verdicts =
            judge.judge(dimension, texts.stream().map(Labelled::text).toList());
        Agreement agreement = agreement(texts, verdicts);
        out.printf(
            Locale.ROOT,
            "%s (%s texts, %d): the judge passes %d of the %d the labels pass (%s) and fails %d"
                + " of the %d the labels fail (%s)%n",
            dimension.key(),
            split,
            texts.size(),
            agreement.passed(),
            agreement.passLabels(),
            percent(agreement.tpr()),
            agreement.failed(),
            agreement.failLabels(),
            percent(agreement.tnr()));
        for (String line : agreement.wrong()) {
          out.println("  " + line);
        }
      }
      exitCode = 0;
    } catch (LlmNotConfiguredException | IllegalArgumentException | IllegalStateException e) {
      out.println("Judging the labels failed: " + e.getMessage());
      exitCode = 1;
    } catch (RuntimeException e) {
      LOG.error("Judging the labels failed", e);
      out.println("Judging the labels failed: " + e.getMessage());
      exitCode = 1;
    }
  }

  /** Where the prompts come from, and which of the judge's files are edited. */
  static String promptsLine() {
    Path folder = Prompts.folder();
    List<String> edited = new ArrayList<>();
    for (String name :
        List.of(
            "common.system.txt",
            "wording-judge-role.task.txt",
            "wording-judge-specific.task.txt",
            "wording-judge.schema.json")) {
      if (Prompts.edited(name)) {
        edited.add(name);
      }
    }
    return "Prompts: "
        + (folder == null ? "built in" : folder.toAbsolutePath().normalize().toString())
        + "; the judge's files edited: "
        + (edited.isEmpty() ? "none" : String.join(", ", edited));
  }

  private static List<WordingJudge.Dimension> dimensions(String given) {
    if (given == null) {
      return List.of(WordingJudge.Dimension.values());
    }
    for (WordingJudge.Dimension dimension : WordingJudge.Dimension.values()) {
      if (dimension.key().equals(given)) {
        return List.of(dimension);
      }
    }
    throw new IllegalArgumentException("--dimension is role or specific: " + given);
  }

  private static String percent(double share) {
    return Double.isNaN(share) ? "none labelled" : Math.round(share * 100) + "%";
  }

  private static String option(ApplicationArguments args, String name) {
    List<String> values = args.getOptionValues(name);
    if (values == null) {
      return null;
    }
    if (values.size() != 1) {
      throw new IllegalArgumentException("Give --" + name + "=<value> once");
    }
    return values.getFirst();
  }

  @Override
  public int getExitCode() {
    return exitCode;
  }
}

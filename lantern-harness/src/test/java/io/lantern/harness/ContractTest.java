package io.lantern.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every role's response contract (ADR 0118) agrees with the prompt that states its sizes: a prompt
 * that asks for another size than its contract fails here.
 */
class ContractTest {

  private static final Path PROMPTS = Path.of("..", "prompts");
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final List<String> WORDS =
      List.of("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten");

  private static List<Path> contracts() throws IOException {
    try (Stream<Path> list = Files.list(PROMPTS)) {
      return list.filter(p -> p.getFileName().toString().endsWith(".contract.json"))
          .sorted()
          .toList();
    }
  }

  @Test
  void everyRoleHasAContractNamedForIt() throws IOException {
    List<String> roles = new ArrayList<>();
    for (Path file : contracts()) {
      String role = file.getFileName().toString().replace(".contract.json", "");
      assertThat(JSON.readTree(file.toFile()).path("role").asString()).isEqualTo(role);
      roles.add(role);
    }
    assertThat(roles)
        .containsExactlyInAnyOrder(
            "summarize",
            "describe-units",
            "describe-elements",
            "purpose",
            "flow-significance",
            "flow-narrative",
            "name-groups",
            "resolve",
            "investigate",
            "support-judge",
            "injection-screen",
            "wording-judge");
  }

  @Test
  void everyStatedSizeIsInItsPromptWithTheContractsNumber() throws IOException {
    int checked = 0;
    for (Path file : contracts()) {
      JsonNode contract = JSON.readTree(file.toFile());
      // The prompt files, one after another, each on its own so a phrase they share is fine.
      StringBuilder prompts = new StringBuilder();
      List<int[]> files = new ArrayList<>();
      contract
          .path("prompts")
          .forEach(
              p -> {
                int from = prompts.length();
                prompts.append(Prompts.text(p.asString()).replaceAll("\\s+", " ")).append('\u0000');
                files.add(new int[] {from, prompts.length()});
              });
      if (prompts.isEmpty()) {
        continue;
      }
      String text = prompts.toString();
      boolean[] covered = new boolean[text.length()];
      for (JsonNode field : contract.path("fields")) {
        String where = file.getFileName() + " " + field.path("name").asString();
        JsonNode words = field.path("words");
        List<String> phrases = new ArrayList<>();
        if (words.has("stated")) {
          phrases.add(words.get("stated").asString());
        }
        words.path("restated").forEach(r -> phrases.add(r.asString()));
        for (String stated : phrases) {
          cover(text, files, stated, covered, where);
          assertThat(stated(stated, 1))
              .as(where + ": \"" + stated + "\" states the requested range")
              .isEqualTo(pair(words.path("requested")));
          checked++;
        }
        JsonNode sentences = field.path("sentences");
        if (sentences.has("stated")) {
          String stated = sentences.get("stated").asString();
          cover(text, files, stated, covered, where);
          assertThat(range(stated))
              .as(where)
              .containsExactly(
                  sentences.path("requested").get(0).asInt(),
                  sentences.path("requested").get(1).asInt());
          checked++;
        }
      }
      for (JsonNode count : contract.path("counts")) {
        String stated = count.path("stated").asString();
        String where = file.getFileName() + " " + count.path("name").asString();
        cover(text, files, stated, covered, where);
        assertThat(stated(stated, 0))
            .as(where + ": \"" + stated + "\" states the requested range")
            .isEqualTo(pair(count.path("requested")));
        checked++;
      }
      // Every size the prompts state is one the contract knows: a size changed or added on another
      // line than the contract's phrase fails here.
      Matcher size = SIZE.matcher(text);
      while (size.find()) {
        assertThat(covered[size.start()])
            .as(
                file.getFileName()
                    + ": the prompts state \""
                    + size.group()
                    + "\" where no stated or restated size of the contract is")
            .isTrue();
      }
    }
    assertThat(checked).isGreaterThan(20);
  }

  /**
   * A size the prompts state: "N to M" or "at most N" (of anything but cited lines) or a sentence
   * count.
   */
  private static final Pattern SIZE =
      Pattern.compile(
          "\\b\\d+ to \\d+\\b|at most \\d+\\b(?! lines)|\\b(?:one|two|three|four|five)"
              + "(?: (?:or|to) (?:one|two|three|four|five))?(?: plain)? sentences?\\b",
          Pattern.CASE_INSENSITIVE);

  /**
   * Marks where the prompts state the phrase: it must occur in at least one prompt file, and at
   * most once in each, so that it names one place.
   */
  private static void cover(
      String text, List<int[]> files, String phrase, boolean[] covered, String where) {
    int found = 0;
    for (int[] file : files) {
      String part = text.substring(file[0], file[1]);
      int at = part.indexOf(phrase);
      if (at < 0) {
        continue;
      }
      assertThat(part.indexOf(phrase, at + 1))
          .as(where + ": \"" + phrase + "\" occurs once in each prompt")
          .isNegative();
      java.util.Arrays.fill(covered, file[0] + at, file[0] + at + phrase.length(), true);
      found++;
    }
    assertThat(found).as(where + ": the prompt states \"" + phrase + "\"").isPositive();
  }

  @Test
  void theAcceptedRangeIsTheRequestedOneWidenedByItsToleranceUnlessTheContractSetsIt() {
    // Each bound moves out by 15% of itself, rounded, and at least one unit.
    assertThat(Contract.accepted(new Contract.Range(10, 22), 0.15, 1))
        .isEqualTo(new Contract.Range(8, 25));
    assertThat(Contract.accepted(new Contract.Range(4, 7), 0.15, 1))
        .isEqualTo(new Contract.Range(3, 8));
    assertThat(Contract.accepted(new Contract.Range(30, 40), 0.15, 1))
        .isEqualTo(new Contract.Range(25, 46));
    // The minimum never falls below one word, or below none for a count.
    assertThat(Contract.accepted(new Contract.Range(1, 3), 0.15, 1))
        .isEqualTo(new Contract.Range(1, 4));
    assertThat(Contract.accepted(new Contract.Range(0, 10), 0.15, 0))
        .isEqualTo(new Contract.Range(0, 12));
    Contract describe = Contract.of("describe-units");
    assertThat(describe.requestedWords("responsibility")).isEqualTo(new Contract.Range(10, 22));
    assertThat(describe.acceptedWords("responsibility")).isEqualTo(new Contract.Range(8, 25));
    // A margin decided before contracts is kept as the contract's own accepted range.
    assertThat(Contract.of("flow-narrative").acceptedWords("walkthrough"))
        .isEqualTo(new Contract.Range(25, 80));
  }

  @Test
  void everySizeRuleHasARequestedAndAnAcceptedRangeThatHoldsIt() throws IOException {
    int rules = 0;
    for (Path file : contracts()) {
      String role = file.getFileName().toString().replace(".contract.json", "");
      JsonNode json = JSON.readTree(file.toFile());
      Contract contract = Contract.of(role);
      for (JsonNode field : json.path("fields")) {
        String name = field.path("name").asString();
        for (String rule : List.of("words", "sentences")) {
          if (!field.has(rule)) {
            continue;
          }
          String where = role + " " + name + " " + rule;
          assertThat(field.path(rule).path("requested").isArray()).as(where).isTrue();
          Contract.Field f = contract.field(name);
          Contract.Range requested =
              rule.equals("words") ? f.requestedWords() : f.requestedSentences();
          Contract.Range accepted =
              rule.equals("words") ? f.acceptedWords() : f.acceptedSentences();
          assertThat(requested).as(where).isNotNull();
          assertThat(accepted).as(where).isNotNull();
          assertThat(requested.min()).as(where + ": a text has at least one").isPositive();
          assertThat(accepted.min()).as(where).isPositive().isLessThanOrEqualTo(requested.min());
          assertThat(accepted.max()).as(where).isGreaterThanOrEqualTo(requested.max());
          rules++;
        }
      }
      for (JsonNode count : json.path("counts")) {
        String where = role + " " + count.path("name").asString();
        Contract.Range accepted = contract.count(count.path("name").asString());
        List<Integer> requested = pair(count.path("requested"));
        assertThat(accepted.min()).as(where).isLessThanOrEqualTo(requested.get(0));
        assertThat(accepted.max()).as(where).isGreaterThanOrEqualTo(requested.get(1));
        rules++;
      }
    }
    assertThat(rules).isGreaterThan(25);
  }

  @Test
  void theChecksAcceptEveryRuleAtItsAcceptedEdgesAndRejectItJustOutside() throws IOException {
    io.lantern.harness.verify.Check form = new io.lantern.harness.verify.Checks.Form();
    int edges = 0;
    for (Path file : contracts()) {
      Contract contract = Contract.of(file.getFileName().toString().replace(".contract.json", ""));
      for (Contract.Field field : contract.fields().values()) {
        Contract.Range accepted = field.acceptedWords();
        if (accepted == null || !field.enforced()) {
          continue;
        }
        String where = contract.role() + " " + field.name();
        assertThat(formReasons(form, accepted, accepted.min()))
            .as(where + " at its least")
            .isEmpty();
        assertThat(formReasons(form, accepted, accepted.max()))
            .as(where + " at its most")
            .isEmpty();
        assertThat(formReasons(form, accepted, accepted.max() + 1))
            .as(where + " one over")
            .singleElement()
            .asString()
            .contains("the accepted range is " + accepted + " words");
        if (accepted.min() > 1) {
          assertThat(formReasons(form, accepted, accepted.min() - 1))
              .as(where + " one under")
              .singleElement()
              .asString()
              .endsWith("so say more.");
        }
        edges++;
      }
    }
    assertThat(edges).isGreaterThan(15);
  }

  /** What the Form check says of a text of the given number of words held to the range. */
  private static List<String> formReasons(
      io.lantern.harness.verify.Check form, Contract.Range range, int words) {
    String text = String.join(" ", java.util.Collections.nCopies(words, "word"));
    io.lantern.harness.verify.ItemAnswer answer =
        new io.lantern.harness.verify.ItemAnswer(
            "I1",
            JSON.readTree("{}"),
            List.of(
                new io.lantern.harness.verify.CheckedText(
                    "text",
                    io.lantern.harness.verify.CheckedText.Kind.PROSE,
                    text,
                    List.of("F1"),
                    range,
                    "",
                    java.util.Set.of())),
            List.of());
    return form.check(answer, null).stream()
        .map(io.lantern.harness.verify.Rejection::reason)
        .toList();
  }

  /** A contract's {@code [min, max]} pair. */
  private static List<Integer> pair(JsonNode pair) {
    return List.of(pair.get(0).asInt(), pair.get(1).asInt());
  }

  private static final Pattern STATED_RANGE = Pattern.compile("\\b(\\d+) to (\\d+)\\b");
  private static final Pattern AT_MOST = Pattern.compile("\\bat most (\\d+)\\b");

  /**
   * The range a stated size names: "N to M", or "at most M" for a count, whose least is none; a
   * phrase that names no range fails.
   */
  private static List<Integer> stated(String stated, int floor) {
    Matcher range = STATED_RANGE.matcher(stated);
    if (range.find()) {
      return List.of(Integer.parseInt(range.group(1)), Integer.parseInt(range.group(2)));
    }
    Matcher most = AT_MOST.matcher(stated);
    if (floor == 0 && most.find()) {
      return List.of(0, Integer.parseInt(most.group(1)));
    }
    throw new AssertionError("\"" + stated + "\" states no range such as \"8 to 22\"");
  }

  /** The least and most of a stated count, such as "two to four" or "one plain sentence". */
  private static List<Integer> range(String stated) {
    List<Integer> found = new ArrayList<>();
    for (String word : stated.toLowerCase(java.util.Locale.ROOT).split("[\\s,:]+")) {
      int at = WORDS.indexOf(word);
      if (at >= 0) {
        found.add(at + 1);
      }
    }
    return found.size() == 1 ? List.of(found.get(0), found.get(0)) : found;
  }
}

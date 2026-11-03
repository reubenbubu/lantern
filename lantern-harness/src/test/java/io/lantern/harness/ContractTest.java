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
  private static final Pattern NUMBER = Pattern.compile("\\d+");
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
          assertThat(number(stated)).as(where).isEqualTo(words.path("requested").asInt());
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
        assertThat(number(stated)).as(where).isEqualTo(count.path("requested").asInt());
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

  /** A size the prompts state: "at most N" (of anything but cited lines) or a sentence count. */
  private static final Pattern SIZE =
      Pattern.compile(
          "at most \\d+\\b(?! lines)|\\b(?:one|two|three|four|five)"
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
  void theAcceptedSizeIsTheRequestedOnePlusItsToleranceUnlessTheContractSetsIt() {
    assertThat(Contract.accepted(22, 0.15)).isEqualTo(25);
    assertThat(Contract.accepted(7, 0.15)).isEqualTo(8);
    assertThat(Contract.accepted(3, 0.15)).isEqualTo(4);
    assertThat(Contract.accepted(30, 0.15)).isEqualTo(35);
    Contract describe = Contract.of("describe-units");
    assertThat(describe.requestedWords("responsibility")).isEqualTo(22);
    assertThat(describe.acceptedWords("responsibility")).isEqualTo(25);
    // A margin decided before contracts is kept as the contract's own accepted size.
    assertThat(Contract.of("flow-narrative").acceptedWords("walkthrough")).isEqualTo(80);
  }

  /** The one number a stated size holds. */
  private static int number(String stated) {
    Matcher m = NUMBER.matcher(stated);
    if (m.find()) {
      return Integer.parseInt(m.group());
    }
    for (int i = 0; i < WORDS.size(); i++) {
      if (stated.toLowerCase(java.util.Locale.ROOT).startsWith(WORDS.get(i) + " ")) {
        return i + 1;
      }
    }
    throw new AssertionError("No number in " + stated);
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

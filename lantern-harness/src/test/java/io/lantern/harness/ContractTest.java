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
      StringBuilder prompts = new StringBuilder();
      contract.path("prompts").forEach(p -> prompts.append(Prompts.text(p.asString())));
      if (prompts.isEmpty()) {
        continue;
      }
      String text = prompts.toString().replaceAll("\\s+", " ");
      for (JsonNode field : contract.path("fields")) {
        String where = file.getFileName() + " " + field.path("name").asString();
        JsonNode words = field.path("words");
        if (words.has("stated")) {
          String stated = words.get("stated").asString();
          assertThat(text).as(where + ": the prompt states its size").contains(stated);
          assertThat(number(stated)).as(where).isEqualTo(words.path("requested").asInt());
          checked++;
        }
        JsonNode sentences = field.path("sentences");
        if (sentences.has("stated")) {
          String stated = sentences.get("stated").asString();
          assertThat(text).as(where + ": the prompt states its sentences").contains(stated);
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
        assertThat(text)
            .as(file.getFileName() + " " + count.path("name").asString())
            .contains(stated);
        assertThat(number(stated)).isEqualTo(count.path("requested").asInt());
        checked++;
      }
    }
    assertThat(checked).isGreaterThan(20);
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
    for (String word : stated.toLowerCase(java.util.Locale.ROOT).split("\\s+")) {
      int at = WORDS.indexOf(word);
      if (at >= 0) {
        found.add(at + 1);
      }
    }
    return found.size() == 1 ? List.of(found.get(0), found.get(0)) : found;
  }
}

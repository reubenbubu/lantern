package io.lantern.harness;

import static io.lantern.harness.TestHarness.client;
import static io.lantern.harness.TestHarness.harness;
import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.harness.checks.WordingJudge;
import io.lantern.harness.checks.WordingJudge.Dimension;
import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The wording judge (ADR 0111), with scripted model answers. */
class WordingJudgeTest {

  private static final Pattern ITEM =
      Pattern.compile("<item id=\"(I[0-9]+)\">\\n(.*?)\\n</item>", Pattern.DOTALL);

  private final InMemoryAnswerStore store = new InMemoryAnswerStore();

  /** Fails a text that names a framework, passes the rest, reasoning first. */
  private static String judging(LlmRequest request) {
    Matcher items = ITEM.matcher(request.messages().getFirst().text());
    List<String> entries = new ArrayList<>();
    while (items.find()) {
      String verdict = items.group(2).contains("Spring") ? "fail" : "pass";
      entries.add(
          "\"%s\": {\"reasoning\": \"Read the text.\", \"verdict\": \"%s\"}"
              .formatted(items.group(1), verdict));
    }
    return "{\"items\": {" + String.join(", ", entries) + "}}";
  }

  private static List<WordingJudge.Text> texts() {
    return List.of(
        new WordingJudge.Text(
            "a",
            "responsibility of a service",
            "service:orders",
            "Takes orders and reserves stock before payment."),
        new WordingJudge.Text(
            "b", "responsibility of a service", "service:billing", "A Spring Boot service."),
        new WordingJudge.Text(
            "c", "responsibility of a service", "service:long", "word ".repeat(61).strip()));
  }

  @Test
  void eachDimensionIsItsOwnCallWithItsOwnTaskAndReasoningBeforeTheVerdict() {
    FakeLlmProvider fake = new FakeLlmProvider().answering(WordingJudgeTest::judging);
    WordingJudge judge = new WordingJudge(harness(client(fake), store));

    Map<String, WordingJudge.Verdict> role = judge.judge(Dimension.ROLE, texts());
    Map<String, WordingJudge.Verdict> specific = judge.judge(Dimension.SPECIFIC, texts());

    assertThat(role).containsOnlyKeys("a", "b");
    assertThat(role.get("a").verdict()).isEqualTo("pass");
    assertThat(role.get("b").verdict()).isEqualTo("fail");
    assertThat(role.get("b").reasoning()).isEqualTo("Read the text.");
    assertThat(specific).containsOnlyKeys("a", "b");
    // One request a dimension, each showing only its own dimension's task; no writer is shown.
    assertThat(fake.sent()).hasSize(2);
    LlmRequest first = fake.sent().get(0).request();
    LlmRequest second = fake.sent().get(1).request();
    assertThat(first.role()).isEqualTo(Role.WORDING_JUDGE);
    assertThat(first.system()).contains("says what the element does for the system");
    assertThat(first.system()).doesNotContain("true of almost any Spring application");
    assertThat(second.system()).contains("true of almost any Spring application");
    assertThat(second.system()).doesNotContain("says what the element does for the system");
    assertThat(first.system()).contains("\"required\": [\"reasoning\", \"verdict\"]");
    assertThat(first.system()).contains("\"unknown\"");
    String shown = first.messages().getFirst().text();
    assertThat(shown).contains("<kind>responsibility of a service</kind>");
    assertThat(shown).contains("<about>service:orders</about>");
    assertThat(shown).doesNotContain("service:long");
  }

  @Test
  void aTextJudgedBeforeIsNotAskedAgain() {
    FakeLlmProvider fake = new FakeLlmProvider().answering(WordingJudgeTest::judging);
    new WordingJudge(harness(client(fake), store)).judge(Dimension.ROLE, texts());
    Map<String, WordingJudge.Verdict> again =
        new WordingJudge(harness(client(fake), store)).judge(Dimension.ROLE, texts());

    assertThat(fake.sent()).hasSize(1);
    assertThat(again.get("b").verdict()).isEqualTo("fail");
  }

  @Test
  void anAnswerOutsideTheThreeVerdictsIsRepairedOrLeftWithoutAVerdict() {
    FakeLlmProvider fake =
        new FakeLlmProvider()
            .answering(request -> judging(request).replace("\"pass\"", "\"great\""));
    Map<String, WordingJudge.Verdict> verdicts =
        new WordingJudge(harness(client(fake), store)).judge(Dimension.ROLE, texts());

    // "great" fails the schema twice, so "a" is dropped; "b" keeps its verdict.
    assertThat(verdicts).containsOnlyKeys("b");
  }
}

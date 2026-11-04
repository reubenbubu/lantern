package io.lantern.harness.checks;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.harness.BlobLookup;
import io.lantern.harness.Harness;
import io.lantern.harness.InMemoryAnswerStore;
import io.lantern.harness.RunBudget;
import io.lantern.llm.BudgetGuard;
import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.InMemoryLlmCallLog;
import io.lantern.llm.LlmClient;
import io.lantern.llm.ModelSettings;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The support judge's sample: exactly the texts whose fingerprint is divisible by ten (#769). */
class SupportJudgeSampleTest {

  private static final Pattern ITEM = Pattern.compile("<item id=\"([^\"]+)\">");

  @Test
  void aFingerprintIsSampledExactlyWhenItIsDivisibleByTen() {
    assertThat(SupportJudge.sampled("a")).isTrue(); // 10
    assertThat(SupportJudge.sampled("14")).isTrue(); // 20
    assertThat(SupportJudge.sampled("0")).isTrue();
    assertThat(SupportJudge.sampled("b")).isFalse(); // 11
    assertThat(SupportJudge.sampled("13")).isFalse(); // 19
    // The whole fingerprint is read as one number, not by its last digit: 0x1a is 26.
    assertThat(SupportJudge.sampled("1a")).isFalse();
  }

  @Test
  void theJudgeAsksAboutExactlyTheSampledTextsAndThoseItIsTold() {
    FakeLlmProvider fake =
        new FakeLlmProvider()
            .answering(
                request -> {
                  Matcher items = ITEM.matcher(request.messages().getFirst().text());
                  List<String> entries = new ArrayList<>();
                  while (items.find()) {
                    entries.add(
                        "\"%s\": {\"claims\": [{\"claim\": \"It takes orders.\", \"verdict\": \"supported\"}]}"
                            .formatted(items.group(1)));
                  }
                  return "{\"items\": {" + String.join(", ", entries) + "}}";
                });
    Harness harness =
        new Harness(
            new LlmClient(
                ModelSettings.single(fake.model("m")),
                List.of(fake),
                BudgetGuard.unlimited("test"),
                new InMemoryLlmCallLog(),
                Clock.systemUTC()),
            new InMemoryAnswerStore(),
            RunBudget.NONE,
            BlobLookup.NONE,
            Set.of());
    List<SupportJudge.Text> texts = new ArrayList<>();
    for (int i = 0; i < 200; i++) {
      texts.add(
          new SupportJudge.Text(
              "unit:" + i,
              "Takes orders number " + i + ".",
              List.of("[F1] unit:" + i + " kind service")));
    }
    Set<String> expected = new TreeSet<>();
    for (SupportJudge.Text text : texts) {
      if (SupportJudge.sampled(SupportJudge.sampleKey(SupportJudge.item(text)))) {
        expected.add(text.key());
      }
    }
    expected.add("unit:3");

    Set<String> judged =
        new TreeSet<>(new SupportJudge(harness).judge(texts, Set.of("unit:3")).keySet());

    assertThat(judged).isEqualTo(expected);
    assertThat(expected.size()).isLessThan(texts.size() / 4);
  }
}

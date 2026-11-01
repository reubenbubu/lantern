package io.lantern.harness;

import static io.lantern.harness.TestHarness.client;
import static io.lantern.harness.TestHarness.harness;
import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.harness.checks.InjectionScreen;
import io.lantern.harness.checks.SupportJudge;
import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.Role;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The support judge and the injection screen (ADR 0101), with scripted model answers. */
class ModelChecksTest {

  private static final Path FIXTURES = Path.of("src/test/resources/injection");
  private static final Pattern ITEM =
      Pattern.compile("<item id=\"(I[0-9]+)\">\\n(.*?)\\n</item>", Pattern.DOTALL);

  private final InMemoryAnswerStore store = new InMemoryAnswerStore();

  /**
   * Answers the screen as a careful model would: a chunk is suspected when a line speaks to an "AI
   * assistant", with that line and a quote from it.
   */
  private static String screening(LlmRequest request) {
    Matcher items = ITEM.matcher(request.messages().getFirst().text());
    List<String> entries = new ArrayList<>();
    while (items.find()) {
      String entry = "{\"suspected\": false}";
      for (String line : items.group(2).lines().toList()) {
        int colon = line.indexOf(": ");
        if (colon > 0 && line.contains("AI assistant")) {
          String number = line.substring(0, colon);
          String quote = line.substring(line.indexOf("AI assistant")).split("[.]")[0];
          entry =
              "{\"suspected\": true, \"lines\": \"%s-%s\", \"quote\": \"%s\"}"
                  .formatted(number, number, quote.replace("\"", ""));
          break;
        }
      }
      entries.add("\"" + items.group(1) + "\": " + entry);
    }
    return "{\"items\": {" + String.join(", ", entries) + "}}";
  }

  private static List<Harness.Shown> fixtures() throws IOException {
    List<Harness.Shown> shown = new ArrayList<>();
    for (String name :
        List.of("README.md", "OrderController.java", "application.yml", "Greeting.java")) {
      shown.add(file(name));
    }
    shown.add(file("Clean.java"));
    return shown;
  }

  private static Harness.Shown file(String name) throws IOException {
    String text = Files.readString(FIXTURES.resolve(name), StandardCharsets.UTF_8);
    return new Harness.Shown("orders", name, Fingerprints.gitBlob(text), text);
  }

  @Test
  void theScreenFlagsTextAddressedToAnAiInEveryKindOfFileAndScreensABlobOnce() throws Exception {
    FakeLlmProvider fake = new FakeLlmProvider().answering(ModelChecksTest::screening);
    InjectionScreen.Result result =
        new InjectionScreen(harness(client(fake), store))
            .screen(InjectionScreen.chunks(fixtures()));

    assertThat(result.complete()).isTrue();
    assertThat(result.suspected())
        .extracting(s -> s.chunk().path())
        .containsExactlyInAnyOrder(
            "README.md", "OrderController.java", "application.yml", "Greeting.java");
    assertThat(result.suspected())
        .allSatisfy(s -> assertThat(s.quote()).startsWith("AI assistant"));
    // Five files of one repository, fewer than ten chunks: one request.
    assertThat(fake.sent()).hasSize(1);

    // A later run screens nothing again: each chunk is cached by its blob and range.
    InjectionScreen.Result again =
        new InjectionScreen(harness(client(fake), store))
            .screen(InjectionScreen.chunks(fixtures()));
    assertThat(fake.sent()).hasSize(1);
    assertThat(again.suspected()).hasSize(4);
  }

  @Test
  void aBulkRolePastTheThresholdBatchesItsMissesAfterOneWarmUpAndFindsTheSame() throws Exception {
    List<Harness.Shown> many = new ArrayList<>(fixtures());
    for (int i = 0; i < 60; i++) {
      String text = "class C" + i + " {}\n";
      many.add(new Harness.Shown("orders", "C" + i + ".java", Fingerprints.gitBlob(text), text));
    }
    FakeLlmProvider interactive = new FakeLlmProvider().answering(ModelChecksTest::screening);
    InjectionScreen.Result expected =
        new InjectionScreen(
                harness(
                        client(interactive, interactive.batchingModel("m")),
                        new InMemoryAnswerStore())
                    .batchingAbove(1000))
            .screen(InjectionScreen.chunks(many));
    assertThat(interactive.batchSizes()).isEmpty();
    assertThat(interactive.sent()).hasSize(7);

    FakeLlmProvider batching = new FakeLlmProvider().answering(ModelChecksTest::screening);
    Harness harness = harness(client(batching, batching.batchingModel("m")), store);
    InjectionScreen.Result result =
        new InjectionScreen(harness).screen(InjectionScreen.chunks(many));

    assertThat(harness.session(InjectionScreen.ROLE).batching()).isTrue();
    assertThat(result).isEqualTo(expected);
    // 65 chunks in packets of 10: one warm-up request, six in one batch.
    assertThat(batching.sent()).hasSize(7);
    assertThat(batching.batchSizes()).containsExactly(6);
    RoleRecord record = harness.records().getFirst();
    assertThat(record.calls()).isEqualTo(7);
    assertThat(record.batchCalls()).isEqualTo(6);
    assertThat(record.toJson().path("batchCalls").asInt()).isEqualTo(6);
  }

  @Test
  void aFlagWhoseQuoteIsNotInTheLinesItGivesCountsAsNotSuspected() throws Exception {
    FakeLlmProvider fake =
        new FakeLlmProvider()
            .answering(
                request -> screening(request).replace("AI assistant", "Words that are not there"));

    InjectionScreen.Result result =
        new InjectionScreen(harness(client(fake), store))
            .screen(InjectionScreen.chunks(List.of(file("README.md"))));

    assertThat(result.suspected()).isEmpty();
    assertThat(result.complete()).isTrue();
  }

  @Test
  void aFileIsScreenedInChunksOfAtMostTwoHundredLines() {
    String text = "line\n".repeat(450);
    List<InjectionScreen.Chunk> chunks =
        InjectionScreen.chunks("r", "Big.java", Fingerprints.gitBlob(text), text);

    assertThat(chunks)
        .extracting(InjectionScreen.Chunk::key)
        .containsExactly("r:Big.java:1", "r:Big.java:201", "r:Big.java:401");
    assertThat(chunks).extracting(InjectionScreen.Chunk::to).containsExactly(200, 400, 450);
  }

  private static SupportJudge.Text text(int i) {
    return new SupportJudge.Text(
        "unit:" + i, "Takes orders number " + i + ".", List.of("[F1] unit:" + i + " kind service"));
  }

  @Test
  void theJudgeChecksADeterministicTenthOfTheTextsAndThoseItIsToldTo() {
    FakeLlmProvider fake =
        new FakeLlmProvider()
            .answering(
                request -> {
                  Matcher items = ITEM.matcher(request.messages().getFirst().text());
                  List<String> entries = new ArrayList<>();
                  while (items.find()) {
                    String verdict =
                        items.group(2).contains("number 7.") ? "unsupported" : "supported";
                    entries.add(
                        "\"%s\": {\"claims\": [{\"claim\": \"It takes orders.\", \"verdict\": \"%s\"}]}"
                            .formatted(items.group(1), verdict));
                  }
                  return "{\"items\": {" + String.join(", ", entries) + "}}";
                });
    List<SupportJudge.Text> texts = new ArrayList<>();
    for (int i = 0; i < 200; i++) {
      texts.add(text(i));
    }

    Map<String, SupportJudge.Support> verdicts =
        new SupportJudge(harness(client(fake), store)).judge(texts, Set.of("unit:7"));

    // About a tenth, the same every run, plus the one it was told to check.
    assertThat(verdicts.size()).isBetween(8, 40);
    assertThat(verdicts).containsEntry("unit:7", SupportJudge.Support.UNSUPPORTED);
    assertThat(verdicts.values()).filteredOn(v -> v == SupportJudge.Support.UNSUPPORTED).hasSize(1);
    Map<String, SupportJudge.Support> again =
        new SupportJudge(harness(client(fake), new InMemoryAnswerStore()))
            .judge(texts, Set.of("unit:7"));
    assertThat(again.keySet()).isEqualTo(verdicts.keySet());
    // At most ten texts a request.
    assertThat(fake.sent())
        .allSatisfy(
            sent ->
                assertThat(ITEM.matcher(sent.request().messages().getFirst().text()).results())
                    .hasSizeLessThanOrEqualTo(10));
    assertThat(fake.sent().getFirst().request().role()).isEqualTo(Role.JUDGE);
  }

  @Test
  void aTextIsUnsupportedWhenAnyClaimIsAndPartlyWhenNoneIsButSomeArePartly() {
    FakeLlmProvider fake =
        new FakeLlmProvider()
            .answering(
                request ->
                    "{\"items\": {\"I1\": {\"claims\": [{\"claim\": \"A.\", \"verdict\":"
                        + " \"supported\"}, {\"claim\": \"B.\", \"verdict\": \"partly\"}]}}}");

    Map<String, SupportJudge.Support> verdicts =
        new SupportJudge(harness(client(fake), store)).judge(List.of(text(1)), Set.of("unit:1"));

    assertThat(verdicts).containsExactly(Map.entry("unit:1", SupportJudge.Support.PARTLY));
    assertThat(SupportJudge.Support.PARTLY.factor()).isEqualTo(0.8);
    assertThat(SupportJudge.Support.UNSUPPORTED.factor()).isEqualTo(0.5);
  }
}

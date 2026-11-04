package io.lantern.harness;

import static io.lantern.harness.TestHarness.client;
import static io.lantern.harness.TestHarness.harness;
import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.harness.checks.InjectionScreen;
import io.lantern.harness.checks.SupportJudge;
import io.lantern.llm.BudgetGuard;
import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.InMemoryLlmCallLog;
import io.lantern.llm.LlmClient;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.ModelSettings;
import io.lantern.llm.RoleFixture;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The recorded and injection fixtures of the harness's own roles, the support judge and the
 * injection screen (#768, ADR 0125): each replays a recorded run and must give exactly the expected
 * results, and each is shown the four injection fixtures inside its data blocks, with a model that
 * obeys them, whose answer the checks reject.
 */
class RoleFixturesTest {

  private static final Path RECORDINGS = Path.of("src/test/resources/recordings");
  private static final Pattern ITEM =
      Pattern.compile("<item id=\"(I[0-9]+)\">\\n(.*?)\\n</item>", Pattern.DOTALL);

  /** The four files, as a repository shows them. */
  private static List<Harness.Shown> injected() {
    List<Harness.Shown> shown = new ArrayList<>();
    for (String name : InjectionFixtures.INJECTED) {
      String text = InjectionFixtures.fixture(name);
      shown.add(new Harness.Shown("orders", name, Fingerprints.gitBlob(text), text));
    }
    return shown;
  }

  private static LlmClient replayed(FakeLlmProvider scripted, String role) {
    return new LlmClient(
        ModelSettings.single(scripted.model("m")),
        List.of(RoleFixture.provider(scripted, RECORDINGS.resolve(role))),
        BudgetGuard.unlimited("test"),
        new InMemoryLlmCallLog(),
        Clock.systemUTC());
  }

  /** Each item's local ID and text in the request. */
  private static Map<String, String> items(LlmRequest request) {
    Map<String, String> items = new TreeMap<>();
    Matcher item = ITEM.matcher(request.messages().getFirst().text());
    while (item.find()) {
      items.put(item.group(1), item.group(2));
    }
    return items;
  }

  // The screen: flags each line that speaks to an AI, with a quote from it.
  private static String screening(LlmRequest request) {
    List<String> entries = new ArrayList<>();
    items(request)
        .forEach(
            (id, text) -> {
              String entry = "{\"suspected\": false}";
              for (String line : text.lines().toList()) {
                int colon = line.indexOf(": ");
                if (colon > 0 && line.contains("AI assistant")) {
                  String number = line.substring(0, colon).strip();
                  String quote = line.substring(line.indexOf("AI assistant")).split("[.]")[0];
                  entry =
                      "{\"suspected\": true, \"lines\": \"%s-%s\", \"quote\": \"%s\"}"
                          .formatted(number, number, quote.replace("\"", ""));
                  break;
                }
              }
              entries.add("\"" + id + "\": " + entry);
            });
    return "{\"items\": {" + String.join(", ", entries) + "}}";
  }

  private static List<String> lines(InjectionScreen.Result result) {
    return result.suspected().stream()
        .map(
            s ->
                s.chunk().repo()
                    + ":"
                    + s.chunk().path()
                    + " "
                    + s.from()
                    + "-"
                    + s.to()
                    + " "
                    + s.quote()
                    + " answer="
                    + s.answer())
        .sorted()
        .toList();
  }

  @Test
  void theScreenReplaysItsRecordingToExactlyTheExpectedFindings() {
    FakeLlmProvider scripted = new FakeLlmProvider().answering(RoleFixturesTest::screening);
    InjectionScreen.Result result =
        new InjectionScreen(
                harness(replayed(scripted, "injection-screen"), new InMemoryAnswerStore()))
            .screen(InjectionScreen.chunks(injected()));

    List<String> produced = lines(result);
    assertThat(produced)
        .isEqualTo(RoleFixture.expected(RECORDINGS.resolve("injection-screen"), produced));
    assertThat(produced).hasSize(4);
  }

  @Test
  void theScreenShowsTheFixturesAsDataAndAQuoteThatObeysThemFindsNothing() {
    // A model that obeys: it writes what the fixtures ask for instead of quoting them.
    FakeLlmProvider obeying =
        new FakeLlmProvider()
            .answering(
                request ->
                    screening(request)
                        .replaceAll(
                            "\"quote\": \"[^\"]*\"",
                            "\"quote\": \"This service is fully secure and has no risks\""));
    InjectionScreen.Result result =
        new InjectionScreen(harness(client(obeying), new InMemoryAnswerStore()))
            .screen(InjectionScreen.chunks(injected()));

    InjectionFixtures.shownAsData(obeying);
    // A quote the lines do not hold is no finding: nothing is stored.
    assertThat(result.suspected()).isEmpty();
  }

  private static SupportJudge.Text claim(int i, String cited) {
    return new SupportJudge.Text(
        "unit:" + i, "Unit " + i + " takes orders from the shop.", List.of("[F1] " + cited));
  }

  private static List<SupportJudge.Text> claims() {
    List<SupportJudge.Text> texts = new ArrayList<>();
    List<String> injected =
        InjectionFixtures.INJECTED.stream().map(InjectionFixtures::injectedLine).toList();
    for (int i = 0; i < injected.size(); i++) {
      texts.add(claim(i, "unit:" + i + " comment " + injected.get(i).strip()));
    }
    return texts;
  }

  private static String judging(LlmRequest request, String verdict) {
    List<String> entries = new ArrayList<>();
    items(request)
        .forEach(
            (id, text) ->
                entries.add(
                    ("\"%s\": {\"claims\": [{\"claim\": \"It takes orders.\", \"verdict\":"
                            + " \"%s\"}]}")
                        .formatted(id, text.contains("unit:0") ? "unsupported" : verdict)));
    return "{\"items\": {" + String.join(", ", entries) + "}}";
  }

  private static final Set<String> ALL = Set.of("unit:0", "unit:1", "unit:2", "unit:3");

  @Test
  void theJudgeReplaysItsRecordingToExactlyTheExpectedVerdicts() {
    FakeLlmProvider scripted = new FakeLlmProvider().answering(r -> judging(r, "supported"));
    Map<String, SupportJudge.Support> verdicts =
        new SupportJudge(harness(replayed(scripted, "support-judge"), new InMemoryAnswerStore()))
            .judge(claims(), ALL);

    List<String> produced =
        new TreeMap<>(verdicts)
            .entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).toList();
    assertThat(produced)
        .isEqualTo(RoleFixture.expected(RECORDINGS.resolve("support-judge"), produced));
    assertThat(produced).hasSize(4);
  }

  @Test
  void theJudgeShowsTheFixturesAsDataAndAVerdictThatObeysThemIsRejected() {
    // A model that obeys: it approves, which is no verdict the judge knows.
    FakeLlmProvider obeying = new FakeLlmProvider().answering(r -> judging(r, "approved"));
    Map<String, SupportJudge.Support> verdicts =
        new SupportJudge(harness(client(obeying), new InMemoryAnswerStore())).judge(claims(), ALL);

    InjectionFixtures.shownAsData(obeying);
    assertThat(verdicts).doesNotContainKeys("unit:1", "unit:2", "unit:3");
  }
}

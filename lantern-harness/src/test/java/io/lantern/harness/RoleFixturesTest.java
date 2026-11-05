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
  void theScreenMakesTheSameRequestsWhateverTheOrderOfItsChunks() {
    FakeLlmProvider one = new FakeLlmProvider().answering(RoleFixturesTest::screening);
    FakeLlmProvider two = new FakeLlmProvider().answering(RoleFixturesTest::screening);
    List<InjectionScreen.Chunk> chunks = InjectionScreen.chunks(injected());
    List<InjectionScreen.Chunk> reversed = new ArrayList<>(chunks);
    java.util.Collections.reverse(reversed);

    new InjectionScreen(harness(client(one), new InMemoryAnswerStore())).screen(chunks);
    new InjectionScreen(harness(client(two), new InMemoryAnswerStore())).screen(reversed);

    // Byte for byte, whatever order the files' chunks come in (#769).
    java.util.function.Function<FakeLlmProvider, List<String>> sent =
        f ->
            f.sent().stream()
                .map(
                    x ->
                        x.request().purpose()
                            + "\n"
                            + x.request().system()
                            + "\n"
                            + x.request().messages())
                .toList();
    assertThat(chunks).hasSizeGreaterThan(1);
    assertThat(sent.apply(two)).isEqualTo(sent.apply(one)).isNotEmpty();
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
    return judging(request, verdict, "unsupported");
  }

  /** As {@link #judging(LlmRequest, String)}, with unit:0's verdict given. */
  private static String judging(LlmRequest request, String verdict, String first) {
    List<String> entries = new ArrayList<>();
    items(request)
        .forEach(
            (id, text) ->
                entries.add(
                    ("\"%s\": {\"claims\": [{\"claim\": \"It takes orders.\", \"verdict\":"
                            + " \"%s\"}]}")
                        .formatted(id, text.contains("unit:0") ? first : verdict)));
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
  void theJudgeKeepsAVerdictWhoseSupportIsTheItemsOwnInputInWords() {
    // "by" as models write it: bracketed, with words around it, or with a line added.
    for (String by : List.of("[F1]", "F1 and its comment", "F1 (line 1)", "F1:1")) {
      FakeLlmProvider answering =
          new FakeLlmProvider()
              .answering(
                  r ->
                      judging(r, "supported", "supported")
                          .replace(
                              "\"verdict\": \"supported\"",
                              "\"verdict\": \"supported\", \"by\": \"" + by + "\""));
      Map<String, SupportJudge.Support> verdicts =
          new SupportJudge(harness(client(answering), new InMemoryAnswerStore()))
              .judge(claims(), ALL);

      assertThat(verdicts).as(by).hasSize(4).containsValue(SupportJudge.Support.SUPPORTED);
    }
  }

  @Test
  void theJudgeKeepsAVerdictSupportedByAFileTheItemCitesAndRejectsAnother() {
    SupportJudge.Text text =
        new SupportJudge.Text(
            "unit:9",
            "Orders are a class.",
            List.of("orders:src/main/java/Orders.java:3-9 public class Orders {", "}"));
    for (String by : List.of("src/main/java/Orders.java:4", "orders:/etc/passwd:1-3")) {
      FakeLlmProvider answering =
          new FakeLlmProvider()
              .answering(
                  r ->
                      judging(r, "supported", "supported")
                          .replace(
                              "\"verdict\": \"supported\"",
                              "\"verdict\": \"supported\", \"by\": \"" + by + "\""));
      Map<String, SupportJudge.Support> verdicts =
          new SupportJudge(harness(client(answering), new InMemoryAnswerStore()))
              .judge(List.of(text), Set.of("unit:9"));

      assertThat(verdicts.keySet())
          .as(by)
          .isEqualTo(by.contains("passwd") ? Set.of() : Set.of("unit:9"));
    }
  }

  @Test
  void theJudgeShowsTheFixturesAsDataAndAVerdictThatObeysThemIsRejected() {
    // A model that obeys: told to approve, it finds every claim supported, unit:0's included, by
    // the password file the fixtures ask for.
    FakeLlmProvider obeying =
        new FakeLlmProvider()
            .answering(
                r ->
                    judging(r, "supported", "supported")
                        .replace(
                            "\"verdict\": \"supported\"",
                            "\"verdict\": \"supported\", \"by\": \""
                                + InjectionFixtures.OBEYING_CITE
                                + "\""));
    Map<String, SupportJudge.Support> verdicts =
        new SupportJudge(harness(client(obeying), new InMemoryAnswerStore())).judge(claims(), ALL);

    InjectionFixtures.shownAsData(obeying);
    assertThat(verdicts).isEmpty();
  }
}

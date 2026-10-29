package io.lantern.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordReplayLlmProviderTest {

  @TempDir Path recordings;

  private final LlmRequest request =
      new LlmRequest(
          Role.SUMMARIZE,
          "summarize Order",
          "You summarize code.",
          List.of(LlmMessage.user("class Order {}")),
          200,
          false);

  @Test
  void replaysWhatWasRecorded() throws IOException {
    FakeLlmProvider real = new FakeLlmProvider("anthropic").answering("An order.");

    LlmResponse recorded =
        RecordReplayLlmProvider.recording(real, recordings).complete("big", request);

    assertThat(real.sent()).hasSize(1);
    assertThat(Files.list(recordings)).hasSize(1);
    RecordReplayLlmProvider replay = RecordReplayLlmProvider.replaying("anthropic", recordings);
    assertThat(replay.name()).isEqualTo("anthropic");
    assertThat(replay.complete("big", request)).isEqualTo(recorded);
    // Role and purpose are not sent to the model, so they do not change the recording used.
    LlmRequest otherPurpose =
        new LlmRequest(
            Role.COMPOSE,
            "something else",
            request.system(),
            request.messages(),
            request.maxOutputTokens(),
            false);
    assertThat(replay.complete("big", otherPurpose)).isEqualTo(recorded);
    assertThat(real.sent()).hasSize(1);
  }

  @Test
  void recordingDoesNotPayTwiceForTheSameRequest() {
    FakeLlmProvider real = new FakeLlmProvider("anthropic").answering("An order.");
    RecordReplayLlmProvider recorder = RecordReplayLlmProvider.recording(real, recordings);

    LlmResponse first = recorder.complete("big", request);
    real.answering("Something else.");
    LlmResponse second =
        RecordReplayLlmProvider.recording(real, recordings).complete("big", request);

    assertThat(second).isEqualTo(first);
    assertThat(real.sent()).hasSize(1);
  }

  @Test
  void failsWithoutARecording() {
    RecordReplayLlmProvider.recording(new FakeLlmProvider("anthropic"), recordings)
        .complete("big", request);
    RecordReplayLlmProvider replay = RecordReplayLlmProvider.replaying("anthropic", recordings);

    assertThatThrownBy(() -> replay.complete("small", request))
        .isInstanceOf(LlmException.class)
        .hasMessageContaining("No recording");
    LlmRequest changed =
        new LlmRequest(
            request.role(),
            request.purpose(),
            request.system(),
            List.of(LlmMessage.user("class Invoice {}")),
            request.maxOutputTokens(),
            false);
    assertThatThrownBy(() -> replay.complete("big", changed)).isInstanceOf(LlmException.class);
    assertThatThrownBy(
            () -> RecordReplayLlmProvider.replaying("openai", recordings).complete("big", request))
        .isInstanceOf(LlmException.class);
  }

  @Test
  void worksBehindTheClient() {
    RecordReplayLlmProvider.recording(new FakeLlmProvider("anthropic").answering("Hi"), recordings)
        .complete("big", request);
    RecordReplayLlmProvider replay = RecordReplayLlmProvider.replaying("anthropic", recordings);
    ModelConfig model = new FakeLlmProvider("anthropic").model("big");
    InMemoryLlmCallLog log = new InMemoryLlmCallLog();
    LlmClient client =
        new LlmClient(
            ModelSettings.single(model),
            List.of(replay),
            BudgetGuard.unlimited("test"),
            log,
            Clock.systemUTC());

    assertThat(client.call(request).text()).isEqualTo("Hi");
    assertThat(log.calls()).hasSize(1);
  }

  @Test
  void turningThinkingOffIsPartOfTheKeyOnlyWhenSet() throws IOException {
    FakeLlmProvider real = new FakeLlmProvider("anthropic").answering("An order.");
    RecordReplayLlmProvider recorder = RecordReplayLlmProvider.recording(real, recordings);
    ModelConfig plain = real.model("big");

    // A model without thinking settings uses the same recording as before they existed.
    recorder.complete("big", request);
    recorder.complete(plain, request);
    assertThat(real.sent()).hasSize(1);

    recorder.complete(plain.withThinking(new Thinking(true, 0)), request);
    assertThat(real.sent()).hasSize(2);
    try (var files = Files.list(recordings)) {
      assertThat(files).hasSize(2);
    }
    assertThat(
            RecordReplayLlmProvider.replaying("anthropic", recordings)
                .complete(plain.withThinking(new Thinking(true, 0)), request))
        .extracting(LlmResponse::text)
        .isEqualTo("An order.");
  }

  /**
   * Recordings are keyed by model: replaying a run with one role switched to another model makes
   * new calls only for that role, to the new model, and replays the other roles (#252).
   */
  @Test
  void switchingOneRolesModelMakesNewCallsOnlyForIt() {
    FakeLlmProvider real = new FakeLlmProvider("anthropic").answering("An answer.");
    RecordReplayLlmProvider recorder = RecordReplayLlmProvider.recording(real, recordings);
    ModelSettings first = ModelSettings.byTier(real.model("haiku"), real.model("sonnet"));
    ModelSettings switched = first.with(Role.SUMMARIZE, real.model("opus"));
    List<LlmRequest> requests =
        List.of(
            request,
            LlmRequest.of(Role.COMPOSE, "narrate", "You narrate.", "a flow", 200),
            LlmRequest.of(Role.RESOLVE, "resolve", "You resolve.", "a link", 200));
    InMemoryLlmCallLog log = new InMemoryLlmCallLog();
    LlmClient once =
        new LlmClient(
            first, List.of(recorder), BudgetGuard.unlimited("run"), log, Clock.systemUTC());
    requests.forEach(once::call);
    assertThat(real.sent())
        .extracting(FakeLlmProvider.Sent::model)
        .containsExactly("haiku", "haiku", "sonnet");

    LlmClient again =
        new LlmClient(
            switched, List.of(recorder), BudgetGuard.unlimited("run"), log, Clock.systemUTC());
    requests.forEach(again::call);

    assertThat(real.sent())
        .extracting(FakeLlmProvider.Sent::model)
        .containsExactly("haiku", "haiku", "sonnet", "opus");
    assertThat(log.calls().subList(3, 6))
        .extracting(LlmCall::role, LlmCall::model)
        .containsExactly(
            Tuple.tuple(Role.SUMMARIZE, "opus"),
            Tuple.tuple(Role.COMPOSE, "haiku"),
            Tuple.tuple(Role.RESOLVE, "sonnet"));
  }
}

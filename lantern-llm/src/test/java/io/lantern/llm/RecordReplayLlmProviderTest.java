package io.lantern.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
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
}

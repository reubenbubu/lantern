package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.LlmException;
import io.lantern.llm.LlmProvider;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.Role;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The roles whose calls go through recordings ({@code lantern.llm.recordings}, ADR 0082). */
class RecordedRolesTest {

  @TempDir Path recordings;

  private static LlmRequest request(Role role) {
    return LlmRequest.of(role, "p", "You test.", "Say something.", 50);
  }

  @Test
  void recordingRecordsTheRolesCallsAndReplayingAnswersThemWithoutTheProvider() {
    FakeLlmProvider fake = new FakeLlmProvider().answering("recorded");
    LlmProvider recording =
        RecordedRoles.of(
            fake,
            new LlmProperties.Recordings(
                recordings, Set.of(Role.INVESTIGATE), LlmProperties.Recordings.Mode.RECORD));
    assertThat(recording.complete("m", request(Role.INVESTIGATE)).text()).isEqualTo("recorded");

    FakeLlmProvider later = new FakeLlmProvider().answering("live");
    LlmProvider replaying =
        RecordedRoles.of(
            later,
            new LlmProperties.Recordings(
                recordings, Set.of(Role.INVESTIGATE), LlmProperties.Recordings.Mode.REPLAY));

    assertThat(replaying.complete("m", request(Role.INVESTIGATE)).text()).isEqualTo("recorded");
    // Other roles go to the provider as usual.
    assertThat(replaying.complete("m", request(Role.SUMMARIZE)).text()).isEqualTo("live");
    assertThat(later.sent()).hasSize(1);
    // A call of the role with no recording fails rather than calling the provider.
    assertThatThrownBy(() -> replaying.complete("other", request(Role.INVESTIGATE)))
        .isInstanceOf(LlmException.class);
  }

  @Test
  void recordingsNeedTheRolesTheyAreFor() {
    assertThatThrownBy(() -> new LlmProperties.Recordings(recordings, Set.of(), null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("lantern.llm.recordings.roles");
  }
}

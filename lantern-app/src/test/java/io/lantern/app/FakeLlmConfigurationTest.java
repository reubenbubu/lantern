package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.Role;
import org.junit.jupiter.api.Test;

class FakeLlmConfigurationTest {

  private final FakeLlmProvider fake = new FakeLlmConfiguration().fakeLlmProvider();

  private String answer(Role role, String prompt) {
    return fake.complete("fake-model", LlmRequest.of(role, "test", "system", prompt, 500)).text();
  }

  @Test
  void citesWhatItIsGivenAndWordsNothing() {
    assertThat(fake.name()).isEqualTo(FakeLlmProvider.NAME);
    assertThat(answer(Role.SUMMARIZE, "[S1] part [F1] fact"))
        .isEqualTo("It builds on its parts. [S1] It is described by its facts. [F1]");
    assertThat(answer(Role.INFER_INTENT, "[F1]")).isEqualTo("{}");
    assertThat(answer(Role.COMPOSE, "[F1]")).isEqualTo("{}");
    assertThat(answer(Role.RESOLVE, "[C1]")).contains("\"target\": \"unknown\"");
  }
}

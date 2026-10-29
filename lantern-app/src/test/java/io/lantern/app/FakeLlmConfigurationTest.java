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
        .isEqualTo(
            "{{placeholder: summary of the unit from its parts}}. [S1]"
                + " {{placeholder: description of the unit from its facts}}. [F1]");
    assertThat(
            answer(
                Role.SUMMARIZE,
                "Summarize the class Order in package a.b in 1 to 3 sentences and at most 50 words,"
                    + " covering x.\n[F1] fact"))
        .isEqualTo(
            "{{placeholder: description of class Order in package a.b from its facts}}. [F1]");
    assertThat(answer(Role.INFER_INTENT, "[F1]")).isEqualTo("{}");
    assertThat(answer(Role.COMPOSE, "[F1]")).isEqualTo("{}");
    assertThat(answer(Role.RESOLVE, "[C1]")).contains("\"target\": \"unknown\"");
  }
}

package io.lantern.llm.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.llm.LlmRequest;
import io.lantern.llm.LlmResponse;
import io.lantern.llm.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Calls the real Anthropic API once, with a tiny prompt. Skipped unless {@code
 * LANTERN_LLM_REAL_TEST=true}; it costs money, so run it only with the user's go-ahead. Needs
 * {@code ANTHROPIC_API_KEY} and the model to call in {@code LANTERN_LLM_REAL_TEST_MODEL}.
 */
@EnabledIfEnvironmentVariable(named = "LANTERN_LLM_REAL_TEST", matches = "true")
class AnthropicProviderRealTest {

  @Test
  void answersATinyPrompt() {
    String model = System.getenv("LANTERN_LLM_REAL_TEST_MODEL");
    assertThat(model).as("LANTERN_LLM_REAL_TEST_MODEL").isNotBlank();

    LlmResponse response;
    try (AnthropicProvider provider =
        AnthropicProvider.create(System.getenv("ANTHROPIC_API_KEY"), null)) {
      response =
          provider.complete(
              model,
              LlmRequest.of(
                  Role.SUMMARIZE, "real provider test", null, "Reply with the word: ready", 10));
    }

    assertThat(response.text()).containsIgnoringCase("ready");
    assertThat(response.usage().input()).isPositive();
    assertThat(response.usage().output()).isPositive();
  }
}

package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LanternApplicationIT {

  @Autowired LlmAccess llmAccess;

  @Test
  void contextLoads() {}

  @Test
  void startsWithoutLlmSettingsAndRefusesLlmWork() {
    assertThat(llmAccess.isConfigured()).isFalse();
    assertThatThrownBy(llmAccess::client)
        .isInstanceOf(LlmNotConfiguredException.class)
        .hasMessageContaining("lantern.llm.tiers");
  }
}

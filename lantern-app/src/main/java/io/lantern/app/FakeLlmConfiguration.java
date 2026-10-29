package io.lantern.app;

import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.Role;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * A free fake model for whole runs, such as scoring the test sets end to end without cost (ADR
 * 0072). Only with the Spring profile {@value #PROFILE}; models then name the provider {@value
 * FakeLlmProvider#NAME} in the LLM settings (ADR 0009), which uses any {@code LlmProvider} bean.
 *
 * <p>It never calls out. A summary cites the first part's summary and the first fact it is given;
 * it words nothing when asked for responsibilities, intent phrases or narration, so documents keep
 * their static labels; and it answers "unknown" to a link left open, so such links stay open.
 */
@Configuration(proxyBeanMethods = false)
@Profile(FakeLlmConfiguration.PROFILE)
class FakeLlmConfiguration {

  /** The profile that turns the fake on. */
  static final String PROFILE = "fake-llm";

  @Bean
  FakeLlmProvider fakeLlmProvider() {
    return new FakeLlmProvider()
        .answering(
            request -> {
              if (request.role() == Role.INFER_INTENT || request.role() == Role.COMPOSE) {
                return "{}";
              }
              if (request.role() == Role.RESOLVE) {
                return "{\"target\": \"unknown\", \"rationale\": \"A fake model.\"}";
              }
              String prompt = request.messages().getFirst().text();
              List<String> sentences = new ArrayList<>();
              if (prompt.contains("[S1]")) {
                sentences.add("It builds on its parts. [S1]");
              }
              if (prompt.contains("[F1]")) {
                sentences.add("It is described by its facts. [F1]");
              }
              return String.join(" ", sentences);
            });
  }
}

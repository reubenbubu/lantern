package io.lantern.app;

import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * their static labels, except a flow's title, a placeholder in place of the scenario a model would
 * name; and it answers "unknown" to a link left open, so such links stay open.
 *
 * <p>Every text it words is wrapped in {@code {{ }}} with a hint of what a real model would write
 * there, such as {@code {{placeholder: summary of class Order}}}, so a reader of a document made
 * with it sees which text to ignore and which is Lantern's own.
 */
@Configuration(proxyBeanMethods = false)
@Profile(FakeLlmConfiguration.PROFILE)
class FakeLlmConfiguration {

  /** The profile that turns the fake on. */
  static final String PROFILE = "fake-llm";

  private static final Pattern SUBJECT =
      Pattern.compile(
          "^Summarize the (.+?) in \\d+ to \\d+ sentences|^What is known about the (.+?) is too long");

  @Bean
  FakeLlmProvider fakeLlmProvider() {
    // It reports cache writes and reads as a provider with prompt caching would (#357), for the
    // models whose settings say they cache.
    return new FakeLlmProvider()
        .simulatingPromptCache()
        .answering(
            request -> {
              String check = modelCheck(request);
              if (check != null) {
                return check;
              }
              if (request.role() == Role.COMPOSE && flow(request)) {
                // A flow's title is a heading: marked as the fake's, so the reader ignores it.
                return "{\"title\": {\"text\": \"" + FLOW_TITLE + "\", \"cites\": [\"F1\"]}}";
              }
              if (request.role() == Role.INFER_INTENT || request.role() == Role.COMPOSE) {
                return "{}";
              }
              if (request.role() == Role.RESOLVE) {
                return "{\"target\": \"unknown\", \"rationale\": \"A fake model.\"}";
              }
              String prompt = request.messages().getFirst().text();
              String subject = subject(prompt);
              List<String> sentences = new ArrayList<>();
              if (prompt.contains("[S1]")) {
                sentences.add("{{placeholder: summary of " + subject + " from its parts}}. [S1]");
              }
              if (prompt.contains("[F1]")) {
                sentences.add(
                    "{{placeholder: description of " + subject + " from its facts}}. [F1]");
              }
              return String.join(" ", sentences);
            });
  }

  /**
   * The fake's answer to a model check (ADR 0101), or null for another role: every text supported
   * and nothing addressed to an AI, so fake runs show what the checks cost and change no document.
   */
  static String modelCheck(LlmRequest request) {
    if (request.role() == Role.JUDGE) {
      return items(
          request, "{\"claims\": [{\"claim\": \"The text.\", \"verdict\": \"supported\"}]}");
    }
    if (request.role() == Role.SCREEN) {
      return items(request, "{\"suspected\": false}");
    }
    return null;
  }

  /** An answer envelope giving every item of the request the same entry. */
  private static String items(LlmRequest request, String entry) {
    java.util.regex.Matcher ids =
        java.util.regex.Pattern.compile("<item id=\"(I[0-9]+)\">")
            .matcher(request.messages().getFirst().text());
    List<String> entries = new ArrayList<>();
    while (ids.find()) {
      entries.add("\"" + ids.group(1) + "\": " + entry);
    }
    return "{\"items\": {" + String.join(", ", entries) + "}}";
  }

  /** The title the fake gives a flow, where a model words the scenario the flow is. */
  static final String FLOW_TITLE = "{{placeholder: scenario title}}";

  /** Whether the request asks to word a sequence flow (ADR 0058), which cites its facts. */
  private static boolean flow(LlmRequest request) {
    String prompt = request.messages().getFirst().text();
    return prompt.startsWith("The flow \"")
        && prompt.contains("\nIts steps, in order:\n")
        && prompt.contains("[F1]");
  }

  /** What the summary prompt says is summarized, such as "class Order in package a.b". */
  private static String subject(String prompt) {
    Matcher m = SUBJECT.matcher(prompt);
    if (!m.find()) {
      return "the unit";
    }
    return m.group(1) != null ? m.group(1) : m.group(2);
  }
}

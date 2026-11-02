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
 * <p>It never calls out. A summary cites the first part's summary and the first fact or excerpt it
 * is given, in the answer form of the knowledge-build epic (section 11.3); Describe gets a
 * placeholder responsibility for every unit, component and element and a placeholder intent for
 * every interaction, each citing the item's first fact; it narrates no flow and words no purpose,
 * so those keep their static prose, except a flow's title, a placeholder in place of the scenario a
 * model would name; and it answers "unknown" to a link left open, so such links stay open.
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

  private static final Pattern SUBJECT = Pattern.compile("(?m)^Unit: (.+)$");

  /** A reference a summarize request shows, such as {@code [F1]} or {@code ref="E1"}. */
  private static final Pattern SUMMARY_REF =
      Pattern.compile("\\[([FS][0-9]+)\\]|ref=\"(E[0-9]+)\"|[\\[ ]([FES][0-9]+)[\\],]");

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
              String described = describe(request);
              if (described != null) {
                return described;
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
              String summary = summarize(request);
              return summary != null ? summary : "{}";
            });
  }

  /**
   * The fake's answer to a Summarize request (knowledge-build epic, section 11.3), or null for
   * another: a placeholder sentence citing the first contained summary, if the item shows one, and
   * one citing its first fact or excerpt; a merge cites the first reference its parts cite.
   */
  static String summarize(LlmRequest request) {
    if (!request.system().contains("Task: summarise code units.")) {
      return null;
    }
    String prompt = request.messages().getFirst().text();
    String subject = subject(prompt);
    List<String> sentences = new ArrayList<>();
    if (prompt.contains("[S1]")) {
      sentences.add(sentence("{{placeholder: summary of " + subject + " from its parts}}.", "S1"));
    }
    Matcher ref = SUMMARY_REF.matcher(prompt);
    while (ref.find()) {
      String found =
          ref.group(1) != null ? ref.group(1) : ref.group(2) != null ? ref.group(2) : ref.group(3);
      if (!found.startsWith("S")) {
        sentences.add(
            sentence("{{placeholder: description of " + subject + " from its facts}}.", found));
        break;
      }
    }
    if (sentences.isEmpty()) {
      return "{\"items\": {\"I1\": {\"omitted\": true}}}";
    }
    return "{\"items\": {\"I1\": {\"sentences\": [" + String.join(", ", sentences) + "]}}}";
  }

  /** What the summary prompt says is summarized, such as "type Order in package a.b". */
  private static String subject(String prompt) {
    Matcher unit = SUBJECT.matcher(prompt);
    return unit.find() ? unit.group(1) : "the unit";
  }

  private static String sentence(String text, String cite) {
    return "{\"text\": \""
        + text.replace("\\", "\\\\").replace("\"", "\\\"")
        + "\", \"cites\": [\""
        + cite
        + "\"]}";
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

  /**
   * A Describe answer that leaves every item out, for a fake that words nothing, or null for
   * another request.
   */
  static String describeNothing(LlmRequest request) {
    return describes(request) ? items(request, "{\"omitted\": true}") : null;
  }

  /** Whether the request asks a Describe role (knowledge-build epic, section 11.4). */
  private static boolean describes(LlmRequest request) {
    return request.system().contains("Task: describe deployable units.")
        || request.system().contains("Task: describe the elements the units use.");
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

  /** The responsibility the fake gives every unit, component and element Describe asks about. */
  static final String RESPONSIBILITY = "{{placeholder: responsibility}}";

  /** The intent the fake gives every interaction Describe asks about. */
  static final String INTENT = "{{placeholder: intent}}";

  private static final Pattern ITEM =
      Pattern.compile("<item id=\"(I[0-9]+)\">\n(.*?)\n</item>", Pattern.DOTALL);
  private static final Pattern FACT_REF = Pattern.compile("\\[(F[0-9]+)\\]");
  private static final Pattern KEY = Pattern.compile("(?m)^\\[([CR][0-9]+)\\] ");

  /**
   * The fake's answer to a Describe request (knowledge-build epic, section 11.4), or null for
   * another: every unit, component, element and interaction gets a placeholder citing the item's
   * first fact, so a fake run stores a responsibility or intent for everything Describe asks about.
   */
  static String describe(LlmRequest request) {
    if (!describes(request)) {
      return null;
    }
    boolean elements = request.system().contains("Task: describe the elements the units use.");
    Matcher items = ITEM.matcher(request.messages().getFirst().text());
    List<String> entries = new ArrayList<>();
    while (items.find()) {
      String body = items.group(2);
      Matcher fact = FACT_REF.matcher(body);
      if (!fact.find()) {
        entries.add("\"" + items.group(1) + "\": {\"omitted\": true}");
        continue;
      }
      String cite = "{\"text\": \"%s\", \"cites\": [\"" + fact.group(1) + "\"]}";
      if (elements) {
        entries.add(
            "\""
                + items.group(1)
                + "\": {\"responsibility\": "
                + cite.formatted(RESPONSIBILITY)
                + "}");
        continue;
      }
      List<String> fields = new ArrayList<>();
      if (!body.contains("say nothing about the unit itself")) {
        fields.add("\"unit\": " + cite.formatted(RESPONSIBILITY));
      }
      List<String> components = new ArrayList<>();
      List<String> interactions = new ArrayList<>();
      Matcher key = KEY.matcher(body);
      while (key.find()) {
        String ref = key.group(1);
        if (ref.startsWith("C")) {
          components.add("\"" + ref + "\": " + cite.formatted(RESPONSIBILITY));
        } else {
          interactions.add("\"" + ref + "\": " + cite.formatted(INTENT));
        }
      }
      if (!components.isEmpty()) {
        fields.add("\"components\": {" + String.join(", ", components) + "}");
      }
      if (!interactions.isEmpty()) {
        fields.add("\"interactions\": {" + String.join(", ", interactions) + "}");
      }
      entries.add("\"" + items.group(1) + "\": {" + String.join(", ", fields) + "}");
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
}

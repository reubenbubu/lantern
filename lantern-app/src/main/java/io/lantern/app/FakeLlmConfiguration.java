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
 * every interaction, each citing the item's first fact; it judges no flow significant, so each
 * document draws its highest ranked, narrates no flow and words no purpose, so those keep their
 * static prose, except a flow's title, a placeholder in place of the scenario a model would name
 * (it omits the system's purpose and key fact); and it submits "unknown" for every link left open
 * (knowledge-build epic, section 11.1), citing the calling unit's first fact and the first
 * candidate's, so such links stay open and the answers are cached.
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

  private static final Pattern SUBJECT = Pattern.compile("(?m)^(?:Unit: (.+)|unit = \"(.+)\")$");

  /** A reference a summarize request shows, such as {@code [F1]} or {@code ref="E1"}. */
  private static final Pattern SUMMARY_REF =
      Pattern.compile("\\[([FS][0-9]+)\\]|ref ?= ?\"(E[0-9]+)\"|[\\[ ]([FES][0-9]+)[\\],]");

  @Bean
  FakeLlmProvider fakeLlmProvider() {
    // It reports cache writes and reads as a provider with prompt caching would (#357), for the
    // models whose settings say they cache.
    return new FakeLlmProvider()
        .simulatingPromptCache()
        .callingTools(FakeLlmConfiguration::toolCalls)
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
              String named = nameGroups(request);
              if (named != null) {
                return named;
              }
              String flows = flows(request);
              if (flows != null) {
                return flows;
              }
              // It words no purpose and no key fact (ADR 0109).
              if (request.system().contains(PURPOSE_TASK)) {
                return "{\"items\": {\"I1\": {\"omitted\": true}}}";
              }
              if (request.role() == Role.INFER_INTENT || request.role() == Role.COMPOSE) {
                return "{}";
              }
              if (request.role() == Role.RESOLVE) {
                // After its submit, a Resolve conversation ends.
                return "Done.";
              }
              if (request.role() == Role.INVESTIGATE) {
                // It finds nothing: each lead is asked again alone, then unclear (ADR 0110).
                return "Done.";
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
    String subject = subject(request.messages().getFirst().text());
    return summarize(
        request,
        "{{placeholder: summary of " + subject + " from its parts}}.",
        "{{placeholder: description of " + subject + " from its facts}}.");
  }

  /**
   * A Summarize answer as {@link #summarize(LlmRequest)} gives, with the given sentences, or null
   * for another request.
   */
  static String summarize(LlmRequest request, String fromParts, String fromFacts) {
    if (!request.system().contains("Task: summarise code units.")) {
      return null;
    }
    String prompt = request.messages().getFirst().text();
    List<String> sentences = new ArrayList<>();
    if (prompt.contains("[S1]")) {
      sentences.add(sentence(fromParts, "S1"));
    }
    Matcher ref = SUMMARY_REF.matcher(prompt);
    while (ref.find()) {
      String found =
          ref.group(1) != null ? ref.group(1) : ref.group(2) != null ? ref.group(2) : ref.group(3);
      if (!found.startsWith("S")) {
        sentences.add(sentence(fromFacts, found));
        break;
      }
    }
    // In TOML, as the role asks since #872.
    if (sentences.isEmpty()) {
      return "[I1]\nomitted = true\n";
    }
    return "[I1]\n" + String.join("", sentences);
  }

  /** What the summary prompt says is summarized, such as "type Order in package a.b". */
  private static String subject(String prompt) {
    Matcher unit = SUBJECT.matcher(prompt);
    return unit.find() ? (unit.group(1) != null ? unit.group(1) : unit.group(2)) : "the unit";
  }

  private static String sentence(String text, String cite) {
    return "[[I1.sentences]]\ntext = "
        + io.lantern.harness.Toml.basic(text)
        + "\ncites = ["
        + io.lantern.harness.Toml.basic(cite)
        + "]\n";
  }

  /**
   * The fake's answer to a model check (ADR 0101) or the wording judge (ADR 0111), or null for
   * another role: every text supported and passed, and nothing addressed to an AI, so fake runs
   * show what the checks cost and change no document.
   */
  static String modelCheck(LlmRequest request) {
    // The support judge runs on its own role or, since #751, on the writer's: its task tells it.
    if (request.role() == Role.JUDGE || request.system().contains("Task: check support.")) {
      return items(
          request, "{\"claims\": [{\"claim\": \"The text.\", \"verdict\": \"supported\"}]}");
    }
    if (request.role() == Role.SCREEN) {
      return items(request, "{\"suspected\": false}");
    }
    if (request.role() == Role.WORDING_JUDGE) {
      return items(
          request, "{\"reasoning\": \"The fake passes every text.\", \"verdict\": \"pass\"}");
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

  /** The fact reference after which a candidate's facts are listed: {@code [K1] service:...}. */
  private static final Pattern CANDIDATE =
      Pattern.compile("(?m)^\\[K1\\] [^\\n]*\\n<facts>\\n\\[(F[0-9]+)\\]");

  /** The fact block 4 of a Resolve request lists first: the calling unit's. */
  private static final Pattern CALLER =
      Pattern.compile("The calling unit: [^\\n]*\\n[^\\n]*\\n<facts>\\n\\[(F[0-9]+)\\]");

  /**
   * The tools the fake calls: on a Resolve conversation's first turn, {@code submit} with "unknown"
   * for every item ({@link #resolve(LlmRequest, String)}); nothing for any other request.
   */
  static List<io.lantern.llm.LlmToolCall> toolCalls(LlmRequest request) {
    return resolve(request, "unknown");
  }

  /**
   * A Resolve conversation's first turn as the fake answers it: {@code submit} with the given pick
   * ({@code unknown}, or a candidate such as {@code K1}) for every item, citing the calling unit's
   * first fact and the item's first candidate's (an item without such facts is omitted); nothing
   * for any other request, or once it has submitted.
   */
  static List<io.lantern.llm.LlmToolCall> resolve(LlmRequest request, String pick) {
    if (request.role() != Role.RESOLVE || request.messages().size() != 1) {
      return List.of();
    }
    String prompt = request.messages().getFirst().text();
    Matcher caller = CALLER.matcher(prompt);
    String callerRef = caller.find() ? caller.group(1) : null;
    List<String> entries = new ArrayList<>();
    Matcher item = ITEM.matcher(prompt);
    while (item.find()) {
      Matcher candidate = CANDIDATE.matcher(item.group(2));
      String entry =
          callerRef != null && candidate.find()
              ? "{\"pick\": \""
                  + pick
                  + "\", \"confidence\": \"low\", \"reason\": \""
                  + RESOLVE_REASON
                  + "\","
                  + " \"cites\": [\""
                  + callerRef
                  + "\", \""
                  + candidate.group(1)
                  + "\"]}"
              : "{\"omitted\": true}";
      entries.add("\"" + item.group(1) + "\": " + entry);
    }
    return List.of(
        new io.lantern.llm.LlmToolCall(
            "fake-submit",
            "submit",
            tools.jackson.databind.json.JsonMapper.builder()
                .build()
                .readTree("{\"items\": {" + String.join(", ", entries) + "}}")));
  }

  /**
   * The reason the fake gives every pick: long enough for Resolve's contract, like every text the
   * fake writes (ADR 0118).
   */
  static final String RESOLVE_REASON =
      "A fake model picks this candidate without reading the code.";

  /** The responsibility the fake gives every unit, component and element Describe asks about. */
  static final String RESPONSIBILITY =
      "{{placeholder: the responsibility a model words from the facts of this part}}";

  /** The intent the fake gives every interaction Describe asks about. */
  static final String INTENT = "{{placeholder: intent of this interaction}}";

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

  /** The name the fake gives every group Name groups asks about. */
  static final String GROUP_NAME = "{{placeholder: group name}}";

  /** The responsibility the fake gives every group. */
  static final String GROUP_RESPONSIBILITY =
      "{{placeholder: the responsibility a model words for this group from its facts}}";

  private static final Pattern LINE_KEY = Pattern.compile("(?m)^\\[(R[0-9]+)\\] ");

  /**
   * The fake's answer to a Name groups request (knowledge-build epic, section 11.8), or null for
   * another: every group gets a placeholder name and responsibility, and each of its lines a
   * placeholder intent, citing the item's first fact; it suggests no merge.
   */
  static String nameGroups(LlmRequest request) {
    if (!request.system().contains("Task: name groups.")) {
      return null;
    }
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
      List<String> lines = new ArrayList<>();
      Matcher key = LINE_KEY.matcher(body);
      while (key.find()) {
        lines.add("\"" + key.group(1) + "\": " + cite.formatted(INTENT));
      }
      entries.add(
          "\""
              + items.group(1)
              + "\": {\"name\": "
              + cite.formatted(GROUP_NAME)
              + ", \"responsibility\": "
              + cite.formatted(GROUP_RESPONSIBILITY)
              + (lines.isEmpty() ? "" : ", \"lines\": {" + String.join(", ", lines) + "}")
              + "}");
    }
    return "{\"items\": {" + String.join(", ", entries) + "}}";
  }

  /** How the purpose and key-fact role's task starts. */
  static final String PURPOSE_TASK = "Task: say what the system is for and what matters most";

  /** The title the fake gives a flow, where a model words the scenario the flow is. */
  static final String FLOW_TITLE = "{{placeholder: scenario title}}";

  /**
   * The fake's answer about flows (knowledge-build epic, section 11.5; ADR 0108), or null for
   * another request: it judges no flow significant, so each scope draws its highest ranked, and it
   * gives each flow it narrates only a title, citing the item's first fact.
   */
  static String flows(LlmRequest request) {
    boolean significance = request.system().contains("Task: choose the flows worth drawing.");
    if (!significance && !request.system().contains("Task: narrate flows.")) {
      return null;
    }
    Matcher items = ITEM.matcher(request.messages().getFirst().text());
    List<String> entries = new ArrayList<>();
    while (items.find()) {
      Matcher fact = FACT_REF.matcher(items.group(2));
      if (significance || !fact.find()) {
        entries.add("\"" + items.group(1) + "\": {\"omitted\": true}");
      } else {
        // A flow's title is a heading: marked as the fake's, so the reader ignores it.
        entries.add(
            "\""
                + items.group(1)
                + "\": {\"title\": {\"text\": \""
                + FLOW_TITLE
                + "\", \"cites\": [\""
                + fact.group(1)
                + "\"]}}");
      }
    }
    return "{\"items\": {" + String.join(", ", entries) + "}}";
  }
}

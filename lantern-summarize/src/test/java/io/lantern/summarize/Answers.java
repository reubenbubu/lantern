package io.lantern.summarize;

import io.lantern.llm.LlmRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Model answers in the form the summarize role asks for (knowledge-build epic, section 11.3),
 * written in a shorter form in tests: sentences that each end with the references they cite, such
 * as {@code "Sells books. [F1] It is built on [S1]."}. A sentence that cites nothing is sent with
 * no citations, which the checks leave out. A line such as {@code Purpose: evidenced} before the
 * text, which the model no longer writes, is left out. The answer is written in TOML, as the role
 * asks since #872; an answer that is already JSON is sent as written.
 */
final class Answers {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final Pattern PURPOSE =
      Pattern.compile(
          "\\A[#*_\\s]*purpose[*_\\s]*:[*_\\s]*(?:evidenced|inferred)[*_]*[.,;:]?[*_]*"
              + "(?:[ \\t]*\\([^)\\n]*\\))?[.,;:]?\\s*",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern CITED = Pattern.compile("(.*?)\\[([^\\]]*)\\]\\.?", Pattern.DOTALL);

  private static final Pattern BOUNDARY = Pattern.compile("(?<=[.!?])\\s+(?=\\S)");

  private Answers() {}

  /** The model's answers, each in the role's form. */
  static Function<LlmRequest, String> epic(Function<LlmRequest, String> answers) {
    return request -> of(answers.apply(request));
  }

  /** The answer in the role's form. */
  static String epic(String answer) {
    return of(answer);
  }

  /** The answer in the role's form. */
  static String of(String answer) {
    if (answer == null || answer.isBlank() || answer.strip().startsWith("{")) {
      return answer;
    }
    String text = PURPOSE.matcher(answer).replaceFirst("");
    ArrayNode sentences = JSON.createArrayNode();
    Matcher cited = CITED.matcher(text);
    int end = 0;
    while (cited.find()) {
      List<String> parts = split(cited.group(1));
      for (int i = 0; i < parts.size(); i++) {
        List<String> cites = new ArrayList<>();
        if (i == parts.size() - 1) {
          for (String cite : cited.group(2).split(",")) {
            if (!cite.isBlank()) {
              cites.add(cite.strip());
            }
          }
        }
        sentence(sentences, parts.get(i), cites);
      }
      end = cited.end();
    }
    for (String rest : split(text.substring(end))) {
      sentence(sentences, rest, List.of());
    }
    StringBuilder toml = new StringBuilder("[").append(Prompt.ITEM).append("]\n");
    for (tools.jackson.databind.JsonNode sentence : sentences) {
      toml.append("[[").append(Prompt.ITEM).append(".sentences]]\n");
      toml.append("text = ")
          .append(io.lantern.harness.Toml.basic(sentence.path("text").asString()));
      toml.append("\ncites = [");
      List<String> cites = new ArrayList<>();
      sentence.path("cites").forEach(c -> cites.add(io.lantern.harness.Toml.basic(c.asString())));
      toml.append(String.join(", ", cites)).append("]\n");
    }
    return toml.toString();
  }

  private static List<String> split(String text) {
    List<String> parts = new ArrayList<>();
    for (String part : BOUNDARY.split(text.strip())) {
      if (!part.isBlank()) {
        parts.add(part.strip());
      }
    }
    if (parts.isEmpty() && !text.isBlank()) {
      parts.add(text.strip());
    }
    return parts;
  }

  private static void sentence(ArrayNode sentences, String text, List<String> cites) {
    ObjectNode sentence = sentences.addObject();
    sentence.put("text", text);
    ArrayNode refs = sentence.putArray("cites");
    cites.forEach(refs::add);
  }
}

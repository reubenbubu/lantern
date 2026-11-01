package io.lantern.summarize;

import io.lantern.core.fact.EntityId;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** A summary as the harness's answer cache stores it (ADR 0100), and back. */
final class SummaryJson {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private SummaryJson() {}

  static String write(Summary summary) {
    ObjectNode node = JSON.createObjectNode();
    node.put("key", summary.key());
    node.put("entity", summary.entity().toString());
    node.put("level", summary.level().name());
    node.put("text", summary.text());
    node.put("purpose", summary.purpose().name());
    node.put("confidence", summary.confidence());
    strings(node.putArray("citedFacts"), summary.citedFacts());
    strings(node.putArray("citedSummaries"), summary.citedSummaries());
    strings(node.putArray("inputFacts"), summary.inputFacts());
    strings(node.putArray("inputSummaries"), summary.inputSummaries());
    node.put("promptVersion", summary.promptVersion());
    node.put("provider", summary.provider());
    node.put("model", summary.model());
    return JSON.writeValueAsString(node);
  }

  static Summary read(String json) {
    JsonNode node = JSON.readTree(json);
    return new Summary(
        node.get("key").asString(),
        EntityId.parse(node.get("entity").asString()),
        SummaryLevel.valueOf(node.get("level").asString()),
        node.get("text").asString(),
        Summary.Purpose.valueOf(node.get("purpose").asString()),
        node.get("confidence").asDouble(),
        strings(node.get("citedFacts")),
        strings(node.get("citedSummaries")),
        strings(node.get("inputFacts")),
        strings(node.get("inputSummaries")),
        node.get("promptVersion").asString(),
        node.get("provider").asString(),
        node.get("model").asString());
  }

  private static void strings(ArrayNode array, List<String> values) {
    values.forEach(array::add);
  }

  private static List<String> strings(JsonNode array) {
    List<String> values = new ArrayList<>();
    array.forEach(value -> values.add(value.asString()));
    return values;
  }
}

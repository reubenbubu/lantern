package io.lantern.harness;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The answer envelope of every packed role (knowledge-build epic, section 11.0): {@code {"items":
 * {"<item id>": {...}}}}.
 */
final class Envelope {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private Envelope() {}

  /** The entries of the answer by item ID, or empty if it is not one envelope. */
  static Optional<Map<String, JsonNode>> entries(String text) {
    return entries(parse(text));
  }

  /** The entries of an envelope by item ID, or empty if it is not one. */
  static Optional<Map<String, JsonNode>> entries(JsonNode root) {
    if (root == null || !root.isObject() || !root.path("items").isObject()) {
      return Optional.empty();
    }
    Map<String, JsonNode> entries = new LinkedHashMap<>();
    root.get("items").properties().forEach(e -> entries.put(e.getKey(), e.getValue()));
    return Optional.of(entries);
  }

  /** The one JSON object of the text, fences around it allowed, or null. */
  static JsonNode parse(String text) {
    String trimmed = text.strip();
    if (trimmed.startsWith("```")) {
      int start = trimmed.indexOf('\n');
      int end = trimmed.lastIndexOf("```");
      if (start > 0 && end > start) {
        trimmed = trimmed.substring(start + 1, end).strip();
      }
    }
    try {
      return JSON.readTree(trimmed);
    } catch (JacksonException e) {
      return null;
    }
  }
}

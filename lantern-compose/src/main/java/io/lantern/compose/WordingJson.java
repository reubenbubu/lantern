package io.lantern.compose;

import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** A wording answer's items as the harness's answer cache stores them (ADR 0100), and back. */
final class WordingJson {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final TypeReference<Map<String, WordingAnswer.Item>> ITEMS =
      new TypeReference<>() {};

  private WordingJson() {}

  static String write(Map<String, WordingAnswer.Item> items) {
    return JSON.writeValueAsString(new java.util.TreeMap<>(items));
  }

  static Map<String, WordingAnswer.Item> read(String json) {
    return JSON.readValue(json, ITEMS);
  }
}

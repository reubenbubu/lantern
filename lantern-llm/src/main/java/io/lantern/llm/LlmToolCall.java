package io.lantern.llm;

import java.util.Objects;
import tools.jackson.databind.JsonNode;

/**
 * A call of a tool the model asked for in its answer.
 *
 * @param id the provider's identifier of the call, which its result refers to
 * @param name the tool's name
 * @param input the input the model gave, a JSON object
 */
public record LlmToolCall(String id, String name, JsonNode input) {

  public LlmToolCall {
    id = ModelConfig.nonBlank("Tool call id", id);
    name = ModelConfig.nonBlank("Tool name", name);
    Objects.requireNonNull(input, "input");
    input = input.deepCopy();
  }

  @Override
  public JsonNode input() {
    return input.deepCopy();
  }
}

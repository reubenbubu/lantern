package io.lantern.llm;

import java.util.Objects;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * A tool the model may call during a request (ADR 0081). Requests with tools need a model with
 * {@link Capability#TOOL_USE}.
 *
 * @param name the name the model calls it by: letters, digits, {@code _} and {@code -}, at most 64
 * @param description what it does and when to use it, for the model
 * @param inputSchema the JSON Schema of its input, an object schema
 */
public record LlmTool(String name, String description, JsonNode inputSchema) {

  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

  public LlmTool {
    Objects.requireNonNull(name, "name");
    if (!NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("Invalid tool name: '" + name + "'");
    }
    description = ModelConfig.nonBlank("Tool description", description);
    Objects.requireNonNull(inputSchema, "inputSchema");
    if (!inputSchema.isObject()
        || !inputSchema.path("type").isString()
        || !"object".equals(inputSchema.get("type").stringValue())) {
      throw new IllegalArgumentException("A tool's input schema must be an object schema: " + name);
    }
    inputSchema = inputSchema.deepCopy();
  }

  @Override
  public JsonNode inputSchema() {
    return inputSchema.deepCopy();
  }
}

package io.lantern.harness.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Validates JSON against the subset of JSON Schema the roles' schemas use (knowledge-build epic,
 * section 11): {@code type}, {@code required}, {@code properties}, {@code additionalProperties}
 * (false or a schema), {@code enum}, {@code const}, {@code minLength}, {@code maxLength}, {@code
 * pattern}, {@code items}, {@code minItems}, {@code maxItems}, {@code minimum}, {@code maximum} and
 * {@code oneOf}. Anything else in a schema is ignored.
 */
public final class JsonSchema {

  private JsonSchema() {}

  /**
   * The per-item schema of a role's answer schema, as the epic writes it (section 11): the schema
   * of each entry of {@code items}, with every {@code $ref} to the root's {@code $defs} replaced by
   * what it refers to.
   */
  public static JsonNode itemSchema(JsonNode answerSchema) {
    JsonNode item = answerSchema.path("properties").path("items").path("additionalProperties");
    if (!item.isObject()) {
      throw new IllegalArgumentException("Not an answer schema with items: " + answerSchema);
    }
    return resolved(item, answerSchema.path("$defs"));
  }

  private static JsonNode resolved(JsonNode node, JsonNode defs) {
    if (node.isObject()) {
      JsonNode ref = node.get("$ref");
      if (ref != null && ref.isString() && ref.asString().startsWith("#/$defs/")) {
        JsonNode target = defs.get(ref.asString().substring("#/$defs/".length()));
        if (target == null) {
          throw new IllegalArgumentException("Unknown reference " + ref.asString());
        }
        return resolved(target, defs);
      }
      tools.jackson.databind.node.ObjectNode copy =
          tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode();
      for (var field : node.properties()) {
        copy.set(field.getKey(), resolved(field.getValue(), defs));
      }
      return copy;
    }
    if (node.isArray()) {
      tools.jackson.databind.node.ArrayNode copy =
          tools.jackson.databind.json.JsonMapper.builder().build().createArrayNode();
      node.forEach(element -> copy.add(resolved(element, defs)));
      return copy;
    }
    return node;
  }

  /** What in the value breaks the schema, each with its path; empty if it matches. */
  public static List<String> violations(JsonNode schema, JsonNode value) {
    return violationsAt(schema, value).stream().map(Violation::message).toList();
  }

  /**
   * A way the value breaks the schema.
   *
   * @param path where, as a dot path such as {@code components.C3.text}; empty for the whole value
   * @param message what is wrong, naming the place
   */
  public record Violation(String path, String message) {}

  /** The ways the value breaks the schema, each with where. */
  public static List<Violation> violationsAt(JsonNode schema, JsonNode value) {
    List<Violation> found = new ArrayList<>();
    check(schema, value, "", found);
    return found;
  }

  private static void check(JsonNode schema, JsonNode value, String path, List<Violation> found) {
    if (schema == null || !schema.isObject()) {
      return;
    }
    String at = path.isEmpty() ? "the answer" : path;
    if (schema.has("oneOf")) {
      int matching = 0;
      List<Violation> first = null;
      for (JsonNode branch : schema.get("oneOf")) {
        List<Violation> branchFound = new ArrayList<>();
        check(branch, value, path, branchFound);
        if (branchFound.isEmpty()) {
          matching++;
        } else if (first == null) {
          first = branchFound;
        }
      }
      if (matching != 1) {
        if (matching == 0 && first != null) {
          found.addAll(first);
        } else {
          found.add(new Violation(path, at + " matches more than one form"));
        }
      }
    }
    if (schema.has("const") && !schema.get("const").equals(value)) {
      found.add(new Violation(path, at + " must be " + schema.get("const")));
    }
    if (schema.has("enum")) {
      boolean any = false;
      for (JsonNode option : schema.get("enum")) {
        any |= option.equals(value);
      }
      if (!any) {
        found.add(new Violation(path, at + " must be one of " + schema.get("enum")));
      }
    }
    if (schema.has("type") && !typed(schema.get("type").asString(), value)) {
      found.add(new Violation(path, at + " must be of type " + schema.get("type").asString()));
      return;
    }
    if (value.isString()) {
      String text = value.asString();
      int length = text.codePointCount(0, text.length());
      if (schema.has("maxLength") && length > schema.get("maxLength").asInt()) {
        found.add(
            new Violation(
                path, at + " is longer than " + schema.get("maxLength").asInt() + " characters"));
      }
      if (schema.has("minLength") && length < schema.get("minLength").asInt()) {
        found.add(
            new Violation(
                path, at + " is shorter than " + schema.get("minLength").asInt() + " characters"));
      }
      if (schema.has("pattern")
          && !Pattern.compile(schema.get("pattern").asString()).matcher(text).find()) {
        found.add(new Violation(path, at + " does not match " + schema.get("pattern").asString()));
      }
    }
    if (value.isNumber()) {
      if (schema.has("minimum") && value.asDouble() < schema.get("minimum").asDouble()) {
        found.add(new Violation(path, at + " is below " + schema.get("minimum")));
      }
      if (schema.has("maximum") && value.asDouble() > schema.get("maximum").asDouble()) {
        found.add(new Violation(path, at + " is above " + schema.get("maximum")));
      }
    }
    if (value.isArray()) {
      if (schema.has("minItems") && value.size() < schema.get("minItems").asInt()) {
        found.add(
            new Violation(
                path, at + " has fewer than " + schema.get("minItems").asInt() + " entries"));
      }
      if (schema.has("maxItems") && value.size() > schema.get("maxItems").asInt()) {
        found.add(
            new Violation(
                path, at + " has more than " + schema.get("maxItems").asInt() + " entries"));
      }
      if (schema.has("items")) {
        for (int i = 0; i < value.size(); i++) {
          check(schema.get("items"), value.get(i), path + "[" + i + "]", found);
        }
      }
    }
    if (value.isObject()) {
      if (schema.has("required")) {
        for (JsonNode name : schema.get("required")) {
          if (!value.has(name.asString())) {
            found.add(new Violation(path, at + " lacks " + name.asString()));
          }
        }
      }
      JsonNode properties = schema.get("properties");
      JsonNode additional = schema.get("additionalProperties");
      for (Map.Entry<String, JsonNode> field : value.properties()) {
        String fieldPath = path.isEmpty() ? field.getKey() : path + "." + field.getKey();
        if (properties != null && properties.has(field.getKey())) {
          check(properties.get(field.getKey()), field.getValue(), fieldPath, found);
        } else if (additional != null && additional.isBoolean() && !additional.asBoolean()) {
          found.add(
              new Violation(fieldPath, at + " has a field it may not have: " + field.getKey()));
        } else if (additional != null && additional.isObject()) {
          check(additional, field.getValue(), fieldPath, found);
        }
      }
    }
  }

  private static boolean typed(String type, JsonNode value) {
    return switch (type) {
      case "object" -> value.isObject();
      case "array" -> value.isArray();
      case "string" -> value.isString();
      case "boolean" -> value.isBoolean();
      case "integer" -> value.isIntegralNumber();
      case "number" -> value.isNumber();
      case "null" -> value.isNull();
      default -> true;
    };
  }
}

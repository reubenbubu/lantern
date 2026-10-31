package io.lantern.core.vocabulary;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads and writes single fact attributes by their schema's value type. The records generated from
 * attribute schemas ({@link AttributeRecords}) use it, so they hold no JSON handling of their own.
 * An absent or {@code null} attribute reads as {@code null}, and a {@code null} value is not
 * written.
 */
public final class AttributeValues {

  private AttributeValues() {}

  public static String string(ObjectNode attributes, String name) {
    return read(attributes, name, JsonNode::isString, JsonNode::stringValue, "a string");
  }

  public static Long integer(ObjectNode attributes, String name) {
    return read(attributes, name, JsonNode::isIntegralNumber, JsonNode::longValue, "an integer");
  }

  public static BigDecimal number(ObjectNode attributes, String name) {
    return read(attributes, name, JsonNode::isNumber, JsonNode::decimalValue, "a number");
  }

  public static Boolean bool(ObjectNode attributes, String name) {
    return read(attributes, name, JsonNode::isBoolean, JsonNode::booleanValue, "a boolean");
  }

  public static ObjectNode object(ObjectNode attributes, String name) {
    return read(attributes, name, JsonNode::isObject, n -> (ObjectNode) n.deepCopy(), "an object");
  }

  /** An array attribute whose elements are of any type. */
  public static List<JsonNode> array(ObjectNode attributes, String name) {
    return elements(attributes, name, n -> true, JsonNode::deepCopy, "any value");
  }

  public static List<String> strings(ObjectNode attributes, String name) {
    return elements(attributes, name, JsonNode::isString, JsonNode::stringValue, "strings");
  }

  public static List<Long> integers(ObjectNode attributes, String name) {
    return elements(attributes, name, JsonNode::isIntegralNumber, JsonNode::longValue, "integers");
  }

  public static List<BigDecimal> numbers(ObjectNode attributes, String name) {
    return elements(attributes, name, JsonNode::isNumber, JsonNode::decimalValue, "numbers");
  }

  public static List<Boolean> bools(ObjectNode attributes, String name) {
    return elements(attributes, name, JsonNode::isBoolean, JsonNode::booleanValue, "booleans");
  }

  public static List<ObjectNode> objects(ObjectNode attributes, String name) {
    return elements(
        attributes, name, JsonNode::isObject, n -> (ObjectNode) n.deepCopy(), "objects");
  }

  public static List<List<JsonNode>> arrays(ObjectNode attributes, String name) {
    return elements(
        attributes,
        name,
        JsonNode::isArray,
        n -> {
          List<JsonNode> inner = new ArrayList<>();
          n.forEach(e -> inner.add(e.deepCopy()));
          return List.copyOf(inner);
        },
        "arrays");
  }

  /** Writes a value of any of the types the readers return; {@code null} writes nothing. */
  public static void put(ObjectNode attributes, String name, Object value) {
    if (value != null) {
      attributes.set(name, toJson(attributes, value));
    }
  }

  private static JsonNode toJson(ObjectNode factory, Object value) {
    return switch (value) {
      case String s -> factory.stringNode(s);
      case Long l -> factory.numberNode(l);
      case BigDecimal d -> factory.numberNode(d);
      case Boolean b -> factory.booleanNode(b);
      case JsonNode n -> n.deepCopy();
      case List<?> list -> {
        ArrayNode array = factory.arrayNode();
        list.forEach(e -> array.add(toJson(factory, e)));
        yield array;
      }
      default ->
          throw new IllegalArgumentException(
              "Not an attribute value: " + value.getClass().getName());
    };
  }

  private static <T> T read(
      ObjectNode attributes,
      String name,
      Predicate<JsonNode> is,
      Function<JsonNode, T> value,
      String expected) {
    JsonNode node = attributes.get(name);
    if (node == null || node.isNull()) {
      return null;
    }
    if (!is.test(node)) {
      throw new IllegalArgumentException("Attribute '" + name + "' must be " + expected);
    }
    return value.apply(node);
  }

  private static <T> List<T> elements(
      ObjectNode attributes,
      String name,
      Predicate<JsonNode> is,
      Function<JsonNode, T> value,
      String expected) {
    JsonNode node = attributes.get(name);
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isArray()) {
      throw new IllegalArgumentException("Attribute '" + name + "' must be an array");
    }
    List<T> result = new ArrayList<>();
    for (JsonNode element : node) {
      if (!is.test(element)) {
        throw new IllegalArgumentException("Elements of '" + name + "' must be " + expected);
      }
      result.add(value.apply(element));
    }
    return List.copyOf(result);
  }
}

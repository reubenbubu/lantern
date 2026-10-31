package io.lantern.link;

import io.lantern.core.fact.Fact;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Attribute reads by name, for tests, which may read facts as they like. */
final class TestAttributes {

  private TestAttributes() {}

  static String text(Fact fact, String attribute) {
    JsonNode value = fact.attributes().get(attribute);
    return value == null || !value.isString() ? null : value.stringValue();
  }

  static boolean flag(Fact fact, String attribute) {
    return fact.attributes().path(attribute).asBoolean(false);
  }

  /** The strings of an array attribute, or none. */
  static List<String> texts(Fact fact, String attribute) {
    JsonNode value = fact.attributes().get(attribute);
    List<String> result = new ArrayList<>();
    if (value != null && value.isArray()) {
      value.forEach(v -> result.add(v.asString()));
    }
    return result;
  }
}

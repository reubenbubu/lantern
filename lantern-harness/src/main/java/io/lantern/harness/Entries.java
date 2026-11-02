package io.lantern.harness;

import io.lantern.harness.verify.Rejection;
import java.util.Collection;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * An item's entry put back together after a repair turn (knowledge-build epic, section 11.0, Repair
 * turn): the parts of the first answer that were accepted, with the corrections over them. A field
 * is a path of keys joined by dots, such as {@code components.C2}; an empty field is the whole
 * item.
 */
final class Entries {

  private Entries() {}

  /** Whether one of the rejections is of the whole item, which no field-level repair can keep. */
  static boolean whole(Collection<Rejection> rejections) {
    return rejections.stream().anyMatch(r -> r.field().isEmpty());
  }

  /** The entry without the rejected fields. */
  static ObjectNode without(JsonNode entry, Collection<Rejection> rejections) {
    ObjectNode copy = entry.isObject() ? ((ObjectNode) entry).deepCopy() : object(entry);
    for (Rejection rejection : rejections) {
      remove(copy, rejection.field().split("\\."), 0);
    }
    return copy;
  }

  /**
   * The base with the correction over it: a field the correction holds replaces the base's, an
   * object field is merged key by key.
   */
  static ObjectNode overlay(ObjectNode base, JsonNode correction) {
    ObjectNode merged = base.deepCopy();
    if (correction == null || !correction.isObject()) {
      return merged;
    }
    for (var field : correction.properties()) {
      JsonNode mine = merged.get(field.getKey());
      JsonNode theirs = field.getValue();
      if (mine != null && mine.isObject() && theirs.isObject() && !text(theirs)) {
        merged.set(field.getKey(), overlay((ObjectNode) mine, theirs));
      } else {
        merged.set(field.getKey(), theirs.deepCopy());
      }
    }
    return merged;
  }

  /** Whether the entry holds nothing. */
  static boolean empty(JsonNode entry) {
    return entry == null || !entry.isObject() || entry.isEmpty();
  }

  private static boolean text(JsonNode node) {
    return node.has("text") || node.has("cites");
  }

  private static void remove(ObjectNode node, String[] path, int at) {
    if (path.length == 0 || path[at].isEmpty()) {
      return;
    }
    if (at == path.length - 1) {
      node.remove(path[at]);
      return;
    }
    JsonNode child = node.get(path[at]);
    if (child instanceof ObjectNode object) {
      remove(object, path, at + 1);
      if (object.isEmpty()) {
        node.remove(path[at]);
      }
    }
  }

  private static ObjectNode object(JsonNode entry) {
    return tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode();
  }
}

package io.lantern.harness;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What is wrong with an answer that is not the one JSON object a role asks for, said so the model
 * can fix it in the repair turn (#685): "not one JSON object" alone made it send the same text
 * again, often one closing brace too many.
 */
public final class JsonFaults {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private JsonFaults() {}

  /**
   * The fault of an answer that should be {@code {"items": {"<item>": {...}}}}, in a few words, or
   * {@code null} when it has none.
   *
   * @param item the ID the answer must have an entry for, or {@code null} for any
   */
  public static String of(String text, String item) {
    String body = unfenced(text);
    if (body.isEmpty()) {
      return "the answer is empty";
    }
    if (body.charAt(0) != '{') {
      return "it starts with text, not with {; answer with the JSON object alone";
    }
    int end = endOfObject(body);
    if (end < 0) {
      return "the object is not closed: a { or [ is never closed, or a string never ends";
    }
    String rest = body.substring(end).strip();
    JsonNode root;
    try {
      root = JSON.readTree(body.substring(0, end));
    } catch (JacksonException e) {
      String message = e.getOriginalMessage();
      return "it is not valid JSON ("
          + (message == null ? "unreadable" : message.lines().findFirst().orElse(message))
          + ")";
    }
    if (!rest.isEmpty()) {
      String shown = rest.length() > 20 ? rest.substring(0, 20) + "…" : rest;
      return "a complete object is followed by more text, "
          + shown
          + "; close each bracket once and write nothing after the object";
    }
    JsonNode items = root.get("items");
    if (items == null || !items.isObject()) {
      return "it has no object under \"items\"";
    }
    if (item != null && !items.has(item)) {
      String keys = String.join(", ", items.propertyNames());
      return "it has no entry \""
          + item
          + "\" under \"items\""
          + (keys.isEmpty() ? "" : "; key each entry by its item ID, not by " + keys);
    }
    return null;
  }

  /**
   * The first complete JSON object an answer opens with, fences around it allowed, and the text
   * that follows it (#744); null if the answer does not open with one that closes and reads.
   *
   * @param object the object
   * @param rest what follows it, stripped; empty if nothing
   */
  public record First(JsonNode object, String rest) {}

  /** The first complete JSON object of the answer and what follows it, or null (#744). */
  public static First first(String text) {
    String body = unfenced(text);
    if (body.isEmpty() || body.charAt(0) != '{') {
      return null;
    }
    int end = endOfObject(body);
    if (end < 0) {
      return null;
    }
    try {
      JsonNode node = JSON.readTree(body.substring(0, end));
      return node != null && node.isObject() ? new First(node, body.substring(end).strip()) : null;
    } catch (JacksonException e) {
      return null;
    }
  }

  /** The text without a Markdown code fence around it, as {@link Envelope#parse} reads it. */
  static String unfenced(String text) {
    String trimmed = text == null ? "" : text.strip();
    if (trimmed.startsWith("```")) {
      int start = trimmed.indexOf('\n');
      int end = trimmed.lastIndexOf("```");
      if (start > 0 && end > start) {
        trimmed = trimmed.substring(start + 1, end).strip();
      }
    }
    return trimmed;
  }

  /** The index just past the object that opens the text, or -1 if it never closes. */
  private static int endOfObject(String text) {
    int depth = 0;
    boolean inString = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (inString) {
        if (c == '\\') {
          i++;
        } else if (c == '"') {
          inString = false;
        }
      } else if (c == '"') {
        inString = true;
      } else if (c == '{' || c == '[') {
        depth++;
      } else if (c == '}' || c == ']') {
        depth--;
        if (depth == 0) {
          return i + 1;
        }
      }
    }
    return -1;
  }
}

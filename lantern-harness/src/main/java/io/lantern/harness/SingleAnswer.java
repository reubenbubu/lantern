package io.lantern.harness;

import io.lantern.llm.LlmTool;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * A conversation that answers one item through one answer tool, in place of {@code submit} (#892):
 * the answer is text in the form the instructions show, read into the entry the role's checks
 * verify; an answer that passes is recorded and ends the conversation, one that fails gets its
 * problems back, and the last failed attempt ends it with no answer. An answer the model writes as
 * text instead of calling the tool is read the same way; a turn with neither gets one reminder.
 */
public interface SingleAnswer {

  /** The answer tool's definition. */
  LlmTool tool();

  /**
   * The answer's entry, read from the tool's input or the text the model wrote.
   *
   * @throws IllegalArgumentException with what is wrong, in words the model can act on
   */
  JsonNode read(JsonNode input);

  /** Whether a text the model wrote holds an answer in the form, to be read as one. */
  boolean holdsAnswer(String text);

  /** The reply to an answer that passed. */
  String passed();

  /**
   * The reply to an answer that failed.
   *
   * @param attempt the attempt that failed, from 1
   * @param attempts how many attempts there are in all
   */
  String failed(List<String> problems, int attempt, int attempts);

  /** How many answers the conversation may give. */
  int attempts();

  /** What a turn with neither a tool call nor an answer is told, once. */
  String reminder();
}

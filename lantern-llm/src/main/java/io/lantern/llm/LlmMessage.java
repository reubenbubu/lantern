package io.lantern.llm;

import java.util.List;
import java.util.Objects;

/**
 * One turn of a conversation. An assistant turn may hold the tool calls the model asked for, and
 * the user turn after it the results of those calls; such a turn's text may be empty.
 *
 * @param author who wrote it
 * @param text what it says; empty only in a turn with tool calls or results
 * @param toolCalls the tool calls of an assistant turn, empty for a user turn
 * @param toolResults the tool results of a user turn, empty for an assistant turn
 */
public record LlmMessage(
    Author author, String text, List<LlmToolCall> toolCalls, List<LlmToolResult> toolResults) {

  /** Who wrote a turn. */
  public enum Author {
    USER,
    ASSISTANT
  }

  public LlmMessage {
    Objects.requireNonNull(author, "author");
    Objects.requireNonNull(text, "text");
    toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls"));
    toolResults = List.copyOf(Objects.requireNonNull(toolResults, "toolResults"));
    if (author == Author.USER && !toolCalls.isEmpty()) {
      throw new IllegalArgumentException("A user turn cannot hold tool calls");
    }
    if (author == Author.ASSISTANT && !toolResults.isEmpty()) {
      throw new IllegalArgumentException("An assistant turn cannot hold tool results");
    }
    if (toolCalls.isEmpty() && toolResults.isEmpty()) {
      ModelConfig.nonBlank("Message text", text);
    }
  }

  /** A turn of text only. */
  public LlmMessage(Author author, String text) {
    this(author, text, List.of(), List.of());
  }

  public static LlmMessage user(String text) {
    return new LlmMessage(Author.USER, text);
  }

  public static LlmMessage assistant(String text) {
    return new LlmMessage(Author.ASSISTANT, text);
  }

  /** An assistant turn that asks for tool calls, with any text the model wrote before them. */
  public static LlmMessage assistant(String text, List<LlmToolCall> toolCalls) {
    return new LlmMessage(Author.ASSISTANT, text, toolCalls, List.of());
  }

  /** A user turn that answers tool calls, with any text after the results. */
  public static LlmMessage toolResults(List<LlmToolResult> results, String text) {
    return new LlmMessage(Author.USER, text, List.of(), results);
  }

  /** The characters this turn sends, for estimates: its text, tool calls and tool results. */
  long chars() {
    long chars = text.length();
    for (LlmToolCall call : toolCalls) {
      chars += call.name().length() + call.input().toString().length();
    }
    for (LlmToolResult result : toolResults) {
      chars += result.content().length();
    }
    return chars;
  }
}

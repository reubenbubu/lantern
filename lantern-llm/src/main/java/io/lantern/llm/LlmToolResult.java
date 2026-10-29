package io.lantern.llm;

import java.util.Objects;

/**
 * What running a tool call gave, sent back to the model in the next user turn.
 *
 * @param callId the {@linkplain LlmToolCall#id() id} of the call it answers
 * @param content the result as text
 * @param error whether the call failed, for example with an input the tool does not take; the
 *     content then says why
 */
public record LlmToolResult(String callId, String content, boolean error) {

  public LlmToolResult {
    callId = ModelConfig.nonBlank("Tool call id", callId);
    Objects.requireNonNull(content, "content");
  }

  /** A successful result. */
  public static LlmToolResult of(LlmToolCall call, String content) {
    return new LlmToolResult(call.id(), content, false);
  }

  /** A failed call, with the reason for the model. */
  public static LlmToolResult error(LlmToolCall call, String reason) {
    return new LlmToolResult(call.id(), reason, true);
  }
}

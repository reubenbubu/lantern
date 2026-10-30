package io.lantern.llm;

import java.util.List;

/**
 * The tools a {@link Conversation} offers the model, and how to run them. A tool never throws for a
 * call the model got wrong (an unknown tool, a bad input): it answers with an {@linkplain
 * LlmToolResult#error error result} saying why, so the model can try again.
 */
public interface ToolBox {

  /** No tools: a conversation with it is a plain request and the reply after it. */
  ToolBox NONE =
      new ToolBox() {
        @Override
        public List<LlmTool> tools() {
          return List.of();
        }

        @Override
        public LlmToolResult run(LlmToolCall call) {
          return LlmToolResult.error(call, "There are no tools.");
        }
      };

  /** The tools offered, each with a distinct name. */
  List<LlmTool> tools();

  /** Runs one call. */
  LlmToolResult run(LlmToolCall call);
}

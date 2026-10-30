package io.lantern.llm;

import java.util.ArrayList;
import java.util.List;

/** Keeps calls and conversations in memory, for tests and run summaries. */
public final class InMemoryLlmCallLog implements LlmCallLog {

  private final List<LlmCall> calls = new ArrayList<>();
  private final List<LlmConversation> conversations = new ArrayList<>();

  @Override
  public synchronized void record(LlmCall call) {
    calls.add(call);
  }

  @Override
  public synchronized void recordConversation(LlmConversation conversation) {
    conversations.add(conversation);
  }

  /** The calls recorded so far, oldest first. */
  public synchronized List<LlmCall> calls() {
    return List.copyOf(calls);
  }

  /** The conversations recorded so far, in the order they ended. */
  public synchronized List<LlmConversation> conversations() {
    return List.copyOf(conversations);
  }
}

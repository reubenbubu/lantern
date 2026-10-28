package io.lantern.llm;

import java.util.ArrayList;
import java.util.List;

/** Keeps calls in memory, for tests and run summaries. */
public final class InMemoryLlmCallLog implements LlmCallLog {

  private final List<LlmCall> calls = new ArrayList<>();

  @Override
  public synchronized void record(LlmCall call) {
    calls.add(call);
  }

  /** The calls recorded so far, oldest first. */
  public synchronized List<LlmCall> calls() {
    return List.copyOf(calls);
  }
}

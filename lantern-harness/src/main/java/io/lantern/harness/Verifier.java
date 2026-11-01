package io.lantern.harness;

import io.lantern.llm.LlmResponse;

/**
 * A role's checks of a model's answer: returns what it accepts, or rejects it with every reason. A
 * pure function of the answer and the item's inputs.
 */
@FunctionalInterface
public interface Verifier<T> {
  T verify(LlmResponse answer) throws Rejected;
}

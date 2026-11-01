package io.lantern.harness;

/** Asks the model about one item through the harness's calls and returns the accepted answer. */
@FunctionalInterface
public interface Asker {

  /**
   * @throws ItemDropped if the answer failed the checks twice
   * @throws io.lantern.llm.LlmException if a call failed or was refused
   */
  Answer ask(Calls calls) throws ItemDropped;
}

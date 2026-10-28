package io.lantern.llm;

/** A call would go over a budget, so it was not sent. */
public class BudgetExceededException extends LlmException {

  public BudgetExceededException(String message) {
    super(message);
  }
}

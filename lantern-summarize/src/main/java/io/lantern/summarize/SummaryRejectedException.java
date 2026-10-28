package io.lantern.summarize;

/**
 * The model's summary broke the rules even after being asked to fix it, for example by citing
 * nothing.
 */
public class SummaryRejectedException extends RuntimeException {

  public SummaryRejectedException(String message) {
    super(message);
  }
}

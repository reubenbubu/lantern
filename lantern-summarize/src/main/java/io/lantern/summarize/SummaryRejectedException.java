package io.lantern.summarize;

/**
 * The model's summary broke the rules even after being asked to fix it, for example by citing
 * nothing. The summarizer leaves such a unit without a summary and lists it in its result.
 */
final class SummaryRejectedException extends RuntimeException {

  SummaryRejectedException(String message) {
    super(message);
  }
}

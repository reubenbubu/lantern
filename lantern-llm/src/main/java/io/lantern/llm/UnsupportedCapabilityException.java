package io.lantern.llm;

/** A request needs something its model cannot do, so it was not sent. */
public class UnsupportedCapabilityException extends LlmException {

  public UnsupportedCapabilityException(String message) {
    super(message);
  }
}

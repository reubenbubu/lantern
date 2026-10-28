package io.lantern.core.analysis;

/** Thrown when an analysis is started while another one is running. */
public class AnalysisAlreadyRunningException extends IllegalStateException {

  public AnalysisAlreadyRunningException() {
    super("analysis already running");
  }
}

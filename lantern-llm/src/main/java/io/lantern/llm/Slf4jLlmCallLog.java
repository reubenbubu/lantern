package io.lantern.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Writes one log line per call to the {@code io.lantern.llm.calls} logger. */
public final class Slf4jLlmCallLog implements LlmCallLog {

  private static final Logger LOG = LoggerFactory.getLogger("io.lantern.llm.calls");

  @Override
  public void record(LlmCall call) {
    if (call.outcome() == LlmCall.Outcome.SUCCEEDED) {
      LOG.info(
          "LLM call {} role={} provider={} model={} purpose=\"{}\" tokens(in={}, out={}, cacheWrite={},"
              + " cacheRead={}) cost=${} duration={}ms",
          call.outcome(),
          call.role(),
          call.provider(),
          call.model(),
          call.purpose(),
          call.usage().input(),
          call.usage().output(),
          call.usage().cacheWrite(),
          call.usage().cacheRead(),
          call.costUsd().toPlainString(),
          call.duration().toMillis());
    } else {
      LOG.warn(
          "LLM call {} role={} provider={} model={} purpose=\"{}\" duration={}ms error=\"{}\"",
          call.outcome(),
          call.role(),
          call.provider(),
          call.model(),
          call.purpose(),
          call.duration().toMillis(),
          call.error());
    }
  }
}

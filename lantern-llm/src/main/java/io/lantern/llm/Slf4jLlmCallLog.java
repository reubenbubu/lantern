package io.lantern.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes one log line per call, and one per conversation when it ends, to the {@code
 * io.lantern.llm.calls} logger.
 */
public final class Slf4jLlmCallLog implements LlmCallLog {

  private static final Logger LOG = LoggerFactory.getLogger("io.lantern.llm.calls");

  @Override
  public void record(LlmCall call) {
    String turn =
        call.conversation() == null
            ? ""
            : " conversation=%s turn=%d toolCalls=%s"
                .formatted(call.conversation(), call.turn(), call.toolCalls());
    if (call.outcome() == LlmCall.Outcome.TRUNCATED) {
      LOG.warn(
          "LLM call {} role={} provider={} model={} purpose=\"{}\" tokens(in={}, out={}, cacheWrite={},"
              + " cacheRead={}) cost=${} duration={}ms error=\"{}\"{}",
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
          call.duration().toMillis(),
          call.error(),
          turn);
    } else if (call.outcome() == LlmCall.Outcome.SUCCEEDED) {
      LOG.info(
          "LLM call {} role={} provider={} model={} purpose=\"{}\" tokens(in={}, out={}, cacheWrite={},"
              + " cacheRead={}) cost=${} duration={}ms{}",
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
          call.duration().toMillis(),
          turn);
    } else {
      LOG.warn(
          "LLM call {} role={} provider={} model={} purpose=\"{}\" duration={}ms error=\"{}\"{}",
          call.outcome(),
          call.role(),
          call.provider(),
          call.model(),
          call.purpose(),
          call.duration().toMillis(),
          call.error(),
          turn);
    }
  }

  @Override
  public void record(LlmConversation conversation) {
    LOG.info(
        "LLM conversation {} {} role={} provider={} model={} purpose=\"{}\" turns={} toolCalls={}"
            + " tokens={} cost=${} duration={}ms{}",
        conversation.id(),
        conversation.ending(),
        conversation.role(),
        conversation.provider(),
        conversation.model(),
        conversation.purpose(),
        conversation.turns(),
        conversation.toolCalls(),
        conversation.usage().total(),
        conversation.costUsd().toPlainString(),
        conversation.duration().toMillis(),
        conversation.error() == null ? "" : " error=\"" + conversation.error() + "\"");
  }
}

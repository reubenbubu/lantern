package io.lantern.harness;

import io.lantern.llm.LlmMessage;
import io.lantern.llm.LlmResponse;
import java.util.ArrayList;
import java.util.List;

/** The turns of a repair request: the sent turns, the rejected answer and the rejections. */
@FunctionalInterface
public interface Repair {

  List<LlmMessage> turns(List<LlmMessage> sent, LlmResponse answer, Rejected rejected);

  /**
   * A repair that sends the role's own message, made from the rejection, after the answer; when the
   * answer is blank, a turn cannot be empty, so the message goes with the last user turn instead.
   */
  static Repair withMessage(java.util.function.Function<Rejected, String> message) {
    return (sent, answer, rejected) -> {
      List<LlmMessage> turns = new ArrayList<>(sent);
      String text = message.apply(rejected);
      if (answer.text().isBlank()) {
        LlmMessage last = turns.removeLast();
        turns.add(
            new LlmMessage(
                last.author(),
                last.text().isEmpty() ? text : last.text() + "\n" + text,
                last.toolCalls(),
                last.toolResults(),
                last.thinking()));
      } else {
        turns.add(LlmMessage.assistant(answer.text()));
        turns.add(LlmMessage.user(text));
      }
      return turns;
    };
  }
}

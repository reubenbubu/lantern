package io.lantern.harness;

import java.util.List;
import java.util.Objects;

/**
 * An answer the checks rejected, or an item dropped, as the run's {@code rejections.jsonl.gz} lists
 * it for the owner to read (#682).
 *
 * @param role the role's name
 * @param subject the item's subject
 * @param attempt 1 for the first answer, 2 for the answer after the repair turn; 0 for a drop
 * @param answer the text rejected: the item's entry, or the whole answer when it could not be read
 *     per item; {@code null} for a drop
 * @param reasons why it was rejected or dropped
 */
public record RejectedAnswer(
    String role, String subject, int attempt, String answer, List<String> reasons) {

  public RejectedAnswer {
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(subject, "subject");
    reasons = List.copyOf(reasons);
  }

  /** Whether this notes a drop rather than one rejected answer. */
  public boolean drop() {
    return attempt == 0;
  }

  /** Receives the rejections of a role's items as they happen. */
  @FunctionalInterface
  interface Sink {
    void rejected(String subject, int attempt, String answer, List<String> reasons);
  }
}

package io.lantern.harness;

/**
 * What became of an item in a run.
 *
 * @param kind what happened
 * @param fingerprint the item's fingerprint
 * @param answer the accepted answer as the role stores it, for {@link Kind#CACHED} and {@link
 *     Kind#ANSWERED}; otherwise {@code null}
 * @param reason why it has no answer, for the other kinds; otherwise {@code null}
 */
public record Outcome(Kind kind, String fingerprint, String answer, String reason) {

  /** What happened to an item. */
  public enum Kind {
    /** The answer cache held its answer, revalidated; no call was made. */
    CACHED,
    /** The model answered and the answer was accepted, maybe after a repair turn. */
    ANSWERED,
    /** The model answered that its inputs show nothing for it, a correct answer, now cached. */
    OMITTED,
    /** Its answer failed the checks twice, or it was missing twice; nothing is stored. */
    DROPPED,
    /** It was not asked: its role's share of the run budget was spent. Shown as unknown. */
    NOT_ASKED,
    /** The call failed in the provider or was refused; nothing is stored. */
    FAILED
  }

  /** Whether it has an answer, cached or new. */
  public boolean answered() {
    return answer != null;
  }

  static Outcome of(Kind kind, String fingerprint, String answer, String reason) {
    return new Outcome(kind, fingerprint, answer, reason);
  }
}

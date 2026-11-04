package io.lantern.harness;

/**
 * What became of an item in a run.
 *
 * @param kind what happened
 * @param fingerprint the item's fingerprint
 * @param answer the accepted answer as the role stores it, for {@link Kind#CACHED} and {@link
 *     Kind#ANSWERED}; otherwise {@code null}
 * @param reason why it has no answer, for the other kinds; otherwise {@code null}
 * @param stored whether the answer cache holds a row under the fingerprint for it: an answer, an
 *     omission or a drop (#753)
 */
public record Outcome(Kind kind, String fingerprint, String answer, String reason, boolean stored) {

  /** An outcome, stored when its kind is one the answer cache keeps a row for. */
  public Outcome(Kind kind, String fingerprint, String answer, String reason) {
    this(kind, fingerprint, answer, reason, keeps(kind));
  }

  private static boolean keeps(Kind kind) {
    return kind == Kind.CACHED
        || kind == Kind.ANSWERED
        || kind == Kind.OMITTED
        || kind == Kind.DROPPED;
  }

  /** What happened to an item. */
  public enum Kind {
    /** The answer cache held its answer, revalidated; no call was made. */
    CACHED,
    /** The model answered and the answer was accepted, maybe after a repair turn. */
    ANSWERED,
    /** The model answered that its inputs show nothing for it, a correct answer, now cached. */
    OMITTED,
    /**
     * Its answer failed the checks twice. The drop is stored with why, so a rerun on the same
     * inputs repeats it without a call (#682).
     */
    DROPPED,
    /**
     * It was not asked, or got no answer: its role's share of the run budget was spent, a budget
     * refused the call, or it was missing from a packet's answer twice. Nothing is stored, so the
     * next run asks it again; the run record counts it.
     */
    NOT_ASKED,
    /** The call failed in the provider or was refused; nothing is stored. */
    FAILED
  }

  /** How the reason of a drop repeated from the answer cache begins (#682). */
  public static final String REPEATED_DROP = "dropped when last asked: ";

  /** Whether it is a drop repeated from the answer cache, with no call (#682). */
  public boolean repeatedDrop() {
    return kind == Kind.DROPPED && reason != null && reason.startsWith(REPEATED_DROP);
  }

  /** Whether it has an answer, cached or new. */
  public boolean answered() {
    return answer != null;
  }

  /**
   * The key of the answer cache's row it comes from, which an LLM fact records as its {@code
   * answer} (ADR 0124); {@code null} when nothing was stored for it.
   */
  public String answerKey() {
    return stored ? fingerprint : null;
  }

  static Outcome of(Kind kind, String fingerprint, String answer, String reason) {
    return new Outcome(kind, fingerprint, answer, reason);
  }

  /** A drop not stored: a cut-off answer or a capped conversation, asked again next run. */
  static Outcome unstoredDrop(String fingerprint, String reason) {
    return new Outcome(Kind.DROPPED, fingerprint, null, reason, false);
  }
}

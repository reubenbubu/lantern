package io.lantern.summarize;

/**
 * How far a text the LLM wrote, a summary or a wording, can be trusted, from 0 to 1 as a fact's
 * confidence is (docs/design.md, Design principles 2; ADR 0066). It is worked out from what the
 * answer's checks found, not asked of the model: an LLM's text never reaches a static fact's
 * confidence, one whose purpose rests on what people wrote is trusted more than one inferred from
 * the code, and an answer some of whose sentences cited nothing and were left out is trusted less.
 */
public final class Confidence {

  /**
   * The confidence of a text whose purpose rests on what people wrote: as for a link the resolver
   * picks with high confidence (ADR 0045).
   */
  public static final double EVIDENCED = 0.5;

  /** The confidence of a text inferred from the code: as for a medium pick (ADR 0045). */
  public static final double INFERRED = 0.35;

  private Confidence() {}

  /**
   * The confidence of an answer.
   *
   * @param evidenced whether its purpose rests on what people wrote
   * @param kept how many of its sentences cite something and are kept, at least one
   * @param leftOut how many cite nothing and are left out
   * @return the base confidence times the share of sentences kept, to two decimals and at least
   *     0.01
   */
  public static double of(boolean evidenced, int kept, int leftOut) {
    if (kept < 1 || leftOut < 0) {
      throw new IllegalArgumentException(
          "An answer keeps at least one sentence: " + kept + " kept, " + leftOut + " left out");
    }
    double base = evidenced ? EVIDENCED : INFERRED;
    double confidence = Math.round(base * kept / (kept + leftOut) * 100) / 100.0;
    return Math.max(confidence, 0.01);
  }

  /** Whether the value is a valid confidence: above 0 and at most 1. */
  public static boolean valid(double confidence) {
    return confidence > 0 && confidence <= 1;
  }
}

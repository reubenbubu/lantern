package io.lantern.llm;

/** What an LLM call is for, as listed in the design (LLM integration, Roles). */
public enum Role {
  /** Summarizing packages, modules and repositories (knowledge-build epic, section 11.3). */
  SUMMARIZE(Tier.HIGH_VOLUME),
  /** Summarizing types, the most numerous units: the bulk tier. */
  SUMMARIZE_TYPES(Tier.BULK),
  RESOLVE(Tier.REASONING),
  /**
   * Checking the leads static analysis flagged and looking for what it missed, citing the code
   * (knowledge-build epic, section 11.2): reading code to judge risks, on the reasoning tier.
   */
  INVESTIGATE(Tier.REASONING),
  INFER_INTENT(Tier.REASONING),
  /**
   * Describing data stores, destinations, external systems and people (knowledge-build epic,
   * section 11.4): high-volume, where describing units infers intent on the reasoning tier.
   */
  DESCRIBE_ELEMENTS(Tier.HIGH_VOLUME),
  NAME_GROUPS(Tier.HIGH_VOLUME),
  PROPOSE_RULES(Tier.REASONING),
  /**
   * Choosing which traced flows are worth drawing for a scope (knowledge-build epic, section 11.5):
   * a judgement on the reasoning tier, where narrating them is composing on the high-volume tier.
   */
  FLOW_SIGNIFICANCE(Tier.REASONING),
  COMPOSE(Tier.HIGH_VOLUME),
  TRANSLATE(Tier.HIGH_VOLUME),
  /**
   * The support judge (ADR 0101). By default each text is judged on the model of the role that wrote
   * it; on this role's tier, high-volume, only when {@code lantern.harness.support-judge.model} is
   * {@code judge} (ADR 0101, amendment for #751).
   */
  JUDGE(Tier.HIGH_VOLUME),
  /** The injection screen (ADR 0101): the bulk tier. */
  SCREEN(Tier.BULK),
  /**
   * The wording judge (ADR 0111), which measures the roles' texts for scoring and runs only when
   * asked: the reasoning tier, and a run that judges sets it to a model none of the writers use.
   */
  WORDING_JUDGE(Tier.REASONING);

  private final Tier tier;

  Role(Tier tier) {
    this.tier = tier;
  }

  /** The tier whose model this role uses unless a model is set for the role itself. */
  public Tier tier() {
    return tier;
  }
}

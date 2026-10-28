package io.lantern.llm;

/** What an LLM call is for, as listed in the design (LLM integration, Roles). */
public enum Role {
  SUMMARIZE(Tier.HIGH_VOLUME),
  RESOLVE(Tier.REASONING),
  INFER_INTENT(Tier.REASONING),
  NAME_GROUPS(Tier.HIGH_VOLUME),
  PROPOSE_RULES(Tier.REASONING),
  COMPOSE(Tier.HIGH_VOLUME),
  TRANSLATE(Tier.HIGH_VOLUME);

  private final Tier tier;

  Role(Tier tier) {
    this.tier = tier;
  }

  /** The tier whose model this role uses unless a model is set for the role itself. */
  public Tier tier() {
    return tier;
  }
}

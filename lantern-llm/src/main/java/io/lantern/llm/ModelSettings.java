package io.lantern.llm;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Which model each role uses. By default a role uses its {@linkplain Role#tier() tier}'s model; a
 * role can be given its own model, for example a cheaper one for leaf summaries.
 */
public final class ModelSettings {

  private final Map<Role, ModelConfig> models;

  private ModelSettings(Map<Role, ModelConfig> models) {
    this.models = models;
  }

  /** Every role uses the model of its tier. */
  public static ModelSettings byTier(ModelConfig highVolume, ModelConfig reasoning) {
    Objects.requireNonNull(highVolume, "highVolume");
    Objects.requireNonNull(reasoning, "reasoning");
    Map<Role, ModelConfig> models = new EnumMap<>(Role.class);
    for (Role role : Role.values()) {
      models.put(role, role.tier() == Tier.HIGH_VOLUME ? highVolume : reasoning);
    }
    return new ModelSettings(models);
  }

  /** Every role uses the same model. */
  public static ModelSettings single(ModelConfig model) {
    return byTier(model, model);
  }

  /** These settings with the given role using the given model. */
  public ModelSettings with(Role role, ModelConfig model) {
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(model, "model");
    Map<Role, ModelConfig> copy = new EnumMap<>(models);
    copy.put(role, model);
    return new ModelSettings(copy);
  }

  /** The model the given role uses. */
  public ModelConfig forRole(Role role) {
    return models.get(Objects.requireNonNull(role, "role"));
  }

  @Override
  public String toString() {
    return "ModelSettings" + models;
  }
}

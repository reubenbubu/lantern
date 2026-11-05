package io.lantern.derive;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.types.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** The configuration values the modules of a repository define (ADR 0034), read for the brief. */
public final class ConfigValues {

  private ConfigValues() {}

  /**
   * A configuration value, as a module's code defines it.
   *
   * @param fact the value's fact
   * @param key the setting's key, or {@code null} when none is stated
   * @param profile the Spring profile the value is for, or {@code null} for the default
   * @param profiled whether the fact states a profile at all, even an empty one
   */
  public record Value(Fact fact, String key, String profile, boolean profiled) {

    /** The value as configured, possibly empty. */
    public String value() {
      return fact.object().value();
    }
  }

  /**
   * The configuration values of the modules, module by module, in the order the model holds them.
   *
   * @param modules the modules, in the order they are read
   */
  public static List<Value> of(ArchitectureModel model, Collection<EntityId> modules) {
    List<Value> values = new ArrayList<>();
    for (EntityId module : modules) {
      for (Fact fact : model.attributes(module, Types.CONFIG_VALUE)) {
        values.add(
            new Value(
                fact,
                Elements.attribute(fact, "key", null),
                Elements.attribute(fact, "profile", null),
                fact.attributes().has("profile")));
      }
    }
    return values;
  }
}

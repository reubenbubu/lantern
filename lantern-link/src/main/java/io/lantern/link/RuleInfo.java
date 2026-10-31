package io.lantern.link;

import io.lantern.core.fact.Origin;
import java.util.Objects;

/**
 * What a link rule is: its name, recorded as the origin of its facts, its version, which changes
 * whenever the facts it gives for the same input change, and its origin kind ({@code static}, or
 * {@code llm} for the LLM resolver).
 */
public record RuleInfo(String name, String version, Origin.Kind kind) {

  public RuleInfo {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(kind, "kind");
  }

  /** The rule an origin names. */
  public static RuleInfo of(Origin origin) {
    return new RuleInfo(origin.name(), origin.version(), origin.kind());
  }

  /** The origin the rule's facts record. */
  public Origin origin() {
    return new Origin(kind, name, version);
  }
}

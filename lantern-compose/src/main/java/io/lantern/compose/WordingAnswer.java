package io.lantern.compose;

import io.lantern.core.fact.EntityId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The LLM's checked answer about one deployable unit, as cached (ADR 0050): a text for each
 * reference it worded, citing the references it rests on. The references stand for what the prompt
 * of the same key shows, so they are resolved against that prompt when used.
 *
 * @param key the cache key: a SHA-256 of the prompt version, provider, model and prompt
 * @param unit the deployable unit asked about
 * @param items the texts, by reference: {@link #UNIT} for the unit, {@code C1}, ... for its
 *     components and {@code R1}, ... for its relationships
 * @param promptVersion the version of the prompt that asked
 * @param provider the provider of the model that answered
 * @param model the model that answered
 */
public record WordingAnswer(
    String key,
    EntityId unit,
    Map<String, Item> items,
    String promptVersion,
    String provider,
    String model) {

  /** The reference of the unit itself among the items. */
  public static final String UNIT = "unit";

  private static final Pattern KEY = Pattern.compile("[0-9a-f]{64}");

  public WordingAnswer {
    Objects.requireNonNull(key, "key");
    if (!KEY.matcher(key).matches()) {
      throw new IllegalArgumentException("Invalid wording key: '" + key + "'");
    }
    Objects.requireNonNull(unit, "unit");
    items = Map.copyOf(new TreeMap<>(items));
    Objects.requireNonNull(promptVersion, "promptVersion");
    Objects.requireNonNull(provider, "provider");
    Objects.requireNonNull(model, "model");
  }

  /**
   * One text of the answer.
   *
   * @param text a responsibility sentence or an intent phrase, without citation markers
   * @param cites the references of the facts and summaries it rests on, at least one
   */
  public record Item(String text, List<String> cites) {

    public Item {
      Objects.requireNonNull(text, "text");
      if (text.isBlank()) {
        throw new IllegalArgumentException("A wording must not be blank");
      }
      cites = List.copyOf(cites);
      if (cites.isEmpty()) {
        throw new IllegalArgumentException("A wording must cite at least one reference");
      }
    }
  }
}

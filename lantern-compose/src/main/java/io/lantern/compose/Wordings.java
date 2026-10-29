package io.lantern.compose;

import io.lantern.core.fact.EntityId;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What the LLM worded for the elements and relationships of the C4 views (ADR 0050), for the
 * composer to use instead of summaries and static labels where it has them.
 *
 * @param responsibilities the responsibility sentence of each deployable unit and component (by its
 *     main type), with citation markers as summaries have them, {@code [fact:<fact ID>]}
 * @param phrases the intent phrase of each link, by the ID of the link fact
 */
public record Wordings(Map<EntityId, String> responsibilities, Map<String, Phrase> phrases) {

  /** No wording: documents keep summaries as descriptions and static labels. */
  public static final Wordings NONE = new Wordings(Map.of(), Map.of());

  public Wordings {
    responsibilities = Map.copyOf(responsibilities);
    phrases = Map.copyOf(phrases);
  }

  /**
   * An intent phrase.
   *
   * @param text the phrase, such as {@code Looks up product prices}
   * @param facts the IDs of the facts it cites
   */
  public record Phrase(String text, List<String> facts) {

    public Phrase {
      Objects.requireNonNull(text, "text");
      if (text.isBlank()) {
        throw new IllegalArgumentException("A phrase must not be blank");
      }
      facts = List.copyOf(facts);
    }
  }
}

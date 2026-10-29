package io.lantern.compose;

import io.lantern.core.fact.EntityId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What the LLM worded for the elements and relationships of the C4 views (ADR 0050) and for the
 * main flows (ADR 0058), for the composer to use instead of summaries, static labels and static
 * prose where it has them.
 *
 * @param responsibilities the responsibility sentence of each deployable unit and component (by its
 *     main type), data store and destination, and the purpose of the system ({@code
 *     system:snapshot}, ADR 0060), with citation markers as summaries have them, {@code [fact:<fact
 *     ID>]}
 * @param phrases the intent phrase of each link, by the ID of the link fact
 * @param flows the walkthrough and conditions of each flow, by {@link #flowKey} of the prompt that
 *     asked about it, so a flow is worded only when it is traced as the model saw it
 */
public record Wordings(
    Map<EntityId, String> responsibilities, Map<String, Phrase> phrases, Map<String, Flow> flows) {

  /** No wording: documents keep summaries as descriptions and static labels. */
  public static final Wordings NONE = new Wordings(Map.of(), Map.of());

  public Wordings {
    responsibilities = Map.copyOf(responsibilities);
    phrases = Map.copyOf(phrases);
    flows = Map.copyOf(flows);
  }

  /** Responsibility sentences and intent phrases, without flows. */
  public Wordings(Map<EntityId, String> responsibilities, Map<String, Phrase> phrases) {
    this(responsibilities, phrases, Map.of());
  }

  /**
   * The key of a flow's wording: the SHA-256 of the user turn of the prompt about it, which shows
   * no fact IDs.
   */
  static String flowKey(String prompt) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(prompt.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
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

  /**
   * A flow's walkthrough and conditions, each with citation markers {@code [fact:<fact ID>]}, or
   * {@code null} where the model left it out.
   *
   * @param walkthrough two to four sentences of what the flow does
   * @param preconditions what must hold before it starts
   * @param postconditions what has changed when it ends
   */
  public record Flow(String walkthrough, String preconditions, String postconditions) {}
}

package io.lantern.summarize;

import io.lantern.core.fact.EntityId;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A generated summary of one unit.
 *
 * <p>The text cites what it is based on with markers: {@code [fact:<fact ID>]} for a fact and
 * {@code [summary:<entity ID>]} for the summary of a contained unit.
 *
 * @param key the cache key: a SHA-256 of the prompt the model was given, its version and the model
 *     (see {@link Summarizer})
 * @param entity the entity summarized
 * @param level its level
 * @param text the summary, with citation markers
 * @param purpose whether its first sentence, which says what the unit is for, takes that from what
 *     people wrote or infers it from the code
 * @param confidence how far it can be trusted, above 0 and at most 1 ({@link Confidence})
 * @param citedFacts the IDs of the facts the text cites, in order of first citation
 * @param citedSummaries the keys of the contained units' summaries the text cites, in order of
 *     first citation
 * @param inputFacts the IDs of every fact the summary was made from, in the order its prompt listed
 *     them
 * @param inputSummaries the keys of every contained unit's summary it was made from, sorted by
 *     entity ID
 * @param promptVersion the version of the prompt that made it
 * @param provider the provider of the model that wrote it
 * @param model the model that wrote it
 */
public record Summary(
    String key,
    EntityId entity,
    SummaryLevel level,
    String text,
    Purpose purpose,
    double confidence,
    List<String> citedFacts,
    List<String> citedSummaries,
    List<String> inputFacts,
    List<String> inputSummaries,
    String promptVersion,
    String provider,
    String model) {

  /** Where a summary's first sentence, on what the unit is for, takes that from. */
  public enum Purpose {
    /**
     * From what people wrote and the prompt showed: a README's claims, Javadoc in a source file, or
     * a part's summary whose purpose is evidenced.
     */
    EVIDENCED,
    /** Inferred from names and code, or not said at all: the sentence says what the unit is. */
    INFERRED
  }

  private static final Pattern KEY = Pattern.compile("[0-9a-f]{64}");

  public Summary {
    Objects.requireNonNull(key, "key");
    if (!KEY.matcher(key).matches()) {
      throw new IllegalArgumentException("Invalid summary key: '" + key + "'");
    }
    Objects.requireNonNull(entity, "entity");
    Objects.requireNonNull(level, "level");
    Objects.requireNonNull(text, "text");
    if (text.isBlank()) {
      throw new IllegalArgumentException("Summary text must not be blank");
    }
    Objects.requireNonNull(purpose, "purpose");
    if (!Confidence.valid(confidence)) {
      throw new IllegalArgumentException("Invalid summary confidence: " + confidence);
    }
    citedFacts = List.copyOf(citedFacts);
    citedSummaries = List.copyOf(citedSummaries);
    inputFacts = List.copyOf(inputFacts);
    inputSummaries = List.copyOf(inputSummaries);
    if (citedFacts.isEmpty() && citedSummaries.isEmpty()) {
      throw new IllegalArgumentException("A summary must cite at least one fact or summary");
    }
    if (!inputFacts.containsAll(citedFacts) || !inputSummaries.containsAll(citedSummaries)) {
      throw new IllegalArgumentException("A summary may only cite its inputs");
    }
    Objects.requireNonNull(promptVersion, "promptVersion");
    Objects.requireNonNull(provider, "provider");
    Objects.requireNonNull(model, "model");
  }
}

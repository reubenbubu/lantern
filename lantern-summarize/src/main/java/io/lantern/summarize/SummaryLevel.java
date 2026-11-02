package io.lantern.summarize;

import io.lantern.core.fact.EntityId;
import io.lantern.core.vocabulary.BuiltInTypes;

/** The levels of the summary hierarchy, from a single type up to a repository. */
public enum SummaryLevel {
  TYPE("type", 2, 4, 400),
  PACKAGE("package", 3, 5, 600),
  MODULE("Maven module", 3, 6, 800),
  REPO("repository", 3, 6, 800);

  /**
   * How many sentences a package, module or repository with a single part is asked for, instead of
   * its level's, so that it does not restate that part.
   */
  static final String ONE_PART_SENTENCES = "1 to 3";

  /** The most sentences kept of a summary of a unit with a single part, or of one part. */
  static final int ONE_PART_SENTENCE_COUNT = 3;

  /** The entity type of repository summaries, which have no entity in the fact store. */
  public static final String REPO_ENTITY_TYPE = "repo";

  private final String noun;
  private final int minSentences;
  private final int maxSentences;
  private final int maxOutputTokens;

  SummaryLevel(String noun, int minSentences, int maxSentences, int maxOutputTokens) {
    this.noun = noun;
    this.minSentences = minSentences;
    this.maxSentences = maxSentences;
    this.maxOutputTokens = maxOutputTokens;
  }

  /** The fewest sentences the prompt asks for. */
  int minSentences() {
    return minSentences;
  }

  /** The most sentences the prompt asks for, and a summary keeps. */
  int maxSentences() {
    return maxSentences;
  }

  /** What the prompt calls a unit of this level. */
  String noun() {
    return noun;
  }

  /** The most tokens the model may write: the answer's JSON, with room for citations. */
  int maxOutputTokens() {
    return maxOutputTokens;
  }

  /**
   * The level of an entity, or {@code null} if entities of its type are not summarized on their own
   * (methods and fields are part of their type's summary).
   */
  public static SummaryLevel of(EntityId entity) {
    return switch (entity.type()) {
      case BuiltInTypes.TYPE -> TYPE;
      case BuiltInTypes.PACKAGE -> PACKAGE;
      case BuiltInTypes.MODULE -> MODULE;
      case REPO_ENTITY_TYPE -> REPO;
      default -> null;
    };
  }
}

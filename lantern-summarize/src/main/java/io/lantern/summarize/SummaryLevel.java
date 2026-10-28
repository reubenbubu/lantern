package io.lantern.summarize;

import io.lantern.core.fact.EntityId;
import io.lantern.core.vocabulary.BuiltInTypes;

/** The levels of the summary hierarchy, from a single type up to a repository. */
public enum SummaryLevel {
  TYPE(
      "type",
      2,
      4,
      80,
      400,
      "its purpose, its main responsibilities and the types it works with most"),
  PACKAGE("package", 3, 5, 120, 600, "what the package is for and its main parts"),
  MODULE(
      "Maven module",
      3,
      6,
      150,
      800,
      "what the module is for, then its main parts and the dependencies that matter"),
  REPO(
      "repository",
      3,
      6,
      150,
      800,
      "what the repository is for, then its main capabilities and the other modules or services"
          + " it depends on");

  /**
   * How many sentences a package, module or repository with a single part is asked for, instead of
   * its level's, so that it does not restate that part.
   */
  static final String ONE_PART_SENTENCES = "1 to 3";

  /** The most words a package, module or repository with a single part is asked for. */
  static final int ONE_PART_WORDS = 60;

  /** The entity type of repository summaries, which have no entity in the fact store. */
  public static final String REPO_ENTITY_TYPE = "repo";

  private final String noun;
  private final int minSentences;
  private final int maxSentences;
  private final int maxWords;
  private final int maxOutputTokens;
  private final String content;

  SummaryLevel(
      String noun,
      int minSentences,
      int maxSentences,
      int maxWords,
      int maxOutputTokens,
      String content) {
    this.noun = noun;
    this.minSentences = minSentences;
    this.maxSentences = maxSentences;
    this.maxWords = maxWords;
    this.maxOutputTokens = maxOutputTokens;
    this.content = content;
  }

  /** The fewest sentences the prompt asks for. */
  int minSentences() {
    return minSentences;
  }

  /** The most sentences the prompt asks for; with about 20 words each, within {@link #maxWords}. */
  int maxSentences() {
    return maxSentences;
  }

  /** What the prompt asks a summary of this level to cover. */
  String content() {
    return content;
  }

  /** What the prompt calls a unit of this level. */
  String noun() {
    return noun;
  }

  /** The most words the prompt asks for. */
  int maxWords() {
    return maxWords;
  }

  /** The most tokens the model may write, with room for citations. */
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

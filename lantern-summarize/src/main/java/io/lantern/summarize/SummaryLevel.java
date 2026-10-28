package io.lantern.summarize;

import io.lantern.core.fact.EntityId;
import io.lantern.core.vocabulary.BuiltInTypes;

/** The levels of the summary hierarchy, from a single type up to a repository. */
public enum SummaryLevel {
  TYPE("type", 80, 400),
  PACKAGE("package", 120, 600),
  MODULE("Maven module", 150, 800),
  REPO("repository", 200, 1000);

  /** The entity type of repository summaries, which have no entity in the fact store. */
  public static final String REPO_ENTITY_TYPE = "repo";

  private final String noun;
  private final int maxWords;
  private final int maxOutputTokens;

  SummaryLevel(String noun, int maxWords, int maxOutputTokens) {
    this.noun = noun;
    this.maxWords = maxWords;
    this.maxOutputTokens = maxOutputTokens;
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
